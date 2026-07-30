package dev.buhanzaz.rwms.worker.feature.camera

import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import java.io.File

/**
 * The three permission states the capture screen can render. Keeping this
 * decision outside Compose makes first denial and the permanent-denial path
 * deterministic on API 23+.
 */
internal enum class CameraPermissionGate {
    GRANTED,
    REQUESTABLE,
    PERMANENTLY_DENIED,
}

internal fun cameraPermissionGate(
    granted: Boolean,
    requestedAtLeastOnce: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionGate = when {
    granted -> CameraPermissionGate.GRANTED
    requestedAtLeastOnce && !shouldShowRationale -> CameraPermissionGate.PERMANENTLY_DENIED
    else -> CameraPermissionGate.REQUESTABLE
}

/**
 * CameraX reports a successful callback even when an OEM camera writes an
 * unusable zero-byte output. Validate the exact private-cache target before
 * offering confirmation so a user can retake instead of reaching a dead end.
 */
internal sealed interface CameraXSaveResult {
    data class Saved(val file: File) : CameraXSaveResult

    data class Failed(val message: String) : CameraXSaveResult
}

internal fun validateCameraXSave(target: File): CameraXSaveResult = when {
    !target.isFile -> CameraXSaveResult.Failed("Камера не сохранила фотографию")
    target.length() <= 0L -> CameraXSaveResult.Failed("Камера сохранила пустую фотографию")
    target.length() > EncryptedEvidenceFileStore.MAX_JPEG_BYTES ->
        CameraXSaveResult.Failed("Фотография больше 15 МБ. Снимите её ещё раз")
    else -> CameraXSaveResult.Saved(target)
}
