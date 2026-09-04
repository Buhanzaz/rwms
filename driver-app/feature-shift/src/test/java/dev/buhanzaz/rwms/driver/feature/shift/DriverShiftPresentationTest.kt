package dev.buhanzaz.rwms.driver.feature.shift

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import dev.buhanzaz.rwms.driver.core.network.DriverShiftClosingReportDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftRouteOperationDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftTaskSummaryDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftWarehouseDto
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
    fun `route timeline keeps server sequence and exact arrival departure eta`() {
        val inbound = routeOperation(
            sequence = 2,
            kind = "INBOUND_POSITIONING",
            arrival = "2026-08-30T10:00:00+03:00",
            departure = "2026-08-30T08:30:00+03:00",
            loadBefore = 0,
            loadAfter = 0,
        )
        val delivery = routeOperation(
            sequence = 4,
            kind = "DELIVERY",
            arrival = "2026-08-30T11:15:00+03:00",
            departure = "2026-08-30T11:35:00+03:00",
            loadBefore = 1,
            loadAfter = 0,
        )
        val start = routeOperation(
            sequence = 1,
            kind = "ORIGIN_START",
            arrival = "2026-08-30T08:30:00+03:00",
            departure = "2026-08-30T08:30:00+03:00",
            loadBefore = 0,
            loadAfter = 0,
        )

        assertThat(orderedRouteOperations(listOf(delivery, inbound, start)).map { it.sequence })
            .containsExactly(1, 2, 4).inOrder()
        assertThat(routeOperationEta(inbound, "Europe/Moscow")).isEqualTo("08:30 → 10:00")
        assertThat(routeOperationEta(delivery, "Europe/Moscow")).isEqualTo("11:15 → 11:35")
        assertThat(routeOperationTitle(inbound.kind))
            .isEqualTo("Переезд на обслуживаемый склад")
        assertThat(routeOperationLoad(inbound)).isEqualTo("Груз: 0")
        assertThat(routeOperationLoad(delivery)).isEqualTo("Груз: 1 → 0")
    }

    @Test
    fun `transfer cargo operations keep server order and use explicit Russian presentation`() {
        val unload = routeOperation(
            sequence = 4,
            kind = "TRANSFER_UNLOAD",
            arrival = "2026-08-30T10:00:00+03:00",
            departure = "2026-08-30T10:15:00+03:00",
            loadBefore = 1,
            loadAfter = 0,
            sourceTransferId = "transfer-1",
        )
        val load = routeOperation(
            sequence = 2,
            kind = "TRANSFER_LOAD",
            arrival = "2026-08-30T08:30:00+03:00",
            departure = "2026-08-30T08:45:00+03:00",
            loadBefore = 0,
            loadAfter = 1,
            sourceTransferId = "transfer-1",
        )

        val ordered = orderedRouteOperations(listOf(unload, load))

        assertThat(ordered.map(DriverShiftRouteOperationDto::kind))
            .containsExactly("TRANSFER_LOAD", "TRANSFER_UNLOAD").inOrder()
        assertThat(ordered.map(DriverShiftRouteOperationDto::sourceTransferId))
            .containsExactly("transfer-1", "transfer-1").inOrder()
        assertThat(routeOperationTitle(load.kind)).isEqualTo("Загрузить межскладской груз")
        assertThat(routeOperationTitle(unload.kind)).isEqualTo("Выгрузить межскладской груз")
        assertThat(routeOperationIcon(load.kind)).isEqualTo(Icons.Filled.Archive)
        assertThat(routeOperationIcon(unload.kind)).isEqualTo(Icons.Filled.Unarchive)
        assertThat(routeOperationTitle("FUTURE_OPERATION")).isEqualTo("Future operation")
        assertThat(routeOperationIcon("FUTURE_OPERATION")).isNull()
    }

    @Test
    fun `vehicle cabin capacity is shown only when the additive fact exists`() {
        assertThat(vehicleCabinCapacityLabel(1)).isEqualTo("Вместимость: 1 бытовка")
        assertThat(vehicleCabinCapacityLabel(2)).isEqualTo("Вместимость: 2 бытовки")
        assertThat(vehicleCabinCapacityLabel(null)).isNull()
    }

    @Test
    fun `route eta converts utc instants into Moscow warehouse time`() {
        val inbound = routeOperation(
            sequence = 2,
            kind = "INBOUND_POSITIONING",
            arrival = "2026-08-30T07:00:00Z",
            departure = "2026-08-30T05:30:00Z",
            loadBefore = 0,
            loadAfter = 0,
        )

        assertThat(routeOperationEta(inbound, "Europe/Moscow"))
            .isEqualTo("08:30 → 10:00")
    }

    @Test
    fun `invalid route zone preserves the instant offset instead of inventing local time`() {
        val delivery = routeOperation(
            sequence = 3,
            kind = "DELIVERY",
            arrival = "2026-08-30T08:15:00Z",
            departure = "2026-08-30T08:35:00Z",
            loadBefore = 1,
            loadAfter = 0,
        )

        assertThat(routeOperationEta(delivery, "Not/A_Time_Zone"))
            .isEqualTo("08:15 → 08:35")
        assertThat(routeOperationEta(delivery, null)).isEqualTo("08:15 → 08:35")
    }

    @Test
    fun `active tasks expose cached route using warehouse zone before shift zone`() {
        val operation = routeOperation(
            sequence = 1,
            kind = "ORIGIN_START",
            arrival = "2026-08-30T05:30:00Z",
            departure = "2026-08-30T05:30:00Z",
            loadBefore = 0,
            loadAfter = 0,
        )
        val activeToday = today(
            action = "SHOW_TASKS",
            shift = shift("Asia/Yekaterinburg"),
            warehouse = warehouse("Europe/Moscow"),
            operations = listOf(operation),
        )

        assertThat(shouldExposeActiveRouteTimeline(activeToday)).isTrue()
        assertThat(routeTimelineTimeZone(activeToday)).isEqualTo("Europe/Moscow")
        assertThat(routeTimelineTimeZone(activeToday.copy(warehouse = null)))
            .isEqualTo("Asia/Yekaterinburg")
        assertThat(routeTimelineTimeZone(activeToday.copy(warehouse = warehouse("Bad/Zone"))))
            .isEqualTo("Asia/Yekaterinburg")
        assertThat(shouldExposeActiveRouteTimeline(activeToday.copy(operations = emptyList())))
            .isFalse()
        assertThat(shouldExposeActiveRouteTimeline(activeToday.copy(enabled = false))).isFalse()
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
        shift: DriverShiftDto? = null,
        warehouse: DriverShiftWarehouseDto? = null,
        operations: List<DriverShiftRouteOperationDto> = emptyList(),
    ): TodayDriverShiftDto = TodayDriverShiftDto(
        enabled = true,
        serverTime = "2026-08-30T16:47:00Z",
        nextRequiredAction = action,
        shift = shift,
        warehouse = warehouse,
        inspection = inspection,
        taskSummary = taskSummary,
        closingReport = closingReport,
        operations = operations,
    )

    private fun shift(timeZone: String): DriverShiftDto = DriverShiftDto(
        id = "shift-1",
        version = 4,
        driverId = "driver-1",
        driverName = "Иванов Иван",
        warehouseId = "warehouse-1",
        workDate = "2026-08-30",
        timeZone = timeZone,
        status = "ACTIVE",
    )

    private fun warehouse(timeZone: String): DriverShiftWarehouseDto = DriverShiftWarehouseDto(
        id = "warehouse-1",
        name = "Склад",
        city = "Москва",
        timeZone = timeZone,
    )

    private fun routeOperation(
        sequence: Int,
        kind: String,
        arrival: String,
        departure: String,
        loadBefore: Int,
        loadAfter: Int,
        sourceTransferId: String? = null,
    ): DriverShiftRouteOperationDto = DriverShiftRouteOperationDto(
        sequence = sequence,
        kind = kind,
        warehouseId = if (kind == "DELIVERY") null else "warehouse-1",
        sourceTaskId = if (kind == "DELIVERY") "task-1" else null,
        sourceTransferId = sourceTransferId,
        locationLabel = "Точка $sequence",
        plannedArrival = arrival,
        plannedDeparture = departure,
        loadBefore = loadBefore,
        loadAfter = loadAfter,
    )
}
