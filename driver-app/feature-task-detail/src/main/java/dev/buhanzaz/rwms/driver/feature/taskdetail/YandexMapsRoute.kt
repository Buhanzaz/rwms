package dev.buhanzaz.rwms.driver.feature.taskdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val YANDEX_MAPS_PACKAGE = "ru.yandex.yandexmaps"

/**
 * Builds a Yandex Maps route link for the driver-selected trip.
 *
 * <p>The trip coordinates remain the preferred destination. A confirmed address is used only
 * when the order has no complete coordinate pair. The blank origin lets Yandex Maps use the
 * driver's current location instead of storing it in RWMS.
 */
internal fun yandexMapsRouteUrl(trip: DriverTripDetailsDto): String? {
    val destination = coordinateDestination(trip.latitude, trip.longitude)
        ?: trip.address?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.name())
    return "https://yandex.ru/maps/?rtext=~$encodedDestination&rtt=auto"
}

/**
 * Opens the installed Yandex Maps application, falling back to its HTTPS route in a browser.
 *
 * <p>No routing API request leaves RWMS: this is a driver-initiated hand-off to the maps client.
 * The method returns false only when the trip lacks a destination or no browser can handle HTTPS.
 */
internal fun openYandexMapsRoute(context: Context, trip: DriverTripDetailsDto): Boolean {
    val routeUri = yandexMapsRouteUrl(trip)?.let(Uri::parse) ?: return false
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, routeUri).setPackage(YANDEX_MAPS_PACKAGE),
        )
        return true
    } catch (_: ActivityNotFoundException) {
        // Yandex Maps is optional; the same user-visible route is still usable in a browser.
    }
    val browserIntent = Intent(Intent.ACTION_VIEW, routeUri)
    if (browserIntent.resolveActivity(context.packageManager) == null) return false
    context.startActivity(browserIntent)
    return true
}

private fun coordinateDestination(latitude: Double?, longitude: Double?): String? {
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return "$latitude,$longitude"
}
