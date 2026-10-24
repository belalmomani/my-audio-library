package com.mylibrary.core

import kotlin.math.sqrt

data class WordRef(val id: String, val normalized: String, val quality: Double)
data class MergeConflict(val normalized: String, val targetWordId: String, val sourceWordId: String)

data class MergePlan(val movable: List<String>, val conflicts: List<MergeConflict>)
data class MergeResolved(val moveIds: List<String>, val deleteSourceIds: List<String>, val replaceTargetIds: List<String>)

enum class ConflictResolution { KEEP_TARGET, USE_SOURCE, KEEP_BEST }

/** دمج مكتبتين لا يحدث إلا بطلب صريح؛ والتعارضات تُعرض على المستخدم قبل التنفيذ. */
object LibraryMergePlanner {
    fun plan(target: List<WordRef>, source: List<WordRef>): MergePlan {
        val byKey = target.associateBy { it.normalized }
        val movable = mutableListOf<String>()
        val conflicts = mutableListOf<MergeConflict>()
        for (w in source) {
            val t = byKey[w.normalized]
            if (t == null) movable += w.id else conflicts += MergeConflict(w.normalized, t.id, w.id)
        }
        return MergePlan(movable, conflicts)
    }

    fun resolve(plan: MergePlan, target: List<WordRef>, source: List<WordRef>, how: ConflictResolution): MergeResolved {
        val tq = target.associate { it.id to it.quality }
        val sq = source.associate { it.id to it.quality }
        val move = plan.movable.toMutableList()
        val delete = mutableListOf<String>()
        val replace = mutableListOf<String>()
        for (c in plan.conflicts) {
            val useSource = when (how) {
                ConflictResolution.KEEP_TARGET -> false
                ConflictResolution.USE_SOURCE -> true
                ConflictResolution.KEEP_BEST -> (sq[c.sourceWordId] ?: 0.0) > (tq[c.targetWordId] ?: 0.0)
            }
            if (useSource) { replace += c.targetWordId; move += c.sourceWordId } else delete += c.sourceWordId
        }
        return MergeResolved(move, delete, replace)
    }
}

/** اقتراح مطابقة متحدث مكتشف مع مكتبة موجودة عبر بصمة الصوت (اختياري، ولا يحدد هوية حقيقية). */
object VoiceMatcher {
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    fun bestMatch(embedding: FloatArray, libraries: Map<String, FloatArray>, threshold: Double = 0.70): String? =
        libraries.maxByOrNull { cosine(embedding, it.value) }
            ?.takeIf { cosine(embedding, it.value) >= threshold }?.key
}

enum class UploadState { PENDING, UPLOADING, DONE, FAILED }

data class UploadItem(val id: String, val attempts: Int)

interface UploadStore {
    fun pending(maxAttempts: Int): List<UploadItem>
    fun mark(id: String, state: UploadState, error: String? = null)
}

class NetworkUnavailableException(cause: Throwable? = null) : Exception("لا يوجد اتصال بالإنترنت", cause)

data class UploadSummary(val done: Int, val failed: Int, val stoppedOffline: Boolean)

/**
 * رفع قابل للاستئناف: كل عنصر يُعلَّم نجاحًا أو فشلًا على حدة، فلا يعاد رفع ما نجح.
 * انقطاع الشبكة يوقف الدورة ويبقي ما تبقى «بانتظار الرفع» دون احتسابه فشلًا.
 */
class ResumableUploader(private val store: UploadStore, private val maxAttempts: Int = 5) {
    fun run(shouldContinue: () -> Boolean = { true }, upload: (UploadItem) -> Unit): UploadSummary {
        var done = 0
        var failed = 0
        for (item in store.pending(maxAttempts)) {
            if (!shouldContinue()) break
            store.mark(item.id, UploadState.UPLOADING)
            try {
                upload(item)
                store.mark(item.id, UploadState.DONE)
                done++
            } catch (e: NetworkUnavailableException) {
                store.mark(item.id, UploadState.PENDING, e.message)
                return UploadSummary(done, failed, stoppedOffline = true)
            } catch (e: Exception) {
                store.mark(item.id, UploadState.FAILED, e.message ?: e.javaClass.simpleName)
                failed++
            }
        }
        return UploadSummary(done, failed, stoppedOffline = false)
    }
}
