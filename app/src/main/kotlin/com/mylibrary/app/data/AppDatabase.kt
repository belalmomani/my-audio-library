package com.mylibrary.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryEntity::class, SourceEntity::class, SpeakerEntity::class, JobEntity::class,
        CandidateEntity::class, WordEntity::class, ReviewItemEntity::class, AuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraries(): LibraryDao
    abstract fun sources(): SourceDao
    abstract fun speakers(): SpeakerDao
    abstract fun jobs(): JobDao
    abstract fun candidates(): CandidateDao
    abstract fun words(): WordDao
    abstract fun reviews(): ReviewDao
    abstract fun audit(): AuditDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "library.db").build()
    }
}
