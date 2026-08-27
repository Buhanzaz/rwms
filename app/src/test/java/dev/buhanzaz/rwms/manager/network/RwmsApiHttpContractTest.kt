package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RwmsApiHttpContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RwmsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder()
                        .add(ExplicitNullJsonAdapterFactory)
                        .addLast(KotlinJsonAdapterFactory())
                        .build(),
                ),
            )
            .build()
            .create(RwmsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `current user parses canonical rental and effective warehouse access fields`() = runTest {
        val userId = "11111111-1111-1111-1111-111111111111"
        val warehouseId = "22222222-2222-2222-2222-222222222222"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$userId","username":"manager","displayName":"Manager","firstName":null,"lastName":null,"email":null,"principalType":"USER","globalRole":"WAREHOUSE_MANAGER","rentalAccess":true,"warehouseAccessAll":false,"warehouseAccesses":[{"warehouseId":"$warehouseId","level":"MANAGE"}]}""",
                ),
        )

        val user = api.currentUser()

        assertThat(user.rentalAccess).isTrue()
        assertThat(user.warehouseAccesses.single().warehouseId).isEqualTo(warehouseId)
        assertThat(user.warehouseAccesses.single().level).isEqualTo("MANAGE")
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/auth/api/users/me")
    }

    @Test
    fun `active capital repairs use the separate public maintenance endpoint`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[],"page":0,"size":200,"totalElements":0}"""),
        )

        val page = api.activeCapitalRepairs(
            warehouseId = "11111111-1111-1111-1111-111111111111",
            size = 200,
        )

        assertThat(page.items).isEmpty()
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo(
            "/api/maintenance/v1/repairs/capital?warehouseId=" +
                "11111111-1111-1111-1111-111111111111&page=0&size=200",
        )
    }

    @Test
    fun `inventory field commands use public paths and preserve canonical request fields`() = runTest {
        val inventoryId = "11111111-1111-1111-1111-111111111111"
        val findingId = "22222222-2222-2222-2222-222222222222"
        val mediaId = "33333333-3333-3333-3333-333333333333"

        val resolve = captureRequest {
            api.resolveInventoryNumber(
                inventoryId = inventoryId,
                idempotencyKey = "inventory-resolve-1",
                request = ResolveNumberRequest(
                    expectedSessionRevision = 7,
                    submittedNumber = "CAB-001",
                ),
            )
        }
        val create = captureRequest {
            api.createInventoryAsset(
                inventoryId = inventoryId,
                findingId = findingId,
                idempotencyKey = "inventory-create-1",
                request = CreateFindingAssetRequest(
                    expectedSessionRevision = 7,
                    expectedFindingRevision = 3,
                    origin = "ADDED_NEW",
                    displayCanonicalNumber = "CAB-001",
                    safePassport = linkedMapOf(
                        "serialNumber" to "SN-42",
                        "doorCount" to 2,
                    ),
                ),
            )
        }
        val inspection = captureRequest {
            api.saveInventoryInspection(
                inventoryId = inventoryId,
                findingId = findingId,
                request = SaveInspectionRequest(
                    expectedSessionRevision = 8,
                    expectedFindingRevision = 4,
                    inspection = "READY",
                    comment = "No defects",
                    passportObservation = ObservationInput(
                        presence = "ABSENT",
                        value = null,
                    ),
                    equipmentObservation = ObservationInput(
                        presence = "ABSENT",
                        value = null,
                    ),
                    media = listOf(MediaReferenceDto(mediaId = mediaId, generation = 2)),
                    planSelection = null,
                ),
            )
        }
        val conflictResolution = captureRequest {
            api.resolveInventoryConflict(
                inventoryId = inventoryId,
                findingId = findingId,
                request = ResolveInventoryConflictRequest(
                    expectedSessionRevision = 9,
                    expectedFindingRevision = 5,
                    strategy = "ACCEPT_REGISTRY",
                    reason = null,
                ),
            )
        }
        resolve.assertJsonCommand(
            method = "POST",
            path = "/api/inventory/v1/sessions/$inventoryId/number-resolutions",
            idempotencyKey = "inventory-resolve-1",
            body = """{"expectedSessionRevision":7,"submittedNumber":"CAB-001"}""",
        )
        create.assertJsonCommand(
            method = "POST",
            path = "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/assets",
            idempotencyKey = "inventory-create-1",
            body = """{"expectedSessionRevision":7,"expectedFindingRevision":3,"sourceRevision":1,"origin":"ADDED_NEW","displayCanonicalNumber":"CAB-001","safePassport":{"serialNumber":"SN-42","doorCount":2}}""",
        )
        inspection.assertJsonCommand(
            method = "PUT",
            path = "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/inspection",
            idempotencyKey = null,
            body = """{"expectedSessionRevision":8,"expectedFindingRevision":4,"inspection":"READY","comment":"No defects","passportObservation":{"presence":"ABSENT","value":null},"equipmentObservation":{"presence":"ABSENT","value":null},"media":[{"mediaId":"$mediaId","generation":2}],"coverMediaId":null,"planSelection":null}""",
        )
        conflictResolution.assertJsonCommand(
            method = "PUT",
            path = "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/conflict-resolution",
            idempotencyKey = null,
            body = """{"expectedSessionRevision":9,"expectedFindingRevision":5,"strategy":"ACCEPT_REGISTRY","reason":null}""",
        )
    }

    @Test
    fun `inventory work staged inspection carries field-only repair intent`() =
        runTest {
            val inventoryId = "10101010-1010-1010-1010-101010101010"
            val findingId = "20202020-2020-2020-2020-202020202020"
            val mediaId = "30303030-3030-3030-3030-303030303030"
            val workNodeId = "40404040-4040-4040-4040-404040404040"

            val inspection = captureRequest {
                api.saveInventoryInspection(
                    inventoryId = inventoryId,
                    findingId = findingId,
                    request = SaveInspectionRequest(
                        expectedSessionRevision = 4,
                        expectedFindingRevision = 2,
                        inspection = "WORK_STAGED",
                        comment = "Требуется ремонт",
                        passportObservation = ObservationInput("ABSENT", null),
                        equipmentObservation = ObservationInput("ABSENT", null),
                        media = listOf(MediaReferenceDto(mediaId, 1)),
                        coverMediaId = mediaId,
                        planSelection = InventoryPlanSelectionDto(
                            mode = "MANUAL",
                            priority = 2,
                            coverMediaId = mediaId,
                            movementToRepair = false,
                            forceCapitalRepair = true,
                            logisticsPlanningMode = null,
                            logisticsScheduledDate = null,
                            lines = listOf(
                                InventoryPlanLineInputDto(
                                    aggregationKind = "CATALOG",
                                    catalogNodeId = workNodeId,
                                    routingCatalogNodeId = null,
                                    description = null,
                                    type = null,
                                    unit = null,
                                    quantity = "1",
                                    unitPriceMinor = null,
                                    normativeMinutes = null,
                                    groupComment = "Каркас",
                                    mediaReferences = listOf(MediaReferenceDto(mediaId, 1)),
                                ),
                            ),
                            stages = listOf(
                                InventoryPlanStageSelectionDto(
                                    catalogNodeId = workNodeId,
                                    kind = "REPAIR_WORK",
                                    order = 0,
                                ),
                            ),
                        ),
                    ),
                )
            }

            inspection.assertJsonCommand(
                method = "PUT",
                path = "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/inspection",
                idempotencyKey = null,
                body = """{"expectedSessionRevision":4,"expectedFindingRevision":2,"inspection":"WORK_STAGED","comment":"Требуется ремонт","passportObservation":{"presence":"ABSENT","value":null},"equipmentObservation":{"presence":"ABSENT","value":null},"media":[{"mediaId":"$mediaId","generation":1}],"coverMediaId":"$mediaId","planSelection":{"mode":"MANUAL","priority":2,"coverMediaId":"$mediaId","movementToRepair":false,"forceCapitalRepair":true,"logisticsPlanningMode":null,"logisticsScheduledDate":null,"lines":[{"aggregationKind":"CATALOG","catalogNodeId":"$workNodeId","routingCatalogNodeId":null,"description":null,"type":null,"unit":null,"quantity":"1","unitPriceMinor":null,"normativeMinutes":null,"groupComment":"Каркас","mediaReferences":[{"mediaId":"$mediaId","generation":1}]}],"stages":[{"catalogNodeId":"$workNodeId","kind":"REPAIR_WORK","order":0}]}}""",
            )
        }

    @Test
    fun `manual inventory work and material lines serialize their technical routing node`() = runTest {
        val inventoryId = "51515151-5151-5151-5151-515151515151"
        val findingId = "61616161-6161-6161-6161-616161616161"
        val routingNodeId = "71717171-7171-7171-7171-717171717171"

        val inspection = captureRequest {
            api.saveInventoryInspection(
                inventoryId = inventoryId,
                findingId = findingId,
                request = SaveInspectionRequest(
                    expectedSessionRevision = 6,
                    expectedFindingRevision = 3,
                    inspection = "WORK_STAGED",
                    comment = "Ручные позиции",
                    passportObservation = ObservationInput("ABSENT", null),
                    equipmentObservation = ObservationInput("ABSENT", null),
                    media = emptyList(),
                    planSelection = InventoryPlanSelectionDto(
                        mode = "MANUAL",
                        priority = 3,
                        movementToRepair = false,
                        logisticsPlanningMode = null,
                        lines = listOf(
                            InventoryPlanLineInputDto(
                                aggregationKind = "MANUAL",
                                catalogNodeId = null,
                                routingCatalogNodeId = routingNodeId,
                                description = "Ручная работа",
                                type = "WORK",
                                unit = "ч",
                                quantity = "1",
                                unitPriceMinor = 12500,
                                normativeMinutes = "30",
                                groupComment = "Каркас",
                            ),
                            InventoryPlanLineInputDto(
                                aggregationKind = "MANUAL",
                                catalogNodeId = null,
                                routingCatalogNodeId = routingNodeId,
                                description = "Ручной материал",
                                type = "MATERIAL",
                                unit = "шт.",
                                quantity = "2",
                                unitPriceMinor = 3500,
                                normativeMinutes = "0",
                                groupComment = null,
                            ),
                        ),
                        stages = listOf(
                            InventoryPlanStageSelectionDto(
                                catalogNodeId = routingNodeId,
                                kind = "REPAIR_WORK",
                                order = 0,
                            ),
                        ),
                    ),
                ),
            )
        }

        inspection.assertJsonCommand(
            method = "PUT",
            path = "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/inspection",
            idempotencyKey = null,
            body = """{"expectedSessionRevision":6,"expectedFindingRevision":3,"inspection":"WORK_STAGED","comment":"Ручные позиции","passportObservation":{"presence":"ABSENT","value":null},"equipmentObservation":{"presence":"ABSENT","value":null},"media":[],"coverMediaId":null,"planSelection":{"mode":"MANUAL","priority":3,"coverMediaId":null,"movementToRepair":false,"forceCapitalRepair":false,"logisticsPlanningMode":null,"logisticsScheduledDate":null,"lines":[{"aggregationKind":"MANUAL","catalogNodeId":null,"routingCatalogNodeId":"$routingNodeId","description":"Ручная работа","type":"WORK","unit":"ч","quantity":"1","unitPriceMinor":12500,"normativeMinutes":"30","groupComment":"Каркас","mediaReferences":[]},{"aggregationKind":"MANUAL","catalogNodeId":null,"routingCatalogNodeId":"$routingNodeId","description":"Ручной материал","type":"MATERIAL","unit":"шт.","quantity":"2","unitPriceMinor":3500,"normativeMinutes":"0","groupComment":null,"mediaReferences":[]}],"stages":[{"catalogNodeId":"$routingNodeId","kind":"REPAIR_WORK","order":0}]}}""",
        )
    }

    @Test
    fun `return commands include public version paths and required evidence`() = runTest {
        val documentId = "44444444-4444-4444-4444-444444444444"
        val lineId = "55555555-5555-5555-5555-555555555555"
        val warehouseId = "88888888-8888-8888-8888-888888888888"
        val mediaId = "66666666-6666-6666-6666-666666666666"
        val equipmentId = "77777777-7777-7777-7777-777777777777"

        val accept = captureRequest {
            api.acceptReturn(
                documentId = documentId,
                expectedVersion = 9,
                idempotencyKey = "return-accept-1",
                request = AcceptReturnRequest(
                    lines = listOf(
                        AcceptReturnLineRequest(
                            lineId = lineId,
                            equipmentConfirmed = true,
                            references = listOf(MediaReferenceDto(mediaId = mediaId, generation = 2)),
                            additionalEquipment = listOf(
                                AdditionalEquipmentRequest(equipmentId = equipmentId, quantity = 1),
                            ),
                        ),
                    ),
                ),
            )
        }
        val estimates = captureRequest {
            api.startReturnEstimates(
                documentId = documentId,
                expectedVersion = 10,
                idempotencyKey = "return-estimate-1",
                request = StartReturnEstimatesRequest(
                    lines = listOf(
                        StartReturnEstimateLine(
                            lineId = lineId,
                            references = listOf(MediaReferenceDto(mediaId = mediaId, generation = 3)),
                        ),
                    ),
                ),
            )
        }
        val sources = captureRequest {
            api.returnEstimateSources(
                warehouseId = warehouseId,
                returnId = documentId,
            )
        }

        accept.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/returns/$documentId/accept-undamaged?expectedVersion=9",
            idempotencyKey = "return-accept-1",
            body = """{"lines":[{"lineId":"$lineId","equipmentConfirmed":true,"references":[{"mediaId":"$mediaId","generation":2}],"additionalEquipment":[{"equipmentId":"$equipmentId","quantity":1}]}]}""",
        )
        estimates.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/returns/$documentId/start-estimates?expectedVersion=10",
            idempotencyKey = "return-estimate-1",
            body = """{"lines":[{"lineId":"$lineId","references":[{"mediaId":"$mediaId","generation":3}]}]}""",
        )
        sources.assertPublicSameOriginPath(
            "/api/maintenance/v1/estimates/return-sources?warehouseId=$warehouseId&returnId=$documentId",
        )
        assertThat(sources.method).isEqualTo("GET")
    }

    @Test
    fun `estimate replacement amendment and completion use the public CAS contract`() = runTest {
        val warehouseId = "12121212-1212-1212-1212-121212121212"
        val estimateId = "23232323-2323-2323-2323-232323232323"
        val lineId = "34343434-3434-3434-3434-343434343434"
        val stageId = "45454545-4545-4545-4545-454545454545"
        val catalogVersionId = "56565656-5656-5656-5656-565656565656"
        val catalogNodeId = "67676767-6767-6767-6767-676767676767"
        val queueId = "78787878-7878-7878-7878-787878787878"
        val mediaId = "89898989-8989-8989-8989-898989898989"
        val routing = RoutingSnapshotDto(
            queueId = queueId,
            queueName = "Ремонт",
            queueType = "REPAIR",
        )
        val line = EstimateLineInputDto(
            id = lineId,
            catalogSnapshot = CatalogNodeSnapshotDto(
                catalogVersionId = catalogVersionId,
                nodeId = catalogNodeId,
                nodeType = "WORK",
                name = "Repair work",
                unit = "hour",
                unitPrice = "120.00",
                durationMinutes = 30,
                routing = routing,
                furnitureEquipment = null,
            ),
            lineType = "WORK",
            description = "Repair work",
            unit = "hour",
            quantity = "1.5",
            unitPrice = "120.00",
            normativeMinutes = 30,
            comment = null,
            mediaReferences = listOf(MediaReferenceDto(mediaId, 2)),
        )
        val plan = PlanStageInputDto(
            id = stageId,
            kind = "REPAIR_WORK",
            order = 0,
            routing = routing,
            includedLineIds = listOf(lineId),
            primaryLineId = lineId,
            groupComment = "",
            taskDeadline = null,
        )

        val replace = captureRequest {
            api.replaceEstimate(
                estimateId = estimateId,
                warehouseId = warehouseId,
                request = ReplaceEstimateRequest(
                    expectedVersion = 7,
                    dispatchDate = "2026-07-27",
                    sourceParty = null,
                    lines = listOf(line),
                    plan = listOf(plan),
                    mediaReferences = listOf(MediaReferenceDto(mediaId, 2)),
                    forceCapitalRepair = true,
                ),
            )
        }
        val complete = captureRequest {
            api.completeEstimate(
                estimateId = estimateId,
                warehouseId = warehouseId,
                idempotencyKey = "estimate-complete-1",
                request = CompleteEstimateRequest(
                    expectedVersion = 8,
                    priority = 2,
                    movementToRepair = false,
                    logisticsPlanningMode = null,
                    logisticsScheduledDate = null,
                ),
            )
        }
        val amendment = captureRequest {
            api.amendEstimate(
                estimateId = estimateId,
                warehouseId = warehouseId,
                idempotencyKey = "estimate-amend-1",
                request = AmendEstimateRequest(
                    expectedVersion = 9,
                    expectedLinkedRepairVersion = 4,
                    dispatchDate = "2026-07-27",
                    reason = "Изменение работ до начала ремонта",
                    sourceParty = null,
                    lines = listOf(line),
                    plan = listOf(plan),
                    mediaReferences = listOf(MediaReferenceDto(mediaId, 2)),
                    forceCapitalRepair = true,
                ),
            )
        }

        replace.assertJsonCommand(
            method = "PUT",
            path = "/api/maintenance/v1/estimates/$estimateId?warehouseId=$warehouseId",
            idempotencyKey = null,
            body = """{"expectedVersion":7,"dispatchDate":"2026-07-27","sourceParty":null,"lines":[{"id":"$lineId","catalogSnapshot":{"catalogVersionId":"$catalogVersionId","nodeId":"$catalogNodeId","nodeType":"WORK","name":"Repair work","unit":"hour","unitPrice":"120.00","durationMinutes":30,"routing":{"queueId":"$queueId","queueName":"Ремонт","queueType":"REPAIR"},"furnitureEquipment":null},"lineType":"WORK","description":"Repair work","unit":"hour","quantity":"1.5","unitPrice":"120.00","normativeMinutes":30,"comment":null,"mediaReferences":[{"mediaId":"$mediaId","generation":2}]}],"plan":[{"id":"$stageId","kind":"REPAIR_WORK","order":0,"routing":{"queueId":"$queueId","queueName":"Ремонт","queueType":"REPAIR"},"includedLineIds":["$lineId"],"primaryLineId":"$lineId","groupComment":"","taskDeadline":null}],"mediaReferences":[{"mediaId":"$mediaId","generation":2}],"coverMediaId":null,"forceCapitalRepair":true}""",
        )
        complete.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/estimates/$estimateId/complete?warehouseId=$warehouseId",
            idempotencyKey = "estimate-complete-1",
            body = """{"expectedVersion":8,"priority":2,"movementToRepair":false,"logisticsPlanningMode":null,"logisticsScheduledDate":null,"allowUnaccountedFurniture":false}""",
        )
        amendment.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/estimates/$estimateId/amendments?warehouseId=$warehouseId",
            idempotencyKey = "estimate-amend-1",
            body = """{"expectedVersion":9,"expectedLinkedRepairVersion":4,"dispatchDate":"2026-07-27","reason":"Изменение работ до начала ремонта","sourceParty":null,"lines":[{"id":"$lineId","catalogSnapshot":{"catalogVersionId":"$catalogVersionId","nodeId":"$catalogNodeId","nodeType":"WORK","name":"Repair work","unit":"hour","unitPrice":"120.00","durationMinutes":30,"routing":{"queueId":"$queueId","queueName":"Ремонт","queueType":"REPAIR"},"furnitureEquipment":null},"lineType":"WORK","description":"Repair work","unit":"hour","quantity":"1.5","unitPrice":"120.00","normativeMinutes":30,"comment":null,"mediaReferences":[{"mediaId":"$mediaId","generation":2}]}],"plan":[{"id":"$stageId","kind":"REPAIR_WORK","order":0,"routing":{"queueId":"$queueId","queueName":"Ремонт","queueType":"REPAIR"},"includedLineIds":["$lineId"],"primaryLineId":"$lineId","groupComment":"","taskDeadline":null}],"mediaReferences":[{"mediaId":"$mediaId","generation":2}],"coverMediaId":null,"forceCapitalRepair":true}""",
        )
    }

    @Test
    fun `direct repair creation and queue use typed public commands`() = runTest {
        val warehouseId = "abababab-abab-abab-abab-abababababab"
        val rentalItemId = "bcbcbcbc-bcbc-bcbc-bcbc-bcbcbcbcbcbc"
        val repairId = "cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd"
        val lineId = "dededede-dede-dede-dede-dededededede"
        val stageId = "efefefef-efef-efef-efef-efefefefefef"
        val catalogVersionId = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f0"
        val catalogNodeId = "01010101-0101-0101-0101-010101010101"
        val queueId = "02020202-0202-0202-0202-020202020202"
        val routing = RoutingSnapshotDto(queueId, "Ремонт", "REPAIR")
        val line = EstimateLineInputDto(
            id = lineId,
            catalogSnapshot = CatalogNodeSnapshotDto(
                catalogVersionId = catalogVersionId,
                nodeId = catalogNodeId,
                nodeType = "WORK",
                name = "Direct repair work",
                unit = null,
                unitPrice = null,
                durationMinutes = 45,
                routing = null,
                furnitureEquipment = null,
            ),
            lineType = "WORK",
            description = "Direct repair work",
            unit = null,
            quantity = "1",
            unitPrice = "0.00",
            normativeMinutes = 45,
            comment = null,
            mediaReferences = emptyList(),
        )
        val plan = PlanStageInputDto(
            id = stageId,
            kind = "REPAIR_WORK",
            order = 0,
            routing = routing,
            includedLineIds = listOf(lineId),
            primaryLineId = lineId,
            groupComment = "",
            taskDeadline = null,
        )

        val create = captureRequest {
            api.createDirectRepair(
                idempotencyKey = "direct-repair-create-1",
                request = CreateDirectRepairRequest(
                    warehouseId = warehouseId,
                    rentalItemId = rentalItemId,
                    dispatchDate = "2026-07-27",
                    sourceParty = null,
                    lines = listOf(line),
                    plan = listOf(plan),
                    mediaReferences = emptyList(),
                    forceCapitalRepair = true,
                ),
            )
        }
        val queue = captureRequest {
            api.queueRepairPlan(
                repairId = repairId,
                warehouseId = warehouseId,
                idempotencyKey = "direct-repair-queue-1",
                request = PriorityVersionRequest(
                    expectedVersion = 4,
                    priority = 1,
                    movementToRepair = true,
                    logisticsPlanningMode = "AUTO",
                    logisticsScheduledDate = null,
                ),
            )
        }

        create.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/repairs/direct",
            idempotencyKey = "direct-repair-create-1",
            body = """{"warehouseId":"$warehouseId","rentalItemId":"$rentalItemId","dispatchDate":"2026-07-27","sourceParty":null,"lines":[{"id":"$lineId","catalogSnapshot":{"catalogVersionId":"$catalogVersionId","nodeId":"$catalogNodeId","nodeType":"WORK","name":"Direct repair work","unit":null,"unitPrice":null,"durationMinutes":45,"routing":null,"furnitureEquipment":null},"lineType":"WORK","description":"Direct repair work","unit":null,"quantity":"1","unitPrice":"0.00","normativeMinutes":45,"comment":null,"mediaReferences":[]}],"plan":[{"id":"$stageId","kind":"REPAIR_WORK","order":0,"routing":{"queueId":"$queueId","queueName":"Ремонт","queueType":"REPAIR"},"includedLineIds":["$lineId"],"primaryLineId":"$lineId","groupComment":"","taskDeadline":null}],"mediaReferences":[],"coverMediaId":null,"forceCapitalRepair":true}""",
        )
        queue.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/repairs/$repairId/plan?warehouseId=$warehouseId",
            idempotencyKey = "direct-repair-queue-1",
            body = """{"expectedVersion":4,"priority":1,"movementToRepair":true,"logisticsPlanningMode":"AUTO","logisticsScheduledDate":null}""",
        )
    }

    @Test
    fun `rework and acceptance use repair lifecycle commands with CAS and idempotency`() = runTest {
        val warehouseId = "13131313-1313-1313-1313-131313131313"
        val repairId = "24242424-2424-2424-2424-242424242424"
        val mediaId = "35353535-3535-3535-3535-353535353535"
        val sourceLineId = "36363636-3636-3636-3636-363636363636"
        val childLineId = "37373737-3737-3737-3737-373737373737"

        val candidates = captureRequest {
            api.reworkCandidates(repairId, warehouseId)
        }
        val rework = captureRequest {
            api.createRework(
                repairId = repairId,
                warehouseId = warehouseId,
                idempotencyKey = "repair-rework-1",
                request = CreateReworkRequest(
                    expectedVersion = 11,
                    reason = "Нужно исправить отделку",
                    lines = listOf(
                        ReworkLineInputDto(
                            id = childLineId,
                            disposition = "REPEAT",
                            sourceRepairId = repairId,
                            sourceLineId = sourceLineId,
                            quantity = "2",
                            comment = "Переделать полностью",
                        ),
                    ),
                    plan = emptyList(),
                    mediaReferences = emptyList(),
                ),
            )
        }
        val accept = captureRequest {
            api.acceptRepair(
                repairId = repairId,
                warehouseId = warehouseId,
                idempotencyKey = "repair-accept-1",
                request = RepairDecisionRequest(
                    expectedVersion = 12,
                    comment = "Работы приняты",
                    mediaReferences = listOf(MediaReferenceDto(mediaId, 3)),
                ),
            )
        }

        candidates.assertPublicSameOriginPath(
            "/api/maintenance/v1/repairs/$repairId/rework-candidates" +
                "?warehouseId=$warehouseId",
        )
        rework.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/repairs/$repairId/reworks?warehouseId=$warehouseId",
            idempotencyKey = "repair-rework-1",
            body = """{"expectedVersion":11,"reason":"Нужно исправить отделку","lines":[{"id":"$childLineId","disposition":"REPEAT","sourceRepairId":"$repairId","sourceLineId":"$sourceLineId","quantity":"2","comment":"Переделать полностью"}],"plan":[],"mediaReferences":[],"coverMediaId":null}""",
        )
        accept.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/repairs/$repairId/accept?warehouseId=$warehouseId",
            idempotencyKey = "repair-accept-1",
            body = """{"expectedVersion":12,"comment":"Работы приняты","mediaReferences":[{"mediaId":"$mediaId","generation":3}]}""",
        )
    }

    @Test
    fun `repair queue reads one aggregate ordinary board without query dimensions`() = runTest {
        val warehouseId = "46464646-4646-4646-4646-464646464646"

        val board = captureRequest {
            api.taskBoard(warehouseId = warehouseId)
        }

        board.assertPublicSameOriginPath(
            "/api/task-board/warehouses/$warehouseId/task-board",
        )
    }

    @Test
    fun `conditional maintenance and queue reads send validators and expose not modified`() = runTest {
        val warehouseId = "11111111-aaaa-bbbb-cccc-111111111111"
        val validator = "W/\"rwms-local-v1\""
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(304)
                    .setHeader("ETag", validator),
            )
        }

        val catalog = api.catalogVersions(warehouseId = warehouseId, ifNoneMatch = validator)
        val estimates = api.estimates(warehouseId = warehouseId, ifNoneMatch = validator)
        val repairs = api.repairs(warehouseId = warehouseId, ifNoneMatch = validator)
        val queue = api.taskBoard(warehouseId = warehouseId, ifNoneMatch = validator)

        assertThat(listOf(catalog, estimates, repairs, queue).map { it.code() })
            .containsExactly(304, 304, 304, 304)
            .inOrder()
        val requests = List(4) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertThat(requests.map { it.getHeader("If-None-Match") })
            .containsExactly(validator, validator, validator, validator)
            .inOrder()
        assertThat(requests.map { it.path }).containsExactly(
            "/api/maintenance/v1/catalog/versions?warehouseId=$warehouseId&page=0&size=200&lifecycle=ACTIVE",
            "/api/maintenance/v1/estimates?warehouseId=$warehouseId&page=0&size=100",
            "/api/maintenance/v1/repairs?warehouseId=$warehouseId&page=0&size=100",
            "/api/task-board/warehouses/$warehouseId/task-board",
        ).inOrder()
    }

    @Test
    fun `custom material command carries explicit type unit and zero duration`() = runTest {
        val warehouseId = "71717171-aaaa-bbbb-cccc-717171717171"
        val rentalItemId = "72727272-aaaa-bbbb-cccc-727272727272"
        val lineId = "73737373-aaaa-bbbb-cccc-737373737373"
        val create = captureRequest {
            api.createEstimate(
                idempotencyKey = "custom-material-1",
                request = CreateEstimateRequest(
                    warehouseId = warehouseId,
                    rentalItemId = rentalItemId,
                    dispatchDate = "2026-07-27",
                    sourceParty = "Контрагент",
                    lines = listOf(
                        EstimateLineInputDto(
                            id = lineId,
                            catalogSnapshot = null,
                            lineType = "MATERIAL",
                            description = "Пользовательский крепёж",
                            unit = "шт.",
                            quantity = "2",
                            unitPrice = "15.50",
                            normativeMinutes = 0,
                            comment = null,
                            mediaReferences = emptyList(),
                        ),
                    ),
                    plan = emptyList(),
                    mediaReferences = emptyList(),
                ),
            )
        }

        create.assertJsonCommand(
            method = "POST",
            path = "/api/maintenance/v1/estimates",
            idempotencyKey = "custom-material-1",
            body = """{"warehouseId":"$warehouseId","rentalItemId":"$rentalItemId","dispatchDate":"2026-07-27","sourceParty":"Контрагент","lines":[{"id":"$lineId","catalogSnapshot":null,"lineType":"MATERIAL","description":"Пользовательский крепёж","unit":"шт.","quantity":"2","unitPrice":"15.50","normativeMinutes":0,"comment":null,"mediaReferences":[]}],"plan":[],"mediaReferences":[],"coverMediaId":null,"forceCapitalRepair":false}""",
        )
    }

    @Test
    fun `maintenance reads use active catalog and public gateway paths`() = runTest {
        val warehouseId = "11111111-aaaa-bbbb-cccc-111111111111"
        val catalogVersionId = "22222222-aaaa-bbbb-cccc-222222222222"
        val estimateId = "33333333-aaaa-bbbb-cccc-333333333333"
        val repairId = "44444444-aaaa-bbbb-cccc-444444444444"
        val rentalItemId = "55555555-aaaa-bbbb-cccc-555555555555"

        val versions = captureRequest { api.catalogVersions(warehouseId) }
        val nodes = captureRequest { api.catalogNodes(catalogVersionId, warehouseId) }
        val links = captureRequest { api.catalogLinks(catalogVersionId, warehouseId) }
        val estimates = captureRequest { api.estimates(warehouseId) }
        val estimate = captureRequest { api.estimate(estimateId, warehouseId) }
        val repairs = captureRequest { api.repairs(warehouseId) }
        val acceptance = captureRequest {
            api.acceptance(warehouseId, size = 200, state = "PENDING")
        }
        val repair = captureRequest { api.repair(repairId, warehouseId) }
        val rentalItem = captureRequest { api.rentalItem(rentalItemId) }

        versions.assertPublicSameOriginPath(
            "/api/maintenance/v1/catalog/versions?warehouseId=$warehouseId&page=0&size=200&lifecycle=ACTIVE",
        )
        nodes.assertPublicSameOriginPath(
            "/api/maintenance/v1/catalog/versions/$catalogVersionId/nodes?warehouseId=$warehouseId",
        )
        links.assertPublicSameOriginPath(
            "/api/maintenance/v1/catalog/versions/$catalogVersionId/links?warehouseId=$warehouseId",
        )
        estimates.assertPublicSameOriginPath(
            "/api/maintenance/v1/estimates?warehouseId=$warehouseId&page=0&size=100",
        )
        estimate.assertPublicSameOriginPath(
            "/api/maintenance/v1/estimates/$estimateId?warehouseId=$warehouseId",
        )
        repairs.assertPublicSameOriginPath(
            "/api/maintenance/v1/repairs?warehouseId=$warehouseId&page=0&size=100",
        )
        acceptance.assertPublicSameOriginPath(
            "/api/maintenance/v1/acceptance?warehouseId=$warehouseId&page=0&size=200&state=PENDING",
        )
        repair.assertPublicSameOriginPath(
            "/api/maintenance/v1/repairs/$repairId?warehouseId=$warehouseId",
        )
        rentalItem.assertPublicSameOriginPath("/api/asset/v1/rental-items/$rentalItemId")
    }

    @Test
    fun `maintenance asset pickers retain repeated public status exclusions`() = runTest {
        val warehouseId = "66666666-aaaa-bbbb-cccc-666666666666"
        val directRepair = captureRequest {
            api.rentalItems(
                warehouseId = warehouseId,
                size = 200,
                excludeStatuses = listOf("RENTED", "AFTER_RENT"),
            )
        }
        val estimate = captureRequest {
            api.rentalItems(
                warehouseId = warehouseId,
                size = 200,
                search = "CAB",
                excludeStatuses = listOf("RENTED", "REPAIR", "FREE"),
            )
        }

        directRepair.assertPublicSameOriginPath(
            "/api/asset/v1/rental-items?warehouseId=$warehouseId&page=0&size=200&excludeStatus=RENTED&excludeStatus=AFTER_RENT",
        )
        estimate.assertPublicSameOriginPath(
            "/api/asset/v1/rental-items?warehouseId=$warehouseId&page=0&size=200&search=CAB&excludeStatus=RENTED&excludeStatus=REPAIR&excludeStatus=FREE",
        )
    }

    @Test
    fun `media upload commands retain same-origin content path and exact payloads`() = runTest {
        val documentId = "88888888-8888-8888-8888-888888888888"
        val lineId = "99999999-9999-9999-9999-999999999999"
        val warehouseId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val folderId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val uploadSessionId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val checksum = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val session = captureRequest {
            api.createUploadSession(
                idempotencyKey = "media-upload-1",
                request = CreateUploadSessionRequest(
                    ownerType = "LOGISTICS_RETURN",
                    documentId = documentId,
                    lineId = lineId,
                    warehouseId = warehouseId,
                    context = "RETURN_INSPECTION",
                    folderId = folderId,
                    fileName = "return-photo.jpg",
                    contentType = "image/jpeg",
                    contentLength = payload.size.toLong(),
                    checksumSha256 = checksum,
                    sortOrder = 3,
                ),
            )
        }
        val content = captureRequest {
            api.uploadContent(
                contentPath = "/api/media/v1/upload-sessions/$uploadSessionId/content",
                idempotencyKey = "media-upload-1",
                body = payload.toRequestBody("image/jpeg".toMediaType()),
            )
        }
        val complete = captureRequest {
            api.finalizeUpload(
                uploadSessionId = uploadSessionId,
                idempotencyKey = "media-upload-1",
                request = FinalizeUploadRequest(
                    objectVersionId = "version-1",
                    etag = "etag-1",
                    checksumSha256 = checksum,
                ),
            )
        }
        session.assertJsonCommand(
            method = "POST",
            path = "/api/media/v1/upload-sessions",
            idempotencyKey = "media-upload-1",
            body = """{"ownerType":"LOGISTICS_RETURN","documentId":"$documentId","lineId":"$lineId","warehouseId":"$warehouseId","context":"RETURN_INSPECTION","folderId":"$folderId","fileName":"return-photo.jpg","contentType":"image/jpeg","contentLength":4,"checksumSha256":"$checksum","sortOrder":3}""",
        )
        content.assertPublicSameOriginPath(
            "/api/media/v1/upload-sessions/$uploadSessionId/content",
        )
        assertThat(content.method).isEqualTo("PUT")
        assertThat(content.getHeader("Idempotency-Key")).isEqualTo("media-upload-1")
        assertThat(content.getHeader("Content-Type")).isEqualTo("image/jpeg")
        assertThat(content.getHeader("Content-Length")).isEqualTo(payload.size.toString())
        assertThat(content.body.readByteArray().toList()).containsExactlyElementsIn(payload.toList()).inOrder()
        complete.assertJsonCommand(
            method = "POST",
            path = "/api/media/v1/upload-sessions/$uploadSessionId/complete",
            idempotencyKey = "media-upload-1",
            body = """{"objectVersionId":"version-1","etag":"etag-1","checksumSha256":"$checksum"}""",
        )
    }

    @Test
    fun `shipment lifecycle uses canonical public paths CAS and idempotency headers`() = runTest {
        val warehouseId = "11111111-aaaa-bbbb-cccc-111111111111"
        val documentId = "22222222-aaaa-bbbb-cccc-222222222222"
        val assetId = "33333333-aaaa-bbbb-cccc-333333333333"
        val equipmentId = "44444444-aaaa-bbbb-cccc-444444444444"
        val clientId = "55555555-aaaa-bbbb-cccc-555555555555"
        val orderId = "66666666-aaaa-bbbb-cccc-666666666666"
        val list = captureRequest { api.shipments(warehouseId) }
        val detail = captureRequest { api.shipment(documentId) }
        val create = captureRequest {
            api.createShipment(
                idempotencyKey = "shipment-create",
                request = CreateShipmentRequest(
                    warehouseId = warehouseId,
                    clientId = clientId,
                    rentalOrderId = orderId,
                    partySnapshot = "ООО Клиент",
                    driverSnapshot = "Иван Петров",
                    lines = listOf(
                        ShipmentLineRequest(
                            assetId = assetId,
                            assetVersion = 8,
                            allocations = listOf(
                                ShipmentEquipmentAllocationRequest(
                                    equipmentId = equipmentId,
                                    quantity = 2,
                                    expectedStockVersion = 5,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        val plan = captureRequest {
            api.replaceShipmentPlan(
                documentId = documentId,
                expectedVersion = 7,
                idempotencyKey = "shipment-plan",
                request = ShipmentPlanRequest(
                    driverSnapshot = "Иван Петров",
                    scheduledDate = "2026-07-29",
                ),
            )
        }
        val readiness = captureRequest {
            api.shipmentFurnitureReadiness(documentId)
        }
        val furniture = captureRequest {
            api.createShipmentFurnitureTasks(
                documentId = documentId,
                expectedVersion = 8,
                idempotencyKey = "shipment-furniture",
            )
        }
        val confirm = captureRequest {
            api.confirmShipmentPreparation(
                documentId = documentId,
                expectedVersion = 9,
                idempotencyKey = "shipment-confirm",
            )
        }
        val cancel = captureRequest {
            api.cancelShipment(
                documentId = documentId,
                expectedVersion = 10,
                idempotencyKey = "shipment-cancel",
            )
        }

        list.assertPublicSameOriginPath(
            "/api/logistics/v1/shipments?warehouseId=$warehouseId",
        )
        detail.assertPublicSameOriginPath("/api/logistics/v1/shipments/$documentId")
        create.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/shipments",
            idempotencyKey = "shipment-create",
            body = """{"warehouseId":"$warehouseId","clientId":"$clientId","rentalOrderId":"$orderId","partySnapshot":"ООО Клиент","driverSnapshot":"Иван Петров","lines":[{"assetId":"$assetId","assetVersion":8,"allocations":[{"equipmentId":"$equipmentId","quantity":2,"expectedStockVersion":5}]}]}""",
        )
        plan.assertJsonCommand(
            method = "PUT",
            path = "/api/logistics/v1/shipments/$documentId/plan?expectedVersion=7",
            idempotencyKey = "shipment-plan",
            body = """{"driverSnapshot":"Иван Петров","scheduledDate":"2026-07-29"}""",
        )
        readiness.assertPublicSameOriginPath(
            "/api/logistics/v1/shipments/$documentId/furniture-readiness",
        )
        furniture.assertPublicSameOriginPath(
            "/api/logistics/v1/shipments/$documentId/furniture-tasks?expectedVersion=8",
        )
        assertThat(furniture.getHeader("Idempotency-Key")).isEqualTo("shipment-furniture")
        confirm.assertPublicSameOriginPath(
            "/api/logistics/v1/shipments/$documentId/confirm-preparation?expectedVersion=9",
        )
        assertThat(confirm.getHeader("Idempotency-Key")).isEqualTo("shipment-confirm")
        cancel.assertPublicSameOriginPath(
            "/api/logistics/v1/shipments/$documentId/cancel?expectedVersion=10",
        )
        assertThat(cancel.getHeader("Idempotency-Key")).isEqualTo("shipment-cancel")
    }

    @Test
    fun `maintenance furniture composition uses the logistics cabin task contract`() = runTest {
        val warehouseId = "11111111-dddd-cccc-bbbb-111111111111"
        val rentalItemId = "22222222-dddd-cccc-bbbb-222222222222"
        val chairId = "33333333-dddd-cccc-bbbb-333333333333"
        val tableId = "44444444-dddd-cccc-bbbb-444444444444"

        val keepInCabin = captureRequest {
            api.createCabinFurnitureTask(
                rentalItemId = rentalItemId,
                idempotencyKey = "maintenance-furniture-keep",
                request = CreateCabinFurnitureTaskRequest(
                    warehouseId = warehouseId,
                    scheduledDate = "2026-07-29",
                    contents = listOf(
                        CabinFurnitureRequirementDto(chairId, 4),
                        CabinFurnitureRequirementDto(tableId, 1),
                    ),
                ),
            )
        }
        val moveToStock = captureRequest {
            api.createCabinFurnitureTask(
                rentalItemId = rentalItemId,
                idempotencyKey = "maintenance-furniture-stock",
                request = CreateCabinFurnitureTaskRequest(
                    warehouseId = warehouseId,
                    scheduledDate = "2026-07-29",
                    contents = emptyList(),
                ),
            )
        }

        keepInCabin.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/rental-items/$rentalItemId/furniture-tasks",
            idempotencyKey = "maintenance-furniture-keep",
            body = """{"warehouseId":"$warehouseId","scheduledDate":"2026-07-29","contents":[{"equipmentId":"$chairId","quantity":4},{"equipmentId":"$tableId","quantity":1}]}""",
        )
        moveToStock.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/rental-items/$rentalItemId/furniture-tasks",
            idempotencyKey = "maintenance-furniture-stock",
            body = """{"warehouseId":"$warehouseId","scheduledDate":"2026-07-29","contents":[]}""",
        )
    }

    @Test
    fun `transfer lifecycle preserves furniture CAS and destination photo references`() = runTest {
        val sourceId = "11111111-bbbb-cccc-dddd-111111111111"
        val destinationId = "22222222-bbbb-cccc-dddd-222222222222"
        val documentId = "33333333-bbbb-cccc-dddd-333333333333"
        val lineId = "44444444-bbbb-cccc-dddd-444444444444"
        val assetId = "55555555-bbbb-cccc-dddd-555555555555"
        val equipmentId = "66666666-bbbb-cccc-dddd-666666666666"
        val mediaId = "77777777-bbbb-cccc-dddd-777777777777"
        val list = captureRequest { api.transfers(sourceId) }
        val detail = captureRequest { api.transfer(documentId) }
        val create = captureRequest {
            api.createTransfer(
                idempotencyKey = "transfer-create",
                request = CreateTransferRequest(
                    warehouseId = sourceId,
                    destinationWarehouseId = destinationId,
                    driverSnapshot = null,
                    scheduledDate = "2026-07-29",
                    lines = listOf(TransferLineRequest(assetId, 12)),
                    furnitureReplacements = listOf(
                        TransferFurnitureReplacementRequest(
                            assetId = assetId,
                            contents = listOf(
                                CabinFurnitureRequirementDto(equipmentId, 3),
                            ),
                        ),
                    ),
                ),
            )
        }
        val readiness = captureRequest {
            api.transferFurnitureReadiness(documentId)
        }
        val depart = captureRequest {
            api.departTransferLine(
                documentId = documentId,
                lineId = lineId,
                expectedVersion = 4,
                expectedLineVersion = 2,
                idempotencyKey = "transfer-depart",
            )
        }
        val arrive = captureRequest {
            api.arriveTransferLine(
                documentId = documentId,
                lineId = lineId,
                expectedVersion = 5,
                expectedLineVersion = 3,
                idempotencyKey = "transfer-arrive",
                request = ArriveTransferLineRequest(
                    listOf(MediaReferenceDto(mediaId, 2)),
                ),
            )
        }
        val cancel = captureRequest {
            api.cancelTransfer(
                documentId = documentId,
                expectedVersion = 6,
                idempotencyKey = "transfer-cancel",
            )
        }
        val reconcile = captureRequest {
            api.reconcileTransfer(
                documentId = documentId,
                expectedVersion = 7,
                idempotencyKey = "transfer-reconcile",
                request = ReconcileLogisticsRequest("Проверено руководителем"),
            )
        }
        val transferOriginal = captureRequest {
            api.originalMedia(
                mediaId = mediaId,
                generation = 2,
                ownerType = "LOGISTICS_TRANSFER",
                documentId = documentId,
                lineId = lineId,
                warehouseId = destinationId,
                context = "TRANSFER",
            )
        }

        list.assertPublicSameOriginPath(
            "/api/logistics/v1/transfers?warehouseId=$sourceId",
        )
        detail.assertPublicSameOriginPath("/api/logistics/v1/transfers/$documentId")
        create.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/transfers",
            idempotencyKey = "transfer-create",
            body = """{"warehouseId":"$sourceId","destinationWarehouseId":"$destinationId","driverSnapshot":null,"scheduledDate":"2026-07-29","lines":[{"assetId":"$assetId","assetVersion":12}],"furnitureReplacements":[{"assetId":"$assetId","contents":[{"equipmentId":"$equipmentId","quantity":3}]}]}""",
        )
        readiness.assertPublicSameOriginPath(
            "/api/logistics/v1/transfers/$documentId/furniture-readiness",
        )
        depart.assertPublicSameOriginPath(
            "/api/logistics/v1/transfers/$documentId/lines/$lineId/depart?expectedVersion=4&expectedLineVersion=2",
        )
        assertThat(depart.getHeader("Idempotency-Key")).isEqualTo("transfer-depart")
        arrive.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/transfers/$documentId/lines/$lineId/arrive?expectedVersion=5&expectedLineVersion=3",
            idempotencyKey = "transfer-arrive",
            body = """{"references":[{"mediaId":"$mediaId","generation":2}]}""",
        )
        cancel.assertPublicSameOriginPath(
            "/api/logistics/v1/transfers/$documentId/cancel?expectedVersion=6",
        )
        assertThat(cancel.getHeader("Idempotency-Key")).isEqualTo("transfer-cancel")
        reconcile.assertJsonCommand(
            method = "POST",
            path = "/api/logistics/v1/transfers/$documentId/reconcile?expectedVersion=7",
            idempotencyKey = "transfer-reconcile",
            body = """{"reason":"Проверено руководителем"}""",
        )
        transferOriginal.assertPublicSameOriginPath(
            "/api/media/v1/assets/$mediaId/original?generation=2&ownerType=LOGISTICS_TRANSFER&documentId=$documentId&lineId=$lineId&warehouseId=$destinationId&context=TRANSFER",
        )
    }

    @Test
    fun `acceptance media originals use exact same-origin owner scopes`() = runTest {
        val warehouseId = "11111111-2222-3333-4444-555555555555"
        val mediaId = "66666666-7777-8888-9999-000000000000"
        val entryId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val estimateId = "12121212-3434-5656-7878-909090909090"
        val repairId = "abababab-cdcd-efef-0101-232323232323"

        val stagePhoto = captureRequest {
            api.originalMedia(
                mediaId = mediaId,
                generation = 7,
                ownerType = "TASK_BOARD_ENTRY",
                ownerId = entryId,
                warehouseId = warehouseId,
                context = "WORK_RESULT",
            )
        }
        val estimatePhoto = captureRequest {
            api.originalMedia(
                mediaId = mediaId,
                generation = 8,
                ownerType = "MAINTENANCE_ESTIMATE",
                ownerId = estimateId,
                warehouseId = warehouseId,
                context = "ESTIMATE",
            )
        }
        val repairPhoto = captureRequest {
            api.originalMedia(
                mediaId = mediaId,
                generation = 9,
                ownerType = "MAINTENANCE_REPAIR",
                ownerId = repairId,
                warehouseId = warehouseId,
                context = "REPAIR",
            )
        }
        val smallVariantPath =
            "/api/media/v1/assets/$mediaId/variants/SMALL/content" +
                "?generation=9&ownerType=MAINTENANCE_REPAIR&ownerId=$repairId" +
                "&warehouseId=$warehouseId&context=REPAIR"
        val repairPreview = captureRequest {
            api.mediaVariantContent(smallVariantPath)
        }

        stagePhoto.assertPublicSameOriginPath(
            "/api/media/v1/assets/$mediaId/original?generation=7&ownerType=TASK_BOARD_ENTRY&ownerId=$entryId&warehouseId=$warehouseId&context=WORK_RESULT",
        )
        estimatePhoto.assertPublicSameOriginPath(
            "/api/media/v1/assets/$mediaId/original?generation=8&ownerType=MAINTENANCE_ESTIMATE&ownerId=$estimateId&warehouseId=$warehouseId&context=ESTIMATE",
        )
        repairPhoto.assertPublicSameOriginPath(
            "/api/media/v1/assets/$mediaId/original?generation=9&ownerType=MAINTENANCE_REPAIR&ownerId=$repairId&warehouseId=$warehouseId&context=REPAIR",
        )
        repairPreview.assertPublicSameOriginPath(smallVariantPath)
        assertThat(stagePhoto.method).isEqualTo("GET")
        assertThat(estimatePhoto.method).isEqualTo("GET")
        assertThat(repairPhoto.method).isEqualTo("GET")
        assertThat(repairPreview.method).isEqualTo("GET")
    }

    private suspend fun captureRequest(command: suspend () -> Any?): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = runCatching { command() }
        val response = result.getOrNull()
        if (response is retrofit2.Response<*>) {
            assertThat(response.code()).isEqualTo(500)
        } else {
            assertThat(result.exceptionOrNull()).isInstanceOf(HttpException::class.java)
        }

        return checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    }

    private fun RecordedRequest.assertJsonCommand(
        method: String,
        path: String,
        idempotencyKey: String?,
        body: String,
    ) {
        assertPublicSameOriginPath(path)
        assertThat(this.method).isEqualTo(method)
        assertThat(requireNotNull(getHeader("Content-Type"))).startsWith("application/json")
        assertThat(getHeader("Idempotency-Key")).isEqualTo(idempotencyKey)
        assertThat(this.body.readUtf8()).isEqualTo(body)
    }

    private fun RecordedRequest.assertPublicSameOriginPath(path: String) {
        val requestUrl = requireNotNull(requestUrl)
        val baseUrl = server.url("/")

        assertThat(requestUrl.host).isEqualTo(baseUrl.host)
        assertThat(requestUrl.port).isEqualTo(baseUrl.port)
        assertThat(requestUrl.encodedPath).startsWith("/api/")
        assertThat(this.path).isEqualTo(path)
    }
}
