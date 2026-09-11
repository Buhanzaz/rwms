package dev.buhanzaz.rwms.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.Animation
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.logo.HorizontalAlignment
import com.yandex.mapkit.logo.VerticalAlignment
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapType
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.SuggestItem
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import com.yandex.runtime.Error
import com.yandex.runtime.image.ImageProvider
import dev.buhanzaz.rwms.client.R
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Address and coordinate pair resolved by the maintained Yandex geocoder. */
internal data class GeocodedDeliveryLocation(
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** One Yandex geo-suggestion that can be rendered and resolved into an exact map point. */
internal data class DeliveryAddressSuggestion(
    val displayText: String,
    val searchText: String,
    val latitude: Double?,
    val longitude: Double?,
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

/** Owns the retained Yandex suggest session used while the customer types an address. */
internal class YandexDeliverySuggestSession(
    searchManager: SearchManager = SearchFactory.getInstance()
        .createSearchManager(SearchManagerType.COMBINED),
) {
    private val gate = LatestDeliveryGeocodeGate()
    private val suggestSession = searchManager.createSuggestSession()
    private var suggestListener: SuggestSession.SuggestListener? = null

    /** Requests current geo suggestions around the selected warehouse. */
    fun suggest(
        query: String,
        depotLatitude: Double,
        depotLongitude: Double,
        onSuccess: (List<DeliveryAddressSuggestion>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_SUGGEST_QUERY_LENGTH) {
            cancel()
            onSuccess(emptyList())
            return
        }
        cancel()
        val token = gate.begin()
        val listener = object : SuggestSession.SuggestListener {
            override fun onResponse(response: SuggestResponse) {
                if (!gate.isCurrent(token)) return
                gate.invalidate()
                suggestListener = null
                onSuccess(response.deliveryAddressSuggestions())
            }

            override fun onError(error: Error) {
                if (!gate.isCurrent(token)) return
                gate.invalidate()
                suggestListener = null
                onFailure("Не удалось получить подсказки Яндекс Карт")
            }
        }
        suggestListener = listener
        suggestSession.suggest(
            normalizedQuery,
            deliverySuggestionBounds(depotLatitude, depotLongitude),
            deliverySuggestOptions(depotLatitude, depotLongitude),
            listener,
        )
    }

    /** Cancels the native request and rejects callbacks from any superseded query. */
    fun cancel() {
        gate.invalidate()
        suggestSession.reset()
        suggestListener = null
    }
}

/**
 * Requests one foreground Android position from every enabled provider and accepts the first
 * valid result. The request is explicitly cancellable and never starts background tracking.
 */
internal class AndroidCurrentLocationProvider(
    context: Context,
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager,
    private val executor: Executor = ContextCompat.getMainExecutor(context),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val gate = LatestDeliveryGeocodeGate()
    private val cancellationSignals = mutableListOf<CancellationSignal>()
    private var timeoutAction: Runnable? = null

    /** Delivers the first valid enabled-provider result or one explicit actionable failure. */
    @Suppress("MissingPermission")
    fun request(
        onSuccess: (Double, Double) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        cancel()
        val token = gate.begin()
        val providers = runCatching {
            preferredCurrentLocationProviders(locationManager.getProviders(true))
        }.getOrElse {
            finishFailure(token, onFailure, "Сервис определения местоположения сейчас недоступен")
            return
        }
        if (providers.isEmpty()) {
            finishFailure(
                token,
                onFailure,
                "Не удалось определить местоположение. Проверьте, включена ли геолокация",
            )
            return
        }
        var pendingProviders = providers.size
        val providerFailed: () -> Unit = {
            if (gate.isCurrent(token)) {
                pendingProviders--
                if (pendingProviders == 0) {
                    finishFailure(
                        token,
                        onFailure,
                        "Не удалось получить текущую точку. Попробуйте ещё раз на открытом месте",
                    )
                }
            }
        }
        providers.forEach { provider ->
            val signal = CancellationSignal()
            cancellationSignals += signal
            runCatching {
                locationManager.getCurrentLocation(provider, signal, executor) { location ->
                    if (!gate.isCurrent(token)) return@getCurrentLocation
                    if (location == null ||
                        location.latitude !in -90.0..90.0 ||
                        location.longitude !in -180.0..180.0
                    ) {
                        providerFailed()
                    } else {
                        finishSuccess(token, onSuccess, location.latitude, location.longitude)
                    }
                }
            }.onFailure { providerFailed() }
        }
        if (gate.isCurrent(token)) {
            Runnable {
                finishFailure(
                    token,
                    onFailure,
                    "Определение местоположения заняло слишком много времени. Попробуйте ещё раз",
                )
            }.also { action ->
                timeoutAction = action
                handler.postDelayed(action, CURRENT_LOCATION_TIMEOUT_MILLIS)
            }
        }
    }

    /** Cancels every platform provider request and rejects all of their late callbacks. */
    fun cancel() {
        gate.invalidate()
        clearAttempt()
    }

    private fun finishSuccess(
        token: Long,
        callback: (Double, Double) -> Unit,
        latitude: Double,
        longitude: Double,
    ) {
        if (!gate.isCurrent(token)) return
        gate.invalidate()
        clearAttempt()
        callback(latitude, longitude)
    }

    private fun finishFailure(token: Long, callback: (String) -> Unit, message: String) {
        if (!gate.isCurrent(token)) return
        gate.invalidate()
        clearAttempt()
        callback(message)
    }

    private fun clearAttempt() {
        timeoutAction?.let(handler::removeCallbacks)
        timeoutAction = null
        cancellationSignals.forEach(CancellationSignal::cancel)
        cancellationSignals.clear()
    }
}

/** Orders only Android providers that are currently enabled for a foreground one-shot request. */
internal fun preferredCurrentLocationProviders(enabledProviders: Collection<String>): List<String> {
    val enabled = enabledProviders.toSet()
    return listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).filter(enabled::contains)
}

/** Full-screen Yandex map picker with zoom and an explicit current-location control. */
@Composable
internal fun DeliveryMapPointPicker(
    latitude: Double?,
    longitude: Double?,
    depotLatitude: Double,
    depotLongitude: Double,
    onPoint: (Double, Double, Float) -> Unit,
    onCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier,
    bottomControlsClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    // The panel measurement excludes IME padding and includes its own navigation-bar inset.
    // Attribution sits above the entire search panel, including its downward suggestions.
    val logoBottom = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        imeBottom + with(density) { bottomControlsClearance.roundToPx() },
    )
    val latestOnPoint by rememberUpdatedState(onPoint)
    val latestOnCurrentLocation by rememberUpdatedState(onCurrentLocation)
    val initialTarget = deliveryMapInitialTarget(latitude, longitude, depotLatitude, depotLongitude)
    val mapHandle = remember(context, depotLatitude, depotLongitude) {
        YandexDeliveryMapHandle(
            context = context,
            initialLatitude = initialTarget.first,
            initialLongitude = initialTarget.second,
            initialZoom = if (latitude == null || longitude == null) DEPOT_ZOOM else DELIVERY_ZOOM,
            initialNightMode = darkTheme,
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
            update = {
                mapHandle.render(deliveryMapRenderState(latitude, longitude, darkTheme))
                mapHandle.positionAttribution(with(density) { 16.dp.roundToPx() }, logoBottom.coerceAtLeast(1))
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (imeBottom == 0) Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.figmaButtonShadow(MAP_CONTROL_SHAPE),
                    shape = MAP_CONTROL_SHAPE,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    IconButton(onClick = { mapHandle.changeZoom(1f) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Приблизить карту",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.figmaButtonShadow(MAP_CONTROL_SHAPE),
                    shape = MAP_CONTROL_SHAPE,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    IconButton(onClick = { mapHandle.changeZoom(-1f) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = "Отдалить карту",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = bottomControlsClearance)
                    .figmaButtonShadow(MAP_CONTROL_SHAPE),
                shape = MAP_CONTROL_SHAPE,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                IconButton(
                    onClick = { latestOnCurrentLocation() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.NearMe,
                        contentDescription = "Определить моё местоположение",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Immutable native-map presentation used to keep marker and night mode in one render update. */
internal data class DeliveryMapRenderState(
    val selectedPoint: Pair<Double, Double>?,
    val nightModeEnabled: Boolean,
)

/** Validates a complete optional coordinate pair and produces the native-map presentation. */
internal fun deliveryMapRenderState(
    latitude: Double?,
    longitude: Double?,
    nightModeEnabled: Boolean,
): DeliveryMapRenderState {
    require((latitude == null) == (longitude == null)) { "Map point must contain both coordinates" }
    val point = if (latitude == null || longitude == null) {
        null
    } else {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        latitude to longitude
    }
    return DeliveryMapRenderState(point, nightModeEnabled)
}

/** Retains the native map view, weak-listener targets, marker, and camera state for one composition. */
private class YandexDeliveryMapHandle(
    context: Context,
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Float,
    initialNightMode: Boolean,
    private val onPoint: (Point, Float) -> Unit,
) {
    // Movable MapKit views use TextureView so their pixels follow Compose page transitions.
    val mapView: MapView = (LayoutInflater.from(context).inflate(R.layout.delivery_map_view, null, false) as MapView).apply {
        contentDescription = "Карта выбора точки доставки"
    }
    private val map: Map = mapView.mapWindow.map
    private val markerImage = deliveryMarkerImage(context)
    private val markerStyle = IconStyle()
        .setAnchor(PointF(0.5f, 1f))
        .setScale(1f)
        .setZIndex(10f)
    private val lifecycleObserver = YandexMapLifecycleObserver(mapView)
    private var renderedPoint: Pair<Double, Double>? = null
    private var renderedNightMode: Boolean? = null
    private var marker: PlacemarkMapObject? = null
    private val inputListener = object : InputListener {
        override fun onMapTap(map: Map, point: Point) {
            renderNativePoint(point, recenter = false)
            onPoint(point, map.cameraPosition.zoom)
        }

        override fun onMapLongTap(map: Map, point: Point) {
            renderNativePoint(point, recenter = false)
            onPoint(point, map.cameraPosition.zoom)
        }
    }

    init {
        map.addInputListener(WeakReference(inputListener))
        map.mapType = MapType.VECTOR_MAP
        map.set2DMode(true)
        renderAppearance(initialNightMode)
        map.logo.setAlignment(com.yandex.mapkit.logo.Alignment(HorizontalAlignment.LEFT, VerticalAlignment.BOTTOM))
        map.move(CameraPosition(Point(initialLatitude, initialLongitude), initialZoom, 0f, 0f))
    }

    /** Keeps provider attribution in the reserved bottom-left gap above system UI or the keyboard. */
    fun positionAttribution(horizontalPadding: Int, bottomPadding: Int) {
        map.logo.setPadding(com.yandex.mapkit.logo.Padding(horizontalPadding, bottomPadding))
    }

    private fun renderAppearance(nightMode: Boolean) {
        if (renderedNightMode == nightMode) return
        map.isNightModeEnabled = nightMode
        map.setMapStyle(deliveryMapStyle(nightMode))
        renderedNightMode = nightMode
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

    /** Applies dark-map state and keeps one persistent marker for the confirmed/candidate point. */
    fun render(state: DeliveryMapRenderState) {
        renderAppearance(state.nightModeEnabled)
        val point = state.selectedPoint
        if (point == renderedPoint) return
        if (point == null) {
            renderedPoint = null
            marker?.let(map.mapObjects::remove)
            marker = null
            return
        }
        renderNativePoint(Point(point.first, point.second), recenter = true)
    }

    private fun renderNativePoint(mapPoint: Point, recenter: Boolean) {
        renderedPoint = mapPoint.latitude to mapPoint.longitude
        val currentMarker = marker
        if (currentMarker == null) {
            marker = map.mapObjects.addPlacemark().apply {
                geometry = mapPoint
                setIcon(markerImage, markerStyle)
            }
        } else {
            currentMarker.geometry = mapPoint
            currentMarker.setIcon(markerImage, markerStyle)
        }
        if (!recenter) return
        map.move(
            CameraPosition(mapPoint, DELIVERY_ZOOM, map.cameraPosition.azimuth, 0f),
            smoothMapAnimation(),
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
            smoothMapAnimation(),
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

private fun deliverySuggestOptions(depotLatitude: Double, depotLongitude: Double): SuggestOptions =
    SuggestOptions().apply {
        suggestTypes = SuggestType.GEO.value
        userPosition = Point(depotLatitude, depotLongitude)
        suggestWords = false
        strictBounds = false
    }

/** Pure latitude/longitude limits for the Yandex address-suggestion request. */
internal data class DeliverySuggestionBoundsCoordinates(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
)

/** Returns broad regional coordinate limits centered on the selected warehouse. */
internal fun deliverySuggestionBoundsCoordinates(
    depotLatitude: Double,
    depotLongitude: Double,
): DeliverySuggestionBoundsCoordinates = DeliverySuggestionBoundsCoordinates(
    southLatitude = (depotLatitude - SUGGEST_LATITUDE_RADIUS).coerceIn(-90.0, 90.0),
    westLongitude = (depotLongitude - SUGGEST_LONGITUDE_RADIUS).coerceIn(-180.0, 180.0),
    northLatitude = (depotLatitude + SUGGEST_LATITUDE_RADIUS).coerceIn(-90.0, 90.0),
    eastLongitude = (depotLongitude + SUGGEST_LONGITUDE_RADIUS).coerceIn(-180.0, 180.0),
)

/** Converts pure regional limits to the Yandex MapKit search boundary. */
internal fun deliverySuggestionBounds(depotLatitude: Double, depotLongitude: Double): BoundingBox {
    val bounds = deliverySuggestionBoundsCoordinates(depotLatitude, depotLongitude)
    return BoundingBox(
        Point(bounds.southLatitude, bounds.westLongitude),
        Point(bounds.northLatitude, bounds.eastLongitude),
    )
}

private fun SuggestResponse.deliveryAddressSuggestions(): List<DeliveryAddressSuggestion> = items.asSequence()
    .mapNotNull(SuggestItem::toDeliveryAddressSuggestion)
    .distinctBy { suggestion ->
        listOf(
            suggestion.searchText.lowercase(),
            suggestion.latitude?.toString().orEmpty(),
            suggestion.longitude?.toString().orEmpty(),
        ).joinToString("|")
    }
    .take(MAX_ADDRESS_SUGGESTIONS)
    .toList()

private fun SuggestItem.toDeliveryAddressSuggestion(): DeliveryAddressSuggestion? {
    val display = displayText?.trim().orEmpty().ifEmpty {
        listOf(title?.text?.trim().orEmpty(), subtitle?.text?.trim().orEmpty())
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
    }
    val query = searchText?.trim().orEmpty().ifEmpty { display }
    if (display.isEmpty() || query.isEmpty()) return null
    return DeliveryAddressSuggestion(
        displayText = display,
        searchText = query,
        latitude = center?.latitude,
        longitude = center?.longitude,
    )
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

/** Renders the imported SVG wordmark at display density with a tip anchored to the selected point. */
private fun deliveryMarkerImage(context: Context): ImageProvider {
    val density = context.resources.displayMetrics.density
    val width = (104f * density).roundToInt().coerceAtLeast(1)
    val height = (56f * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { scale(density, density) }
    val pin = Path().apply {
        moveTo(44f, 43f)
        lineTo(52f, 56f)
        lineTo(60f, 43f)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    canvas.drawPath(pin, paint)
    canvas.drawRoundRect(0f, 0f, 104f, 46f, 8f, 8f, paint)
    val logo = requireNotNull(ContextCompat.getDrawable(context, R.drawable.block_box_logo_svg))
    logo.setBounds(8, 5, 96, 41)
    logo.draw(canvas)
    return ImageProvider.fromBitmap(bitmap, true, "block-box-svg-delivery-pin-${context.resources.displayMetrics.densityDpi}")
}

/** MapKit styling changes presentation only; roads, addresses and provider labels remain native. */
private fun deliveryMapStyle(nightMode: Boolean): String {
    val land = if (nightMode) "#1C3247" else "#EDF5FB"
    val water = if (nightMode) "#173F60" else "#AED8EF"
    val building = if (nightMode) "#2E506C" else "#D9E7F1"
    val road = if (nightMode) "#42627B" else "#FFFFFF"
    val text = if (nightMode) "#DFEFFA" else "#31506B"
    return """[
        {"elements":"geometry","stylers":{"hue":"#549AC5","saturation":-0.25}},
        {"tags":{"any":["landscape"]},"elements":"geometry.fill","stylers":{"color":"$land"}},
        {"tags":{"any":["water"]},"elements":"geometry.fill","stylers":{"color":"$water"}},
        {"tags":{"any":["building"]},"elements":"geometry.fill","stylers":{"color":"$building"}},
        {"tags":{"any":["road"]},"elements":"geometry.fill","stylers":{"color":"$road"}},
        {"elements":"label.text.fill","stylers":{"color":"$text"}},
        {"elements":"label.text.outline","stylers":{"color":"$land"}}
    ]""".trimIndent()
}

private const val DEPOT_ZOOM = 10f
private const val DELIVERY_ZOOM = 15f
private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 21f
private const val MIN_SUGGEST_QUERY_LENGTH = 2
private const val MAX_ADDRESS_SUGGESTIONS = 5
private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 15_000L
private const val SUGGEST_LATITUDE_RADIUS = 2.5
private const val SUGGEST_LONGITUDE_RADIUS = 4.0
private fun smoothMapAnimation(): Animation = Animation(Animation.Type.SMOOTH, 0.25f)
private val MAP_CONTROL_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
