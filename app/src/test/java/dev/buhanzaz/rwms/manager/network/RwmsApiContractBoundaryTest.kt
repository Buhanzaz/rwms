package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.media.MediaUploader
import dev.buhanzaz.rwms.manager.media.PhotoPayload
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * Pins every manager Retrofit operation to the current public operations in
 * `contracts/openapi/{auth,warehouse,inventory,asset,logistics,maintenance,task-board,media}-service.yaml`.
 * Response fixtures are decoded as server-owned projections; request fixtures are encoded with
 * the same Moshi configuration as [RwmsBackend]. Binary bodies are converter-checked but are not
 * JSON fixtures.
 */
class RwmsApiContractBoundaryTest {
    private val moshi = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `all declared routes remain exact public gateway operations`() {
        val expected = expectedManagerRoutes()
        val methods = RwmsApi::class.java.declaredMethods
            .filterNot(Method::isSynthetic)
            .associateBy(Method::getName)

        assertThat(expected).hasSize(63)
        assertWithMessage("RwmsApi method inventory must stay synchronized with canonical public OpenAPI")
            .that(methods.keys)
            .containsExactlyElementsIn(expected.keys)

        expected.forEach { (methodName, contractRoute) ->
            val method = checkNotNull(methods[methodName])
            val (actualVerb, actualPath) = httpRoute(method)
            assertWithMessage("$methodName verb must match ${contractRoute.canonicalSource}")
                .that(actualVerb)
                .isEqualTo(contractRoute.verb)
            assertWithMessage(
                "$methodName gateway path must match ${contractRoute.canonicalSource}; " +
                    "only placeholder names are normalized",
            ).that(normalizePlaceholderNames(actualPath))
                .isEqualTo(normalizePlaceholderNames(contractRoute.gatewayPath))

            val hasDynamicUrl = method.parameterAnnotations
                .any { annotations -> annotations.any { annotation -> annotation is Url } }
            assertWithMessage("$methodName dynamic URL policy comes from ${contractRoute.canonicalSource}")
                .that(hasDynamicUrl)
                .isEqualTo(contractRoute.gatewayPath.isEmpty())

            if (actualPath.isNotEmpty()) {
                assertPublicGatewayPath(methodName, actualPath, contractRoute.canonicalSource)
            }
        }

        val dynamicMethods = expected
            .filterValues { route -> route.gatewayPath.isEmpty() }
            .keys
        assertThat(dynamicMethods).containsExactly("uploadContent", "mediaVariantContent")
    }

