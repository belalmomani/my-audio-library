package com.mylibrary.app.cloud

import com.mylibrary.app.BuildConfig
import com.mylibrary.app.data.SettingsStore
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Presigned(val url: String, val contentType: String?)
data class ServerJob(val stage: String, val progress: Float, val error: String?, val warnings: List<String>, val result: JSONObject?)

/** عميل خادم المعالجة (server/) — كل الاستدعاءات بتوكن المستخدم؛ لا مفاتيح سرية في التطبيق. */
class BackendApi(private val auth: AuthRepository, private val settings: SettingsStore) {
    val baseUrl: String get() = settings.current.backendUrl.ifBlank { BuildConfig.BACKEND_URL }.trimEnd('/')
    val isConfigured: Boolean get() = baseUrl.startsWith("https://")

    private suspend fun builder(path: String): Request.Builder {
        val token = auth.validToken() ?: throw IllegalStateException("سجّل الدخول أولًا")
        if (!isConfigured) throw IllegalStateException("لم يُضبط عنوان خادم المعالجة (HTTPS)")
        return Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token")
    }

    suspend fun presign(key: String, method: String, contentType: String = "application/octet-stream", size: Long = 0): Presigned {
        val b = builder("/v1/storage/presign").post(Http.jsonBody(
            JSONObject().put("key", key).put("method", method).put("content_type", contentType).put("size", size))).build()
        val j = JSONObject(Http.io { Http.execOk(b) })
        return Presigned(j.getString("url"), j.optJSONObject("headers")?.optString("Content-Type"))
    }

    suspend fun put(p: Presigned, body: okhttp3.RequestBody) {
        val req = Request.Builder().url(p.url).put(body).apply { p.contentType?.let { header("Content-Type", it) } }.build()
        Http.io { Http.execOk(req) }
    }

    suspend fun putFile(p: Presigned, f: File, type: String) = put(p, Http.fileBody(type, f))

    suspend fun download(p: Presigned, dest: File) {
        Http.io {
            dest.parentFile?.mkdirs()
            Http.client.newCall(Request.Builder().url(p.url).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpException(r.code, "download")
                dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
        }
    }

    suspend fun createJob(sourceKey: String, separate: Boolean, diarize: Boolean, language: String, keepSource: Boolean): String {
        val body = JSONObject().put("source_key", sourceKey).put("consent", true).put("options",
            JSONObject().put("separate", separate).put("diarize", diarize).put("language", language).put("keep_source", keepSource))
        val b = builder("/v1/jobs").post(Http.jsonBody(body)).build()
        return JSONObject(Http.io { Http.execOk(b) }).getString("id")
    }

    suspend fun getJob(id: String): ServerJob {
        val b = builder("/v1/jobs/$id").get().build()
        val j = JSONObject(Http.io { Http.execOk(b) })
        val w = j.optJSONArray("warnings") ?: JSONArray()
        return ServerJob(j.getString("stage"), j.optDouble("progress", 0.0).toFloat(), j.optString("error").takeIf { it.isNotBlank() && it != "null" },
            List(w.length()) { w.getString(it) }, j.optJSONObject("result"))
    }

    suspend fun cancelJob(id: String) { val b = builder("/v1/jobs/$id/cancel").post(Http.jsonBody(JSONObject())).build(); Http.io { Http.execOk(b) } }
    suspend fun resumeJob(id: String) { val b = builder("/v1/jobs/$id/resume").post(Http.jsonBody(JSONObject())).build(); Http.io { Http.execOk(b) } }
    suspend fun deleteJob(id: String) { val b = builder("/v1/jobs/$id").delete().build(); Http.io { Http.execOk(b) } }
    suspend fun deleteWord(id: String) { val b = builder("/v1/words/$id").delete().build(); Http.io { Http.execOk(b) } }
    suspend fun deleteLibrary(id: String) { val b = builder("/v1/libraries/$id").delete().build(); Http.io { Http.execOk(b) } }
    suspend fun deleteAccount() { val b = builder("/v1/account").delete().build(); Http.io { Http.execOk(b) } }
}
