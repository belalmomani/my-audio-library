package com.mylibrary.app.cloud

import android.content.Context
import com.mylibrary.app.BuildConfig
import com.mylibrary.app.data.*
import com.mylibrary.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * مزامنة المكتبات مع Supabase (البيانات الوصفية عبر PostgREST + RLS) وCloudflare R2 (الملفات عبر روابط موقّعة).
 * الرفع لا يبدأ إلا إذا فعّل المستخدم «المزامنة السحابية» وسجّل الدخول.
 */
class CloudSync(
    private val ctx: Context,
    private val db: AppDatabase,
    private val auth: AuthRepository,
    private val backend: BackendApi,
    private val settings: SettingsStore,
) {
    private fun restReq(path: String, token: String) = Request.Builder()
        .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/" + path)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token")

    private suspend fun upsert(table: String, row: JSONObject, token: String) {
        val req = restReq(table, token).header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(Http.jsonBody(row)).build()
        Http.io { Http.execOk(req) }
    }

    private inner class Store : UploadStore {
        val dao = db.words()
        override fun pending(maxAttempts: Int) = dao.pendingUploadsBlocking(maxAttempts).map { UploadItem(it.id, it.uploadAttempts) }
        override fun mark(id: String, state: UploadState, error: String?) {
            // الفشل الحقيقي يزيد عدّاد المحاولات؛ انقطاع الشبكة (PENDING) لا يزيده
            dao.markUploadBlocking(id, state.name, error, if (state == UploadState.FAILED) 1 else 0)
        }
    }

    val canSync: Boolean get() = settings.current.syncToCloud && auth.session.value != null && backend.isConfigured && auth.isConfigured

    /** يرفع الكلمات المعلّقة؛ ما نجح لا يُعاد، وما فشل يُعاد وحده. */
    suspend fun syncPending(shouldContinue: () -> Boolean = { true }): UploadSummary {
        if (!canSync) return UploadSummary(0, 0, false)
        return ResumableUploader(Store()).run(shouldContinue) { item ->
            runBlocking { uploadWord(item.id) }
        }
    }

    private suspend fun uploadWord(wordId: String) {
        val w = db.words().getBlocking(wordId) ?: return
        val session = auth.session.value ?: throw IllegalStateException("غير مسجّل")
        val token = auth.validToken() ?: throw IllegalStateException("انتهت الجلسة")
        val uid = session.userId
        val lib = db.libraries().get(w.libraryId) ?: return
        upsert("libraries", JSONObject().put("id", lib.id).put("user_id", uid).put("name", lib.name), token)
        w.sourceId?.let { sid ->
            db.sources().get(sid)?.let { s ->
                upsert("source_recordings", JSONObject().put("id", s.id).put("user_id", uid).put("name", s.name)
                    .put("duration_ms", s.durationMs).put("size_bytes", s.sizeBytes), token)
            }
        }
        val key = "users/$uid/words/${w.id}.wav"
        val f = File(w.localPath)
        if (!f.exists()) throw IllegalStateException("الملف المحلي مفقود")
        backend.putFile(backend.presign(key, "put", "audio/wav", f.length()), f, "audio/wav")
        upsert("words", JSONObject().put("id", w.id).put("user_id", uid).put("library_id", w.libraryId)
            .put("source_id", if (w.sourceId != null && db.sources().get(w.sourceId) != null) w.sourceId else JSONObject.NULL)
            .put("text", w.text).put("normalized_text", w.normalized)
            .put("start_ms", w.startMs).put("end_ms", maxOf(w.endMs, w.startMs + 1)).put("duration_ms", maxOf(w.durationMs, 1))
            .put("review_status", w.reviewStatus).put("quality", w.quality.coerceIn(0.0, 1.0))
            .put("processing_version", w.processingVersion).put("user_locked", w.userLocked), token)
        upsert("audio_files", JSONObject().put("id", w.id).put("user_id", uid).put("word_id", w.id).put("object_key", key)
            .put("file_name", w.fileName).put("format", "wav").put("size_bytes", f.length()).put("sha256", w.sha256), token)
        db.words().setCloudKeyBlocking(w.id, key)
    }

    private suspend fun fetchAll(table: String, token: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val arr = JSONArray(Http.io { Http.execOk(restReq("$table?select=*&order=id&limit=1000&offset=$offset", token).get().build()) })
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (arr.length() < 1000) return out
            offset += 1000
        }
    }

    /** استعادة المكتبات على هاتف جديد: تنزيل ما ليس موجودًا محليًا فقط. */
    suspend fun restore(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val token = auth.validToken() ?: throw IllegalStateException("سجّل الدخول أولًا")
        val libs = fetchAll("libraries", token)
        val words = fetchAll("words", token)
        val files = fetchAll("audio_files", token).associateBy { it.getString("word_id") }
        for (l in libs) {
            if (db.libraries().get(l.getString("id")) == null)
                db.libraries().upsert(LibraryEntity(l.getString("id"), l.getString("name"), null, System.currentTimeMillis()))
        }
        var done = 0
        for ((i, w) in words.withIndex()) {
            onProgress(i, words.size)
            val id = w.getString("id")
            if (db.words().get(id) != null) continue
            val af = files[id] ?: continue
            val libId = w.getString("library_id")
            val dest = File(ctx.filesDir, "libraries/$libId/$id.wav")
            backend.download(backend.presign(af.getString("object_key"), "get"), dest)
            val sha = Hashing.sha256(dest)
            if (!af.isNull("sha256") && af.getString("sha256") != sha) { dest.delete(); continue } // ملف تالف
            val wav = WavFile.open(dest)
            val inserted = db.words().insert(WordEntity(
                id = id, libraryId = libId, text = w.getString("text"), normalized = w.getString("normalized_text"),
                fileName = af.optString("file_name", "$id.wav"), localPath = dest.absolutePath, cloudKey = af.getString("object_key"),
                startMs = w.getLong("start_ms"), endMs = w.getLong("end_ms"), durationMs = w.getLong("duration_ms"),
                sampleRate = wav.sampleRate, sizeBytes = dest.length(), extractedAt = System.currentTimeMillis(),
                reviewStatus = w.optString("review_status", "APPROVED"), quality = w.optDouble("quality", 0.0), sha256 = sha,
                processingVersion = w.optString("processing_version", "1"), userLocked = w.optBoolean("user_locked", false),
                uploadState = "DONE",
            ))
            if (inserted != -1L) done++
        }
        return done
    }

    /** حذف مكتبة من السحابة (الملفات ثم الصفوف) عبر الخادم؛ يُستدعى قبل الحذف المحلي إذا كانت مزامَنة. */
    /** يحذف كلمة من السحابة إن كانت قد رُفعت؛ يُستدعى قبل الحذف المحلي. */
    suspend fun deleteWordRemote(id: String) { if (canSync) backend.deleteWord(id) }

    suspend fun deleteLibraryRemote(id: String) { if (canSync) backend.deleteLibrary(id) }

    /** حذف الحساب وكل بياناته السحابية، ثم مسح الجلسة المحلية (البيانات المحلية تبقى بقرار المستخدم). */
    suspend fun deleteAccountRemote() { backend.deleteAccount(); auth.clearLocal() }
}
