package dev.buhanzaz.rwms.client

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import com.yandex.mapkit.MapKitFactory
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient

/** Initializes the customer graph, protected Yandex MapKit key, and authenticated media loader. */
@HiltAndroidApp
class CustomerApplication : Application(), SingletonImageLoader.Factory {
    /** Authenticated client shared with Coil so protected cabin photos receive the current Bearer token. */
    @Inject
    @Named("customer")
    lateinit var customerHttpClient: OkHttpClient

    /** Server inbox delivery is available even when the booking screen is not open. */
    @Inject
    lateinit var notifications: dev.buhanzaz.rwms.client.notifications.CustomerNotifications

    override fun onCreate() {
        super.onCreate()
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        notifications.start()
    }

    /** Builds the process image loader on the same authenticated, refresh-capable HTTP boundary. */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { customerHttpClient }))
        }
        .build()
}
