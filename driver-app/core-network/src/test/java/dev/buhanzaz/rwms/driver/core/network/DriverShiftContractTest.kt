package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

/** Pins the Driver Shift gateway routes, idempotency forwarding, and startup projection shape. */
@OptIn(ExperimentalSerializationApi::class)
class DriverShiftContractTest {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `shift routes use the exact public gateway paths`() {
        assertThat(route<GET>("todayDriverShift")).isEqualTo("/api/task-board/driver/v1/shift/today")
        assertThat(route<POST>("markShiftBriefingSeen"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/briefing/seen")
        assertThat(route<POST>("confirmShiftMedicalCheck"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/medical-check")
        assertThat(route<PUT>("updateShiftInspectionItem"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/items/{itemId}")
        assertThat(route<POST>("completeShiftInspection"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/complete")
        assertThat(route<POST>("startShift"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/start")
        assertThat(route<POST>("startShiftClosing"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/closing/start")
        assertThat(route<POST>("confirmShiftWarehouseReturn"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/return-to-warehouse")
        assertThat(route<POST>("submitShiftClosingReport"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/closing-report")
        assertThat(route<POST>("reserveShiftPhoto"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/photos/reservations")
        assertThat(route<POST>("closeShift"))
            .isEqualTo("/api/task-board/driver/v1/shifts/{shiftId}/close")
    }

    @Test
    fun `every mutable shift client call forwards operation id as idempotency key`() = runTest {
        val calledMethods = mutableListOf<String>()
        val today = completeTodayShift()
        val api = Proxy.newProxyInstance(
            DriverGatewayApi::class.java.classLoader,
            arrayOf(DriverGatewayApi::class.java),
        ) { _, method, arguments ->
            val callArguments = checkNotNull(arguments)
            val headerIndex = method.parameterAnnotations.indexOfFirst { annotations ->
                annotations.any { annotation ->
                    annotation is Header && annotation.value == "Idempotency-Key"
                }
            }
            val bodyIndex = method.parameterAnnotations.indexOfFirst { annotations ->
                annotations.any { annotation -> annotation is Body }
            }
            val request = callArguments[bodyIndex]
            val operationId = when (request) {
                is ShiftTransitionRequestDto -> request.operationId
                is ConfirmMedicalCheckRequestDto -> request.operationId
                is UpdateInspectionItemRequestDto -> request.operationId
                is ReturnToWarehouseRequestDto -> request.operationId
                is SubmitClosingReportRequestDto -> request.operationId
                is ReserveShiftPhotoRequestDto -> request.operationId
                else -> error("Unsupported Driver Shift request ${request::class.java.simpleName}")
            }

            assertThat(callArguments[headerIndex]).isEqualTo(operationId)
            calledMethods += method.name
            Response.success(today)
        } as DriverGatewayApi
        val client = DriverGatewayClient(api, json)

        client.markShiftBriefingSeen("shift-1", ShiftTransitionRequestDto("briefing-op", 1))
        client.confirmShiftMedicalCheck(
            "shift-1",
            ConfirmMedicalCheckRequestDto("medical-op", 2, "2026-08-30T04:43:00Z"),
        )
        client.updateShiftInspectionItem(
            "shift-1",
            "item-1",
            UpdateInspectionItemRequestDto("item-op", 3, 0, "OK"),
        )
        client.completeShiftInspection("shift-1", ShiftTransitionRequestDto("inspection-op", 4))
        client.startShift("shift-1", ShiftTransitionRequestDto("start-op", 5))
        client.startShiftClosing("shift-1", ShiftTransitionRequestDto("closing-start-op", 6))
        client.confirmShiftWarehouseReturn(
            "shift-1",
            ReturnToWarehouseRequestDto("return-op", 7, "MANUAL"),
        )
        client.submitShiftClosingReport(
            "shift-1",
            SubmitClosingReportRequestDto(
                operationId = "report-op",
                expectedVersion = 8,
                vehicleCondition = "OK",
                endOdometer = 128_642,
                fuelLevelPercent = 63,
                confirmSuspiciousOdometer = false,
            ),
        )
        client.reserveShiftPhoto(
            "shift-1",
            ReserveShiftPhotoRequestDto(
                operationId = "photo-op",
                expectedVersion = 9,
                clientReferenceId = "client-photo-1",
                evidenceId = "evidence-1",
                role = "VEHICLE_OVERVIEW",
                capturedAt = "2026-08-30T16:44:00Z",
                sizeBytes = 512,
                sha256 = "a".repeat(64),
            ),
        )
        client.closeShift("shift-1", ShiftTransitionRequestDto("close-op", 10))

        assertThat(calledMethods).containsExactly(
            "markShiftBriefingSeen",
            "confirmShiftMedicalCheck",
            "updateShiftInspectionItem",
            "completeShiftInspection",
            "startShift",
            "startShiftClosing",
            "confirmShiftWarehouseReturn",
            "submitShiftClosingReport",
            "reserveShiftPhoto",
            "closeShift",
        ).inOrder()
    }

    @Test
    fun `today shift projection serializes and restores every workflow section`() {
        val expected = completeTodayShift()

        val restored = json.decodeFromString<TodayDriverShiftDto>(json.encodeToString(expected))

        assertThat(restored).isEqualTo(expected)
        assertThat(restored.shift?.medicalCheck?.confirmationType).isEqualTo("SELF_CONFIRMATION_TEST")
        assertThat(restored.briefing?.weather?.hazards?.single()?.type).isEqualTo("HIGH_WIND")
        assertThat(restored.vehicle?.trailer?.registrationNumber).isEqualTo("В456ВВ78")
        assertThat(restored.vehicle?.cabinCapacity).isEqualTo(2)
        assertThat(restored.inspection?.items?.single()?.defect?.severity).isEqualTo("BLOCKING")
        assertThat(restored.taskSummary?.canStartClosing).isTrue()
        assertThat(restored.closingReport?.odometerDistance).isEqualTo(214)
        assertThat(restored.operations.map(DriverShiftRouteOperationDto::sequence))
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(restored.operations.map(DriverShiftRouteOperationDto::kind))
            .containsExactly(
                "ORIGIN_START",
                "TRANSFER_LOAD",
                "INBOUND_POSITIONING",
                "TRANSFER_UNLOAD",
                "DEPOT_LOAD",
                "DELIVERY",
                "DEPOT_RETURN",
                "RETURN_POSITIONING",
            ).inOrder()
        assertThat(restored.operations[2].plannedDeparture).isEqualTo("2026-08-30T05:45:00Z")
        assertThat(restored.operations[2].plannedArrival).isEqualTo("2026-08-30T07:00:00Z")
        assertThat(restored.operations.mapNotNull(DriverShiftRouteOperationDto::sourceTransferId))
            .containsExactly("transfer-1", "transfer-1").inOrder()
        assertThat(restored.photos.single().mediaGeneration).isEqualTo(2)
    }

    @Test
    fun `legacy today response without route operations restores an empty snapshot`() {
        val restored = json.decodeFromString<TodayDriverShiftDto>(
            """
            {
              "enabled": true,
              "serverTime": "2026-08-30T16:47:00Z",
              "nextRequiredAction": "SHIFT_NOT_AVAILABLE",
              "photos": []
            }
            """.trimIndent(),
        )

        assertThat(restored.operations).isEmpty()
    }

    @Test
    fun `legacy vehicle and route operation omit additive transfer fields`() {
        val restored = json.decodeFromString<TodayDriverShiftDto>(
            """
            {
              "enabled": true,
              "serverTime": "2026-08-30T16:47:00Z",
              "nextRequiredAction": "SHOW_TASKS",
              "vehicle": {
                "id": "vehicle-1",
                "name": "MAN TGS",
                "registrationNumber": "А123АА78",
                "configurationType": "TRUCK"
              },
              "operations": [{
                "sequence": 1,
                "kind": "DEPOT_LOAD",
                "warehouseId": "warehouse-1",
                "locationLabel": "Склад Санкт-Петербург",
                "plannedArrival": "2026-08-30T05:30:00Z",
                "plannedDeparture": "2026-08-30T05:45:00Z",
                "loadBefore": 0,
                "loadAfter": 1
              }],
              "photos": []
            }
            """.trimIndent(),
        )

        assertThat(restored.vehicle?.cabinCapacity).isNull()
        assertThat(restored.operations.single().sourceTransferId).isNull()
    }

    private inline fun <reified T : Annotation> route(methodName: String): String {
        val method = DriverGatewayApi::class.java.declaredMethods.single { candidate ->
            candidate.name == methodName && !candidate.isSynthetic
        }
        val annotation = method.getAnnotation(T::class.java)
        return when (annotation) {
            is GET -> annotation.value
            is POST -> annotation.value
            is PUT -> annotation.value
            else -> error("Unsupported route annotation on $methodName")
        }
    }

    private fun completeTodayShift(): TodayDriverShiftDto = TodayDriverShiftDto(
        enabled = true,
        serverTime = "2026-08-30T16:47:00Z",
        nextRequiredAction = "SHIFT_CLOSED",
        shift = DriverShiftDto(
            id = "shift-1",
            version = 11,
            driverId = "driver-1",
            driverName = "Александр",
            warehouseId = "warehouse-1",
            workDate = "2026-08-30",
            timeZone = "Europe/Moscow",
            status = "SHIFT_CLOSED",
            briefingSeenAt = "2026-08-30T04:40:00Z",
            medicalCheck = DriverMedicalCheckDto(
                driverId = "driver-1",
                shiftId = "shift-1",
                workDate = "2026-08-30",
                confirmationType = "SELF_CONFIRMATION_TEST",
                completedAt = "2026-08-30T04:43:00Z",
            ),
            vehicleInspectionCompletedAt = "2026-08-30T04:48:00Z",
            startedAt = "2026-08-30T04:49:00Z",
            closingStartedAt = "2026-08-30T16:40:00Z",
            returnedToWarehouseAt = "2026-08-30T16:42:00Z",
            returnConfirmationType = "MANUAL",
            closedAt = "2026-08-30T16:47:00Z",
        ),
        warehouse = DriverShiftWarehouseDto(
            id = "warehouse-1",
            name = "Склад Санкт-Петербург",
            city = "Санкт-Петербург",
            latitude = 59.9343,
            longitude = 30.3351,
            timeZone = "Europe/Moscow",
        ),
        briefing = DailyBriefingDto(
            locationName = "Санкт-Петербург",
            weather = DailyWeatherBriefingDto(
                available = true,
                attribution = "MET Norway",
                currentTempC = 3.0,
                feelsLikeC = 0.0,
                minTempC = 2.0,
                maxTempC = 6.0,
                condition = "Дождь",
                precipitationProbabilityPercent = 70,
                precipitationType = "RAIN",
                windSpeedMetersPerSecond = 8.0,
                windGustMetersPerSecond = 13.0,
                hazards = listOf(
                    WeatherHazardDto(
                        type = "HIGH_WIND",
                        severity = "WARNING",
                        title = "Сильный ветер",
                        description = "Ожидаются сильные порывы ветра",
                    ),
                ),
            ),
        ),
        vehicle = DriverShiftVehicleDto(
            id = "vehicle-1",
            name = "MAN TGS",
            registrationNumber = "А123АА78",
            configurationType = "TRUCK_WITH_TRAILER",
            cabinCapacity = 2,
            startOdometer = 128_428,
            trailer = DriverShiftTrailerDto(
                id = "trailer-1",
                name = "Прицеп",
                registrationNumber = "В456ВВ78",
            ),
        ),
        inspection = DriverVehicleInspectionDto(
            id = "inspection-1",
            version = 2,
            completedAt = "2026-08-30T04:48:00Z",
            totalRequired = 1,
            checkedRequired = 1,
            blockingDefectCount = 1,
            items = listOf(
                DriverVehicleInspectionItemDto(
                    id = "item-1",
                    version = 1,
                    templateItemCode = "BRAKES",
                    section = "TRUCK",
                    label = "Тормозная система",
                    required = true,
                    sortOrder = 1,
                    state = "DEFECT",
                    defect = DriverVehicleDefectDto(
                        id = "defect-1",
                        shiftId = "shift-1",
                        vehicleId = "vehicle-1",
                        inspectionItemId = "item-1",
                        description = "Повреждение",
                        severity = "BLOCKING",
                        status = "OPEN",
                        photoIds = listOf("photo-1"),
                        createdAt = "2026-08-30T04:46:00Z",
                    ),
                ),
            ),
        ),
        taskSummary = DriverShiftTaskSummaryDto(
            totalCount = 3,
            activeCount = 0,
            completedCount = 3,
            tripCount = 3,
            routeDistanceMeters = 247_500,
            canStartClosing = true,
        ),
        closingReport = DriverShiftClosingReportDto(
            vehicleCondition = "DEFECT_REPORTED",
            endOdometer = 128_642,
            odometerDistance = 214,
            fuelLevelPercent = 63,
            defectId = "defect-1",
            photoCount = 1,
            completedAt = "2026-08-30T16:45:00Z",
        ),
        operations = listOf(
            routeOperation(
                1,
                "ORIGIN_START",
                "warehouse-1",
                "Склад Санкт-Петербург",
                "2026-08-30T05:30:00Z",
                "2026-08-30T05:30:00Z",
                0,
                0,
            ),
            routeOperation(
                2,
                "TRANSFER_LOAD",
                "warehouse-1",
                "Склад Санкт-Петербург",
                "2026-08-30T05:30:00Z",
                "2026-08-30T05:45:00Z",
                0,
                1,
                sourceTransferId = "transfer-1",
            ),
            routeOperation(
                3,
                "INBOUND_POSITIONING",
                "warehouse-2",
                "Склад Великий Новгород",
                "2026-08-30T07:00:00Z",
                "2026-08-30T05:45:00Z",
                1,
                1,
            ),
            routeOperation(
                4,
                "TRANSFER_UNLOAD",
                "warehouse-2",
                "Склад Великий Новгород",
                "2026-08-30T07:00:00Z",
                "2026-08-30T07:15:00Z",
                1,
                0,
                sourceTransferId = "transfer-1",
            ),
            routeOperation(
                5,
                "DEPOT_LOAD",
                "warehouse-2",
                "Склад Великий Новгород",
                "2026-08-30T07:15:00Z",
                "2026-08-30T07:30:00Z",
                0,
                1,
            ),
            DriverShiftRouteOperationDto(
                sequence = 6,
                kind = "DELIVERY",
                sourceTaskId = "task-1",
                locationLabel = "Невский проспект, 1",
                plannedArrival = "2026-08-30T08:00:00Z",
                plannedDeparture = "2026-08-30T08:20:00Z",
                loadBefore = 1,
                loadAfter = 0,
            ),
            routeOperation(
                7,
                "DEPOT_RETURN",
                "warehouse-2",
                "Склад Великий Новгород",
                "2026-08-30T09:00:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0,
            ),
            routeOperation(
                8,
                "RETURN_POSITIONING",
                "warehouse-1",
                "Склад Санкт-Петербург",
                "2026-08-30T10:30:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0,
            ),
        ),
        photos = listOf(
            DriverShiftPhotoDto(
                id = "photo-1",
                clientReferenceId = "client-photo-1",
                evidenceId = "evidence-1",
                role = "DEFECT",
                defectId = "defect-1",
                inspectionItemId = "item-1",
                state = "READY",
                mediaId = "media-1",
                mediaGeneration = 2,
                capturedAt = "2026-08-30T04:46:00Z",
                contentType = "image/jpeg",
                sizeBytes = 512,
                sha256 = "a".repeat(64),
            ),
        ),
    )

    private fun routeOperation(
        sequence: Int,
        kind: String,
        warehouseId: String,
        locationLabel: String,
        plannedArrival: String,
        plannedDeparture: String,
        loadBefore: Int,
        loadAfter: Int,
        sourceTransferId: String? = null,
    ): DriverShiftRouteOperationDto = DriverShiftRouteOperationDto(
        sequence = sequence,
        kind = kind,
        warehouseId = warehouseId,
        sourceTransferId = sourceTransferId,
        locationLabel = locationLabel,
        plannedArrival = plannedArrival,
        plannedDeparture = plannedDeparture,
        loadBefore = loadBefore,
        loadAfter = loadAfter,
    )
}
