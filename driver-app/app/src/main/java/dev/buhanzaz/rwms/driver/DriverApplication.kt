package dev.buhanzaz.rwms.driver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DriverNotifications.createChannels(this)
    }
}
