package dev.buhanzaz.rwms.driver.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DriverCameraSettingsTest {
    @Test
    fun `photo controls match manager 0_3_29 zoom and setting normalization`() {
        assertThat(driverSupportedZoomStops(0.6f, 4f)).containsExactly(0.6f, 1f, 2f, 3f).inOrder()
        assertThat(driverCoerceZoom(8f, 1f, 4f)).isEqualTo(4f)
        assertThat(driverZoomLabel(0.6f)).isEqualTo("0.6×")
        assertThat(
            normalizeDriverCameraSettings(DriverCameraSettings(exposureEvTenths = 100))
                .exposureEvTenths,
        ).isEqualTo(20)
    }

    @Test
    fun `video tab never changes driver evidence away from JPEG photo mode`() {
        val selection = selectDriverCameraMode(DriverCameraMode.Video)

        assertThat(selection.mode).isEqualTo(DriverCameraMode.Photo)
        assertThat(selection.message).contains("не поддерживается")
    }

    @Test
    fun `night and photo remain selectable`() {
        assertThat(selectDriverCameraMode(DriverCameraMode.Night).mode).isEqualTo(DriverCameraMode.Night)
        assertThat(selectDriverCameraMode(DriverCameraMode.Photo).mode).isEqualTo(DriverCameraMode.Photo)
    }
}
