package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.network.DriverShiftClosingReportDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftPhotoDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import org.junit.Test

class CompletionGateTest {
    @Test
    fun `completion is blocked until the required evidence is ready`() {
        assertThat(completionGateAllows(requiredReadyEvidence = 1, actualReadyEvidence = 0)).isFalse()
        assertThat(completionGateAllows(requiredReadyEvidence = 1, actualReadyEvidence = 1)).isTrue()
    }

    @Test
    fun `queues without result photos remain completable`() {
        assertThat(completionGateAllows(requiredReadyEvidence = 0, actualReadyEvidence = 0)).isTrue()
    }

    @Test
    fun `shift close waits for the exact closing defect photo to become ready`() {
        assertThat(closingToday(photoState = null).closeEvidenceReady(SHIFT_ID)).isFalse()
        assertThat(closingToday(photoState = "RESERVED").closeEvidenceReady(SHIFT_ID)).isFalse()
        assertThat(closingToday(photoState = "PROCESSING").closeEvidenceReady(SHIFT_ID)).isFalse()
        assertThat(closingToday(photoState = "READY").closeEvidenceReady(SHIFT_ID)).isTrue()
    }

    @Test
    fun `shift without a closing defect does not require evidence`() {
        assertThat(closingToday(defectId = null, photoState = null).closeEvidenceReady(SHIFT_ID)).isTrue()
    }

    @Test
    fun `all pages must describe one stable feed revision`() {
        val first = validateFeedPage(null, revision = 8, serverTime = "2026-07-25T10:00:00Z")

        assertThat(validateFeedPage(first, revision = 8, serverTime = "2026-07-25T10:00:00Z")).isEqualTo(first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixed feed pages are rejected`() {
        validateFeedPage(FeedPageConsistency(8, "2026-07-25T10:00:00Z"), 9, "2026-07-25T10:00:00Z")
    }

    private fun closingToday(
        defectId: String? = DEFECT_ID,
        photoState: String?,
    ) = TodayDriverShiftDto(
        enabled = true,
        serverTime = "2026-08-30T16:47:00Z",
        nextRequiredAction = "CLOSE_SHIFT",
        shift = DriverShiftDto(
            id = SHIFT_ID,
            version = 8,
            driverId = "driver",
            driverName = "Driver",
            warehouseId = "warehouse",
            workDate = "2026-08-30",
            timeZone = "Europe/Moscow",
            status = "SHIFT_READY_TO_CLOSE",
        ),
        closingReport = DriverShiftClosingReportDto(
            vehicleCondition = if (defectId == null) "NO_NEW_DEFECTS" else "DEFECT_REPORTED",
            endOdometer = 128_642,
            odometerDistance = 214,
            fuelLevelPercent = 63,
            defectId = defectId,
            photoCount = if (photoState == null) 0 else 1,
            completedAt = "2026-08-30T16:42:00Z",
        ),
        photos = if (photoState == null || defectId == null) {
            emptyList()
        } else {
            listOf(
                DriverShiftPhotoDto(
                    id = "photo",
                    clientReferenceId = "photo",
                    evidenceId = "photo",
                    role = "END_SHIFT_DEFECT",
                    defectId = defectId,
                    state = photoState,
                    capturedAt = "2026-08-30T16:43:00Z",
                    contentType = "image/jpeg",
                    sizeBytes = 10,
                    sha256 = "a".repeat(64),
                ),
            )
        },
    )

    private companion object {
        const val SHIFT_ID = "shift"
        const val DEFECT_ID = "defect"
    }
}
