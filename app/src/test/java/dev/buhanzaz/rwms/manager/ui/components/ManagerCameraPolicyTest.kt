package dev.buhanzaz.rwms.manager.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ManagerCameraPolicyTest {
    @Test
    fun `camera exposes only the requested modes`() {
        assertThat(ManagerCameraMode.entries.map(ManagerCameraMode::label))
            .containsExactly("НОЧЬ", "ФОТО", "ВИДЕО")
            .inOrder()
    }

    @Test
    fun `zoom controls are derived from the bound CameraInfo range`() {
        assertThat(managerSupportedZoomStops(0.7f, 3.6f))
            .containsExactly(0.7f, 1f, 2f, 3f)
            .inOrder()
        assertThat(managerSupportedZoomStops(1.25f, 1.25f))
            .containsExactly(1.25f)
    }

    @Test
    fun `requested zoom is always constrained to the supported lens range`() {
        assertThat(managerCoerceZoom(0.5f, 0.7f, 3.6f)).isEqualTo(0.7f)
        assertThat(managerCoerceZoom(2f, 0.7f, 3.6f)).isEqualTo(2f)
        assertThat(managerCoerceZoom(9f, 0.7f, 3.6f)).isEqualTo(3.6f)
    }

    @Test
    fun `logical camera exposes 0 point 6 wide stop only when hardware supports it`() {
        assertThat(
            managerCaptureMinimumZoom(
                hardwareMinimum = 0.5f,
                hardwareMaximum = 8f,
                selectedLensIsPhysical = false,
            ),
        ).isEqualTo(0.6f)
        assertThat(
            managerCaptureMinimumZoom(
                hardwareMinimum = 0.7f,
                hardwareMaximum = 8f,
                selectedLensIsPhysical = false,
            ),
        ).isEqualTo(0.7f)
        assertThat(
            managerCaptureMinimumZoom(
                hardwareMinimum = 0.4f,
                hardwareMaximum = 0.55f,
                selectedLensIsPhysical = false,
            ),
        ).isEqualTo(0.4f)
        assertThat(
            managerCaptureMinimumZoom(
                hardwareMinimum = 0.5f,
                hardwareMaximum = 8f,
                selectedLensIsPhysical = true,
            ),
        ).isEqualTo(1f)
        assertThat(managerCaptureMaximumZoom(0.5f, 8f)).isEqualTo(8f)
    }

    @Test
    fun `optical lens ratio is presented as a stable camera stop`() {
        assertThat(managerNormalizedLensZoomRatio(0.58f)).isEqualTo(0.6f)
        assertThat(managerNormalizedLensZoomRatio(1.97f)).isEqualTo(2f)
    }

    @Test
    fun `high resolution request selects the sensor that really exposes it`() {
        val lenses = listOf(
            ManagerCameraLensProfile(
                key = "wide",
                displayZoom = 0.6f,
                pixelCounts = listOf(12_000_000L),
            ),
            ManagerCameraLensProfile(
                key = "main",
                displayZoom = 1f,
                pixelCounts = listOf(50_331_648L, 12_582_912L),
                isDefault = true,
            ),
        )

        assertThat(managerPreferredCameraLensKey(lenses, "wide", 50)).isEqualTo("main")
        assertThat(managerPreferredCameraLensKey(lenses, "wide", 12)).isEqualTo("wide")
    }

    @Test
    fun `physical camera remains selectable when vendor omits its own jpeg table`() {
        assertThat(
            managerPhysicalLensOutputs(
                physicalOutputs = emptyList<Int>(),
                logicalCameraOutputs = listOf(13, 8, 5),
            ),
        ).containsExactly(13, 8, 5).inOrder()
        assertThat(
            managerPhysicalLensOutputs(
                physicalOutputs = listOf(50, 13),
                logicalCameraOutputs = listOf(13, 8, 5),
            ),
        ).containsExactly(50, 13).inOrder()
    }

    @Test
    fun `megapixel controls keep maximum and only realistic common sizes`() {
        assertThat(
            managerPhotoMegapixelOptions(
                listOf(48_000_000L, 12_200_000L, 8_100_000L, 3_900_000L, 2_000_000L),
            ),
        ).containsExactly(48, 12, 8, 4).inOrder()
        assertThat(managerPreferredPixelCount(listOf(12_200_000L, 8_100_000L), 8))
            .isEqualTo(8_100_000L)
    }

    @Test
    fun `unsupported persisted camera settings are normalized safely`() {
        val normalized = normalizeManagerCameraSettings(
            ManagerCameraSettings(
                requestedMegapixels = -1,
                exposureEvTenths = 50,
                videoFramesPerSecond = 24,
            ),
        )

        assertThat(normalized.requestedMegapixels).isNull()
        assertThat(normalized.exposureEvTenths).isEqualTo(20)
        assertThat(normalized.videoFramesPerSecond).isEqualTo(30)
        assertThat(normalized.ultraHdrEnabled).isTrue()
        assertThat(normalized.videoHdrEnabled).isFalse()
    }

    @Test
    fun `video quality falls back to a supported operational profile`() {
        assertThat(
            managerEffectiveVideoQuality(
                ManagerVideoQuality.Uhd,
                setOf(ManagerVideoQuality.Fhd, ManagerVideoQuality.Hd),
            ),
        ).isEqualTo(ManagerVideoQuality.Fhd)
    }

    @Test
    fun `recording timer is stable for minutes and hours`() {
        assertThat(managerRecordingTimerLabel(0L)).isEqualTo("00:00")
        assertThat(managerRecordingTimerLabel(65_900L)).isEqualTo("01:05")
        assertThat(managerRecordingTimerLabel(3_661_000L)).isEqualTo("1:01:01")
    }

    @Test
    fun `video status names active HDR without claiming it for SDR`() {
        assertThat(managerVideoSettingLabel(ManagerVideoQuality.Fhd, 30))
            .isEqualTo("1080P  ·  30")
        assertThat(managerVideoSettingLabel(ManagerVideoQuality.Fhd, 30, hdrActive = true))
            .isEqualTo("1080P  ·  30  ·  HDR")
    }
}
