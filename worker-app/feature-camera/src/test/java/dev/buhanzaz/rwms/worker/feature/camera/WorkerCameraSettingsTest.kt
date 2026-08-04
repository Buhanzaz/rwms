package dev.buhanzaz.rwms.worker.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerCameraSettingsTest {
    @Test
    fun `photo controls match manager 0_3_29 zoom and setting normalization`() {
        assertThat(workerSupportedZoomStops(0.6f, 4f)).containsExactly(0.6f, 1f, 2f, 3f).inOrder()
        assertThat(workerCoerceZoom(8f, 1f, 4f)).isEqualTo(4f)
        assertThat(workerZoomLabel(0.6f)).isEqualTo("0.6×")
        assertThat(
            normalizeWorkerCameraSettings(WorkerCameraSettings(exposureEvTenths = 100))
                .exposureEvTenths,
        ).isEqualTo(20)
    }

    @Test
    fun `video tab never changes worker evidence away from JPEG photo mode`() {
        val selection = selectWorkerCameraMode(WorkerCameraMode.Video)

        assertThat(selection.mode).isEqualTo(WorkerCameraMode.Photo)
        assertThat(selection.message).contains("не поддерживается")
    }

    @Test
    fun `night and photo remain selectable`() {
        assertThat(selectWorkerCameraMode(WorkerCameraMode.Night).mode).isEqualTo(WorkerCameraMode.Night)
        assertThat(selectWorkerCameraMode(WorkerCameraMode.Photo).mode).isEqualTo(WorkerCameraMode.Photo)
    }
}