    @Test
    fun `production Moshi resolves every Retrofit request and response shape eagerly`() {
        val api = Retrofit.Builder()
            .baseUrl("https://gateway.example.test/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .validateEagerly(true)
            .build()
            .create(RwmsApi::class.java)

        assertThat(api).isNotNull()
    }

    @Test
    fun `auth response fixture decodes from the canonical public shape`() {
        val currentUser = decode<CurrentUserDto>(
            """
            {
              "id":"11111111-1111-1111-1111-111111111111",
              "username":"manager",
              "displayName":"Warehouse Manager",
              "firstName":"Warehouse",
              "lastName":"Manager",
              "email":"manager@example.test",
              "principalType":"USER",
              "globalRole":"WAREHOUSE_MANAGER",
              "rentalAccess":true,
              "warehouseAccessAll":false,
              "warehouseAccesses":[{
                "warehouseId":"22222222-2222-2222-2222-222222222222",
                "level":"EDIT"
              }]
            }
            """.trimIndent(),
        )

        assertThat(currentUser.globalRole).isEqualTo("WAREHOUSE_MANAGER")
        assertThat(currentUser.warehouseAccesses.single().level).isEqualTo("EDIT")
    }

    @Test
    fun `warehouse response fixture decodes required lifecycle projection additions`() {
        val warehouse = decode<WarehouseDto>(
            """
            {
              "id":"22222222-2222-2222-2222-222222222222",
              "version":7,
              "name":"Central warehouse",
              "city":"Moscow",
              "address":"Warehouse street, 1",
              "timeZone":"Europe/Moscow",
              "active":true,
              "lifecycleState":"ACTIVE",
              "sortOrder":10
            }
            """.trimIndent(),
        )

        assertThat(warehouse.timeZone).isEqualTo("Europe/Moscow")
        assertThat(warehouse.active).isTrue()
    }

    @Test
    fun `inventory response and command fixtures use the current canonical fields`() {
        val inventory = decode<InventorySessionDto>(
            """
            {
              "id":"33333333-3333-3333-3333-333333333333",
              "sessionRevision":9,
              "warehouseId":"22222222-2222-2222-2222-222222222222",
              "warehouseVersion":7,
              "warehouseTimeZone":"Europe/Moscow",
              "author":{"actorId":"11111111-1111-1111-1111-111111111111","actorType":"USER"},
              "businessDate":"2026-08-09",
              "lifecycle":"ACTIVE",
              "reviewStage":"CABINS",
              "furnitureReconciliationState":"NOT_REQUIRED",
              "expectedCount":12,
              "findingCount":4,
              "inspectedCount":3,
              "startedAt":"2026-08-09T08:00:00Z",
              "terminalAt":null,
              "publicationState":"NOT_REQUESTED",
              "membershipMovements":[],
              "statistics":null,
              "cancellation":null
            }
            """.trimIndent(),
        )
        val request = SaveInspectionRequest(
            expectedSessionRevision = 9,
            expectedFindingRevision = 4,
            inspection = "READY",
            comment = "Checked",
            passportObservation = ObservationInput("ABSENT", null),
            equipmentObservation = ObservationInput("EXPLICIT_EMPTY", emptyList<Any>()),
            media = emptyList(),
            coverMediaId = null,
            planSelection = null,
        )
        val encoded = moshi.adapter(SaveInspectionRequest::class.java).toJson(request)

        assertThat(inventory.sessionRevision).isEqualTo(9)
        assertThat(inventory.publicationState).isEqualTo("NOT_REQUESTED")
        assertThat(encoded).contains("\"expectedSessionRevision\":9")
        assertThat(encoded).contains("\"passportObservation\":{\"presence\":\"ABSENT\",\"value\":null}")
        assertThat(encoded).contains("\"coverMediaId\":null")
        assertThat(encoded).contains("\"planSelection\":null")
    }

    @Test
    fun `asset response fixture decodes the public rental item projection`() {
        val rentalItem = decode<RentalItemDto>(
            """
            {
              "id":"44444444-4444-4444-4444-444444444444",
              "version":5,
              "warehouseId":"22222222-2222-2222-2222-222222222222",
              "number":"CAB-001",
              "status":"WAREHOUSE",
              "rentalTypeId":null,
              "rentalType":null,
              "dimensionId":null,
              "dimensions":null,
              "finishingId":null,
              "finishing":null,
              "category":null,
              "characteristics":[],
              "linoleum":null,
              "generalComment":null,
              "passport":{},
              "tags":[],
              "contents":[],
              "activeOrderReservation":null,
              "createdAt":"2026-08-09T08:00:00Z",
              "updatedAt":"2026-08-09T08:05:00Z"
            }
            """.trimIndent(),
        )

        assertThat(rentalItem.number).isEqualTo("CAB-001")
        assertThat(rentalItem.status).isEqualTo("WAREHOUSE")
    }

    @Test
    fun `logistics response and command fixtures keep warehouse and version ownership`() {
        val document = decode<LogisticsDocumentDto>(
            """
            {
              "id":"55555555-5555-5555-5555-555555555555",
              "version":6,
              "documentType":"TRANSFER",
              "state":"DRAFT",
              "warehouseId":"22222222-2222-2222-2222-222222222222",
              "destinationWarehouseId":"66666666-6666-6666-6666-666666666666",
              "partySnapshot":null,
              "driverSnapshot":null,
              "clientId":null,
              "equipmentMovementTaskId":null,
              "scheduledDate":"2026-08-10",
              "rentalOrderId":null,
              "rentalShipmentId":null,
              "lines":[],
              "createdAt":"2026-08-09T08:00:00Z",
              "updatedAt":"2026-08-09T08:05:00Z"
            }
            """.trimIndent(),
        )
        val request = CreateTransferRequest(
            warehouseId = "22222222-2222-2222-2222-222222222222",
            destinationWarehouseId = "66666666-6666-6666-6666-666666666666",
            driverSnapshot = null,
            scheduledDate = "2026-08-10",
            lines = listOf(TransferLineRequest("44444444-4444-4444-4444-444444444444", 5)),
            furnitureReplacements = emptyList(),
        )
        val encoded = moshi.adapter(CreateTransferRequest::class.java).toJson(request)

        assertThat(document.documentType).isEqualTo("TRANSFER")
        assertThat(document.destinationWarehouseId)
            .isEqualTo("66666666-6666-6666-6666-666666666666")
        assertThat(document.scheduledDate).isEqualTo("2026-08-10")
        assertThat(encoded).contains("\"driverSnapshot\":null")
        assertThat(encoded).contains("\"assetVersion\":5")
    }

    @Test
    fun `maintenance response and command fixtures keep server-owned repair semantics`() {
        val repair = decode<RepairDto>(
            """
            {
              "id":"77777777-7777-7777-7777-777777777777",
              "rootRepairId":"77777777-7777-7777-7777-777777777777",
              "sourceRepairId":null,
              "estimateId":null,
              "warehouseId":"22222222-2222-2222-2222-222222222222",
              "rentalItemId":"44444444-4444-4444-4444-444444444444",
              "origin":"DIRECT",
              "kind":"CURRENT",
              "executionState":"DRAFT",
              "acceptanceState":"NOT_READY",
              "reclassificationState":"NONE",
              "version":3,
              "dispatchDate":"2026-08-09",
              "priority":3,
              "sourceParty":null,
              "plan":{
                "repairId":"77777777-7777-7777-7777-777777777777",
                "repairVersion":3,
                "stages":[]
              },
              "inventorySource":null,
              "lease":null,
              "mediaReferences":[],
              "coverMediaId":null,
              "complexity":{
                "type":"LIGHT",
                "name":"Лёгкий ремонт",
                "color":"#00AA00",
                "plannedMinutes":"0",
                "forcedCapital":false
              },
              "movementToRepair":false,
              "logisticsPlanningMode":null,
              "logisticsScheduledDate":null,
              "createdAt":"2026-08-09T08:00:00Z",
              "updatedAt":"2026-08-09T08:05:00Z",
              "actor":{"actorId":"11111111-1111-1111-1111-111111111111","actorType":"USER"}
            }
            """.trimIndent(),
        )
        val request = CompleteEstimateRequest(
            expectedVersion = 3,
            priority = 3,
            movementToRepair = false,
            logisticsPlanningMode = null,
            logisticsScheduledDate = null,
            allowUnaccountedFurniture = false,
        )
        val encoded = moshi.adapter(CompleteEstimateRequest::class.java).toJson(request)

        assertThat(repair.version).isEqualTo(3)
        assertThat(repair.plan.repairId).isEqualTo(repair.id)
        assertThat(encoded).contains("\"expectedVersion\":3")
        assertThat(encoded).contains("\"logisticsPlanningMode\":null")
        assertThat(encoded).contains("\"allowUnaccountedFurniture\":false")
    }

    @Test
    fun `task board response and command fixtures keep exact optimistic fences`() {
        val snapshot = decode<TaskBoardSnapshotDto>(
            """
            {
              "warehouseId":"22222222-2222-2222-2222-222222222222",
              "selectedDate":"2026-08-09",
              "availableDates":["2026-08-09"],
              "columns":[]
            }
            """.trimIndent(),
        )
        val request = MoveTaskBoardEntryRequest(
            expectedVersion = 8,
            expectedTaskVersion = 13,
            targetQueueId = "88888888-8888-8888-8888-888888888888",
            targetIndex = 2,
            targetDate = "2026-08-10",
        )
        val encoded = moshi.adapter(MoveTaskBoardEntryRequest::class.java).toJson(request)

        assertThat(snapshot.selectedDate).isEqualTo("2026-08-09")
        assertThat(encoded).contains("\"expectedVersion\":8")
        assertThat(encoded).contains("\"expectedTaskVersion\":13")
        assertThat(encoded).contains("\"targetDate\":\"2026-08-10\"")
    }

    @Test
    fun `media response and command fixtures expose only same-origin public paths`() {
        val session = decode<UploadSessionDto>(
            """
            {
              "uploadSessionId":"99999999-9999-9999-9999-999999999999",
              "mediaId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "expiresAt":"2026-08-09T09:00:00Z",
              "contentUploadUrl":"/api/media/v1/upload-sessions/99999999-9999-9999-9999-999999999999/content"
            }
            """.trimIndent(),
        )
        val asset = decode<MediaAssetDto>(
            """
            {
              "id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "folderId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "clientReferenceId":null,
              "fileName":"photo.jpg",
              "contentType":"image/jpeg",
              "kind":"IMAGE",
              "status":"READY",
              "version":2,
              "generation":1,
              "rotationDegrees":0,
              "sortOrder":0,
              "sizeBytes":128,
              "createdAt":"2026-08-09T08:00:00Z",
              "variants":[{
                "kind":"SMALL",
                "contentType":"image/webp",
                "contentPath":"/api/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/variants/SMALL/content?generation=1",
                "width":320,
                "height":180
              }]
            }
            """.trimIndent(),
        )
        val create = CreateUploadSessionRequest(
            ownerType = "INVENTORY_FINDING",
            ownerId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            warehouseId = "22222222-2222-2222-2222-222222222222",
            context = "INSPECTION",
            folderId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            fileName = "photo.jpg",
            contentType = "image/jpeg",
            contentLength = 128,
            checksumSha256 = "a".repeat(64),
            sortOrder = 0,
        )
        val finalize = FinalizeUploadRequest("object-version", "etag", "a".repeat(64))
        val createJson = moshi.adapter(CreateUploadSessionRequest::class.java).toJson(create)
        val finalizeJson = moshi.adapter(FinalizeUploadRequest::class.java).toJson(finalize)

        assertThat(session.contentUploadUrl).startsWith("/api/media/v1/upload-sessions/")
        assertThat(asset.variants.single().contentPath).startsWith("/api/media/v1/assets/")
        assertThat(createJson).contains("\"ownerType\":\"INVENTORY_FINDING\"")
        assertThat(createJson).contains("\"contentLength\":128")
        assertThat(finalizeJson).contains("\"objectVersionId\":\"object-version\"")
    }

    @Test
    fun `dynamic media callers reject service origins before Retrofit is invoked`() = runTest {
        val uploadCalls = mutableListOf<String>()
        val unsafeUploadApi = proxyApi { method ->
            uploadCalls += method.name
            when (method.name) {
                "createUploadSession" -> UploadSessionDto(
                    uploadSessionId = "99999999-9999-9999-9999-999999999999",
                    mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    expiresAt = "2026-08-09T09:00:00Z",
                    contentUploadUrl = "https://media-service:8080/private/object",
                )
                else -> error("Unexpected Retrofit call ${method.name}")
            }
        }
        val uploadFailure = runCatching {
            MediaUploader(
                api = unsafeUploadApi,
                payloadLoader = {
                    PhotoPayload(
                        fileName = "photo.jpg",
                        contentType = "image/jpeg",
                        bytes = byteArrayOf(1),
                        checksumSha256 = "a".repeat(64),
                    )
                },
                testContract = Unit,
            ).upload(
                owner = MediaOwner(
                    ownerType = "INVENTORY_FINDING",
                    ownerId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                    warehouseId = "22222222-2222-2222-2222-222222222222",
                    context = "INSPECTION",
                ),
                photoUris = listOf("local-photo"),
            )
        }.exceptionOrNull()

        assertThat(uploadFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(uploadCalls).containsExactly("createUploadSession")

        val downloadCalls = mutableListOf<String>()
        val unsafeDownloadApi = proxyApi { method ->
            downloadCalls += method.name
            error("Unsafe dynamic read must be rejected before ${method.name}")
        }
        val downloadFailure = runCatching {
            MediaDownloader(
                api = unsafeDownloadApi,
                cacheDir = File(System.getProperty("java.io.tmpdir"), "rwms-manager-boundary-test"),
            ).downloadVariant(
                mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                generation = 1,
                contentPath =
                    "https://localhost/api/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/variants/SMALL/content",
            )
        }.exceptionOrNull()

        assertThat(downloadFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(downloadCalls).isEmpty()
    }

    private inline fun <reified T : Any> decode(fixture: String): T =
        checkNotNull(moshi.adapter(T::class.java).fromJson(fixture))

    @Suppress("UNCHECKED_CAST")
    private fun proxyApi(result: (Method) -> Any?): RwmsApi =
        Proxy.newProxyInstance(
            RwmsApi::class.java.classLoader,
            arrayOf(RwmsApi::class.java),
        ) { _, method, _ -> result(method) } as RwmsApi
}

/** One manager gateway operation and the canonical OpenAPI location that authorizes it. */
private data class ManagerContractRoute(
    val verb: String,
    val gatewayPath: String,
    val canonicalSource: String,
)

private fun httpRoute(method: Method): Pair<String, String> {
    val annotations = listOfNotNull(
        method.getAnnotation(GET::class.java)?.let { annotation -> "GET" to annotation.value },
        method.getAnnotation(POST::class.java)?.let { annotation -> "POST" to annotation.value },
        method.getAnnotation(PUT::class.java)?.let { annotation -> "PUT" to annotation.value },
        method.getAnnotation(DELETE::class.java)?.let { annotation -> "DELETE" to annotation.value },
    )
    assertWithMessage("${method.name} must declare exactly one supported Retrofit HTTP annotation")
        .that(annotations)
        .hasSize(1)
    return annotations.single()
}

private fun normalizePlaceholderNames(path: String): String =
    path.replace(Regex("\\{[^/{}]+}"), "{parameter}")

private fun assertPublicGatewayPath(methodName: String, path: String, canonicalSource: String) {
    val normalized = path.removePrefix("/")
    assertWithMessage("$methodName must stay relative to the public gateway: $canonicalSource")
        .that(normalized.startsWith("api/") || normalized.startsWith("auth/"))
        .isTrue()
    listOf("://", "localhost", "127.0.0.1", "/internal/", "/private/", "\\", "\n", "\r")
        .forEach { forbidden ->
            assertWithMessage("$methodName must not expose '$forbidden': $canonicalSource")
                .that(path.lowercase())
                .doesNotContain(forbidden)
        }
}

private fun expectedManagerRoutes(): Map<String, ManagerContractRoute> {
    val auth = "contracts/openapi/auth-service.yaml"
    val warehouse = "contracts/openapi/warehouse-service.yaml"
    val inventory = "contracts/openapi/inventory-service.yaml"
    val asset = "contracts/openapi/asset-service.yaml"
    val logistics = "contracts/openapi/logistics-service.yaml"
    val maintenance = "contracts/openapi/maintenance-service.yaml"
    val taskBoard = "contracts/openapi/task-board-service.yaml"
    val media = "contracts/openapi/media-service.yaml"
    fun route(verb: String, gatewayPath: String, source: String): ManagerContractRoute =
        ManagerContractRoute(verb, gatewayPath, source)

    return linkedMapOf(
        "currentUser" to route("GET", "auth/api/users/me", "$auth /api/users/me"),
        "warehouses" to route("GET", "api/warehouse/v1/warehouses", "$warehouse /api/warehouse/v1/warehouses"),
        "activeInventory" to route("GET", "api/inventory/v1/sessions/active", "$inventory /api/inventory/v1/sessions/active"),
        "inventory" to route("GET", "api/inventory/v1/sessions/{inventoryId}", "$inventory /api/inventory/v1/sessions/{inventoryId}"),
        "inventoryFindings" to route("GET", "api/inventory/v1/sessions/{inventoryId}/findings", "$inventory /api/inventory/v1/sessions/{inventoryId}/findings"),
        "resolveInventoryNumber" to route("POST", "api/inventory/v1/sessions/{inventoryId}/number-resolutions", "$inventory /api/inventory/v1/sessions/{inventoryId}/number-resolutions"),
        "createInventoryAsset" to route("POST", "api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/assets", "$inventory /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/assets"),
        "saveInventoryInspection" to route("PUT", "api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/inspection", "$inventory /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/inspection"),
        "resolveInventoryConflict" to route("PUT", "api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/conflict-resolution", "$inventory /api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/conflict-resolution"),
        "rentalItems" to route("GET", "api/asset/v1/rental-items", "$asset /api/asset/v1/rental-items"),
        "rentalItem" to route("GET", "api/asset/v1/rental-items/{rentalItemId}", "$asset /api/asset/v1/rental-items/{id}"),
        "rentalItemCreationOptions" to route("GET", "api/asset/v1/rental-items/creation-options", "$asset /api/asset/v1/rental-items/creation-options"),
        "equipment" to route("GET", "api/asset/v1/equipment", "$asset /api/asset/v1/equipment"),
        "returns" to route("GET", "api/logistics/v1/returns", "$logistics /api/logistics/v1/returns"),
        "returnDocument" to route("GET", "api/logistics/v1/returns/{documentId}", "$logistics /api/logistics/v1/returns/{documentId}"),
        "acceptReturn" to route("POST", "api/logistics/v1/returns/{documentId}/accept-undamaged", "$logistics /api/logistics/v1/returns/{documentId}/accept-undamaged"),
        "startReturnEstimates" to route("POST", "api/logistics/v1/returns/{documentId}/start-estimates", "$logistics /api/logistics/v1/returns/{documentId}/start-estimates"),
        "shipments" to route("GET", "api/logistics/v1/shipments", "$logistics /api/logistics/v1/shipments"),
        "shipment" to route("GET", "api/logistics/v1/shipments/{documentId}", "$logistics /api/logistics/v1/shipments/{documentId}"),
        "createShipment" to route("POST", "api/logistics/v1/shipments", "$logistics /api/logistics/v1/shipments"),
        "replaceShipmentPlan" to route("PUT", "api/logistics/v1/shipments/{documentId}/plan", "$logistics /api/logistics/v1/shipments/{documentId}/plan"),
        "shipmentFurnitureReadiness" to route("GET", "api/logistics/v1/shipments/{documentId}/furniture-readiness", "$logistics /api/logistics/v1/shipments/{documentId}/furniture-readiness"),
        "createShipmentFurnitureTasks" to route("POST", "api/logistics/v1/shipments/{documentId}/furniture-tasks", "$logistics /api/logistics/v1/shipments/{documentId}/furniture-tasks"),
        "createCabinFurnitureTask" to route("POST", "api/logistics/v1/rental-items/{rentalItemId}/furniture-tasks", "$logistics /api/logistics/v1/rental-items/{rentalItemId}/furniture-tasks"),
        "confirmShipmentPreparation" to route("POST", "api/logistics/v1/shipments/{documentId}/confirm-preparation", "$logistics /api/logistics/v1/shipments/{documentId}/confirm-preparation"),
        "cancelShipment" to route("POST", "api/logistics/v1/shipments/{documentId}/cancel", "$logistics /api/logistics/v1/shipments/{documentId}/cancel"),
        "transfers" to route("GET", "api/logistics/v1/transfers", "$logistics /api/logistics/v1/transfers"),
        "transfer" to route("GET", "api/logistics/v1/transfers/{documentId}", "$logistics /api/logistics/v1/transfers/{documentId}"),
        "createTransfer" to route("POST", "api/logistics/v1/transfers", "$logistics /api/logistics/v1/transfers"),
        "transferFurnitureReadiness" to route("GET", "api/logistics/v1/transfers/{documentId}/furniture-readiness", "$logistics /api/logistics/v1/transfers/{documentId}/furniture-readiness"),
        "departTransferLine" to route("POST", "api/logistics/v1/transfers/{documentId}/lines/{lineId}/depart", "$logistics /api/logistics/v1/transfers/{documentId}/lines/{lineId}/depart"),
        "arriveTransferLine" to route("POST", "api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrive", "$logistics /api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrive"),
        "cancelTransfer" to route("POST", "api/logistics/v1/transfers/{documentId}/cancel", "$logistics /api/logistics/v1/transfers/{documentId}/cancel"),
        "reconcileTransfer" to route("POST", "api/logistics/v1/transfers/{documentId}/reconcile", "$logistics /api/logistics/v1/{documentType}/{documentId}/reconcile (documentType=transfers)"),
        "estimates" to route("GET", "api/maintenance/v1/estimates", "$maintenance /api/maintenance/v1/estimates"),
        "returnEstimateSources" to route("GET", "api/maintenance/v1/estimates/return-sources", "$maintenance /api/maintenance/v1/estimates/return-sources"),
        "createEstimate" to route("POST", "api/maintenance/v1/estimates", "$maintenance /api/maintenance/v1/estimates"),
        "estimate" to route("GET", "api/maintenance/v1/estimates/{estimateId}", "$maintenance /api/maintenance/v1/estimates/{id}"),
        "replaceEstimate" to route("PUT", "api/maintenance/v1/estimates/{estimateId}", "$maintenance /api/maintenance/v1/estimates/{id}"),
        "amendEstimate" to route("POST", "api/maintenance/v1/estimates/{estimateId}/amendments", "$maintenance /api/maintenance/v1/estimates/{id}/amendments"),
        "completeEstimate" to route("POST", "api/maintenance/v1/estimates/{estimateId}/complete", "$maintenance /api/maintenance/v1/estimates/{id}/complete"),
        "repairs" to route("GET", "api/maintenance/v1/repairs", "$maintenance /api/maintenance/v1/repairs"),
        "activeCapitalRepairs" to route("GET", "api/maintenance/v1/repairs/capital", "$maintenance /api/maintenance/v1/repairs/capital"),
        "acceptance" to route("GET", "api/maintenance/v1/acceptance", "$maintenance /api/maintenance/v1/acceptance"),
        "createDirectRepair" to route("POST", "api/maintenance/v1/repairs/direct", "$maintenance /api/maintenance/v1/repairs/direct"),
        "repair" to route("GET", "api/maintenance/v1/repairs/{repairId}", "$maintenance /api/maintenance/v1/repairs/{id}"),
        "replaceRepairPlan" to route("PUT", "api/maintenance/v1/repairs/{repairId}/plan", "$maintenance /api/maintenance/v1/repairs/{id}/plan"),
        "queueRepairPlan" to route("POST", "api/maintenance/v1/repairs/{repairId}/plan", "$maintenance /api/maintenance/v1/repairs/{id}/plan"),
        "createRework" to route("POST", "api/maintenance/v1/repairs/{repairId}/reworks", "$maintenance /api/maintenance/v1/repairs/{id}/reworks"),
        "reworkCandidates" to route("GET", "api/maintenance/v1/repairs/{repairId}/rework-candidates", "$maintenance /api/maintenance/v1/repairs/{id}/rework-candidates"),
        "acceptRepair" to route("POST", "api/maintenance/v1/repairs/{repairId}/accept", "$maintenance /api/maintenance/v1/repairs/{id}/accept"),
        "taskBoard" to route("GET", "api/task-board/warehouses/{warehouseId}/task-board", "$taskBoard /warehouses/{warehouseId}/task-board"),
        "moveTaskBoardEntry" to route("POST", "api/task-board/warehouses/{warehouseId}/task-board/entries/{entryId}/move", "$taskBoard /warehouses/{warehouseId}/task-board/entries/{entryId}/move"),
        "swapTaskBoardDates" to route("POST", "api/task-board/warehouses/{warehouseId}/task-board/dates/swap", "$taskBoard /warehouses/{warehouseId}/task-board/dates/swap"),
        "catalogVersions" to route("GET", "api/maintenance/v1/catalog/versions", "$maintenance /api/maintenance/v1/catalog/versions"),
        "catalogNodes" to route("GET", "api/maintenance/v1/catalog/versions/{catalogVersionId}/nodes", "$maintenance /api/maintenance/v1/catalog/versions/{id}/nodes"),
        "catalogLinks" to route("GET", "api/maintenance/v1/catalog/versions/{catalogVersionId}/links", "$maintenance /api/maintenance/v1/catalog/versions/{id}/links"),
        "createUploadSession" to route("POST", "api/media/v1/upload-sessions", "$media /api/media/v1/upload-sessions"),
        "uploadContent" to route("PUT", "", "$media /api/media/v1/upload-sessions/{uploadSessionId}/content via guarded @Url"),
        "finalizeUpload" to route("POST", "api/media/v1/upload-sessions/{uploadSessionId}/complete", "$media /api/media/v1/upload-sessions/{uploadSessionId}/complete"),
        "ownerMedia" to route("GET", "api/media/v1/assets", "$media /api/media/v1/assets"),
        "originalMedia" to route("GET", "api/media/v1/assets/{mediaId}/original", "$media /api/media/v1/assets/{mediaId}/original"),
        "mediaVariantContent" to route("GET", "", "$media /api/media/v1/assets/{mediaId}/variants/{variant}/content via guarded @Url"),
    )
}
