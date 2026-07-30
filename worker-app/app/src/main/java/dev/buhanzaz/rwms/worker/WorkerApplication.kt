package dev.buhanzaz.rwms.worker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WorkerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkerNotifications.createChannels(this)
    }
}
