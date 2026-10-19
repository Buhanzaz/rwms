package dev.buhanzaz.rwms.client.ui

import android.location.LocationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Focused policy tests for current-location providers, marker state, and map appearance. */
class YandexDeliveryMapLocationTest {
    @Test
    fun `enabled android location providers are ordered without inventing a provider`() {
        val providers = preferredCurrentLocationProviders(
            listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER),
        )

        assertThat(providers)
            .containsExactly(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .inOrder()
        assertThat(providers).doesNotContain(LocationManager.GPS_PROVIDER)
    }

    @Test
    fun `location permission is requested only when arrow action lacks a grant`() {
        assertThat(shouldRequestDeliveryLocationPermission(permissionGranted = false)).isTrue()
        assertThat(shouldRequestDeliveryLocationPermission(permissionGranted = true)).isFalse()
    }

    @Test
    fun `selected point and dark appearance travel in one map render state`() {
        val state = deliveryMapRenderState(59.9355, 30.3274, nightModeEnabled = true)

        assertThat(state.selectedPoint).isEqualTo(59.9355 to 30.3274)
        assertThat(state.nightModeEnabled).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial map coordinate cannot silently hide its marker`() {
        deliveryMapRenderState(latitude = 59.9355, longitude = null, nightModeEnabled = false)
    }

    @Test
    fun `suggestion bounds surround selected spb warehouse`() {
        val bounds = deliverySuggestionBoundsCoordinates(59.9355, 30.3274)

        assertThat(bounds.southLatitude).isLessThan(59.9355)
        assertThat(bounds.westLongitude).isLessThan(30.3274)
        assertThat(bounds.northLatitude).isGreaterThan(59.9355)
        assertThat(bounds.eastLongitude).isGreaterThan(30.3274)
    }
}
