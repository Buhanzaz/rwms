package dev.buhanzaz.rwms.client.ui

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.Animation
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapType
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.runtime.Error
import com.yandex.runtime.image.ImageProvider
import dev.buhanzaz.rwms.client.R
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Address and coordinate pair resolved by the maintained Yandex geocoder. */
internal data class GeocodedDeliveryLocation(
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** Owns one retained Yandex search session for forward and reverse delivery geocoding. */
internal class YandexDeliveryGeocoder(
    private val searchManager: SearchManager = SearchFactory.getInstance()
        .createSearchManager(SearchManagerType.COMBINED),
) {
    private val gate = LatestDeliveryGeocodeGate()
    private var searchSession: Session? = null
    private var searchListener: Session.SearchListener? = null

    /** Resolves a typed address near the selected warehouse and returns the first toponym point. */
    fun searchAddress(
        query: String,
        depotLatitude: Double,
        depotLongitude: Double,
        onSuccess: (GeocodedDeliveryLocation) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty())
        submit(
            fallbackAddress = normalizedQuery,
            onSuccess = onSuccess,
            onFailure = onFailure,
        ) { listener ->
            searchManager.submit(
                normalizedQuery,
                Geometry.fromPoint(Point(depotLatitude, depotLongitude)),
                deliverySearchOptions(),
                listener,
            )
        }
    }

    /** Resolves a tapped point to a visible address while preserving the exact tapped coordinates. */
    fun reversePoint(
        latitude: Double,
        longitude: Double,
        zoom: Float,
        onSuccess: (GeocodedDeliveryLocation) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val tappedPoint = Point(latitude, longitude)
        submit(
            fallbackAddress = null,
            forcedPoint = tappedPoint,
            onSuccess = onSuccess,
            onFailure = onFailure,
        ) { listener ->
            searchManager.submit(
                tappedPoint,
                zoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt()),
                deliverySearchOptions(),
                listener,
            )
        }
    }

    /** Cancels native work and makes all already queued callbacks stale. */
    fun cancel() {
        gate.invalidate()
        searchSession?.cancel()
        searchSession = null
        searchListener = null
    }

    private fun submit(
        fallbackAddress: String?,
        forcedPoint: Point? = null,
        onSuccess: (GeocodedDeliveryLocation) -> Unit,
        onFailure: (String) -> Unit,
        start: (Session.SearchListener) -> Session,
    ) {
        cancel()
        val token = gate.begin()
        val listener = object : Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                if (!gate.isCurrent(token)) return
                val location = response.firstDeliveryLocation(fallbackAddress, forcedPoint)
                searchSession = null
                searchListener = null
                if (location == null) {
                    onFailure("Яндекс не нашёл адрес для выбранной точки")
                } else {
                    onSuccess(location)
                }
            }

            override fun onSearchError(error: Error) {
                if (!gate.isCurrent(token)) return
                searchSession = null
                searchListener = null
                onFailure("Не удалось получить адрес от Яндекс Карт")
            }
        }
        searchListener = listener
        searchSession = start(listener)
    }
}

/** Full-screen Yandex MapKit picker with map style, zoom, and selected-point recenter controls. */
@Composable
internal fun DeliveryMapPointPicker(
    latitude: Double?,
    longitude: Double?,
    depotLatitude: Double,
    depotLongitude: Double,
    onPoint: (Double, Double, Float) -> Unit,
    modifier: Modifier = Modifier,
    bottomControlsClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnPoint by rememberUpdatedState(onPoint)
    val initialTarget = deliveryMapInitialTarget(latitude, longitude, depotLatitude, depotLongitude)
    val mapHandle = remember(context, depotLatitude, depotLongitude) {
        YandexDeliveryMapHandle(
            context = context,
            initialLatitude = initialTarget.first,
            initialLongitude = initialTarget.second,
            initialZoom = if (latitude == null || longitude == null) DEPOT_ZOOM else DELIVERY_ZOOM,
            onPoint = { point, zoom -> latestOnPoint(point.latitude, point.longitude, zoom) },
        )
    }
    DisposableEffect(lifecycleOwner, mapHandle) {
        mapHandle.attach(lifecycleOwner)
        onDispose { mapHandle.detach(lifecycleOwner) }
    }
    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapHandle.mapView.apply {
                    setOnTouchListener { view, event ->
                        val interacting = event.actionMasked != MotionEvent.ACTION_UP &&
                            event.actionMasked != MotionEvent.ACTION_CANCEL
                        view.parent?.requestDisallowInterceptTouchEvent(interacting)
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        false
                    }
                }
            },
            update = { mapHandle.renderPoint(latitude, longitude) },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd),
                shape = MAP_CONTROL_SHAPE,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 5.dp,
            ) {
                IconButton(onClick = mapHandle::changeMapType, modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Default.Layers, contentDescription = "Сменить вид карты", tint = Color(0xFF303236))
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd),
                shape = MAP_CONTROL_SHAPE,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 5.dp,
            ) {
                Column {
                    IconButton(onClick = { mapHandle.changeZoom(1f) }, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Приблизить карту", tint = Color(0xFF303236))
                    }
                    HorizontalDivider()
                    IconButton(onClick = { mapHandle.changeZoom(-1f) }, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "Отдалить карту", tint = Color(0xFF303236))
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = bottomControlsClearance),
                shape = MAP_CONTROL_SHAPE,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 5.dp,
            ) {
                IconButton(
                    onClick = {
                        mapHandle.recenter(
                            latitude = latitude ?: depotLatitude,
                            longitude = longitude ?: depotLongitude,
                            zoom = if (latitude == null || longitude == null) DEPOT_ZOOM else DELIVERY_ZOOM,
                        )
                    },
                    modifier = Modifier.size(54.dp),
                ) {
                    Icon(Icons.Default.NearMe, contentDescription = "Вернуться к выбранной точке", tint = Color(0xFF303236))
                }
            }
        }
    }
}

