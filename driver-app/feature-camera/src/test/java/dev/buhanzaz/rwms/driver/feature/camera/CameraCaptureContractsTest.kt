package dev.buhanzaz.rwms.driver.feature.camera

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
    fun `saved capture is consumed once before a route can be reopened`() {
        val saved = CameraUiState(savedEvidenceId = "evidence-1")

        val consumed = saved.consumeSavedCapture("evidence-1")

        assertThat(consumed.savedEvidenceId).isNull()
        assertThat(consumed.consumeSavedCapture("evidence-1")).isEqualTo(consumed)
    }
}
