package dev.buhanzaz.rwms.driver.feature.shift

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import dev.buhanzaz.rwms.driver.core.network.DriverShiftClosingReportDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftTaskSummaryDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleInspectionDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import org.junit.Test

/** Covers warehouse-local greeting boundaries and compact Driver Shift text formatting. */
class DriverShiftPresentationTest {
    @Test
    fun `greeting follows actual warehouse local time boundaries`() {
        val warehouseZone = ZoneId.of("Asia/Yekaterinburg")
        val workDate = LocalDate.of(2026, 8, 30)

        assertThat(greetingAt(workDate.atTime(LocalTime.of(5, 0)).atZone(warehouseZone).toInstant(), warehouseZone.id))
            .isEqualTo("Доброе утро")
        assertThat(greetingAt(workDate.atTime(LocalTime.of(12, 0)).atZone(warehouseZone).toInstant(), warehouseZone.id))
            .isEqualTo("Добрый день")
        assertThat(greetingAt(workDate.atTime(LocalTime.of(18, 0)).atZone(warehouseZone).toInstant(), warehouseZone.id))
            .isEqualTo("Добрый вечер")
    }

    @Test
    fun `invalid warehouse zone falls back to Moscow time`() {
        val instant = LocalDate.of(2026, 8, 30)
            .atTime(LocalTime.of(5, 30))
            .atZone(ZoneId.of("Europe/Moscow"))
            .toInstant()

        assertThat(greetingAt(instant, "Not/A_Time_Zone")).isEqualTo("Доброе утро")
        assertThat(greetingAt(instant, null)).isEqualTo("Доброе утро")
    }

    @Test
    fun `next action remains the server driven presentation selector`() {
        val supportedActions = listOf(
            "SHIFT_NOT_AVAILABLE",
            "SHOW_DAILY_BRIEFING",
            "COMPLETE_MEDICAL_CHECK",
            "COMPLETE_VEHICLE_INSPECTION",
            "START_SHIFT",
            "SHOW_TASKS",
            "START_SHIFT_CLOSING",
            "CONFIRM_WAREHOUSE_RETURN",
            "COMPLETE_END_OF_SHIFT_REPORT",
            "CLOSE_SHIFT",
            "SHIFT_CLOSED",
        )

        val renderedActions = supportedActions.map { action ->
            DriverShiftUiState(loading = false, today = today(action)).today?.nextRequiredAction
        }

        assertThat(renderedActions).containsExactlyElementsIn(supportedActions).inOrder()
    }

    @Test
    fun `inspection presentation restores progress and keeps blocking defect visible`() {
        val inspection = DriverVehicleInspectionDto(
            id = "inspection-1",
            version = 9,
            totalRequired = 16,
            checkedRequired = 9,
            blockingDefectCount = 1,
            items = emptyList(),
        )
        val state = DriverShiftUiState(
            loading = false,
            today = today(
                action = "COMPLETE_VEHICLE_INSPECTION",
                inspection = inspection,
            ),
        )
        val restored = checkNotNull(state.today?.inspection)
        val progressText = "${restored.checkedRequired} из ${restored.totalRequired} проверено"
        val canComplete = restored.checkedRequired == restored.totalRequired &&
            restored.blockingDefectCount == 0

        assertThat(progressText).isEqualTo("9 из 16 проверено")
        assertThat(canComplete).isFalse()
        assertThat(restored.blockingDefectCount).isEqualTo(1)
    }

    @Test
    fun `audit time formatting is compact and safe for missing values`() {
        assertThat(formatTime("2026-08-30T07:43:59+03:00")).isEqualTo("07:43")
        assertThat(formatTime("not-a-time")).isEqualTo("—")
        assertThat(formatTime(null)).isEqualTo("—")
    }

    @Test
    fun `odometer formatting uses a readable thousands separator`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertThat(formatLong(128_642)).isEqualTo("128 642")
            assertThat(formatLong(0)).isEqualTo("0")
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `trip label follows Russian plural forms`() {
        assertThat(tripsLabel(0)).isEqualTo("ходок")
        assertThat(tripsLabel(1)).isEqualTo("ходка")
        assertThat(tripsLabel(2)).isEqualTo("ходки")
        assertThat(tripsLabel(4)).isEqualTo("ходки")
        assertThat(tripsLabel(5)).isEqualTo("ходок")
        assertThat(tripsLabel(11)).isEqualTo("ходок")
        assertThat(tripsLabel(14)).isEqualTo("ходок")
        assertThat(tripsLabel(21)).isEqualTo("ходка")
        assertThat(tripsLabel(22)).isEqualTo("ходки")
        assertThat(tripsLabel(25)).isEqualTo("ходок")
        assertThat(tripsLabel(111)).isEqualTo("ходок")
        assertThat(tripsLabel(112)).isEqualTo("ходок")
    }

    @Test
    fun `closed shift summary formats server audited closing facts`() {
        val today = today(
            action = "SHIFT_CLOSED",
            taskSummary = DriverShiftTaskSummaryDto(
                totalCount = 3,
                activeCount = 0,
                completedCount = 3,
                tripCount = 3,
                routeDistanceMeters = 247_500,
                canStartClosing = true,
            ),
            closingReport = DriverShiftClosingReportDto(
                vehicleCondition = "NO_NEW_DEFECTS",
                endOdometer = 128_642,
                odometerDistance = 214,
                fuelLevelPercent = 63,
                photoCount = 2,
                completedAt = "2026-08-30T19:47:00+03:00",
            ),
        )
        val report = checkNotNull(today.closingReport)
        val tasks = checkNotNull(today.taskSummary)

        assertThat("${tasks.tripCount} ${tripsLabel(tasks.tripCount)}").isEqualTo("3 ходки")
        assertThat(formatRouteDistance(tasks.routeDistanceMeters)).isEqualTo("247.5 км")
        assertThat("${formatLong(report.endOdometer)} км").isEqualTo("128 642 км")
        assertThat("${formatLong(checkNotNull(report.odometerDistance))} км").isEqualTo("214 км")
        assertThat("${report.fuelLevelPercent}%").isEqualTo("63%")
        assertThat(report.photoCount).isEqualTo(2)
        assertThat(formatTime(report.completedAt)).isEqualTo("19:47")
    }

    private fun today(
        action: String,
        inspection: DriverVehicleInspectionDto? = null,
        taskSummary: DriverShiftTaskSummaryDto? = null,
        closingReport: DriverShiftClosingReportDto? = null,
    ): TodayDriverShiftDto = TodayDriverShiftDto(
        enabled = true,
        serverTime = "2026-08-30T16:47:00Z",
        nextRequiredAction = action,
        inspection = inspection,
        taskSummary = taskSummary,
        closingReport = closingReport,
    )
}