/** Retains the native map view, weak-listener targets, marker, and camera state for one composition. */
private class YandexDeliveryMapHandle(
    context: Context,
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Float,
    private val onPoint: (Point, Float) -> Unit,
) {
    val mapView: MapView = MapView(context).apply {
        contentDescription = "Карта выбора точки доставки"
    }
    private val map: Map = mapView.mapWindow.map
    private val markerImage = ImageProvider.fromResource(context, R.drawable.ic_delivery_pin)
    private val lifecycleObserver = YandexMapLifecycleObserver(mapView)
    private var renderedPoint: Pair<Double, Double>? = null
    private val inputListener = object : InputListener {
        override fun onMapTap(map: Map, point: Point) {
            onPoint(point, map.cameraPosition.zoom)
        }

        override fun onMapLongTap(map: Map, point: Point) {
            onPoint(point, map.cameraPosition.zoom)
        }
    }

    init {
        map.addInputListener(WeakReference(inputListener))
        map.mapType = MapType.VECTOR_MAP
        map.set2DMode(true)
        map.move(CameraPosition(Point(initialLatitude, initialLongitude), initialZoom, 0f, 0f))
    }

    /** Attaches the balanced MapKit/MapView lifecycle pair to the current host. */
    fun attach(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    /** Stops native rendering when the map destination leaves composition. */
    fun detach(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(lifecycleObserver)
        lifecycleObserver.stopIfStarted()
    }

    /** Replaces the visible marker and recenters only when the confirmed/candidate point changes. */
    fun renderPoint(latitude: Double?, longitude: Double?) {
        val point = if (latitude != null && longitude != null) latitude to longitude else null
        if (point == renderedPoint) return
        renderedPoint = point
        map.mapObjects.clear()
        if (point == null) return
        val mapPoint = Point(point.first, point.second)
        map.mapObjects.addPlacemark().apply {
            geometry = mapPoint
            setIcon(markerImage)
        }
        map.move(
            CameraPosition(mapPoint, DELIVERY_ZOOM, map.cameraPosition.azimuth, 0f),
            SMOOTH_ANIMATION,
            null,
        )
    }

    /** Changes zoom around the current camera target while retaining pan/rotation state. */
    fun changeZoom(delta: Float) {
        val current = map.cameraPosition
        map.move(
            CameraPosition(
                current.target,
                (current.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM),
                current.azimuth,
                current.tilt,
            ),
            SMOOTH_ANIMATION,
            null,
        )
    }

    /** Toggles the Yandex base layers permitted for third-party MapKit applications. */
    fun changeMapType() {
        map.mapType = when (map.mapType) {
            MapType.VECTOR_MAP -> MapType.MAP
            else -> MapType.VECTOR_MAP
        }
    }

    /** Returns the camera to the selected delivery point or the warehouse depot. */
    fun recenter(latitude: Double, longitude: Double, zoom: Float) {
        val current = map.cameraPosition
        map.move(
            CameraPosition(Point(latitude, longitude), zoom, current.azimuth, 0f),
            SMOOTH_ANIMATION,
            null,
        )
    }
}

/** Balances MapKit singleton and MapView start/stop calls for the map destination lifecycle. */
private class YandexMapLifecycleObserver(private val mapView: MapView) : DefaultLifecycleObserver {
    private var started = false

    override fun onStart(owner: LifecycleOwner) {
        if (started) return
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        started = true
    }

    override fun onStop(owner: LifecycleOwner) {
        stopIfStarted()
    }

    /** Stops both native owners exactly once, including composition disposal while STARTED. */
    fun stopIfStarted() {
        if (!started) return
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        started = false
    }
}

private fun deliverySearchOptions(): SearchOptions = SearchOptions().apply {
    searchTypes = SearchType.GEO.value
    resultPageSize = 5
}

private fun Response.firstDeliveryLocation(
    fallbackAddress: String?,
    forcedPoint: Point?,
): GeocodedDeliveryLocation? = collection.children.asSequence()
    .mapNotNull { it.obj }
    .mapNotNull { geoObject -> geoObject.toDeliveryLocation(fallbackAddress, forcedPoint) }
    .firstOrNull()

private fun GeoObject.toDeliveryLocation(
    fallbackAddress: String?,
    forcedPoint: Point?,
): GeocodedDeliveryLocation? {
    val point = forcedPoint ?: geometry.firstNotNullOfOrNull { it.point } ?: return null
    val address = deliveryDisplayAddress(descriptionText, name, fallbackAddress)
    if (address.isBlank()) return null
    return GeocodedDeliveryLocation(address, point.latitude, point.longitude)
}

private const val DEPOT_ZOOM = 10f
private const val DELIVERY_ZOOM = 15f
private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 21f
private val SMOOTH_ANIMATION = Animation(Animation.Type.SMOOTH, 0.25f)
private val MAP_CONTROL_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
