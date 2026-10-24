package com.mylibrary.app

import android.app.Application
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.app.work.Notifications

class MyLibraryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.app = this
        Notifications.ensureChannel(this)
        com.mylibrary.app.ui.Ads.init(this)
    }
}
