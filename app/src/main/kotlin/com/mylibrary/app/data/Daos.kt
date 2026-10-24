package com.mylibrary.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query(
        "SELECT l.id, l.name, l.createdAt, " +
            "(SELECT COUNT(*) FROM words w WHERE w.libraryId = l.id) AS wordCount, " +
            "(SELECT COALESCE(SUM(sizeBytes),0) FROM words w WHERE w.libraryId = l.id) AS sizeBytes " +
            "FROM libraries l ORDER BY l.createdAt DESC"
    )
    fun observeSummaries(): Flow<List<LibrarySummary>>

    @Query("SELECT * FROM libraries WHERE id = :id") suspend fun get(id: String): LibraryEntity?
    @Query("SELECT * FROM libraries") suspend fun all(): List<LibraryEntity>
    @Query("SELECT * FROM libraries WHERE id = :id") fun observe(id: String): Flow<LibraryEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(l: LibraryEntity)
    @Query("UPDATE libraries SET name = :name WHERE id = :id") suspend fun rename(id: String, name: String)
    @Query("DELETE FROM libraries WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM libraries") fun observeCount(): Flow<Int>
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SourceEntity)
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun get(id: String): SourceEntity?
    @Query("SELECT * FROM sources") suspend fun all(): List<SourceEntity>
    @Query("UPDATE sources SET workPath = :path, durationMs = :dur WHERE id = :id")
    suspend fun setWork(id: String, path: String?, dur: Long)
    @Query("UPDATE sources SET workPath = NULL WHERE id = :id") suspend fun clearWork(id: String)
    @Query(
        "SELECT DISTINCT s.* FROM sources s JOIN words w ON w.sourceId = s.id WHERE w.libraryId = :libraryId"
    ) suspend fun forLibrary(libraryId: String): List<SourceEntity>
}

@Dao
interface SpeakerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<SpeakerEntity>)
    @Query("SELECT * FROM speakers WHERE jobId = :jobId ORDER BY ordinal") fun observeForJob(jobId: String): Flow<List<SpeakerEntity>>
    @Query("SELECT * FROM speakers WHERE jobId = :jobId ORDER BY ordinal") suspend fun forJob(jobId: String): List<SpeakerEntity>
    @Query("UPDATE speakers SET libraryId = :libraryId WHERE id = :id") suspend fun assign(id: String, libraryId: String?)
    @Query("SELECT COUNT(*) FROM speakers") fun observeCount(): Flow<Int>
    @Query("DELETE FROM speakers WHERE jobId = :jobId") suspend fun deleteForJob(jobId: String)
    @Query("DELETE FROM speakers WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM speakers WHERE libraryId = :libraryId") suspend fun forLibrary(libraryId: String): List<SpeakerEntity>
}

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(j: JobEntity)
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: String): JobEntity?
    @Query("SELECT * FROM jobs WHERE id = :id") fun observe(id: String): Flow<JobEntity?>
    @Query("SELECT * FROM jobs ORDER BY startedAt DESC") fun observeAll(): Flow<List<JobEntity>>
    @Query("SELECT COUNT(*) FROM jobs WHERE stage NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELED')")
    fun observeActiveCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM jobs WHERE stage NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELED') AND paused = 0 AND stage <> 'AWAITING_SPEAKER_REVIEW'")
    fun observeRunningCount(): Flow<Int>
    @Query("UPDATE jobs SET stage = :stage, progress = :progress WHERE id = :id")
    suspend fun setStage(id: String, stage: String, progress: Float)
    @Query("UPDATE jobs SET progress = :progress WHERE id = :id") suspend fun setProgress(id: String, progress: Float)
    @Query("UPDATE jobs SET paused = :paused WHERE id = :id") suspend fun setPaused(id: String, paused: Boolean)
    @Query("UPDATE jobs SET lastError = :err, errors = errors + 1 WHERE id = :id") suspend fun addError(id: String, err: String)
    @Query("UPDATE jobs SET serverJobId = :sid, serverSourceKey = :key WHERE id = :id")
    suspend fun setServer(id: String, sid: String?, key: String?)
    @Query("UPDATE jobs SET processedPath = :p WHERE id = :id") suspend fun setProcessed(id: String, p: String?)
    @Query("UPDATE jobs SET speakersFound = :sp, wordsFound = :w WHERE id = :id")
    suspend fun setFound(id: String, sp: Int, w: Int)
    @Query("UPDATE jobs SET warnings = :w WHERE id = :id") suspend fun setWarnings(id: String, w: String)
    @Query(
        "UPDATE jobs SET newWords = newWords + :n, duplicatesSkipped = duplicatesSkipped + :d, needsReview = needsReview + :r, filesSaved = filesSaved + :f WHERE id = :id"
    ) suspend fun addCounts(id: String, n: Int, d: Int, r: Int, f: Int)
    @Query("UPDATE jobs SET finishedAt = :t WHERE id = :id") suspend fun setFinished(id: String, t: Long)
    @Query("UPDATE jobs SET resumeStage = :s WHERE id = :id") suspend fun setResume(id: String, s: String?)
    @Query("UPDATE jobs SET lastError = :e WHERE id = :id") suspend fun setLastError(id: String, e: String?)
    @Query("DELETE FROM jobs WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM jobs WHERE stage IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELED')")
    suspend fun finished(): List<JobEntity>
}

