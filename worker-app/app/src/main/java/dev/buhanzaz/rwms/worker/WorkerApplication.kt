package dev.buhanzaz.rwms.worker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
class WorkerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkerNotifications.createChannels(this)
    }
}
