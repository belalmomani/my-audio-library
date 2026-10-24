package com.mylibrary.app.di

import android.app.Application
import com.mylibrary.app.cloud.AuthRepository
import com.mylibrary.app.cloud.BackendApi
import com.mylibrary.app.cloud.CloudSync
import com.mylibrary.app.data.AppDatabase
import com.mylibrary.app.data.LibraryRepository
import com.mylibrary.app.data.SettingsStore
import com.mylibrary.app.work.JobRepository
import com.mylibrary.app.work.JobRunner
import com.mylibrary.app.work.LocalDiarizer
import com.mylibrary.app.work.LocalSpeechModel

/** حاوية تبعيات بسيطة (بدل Hilt) لإبقاء المشروع صغيرًا وسهل القراءة. */
object ServiceLocator {
    lateinit var app: Application
    val settings by lazy { SettingsStore(app) }
    val db by lazy { AppDatabase.create(app) }
    val library by lazy { LibraryRepository(app, db) }
    val auth by lazy { AuthRepository(app) }
    val backend by lazy { BackendApi(auth, settings) }
    val sync by lazy { CloudSync(app, db, auth, backend, settings) }
    val jobs by lazy { JobRepository(app, db, library, settings, backend) }
    val exporter by lazy { com.mylibrary.app.data.Exporter(app, db) }
    val runner by lazy { JobRunner(app, db, library, settings, auth, backend, sync) }

    /** نقاط الإضافة للنماذج المحلية (null = غير مثبّتة). */
    @Volatile var localSpeechModel: LocalSpeechModel? = null
    @Volatile var localDiarizer: LocalDiarizer? = null
}
