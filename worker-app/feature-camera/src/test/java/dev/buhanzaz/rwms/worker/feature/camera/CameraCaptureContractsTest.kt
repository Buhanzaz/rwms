package dev.buhanzaz.rwms.worker.feature.camera

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraCaptureContractsTest {
    @Test
    fun `first camera permission request remains requestable`() {
        assertThat(
            cameraPermissionGate(
                granted = false,
                requestedAtLeastOnce = false,
                shouldShowRationale = false,
            ),
        ).isEqualTo(CameraPermissionGate.REQUESTABLE)
    }

    @Test
    fun `permanent camera denial opens settings path`() {
        assertThat(
            cameraPermissionGate(
                granted = false,
                requestedAtLeastOnce = true,
                shouldShowRationale = false,
            ),
        ).isEqualTo(CameraPermissionGate.PERMANENTLY_DENIED)
    }

    @Test
    fun `camera x successful callback accepts a nonempty jpeg target`() {
        val target = File.createTempFile("rwms-camera", ".jpg")
        try {
            target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))

            val result = validateCameraXSave(target)

            assertThat(result).isInstanceOf(CameraXSaveResult.Saved::class.java)
            assertThat((result as CameraXSaveResult.Saved).file).isEqualTo(target)
        } finally {
            target.delete()
        }
    }

    @Test
    fun `camera x successful callback rejects an empty output target`() {
        val target = File.createTempFile("rwms-camera", ".jpg")
        try {
            val result = validateCameraXSave(target)

            assertThat(result).isInstanceOf(CameraXSaveResult.Failed::class.java)
            assertThat((result as CameraXSaveResult.Failed).message).contains("пустую")
        } finally {
            target.delete()
        }
    }

    @Test
    fun `camera x jpeg allocation is rejected before exceeding evidence limit`() {
        assertThat(workerCameraJpegSizeAllowed(4)).isTrue()
        assertThat(
            workerCameraJpegSizeAllowed(15 * 1024 * 1024),
        ).isTrue()
        assertThat(
            workerCameraJpegSizeAllowed(15 * 1024 * 1024 + 1),
        ).isFalse()
    }

    @Test
    fun `saved captures are consumed once before a route can be reopened`() {
        val evidenceIds = listOf("evidence-1", "evidence-2")
        val saved = CameraUiState(savedEvidenceIds = evidenceIds)

        val consumed = saved.consumeSavedCaptures(evidenceIds)

        assertThat(consumed.savedEvidenceIds).isEmpty()
        assertThat(consumed.consumeSavedCaptures(evidenceIds)).isEqualTo(consumed)
    }

    @Test
    fun `saved captures remain pending when a different result tries to consume them`() {
        val saved = CameraUiState(savedEvidenceIds = listOf("evidence-1", "evidence-2"))

        val unchanged = saved.consumeSavedCaptures(listOf("evidence-1"))

        assertThat(unchanged).isEqualTo(saved)
    }

    @Test
    fun `empty result cannot clear an unrelated camera error`() {
        val failed = CameraUiState(error = "capture failed")

        val unchanged = failed.consumeSavedCaptures(emptyList())

        assertThat(unchanged).isEqualTo(failed)
    }

    @Test
    fun `partial gallery failure reports the durable subset`() {
        val message = galleryBatchFailureMessage(
            savedCount = 2,
            selectedCount = 4,
            causeMessage = "файл повреждён",
        )

        assertThat(message).contains("Добавлено 2 из 4 фото")
        assertThat(message).contains("файл повреждён")
    }

    @Test
    fun `gallery failure without saved evidence returns its cause`() {
        assertThat(
            galleryBatchFailureMessage(
                savedCount = 0,
                selectedCount = 3,
                causeMessage = "файл недоступен",
            ),
        ).isEqualTo("файл недоступен")
    }
}
