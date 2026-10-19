package dev.buhanzaz.rwms.driver.feature.shift

import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.traffic.TrafficLayer
import com.yandex.mapkit.traffic.TrafficLevel
import com.yandex.mapkit.traffic.TrafficListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Result of a nonblocking traffic lookup around the assigned warehouse. */
data class TrafficBriefing(
    val level: Int?,
    val available: Boolean,
)

/** Replaceable boundary for current traffic and future route-delay providers. */
interface TrafficBriefingProvider {
    /** Attaches the documented visible MapKit window used by the briefing traffic card. */
    fun attach(mapWindow: MapWindow)

    /** Detaches only the window leaving composition, without disrupting a replacement window. */
    fun detach(mapWindow: MapWindow)

    /** Returns a MapKit 0..10 traffic level, or an unavailable result without blocking the shift. */
    suspend fun load(latitude: Double, longitude: Double): TrafficBriefing
}

/** Official Yandex MapKit traffic-layer implementation backed by the briefing's visible MapWindow. */
@Singleton
class YandexMapKitTrafficBriefingProvider @Inject constructor() : TrafficBriefingProvider {
    private val activeMapWindow = MutableStateFlow<MapWindow?>(null)

    override fun attach(mapWindow: MapWindow) {
        activeMapWindow.value = mapWindow
    }

    override fun detach(mapWindow: MapWindow) {
        activeMapWindow.compareAndSet(mapWindow, null)
    }

    override suspend fun load(latitude: Double, longitude: Double): TrafficBriefing =
        withContext(Dispatchers.Main.immediate) {
            val level = runCatching {
                withTimeoutOrNull(TRAFFIC_TIMEOUT_MILLIS) {
                    awaitTrafficLevel(activeMapWindow.filterNotNull().first(), latitude, longitude)
                }
            }.getOrNull()
            TrafficBriefing(level = level, available = level != null)
        }

    private suspend fun awaitTrafficLevel(
        mapWindow: MapWindow,
        latitude: Double,
        longitude: Double,
    ): Int? =
        suspendCancellableCoroutine { continuation ->
            val mapKit = MapKitFactory.getInstance()
            mapWindow.map.move(
                CameraPosition(Point(latitude, longitude), WAREHOUSE_ZOOM, 0f, 0f),
            )
            val layer = mapKit.createTrafficLayer(mapWindow)
            lateinit var listener: TrafficListener
            val strongListener = arrayOfNulls<TrafficListener>(1)
            var finished = false

            fun finish(value: Int?) {
                if (finished) return
                finished = true
                removeListenerSafely(layer, listener)
                strongListener[0] = null
                if (continuation.isActive) continuation.resume(value)
            }

            listener = object : TrafficListener {
                override fun onTrafficChanged(trafficLevel: TrafficLevel?) {
                    finish(trafficLevel?.level?.takeIf { it in 0..10 })
                }

                override fun onTrafficLoading() = Unit

                override fun onTrafficExpired() {
                    finish(null)
                }
            }
            strongListener[0] = listener
            layer.addTrafficListener(listener)
            layer.isTrafficVisible = true
            continuation.invokeOnCancellation {
                removeListenerSafely(layer, listener)
                strongListener[0] = null
            }
        }

    private fun removeListenerSafely(
        layer: TrafficLayer,
        listener: TrafficListener,
    ) {
        if (layer.isValid) runCatching { layer.removeTrafficListener(listener) }
    }

    private companion object {
        const val TRAFFIC_TIMEOUT_MILLIS = 8_000L
        const val WAREHOUSE_ZOOM = 10f
    }
}

/** Supplies the current MapKit implementation behind the replaceable traffic boundary. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TrafficBriefingModule {
    @Binds
    abstract fun bindTrafficBriefingProvider(
        implementation: YandexMapKitTrafficBriefingProvider,
    ): TrafficBriefingProvider
}
