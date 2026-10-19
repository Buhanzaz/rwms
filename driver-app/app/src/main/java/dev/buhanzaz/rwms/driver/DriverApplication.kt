package dev.buhanzaz.rwms.driver

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class DriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The protected key is injected by Gradle from the same external file
        // used by client-app. It is never persisted or logged by Driver Up.
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        DriverNotifications.createChannels(this)
    }
}