@Dao
interface CandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<CandidateEntity>)
    @Query("SELECT * FROM candidates WHERE jobId = :jobId AND done = 0 AND quality IS NULL LIMIT :n")
    suspend fun unscored(jobId: String, n: Int): List<CandidateEntity>
    @Query("SELECT * FROM candidates WHERE jobId = :jobId AND done = 0") suspend fun pending(jobId: String): List<CandidateEntity>
    @Query("SELECT COUNT(*) FROM candidates WHERE jobId = :jobId") suspend fun count(jobId: String): Int
    @Query("SELECT COUNT(*) FROM candidates WHERE jobId = :jobId AND done = 0") suspend fun pendingCount(jobId: String): Int
    @Query("UPDATE candidates SET quality = :q WHERE id = :id") suspend fun setQuality(id: String, q: Double)
    @Query("UPDATE candidates SET done = 1 WHERE jobId = :jobId") suspend fun markAllDone(jobId: String)
    @Query("UPDATE candidates SET speakerId = :to WHERE jobId = :jobId AND speakerId = :from")
    suspend fun moveSpeaker(jobId: String, from: String, to: String)
    @Query("UPDATE candidates SET speakerId = :to WHERE id IN (:ids)") suspend fun moveCandidates(ids: List<String>, to: String)
    @Query("SELECT * FROM candidates WHERE jobId = :jobId AND speakerId = :speakerId ORDER BY startMs")
    suspend fun forSpeaker(jobId: String, speakerId: String): List<CandidateEntity>
    @Query("DELETE FROM candidates WHERE jobId = :jobId") suspend fun deleteForJob(jobId: String)
}

@Dao
interface WordDao {
    @Query("SELECT * FROM words WHERE libraryId = :lib ORDER BY normalized") fun observeByLibrary(lib: String): Flow<List<WordEntity>>
    @Query("SELECT * FROM words WHERE libraryId = :lib") suspend fun byLibrary(lib: String): List<WordEntity>
    @Query("SELECT * FROM words WHERE id = :id") suspend fun get(id: String): WordEntity?
    @Query("SELECT * FROM words WHERE libraryId = :lib AND normalized = :n") suspend fun find(lib: String, n: String): WordEntity?
    @Query("SELECT id, libraryId, normalized, quality, userLocked FROM words WHERE libraryId IN (:libs)")
    suspend fun existing(libs: List<String>): List<ExistingRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(w: List<WordEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(w: WordEntity): Long
    @Update suspend fun update(w: WordEntity)
    @Query("DELETE FROM words WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE words SET libraryId = :to, uploadState = 'PENDING' WHERE id = :id") suspend fun move(id: String, to: String)
    @Query("SELECT * FROM words WHERE libraryId = :lib AND (text LIKE '%' || :q || '%' OR normalized LIKE '%' || :q || '%') ORDER BY normalized")
    suspend fun searchInLibrary(lib: String, q: String): List<WordEntity>
    @Query("SELECT * FROM words WHERE text LIKE '%' || :q || '%' OR normalized LIKE '%' || :q || '%' ORDER BY normalized LIMIT 200")
    suspend fun searchAll(q: String): List<WordEntity>
    @Query("SELECT COUNT(*) FROM words") fun observeCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM words") fun observeBytes(): Flow<Long>
    @Query("SELECT COUNT(*) FROM words WHERE libraryId = :lib") suspend fun count(lib: String): Int
    @Query("DELETE FROM words WHERE libraryId = :lib") suspend fun deleteByLibrary(lib: String)
    @Query("SELECT * FROM words") suspend fun all(): List<WordEntity>

    // الرفع (استعلامات متزامنة لتُستخدم من خيط عمل خلفي عبر UploadStore)
    @Query("SELECT id, uploadAttempts FROM words WHERE uploadState IN ('PENDING','FAILED','UPLOADING') AND uploadAttempts < :max")
    fun pendingUploadsBlocking(max: Int): List<UploadRow>
    @Query("UPDATE words SET uploadState = :state, lastError = :err, uploadAttempts = uploadAttempts + :inc WHERE id = :id")
    fun markUploadBlocking(id: String, state: String, err: String?, inc: Int)
    @Query("UPDATE words SET cloudKey = :key WHERE id = :id") fun setCloudKeyBlocking(id: String, key: String)
    @Query("SELECT * FROM words WHERE id = :id") fun getBlocking(id: String): WordEntity?
    @Query("SELECT COUNT(*) FROM words WHERE uploadState <> 'DONE'") fun observeUnsynced(): Flow<Int>
    @Query("UPDATE words SET uploadState = 'PENDING', uploadAttempts = 0 WHERE uploadState = 'FAILED'") suspend fun resetFailed()
}

@Dao
interface ReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<ReviewItemEntity>)
    @Query("SELECT * FROM review_items ORDER BY createdAt DESC") fun observeAll(): Flow<List<ReviewItemEntity>>
    @Query("SELECT * FROM review_items WHERE libraryId = :lib ORDER BY startMs") fun observeForLibrary(lib: String): Flow<List<ReviewItemEntity>>
    @Query("SELECT * FROM review_items WHERE id = :id") suspend fun get(id: String): ReviewItemEntity?
    @Query("DELETE FROM review_items WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM review_items") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM review_items WHERE sourceId = :sourceId") suspend fun countForSource(sourceId: String): Int
    @Query("UPDATE review_items SET libraryId = :to WHERE libraryId = :from") suspend fun reassign(from: String, to: String)
}

@Dao
interface AuditDao {
    @Insert suspend fun add(a: AuditEntity)
}
