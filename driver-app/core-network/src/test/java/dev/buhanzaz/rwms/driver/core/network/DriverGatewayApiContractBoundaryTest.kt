package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * Pins every driver Retrofit operation to the public operations in
 * `contracts/openapi/task-board-service.yaml`, `contracts/openapi/logistics-service.yaml`, and
 * `contracts/openapi/media-service.yaml`.
 * All active JSON request/response roots have current canonical fixtures. Binary media bodies,
 * `ResponseBody`, and the `Unit` unregister response are converter-checked but intentionally are
 * not represented as JSON fixtures.
 */
@OptIn(ExperimentalSerializationApi::class)
class DriverGatewayApiContractBoundaryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `all declared routes remain exact public gateway operations`() {
        val expected = expectedDriverRoutes()
        val methods = DriverGatewayApi::class.java.declaredMethods
            .filterNot(Method::isSynthetic)
            .associateBy(Method::getName)

        assertThat(expected).hasSize(24)
        assertWithMessage(
            "DriverGatewayApi method inventory must stay synchronized with canonical public OpenAPI",
        ).that(methods.keys)
            .containsExactlyElementsIn(expected.keys)

        expected.forEach { (methodName, contractRoute) ->
            val method = checkNotNull(methods[methodName])
            val (actualVerb, actualPath) = driverHttpRoute(method)
            assertWithMessage("$methodName verb must match ${contractRoute.canonicalSource}")
                .that(actualVerb)
                .isEqualTo(contractRoute.verb)
            assertWithMessage(
                "$methodName gateway path must match ${contractRoute.canonicalSource}; " +
                    "only placeholder names are normalized",
            ).that(normalizeDriverPlaceholderNames(actualPath))
                .isEqualTo(normalizeDriverPlaceholderNames(contractRoute.gatewayPath))

            val hasDynamicUrl = method.parameterAnnotations
                .any { annotations -> annotations.any { annotation -> annotation is Url } }
            assertWithMessage("$methodName dynamic URL policy comes from ${contractRoute.canonicalSource}")
                .that(hasDynamicUrl)
                .isEqualTo(contractRoute.gatewayPath.isEmpty())

            if (actualPath.isNotEmpty()) {
                assertDriverPublicGatewayPath(methodName, actualPath, contractRoute.canonicalSource)
            }
        }

        val dynamicMethods = expected
            .filterValues { route -> route.gatewayPath.isEmpty() }
            .keys
        assertThat(dynamicMethods).containsExactly("uploadMediaContent", "mediaContent")
    }

    @Test
    fun `production kotlinx serializer resolves every Retrofit shape eagerly`() {
        val api = Retrofit.Builder()
            .baseUrl("https://gateway.example.test/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .validateEagerly(true)
            .build()
            .create(DriverGatewayApi::class.java)

        assertThat(api).isNotNull()
    }

    @Test
    fun `context and feed fixtures decode current task board projections`() {
        val context = json.decodeFromString<DriverContextDto>(
            """
            {
              "worker":{
                "id":"11111111-1111-1111-1111-111111111111",
                "warehouseId":"22222222-2222-2222-2222-222222222222",
                "login":"driver-1",
                "displayName":"Driver One"
              },
              "currentGroup":null,
              "operationalAvailability":"AVAILABLE",
              "groups":[],
              "qualifications":[],
              "categories":[],
              "kpiPalette":null,
              "serverTime":"2026-08-09T08:00:00Z",
              "revision":7,
              "offlineLease":{
                "id":"33333333-3333-3333-3333-333333333333",
                "issuedAt":"2026-08-09T08:00:00Z",
                "expiresAt":"2026-08-10T08:00:00Z",
                "syncRevision":7
              }
            }
            """.trimIndent(),
        )
        val feed = json.decodeFromString<DriverFeedDto>(
            """
            {
              "revision":8,
              "serverTime":"2026-08-09T08:01:00Z",
              "categories":[],
              "nextCursor":null
            }
            """.trimIndent(),
        )

        assertThat(context.driver.warehouseId).isEqualTo("22222222-2222-2222-2222-222222222222")
        assertThat(context.operationalAvailability).isEqualTo("AVAILABLE")
        assertThat(feed.revision).isEqualTo(8)
        assertThat(feed.nextCursor).isNull()
    }

    @Test
    fun `detail action and evidence fixtures preserve exact task transition fields`() {
        val detailFixture = canonicalDriverTaskDetailFixture()
        val detail = json.decodeFromString<DriverTaskDetailDto>(detailFixture)
        val actionResult = json.decodeFromString<DriverActionResultDto>(
            """
            {
              "outcome":"APPLIED",
              "currentVersion":9,
              "entry":$detailFixture
            }
            """.trimIndent(),
        )
        val evidence = json.decodeFromString<TaskEvidenceDto>(
            """
            {
              "evidenceId":"66666666-6666-6666-6666-666666666666",
              "version":1,
              "entryId":"44444444-4444-4444-4444-444444444444",
              "routeIndex":0,
              "workerId":"11111111-1111-1111-1111-111111111111",
              "workerGroupId":null,
              "capturedAt":"2026-08-09T08:02:00Z",
              "recordedAt":"2026-08-09T08:02:01Z",
              "state":"RESERVED",
              "mediaId":null,
              "mediaGeneration":null,
              "reviewReason":null,
              "contentType":"image/jpeg",
              "readPath":null,
              "thumbnailPath":null
            }
            """.trimIndent(),
        )
        val actionRequest = DriverActionRequestDto(
            operationId = "77777777-7777-7777-7777-777777777777",
            action = "PAUSE",
            expectedVersion = 9,
            driverGroupId = null,
            evidenceId = null,
            occurredAt = "2026-08-09T08:03:00Z",
            offlineLeaseId = "33333333-3333-3333-3333-333333333333",
        )
        val evidenceRequest = EvidenceReservationRequestDto(
            operationId = "88888888-8888-8888-8888-888888888888",
            evidenceId = evidence.evidenceId,
            routeIndex = 0,
            capturedAt = evidence.capturedAt,
            offlineLeaseId = "33333333-3333-3333-3333-333333333333",
            sizeBytes = 128,
            sha256 = "a".repeat(64),
        )
        val actionJson = json.encodeToString(actionRequest)
        val evidenceJson = json.encodeToString(evidenceRequest)

        assertThat(detail.entryId).isEqualTo("44444444-4444-4444-4444-444444444444")
        assertThat(actionResult.entry).isEqualTo(detail)
        assertThat(evidence.state).isEqualTo("RESERVED")
        assertThat(json.parseToJsonElement(actionJson).jsonObject.keys).containsExactly(
            "operationId",
            "action",
            "expectedVersion",
            "workerGroupId",
            "evidenceId",
            "occurredAt",
            "offlineLeaseId",
        )
        assertThat(actionJson).contains("\"workerGroupId\":null")
        assertThat(actionJson).contains("\"evidenceId\":null")
        assertThat(evidenceJson).contains("\"contentType\":\"image/jpeg\"")
        assertThat(evidenceJson).contains("\"sha256\":\"${"a".repeat(64)}\"")
    }

    @Test
    fun `device fixtures encode and decode the public registration shape`() {
        val registration = json.decodeFromString<DriverDeviceRegistrationDto>(
            """
            {
              "installationId":"installation-1",
              "provider":"FCM",
              "status":"ACTIVE",
              "registeredAt":"2026-08-09T08:00:00Z",
              "updatedAt":"2026-08-09T08:05:00Z"
            }
            """.trimIndent(),
        )
        val request = DriverDeviceRegistrationRequestDto(
            token = "firebase-installation-id",
            appVersion = "0.3.0",
            sdkInt = 36,
            locale = "ru-RU",
        )
        val encoded = json.encodeToString(request)

        assertThat(registration.status).isEqualTo("ACTIVE")
        assertThat(encoded).contains("\"provider\":\"FCM\"")
        assertThat(encoded).contains("\"targetKind\":\"FID\"")
        assertThat(encoded).contains("\"token\":\"firebase-installation-id\"")
        assertThat(encoded).contains("\"sdkInt\":36")
    }

    @Test
    fun `media fixtures expose only opaque metadata and public same-origin paths`() {
        val session = json.decodeFromString<UploadSessionDto>(
            """
            {
              "uploadSessionId":"99999999-9999-9999-9999-999999999999",
              "mediaId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "expiresAt":"2026-08-09T09:00:00Z",
              "contentUploadUrl":"/api/media/v1/upload-sessions/99999999-9999-9999-9999-999999999999/content"
            }
            """.trimIndent(),
        )
        val uploaded = json.decodeFromString<UploadedObjectDto>(
            """
            {
              "objectVersionId":"opaque-object-version",
              "etag":"opaque-etag",
              "checksumSha256":"${"b".repeat(64)}"
            }
            """.trimIndent(),
        )
        val asset = json.decodeFromString<MediaAssetDto>(
            """
            {
              "id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "folderId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "clientReferenceId":"66666666-6666-6666-6666-666666666666",
              "fileName":"evidence.jpg",
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
        val createRequest = CreateUploadSessionRequestDto(
            ownerId = "44444444-4444-4444-4444-444444444444",
            warehouseId = "22222222-2222-2222-2222-222222222222",
            clientReferenceId = "66666666-6666-6666-6666-666666666666",
            fileName = "evidence.jpg",
            contentLength = 128,
            checksumSha256 = "b".repeat(64),
        )
        val finalizeRequest = FinalizeUploadRequestDto(
            objectVersionId = uploaded.objectVersionId,
            etag = uploaded.etag,
            checksumSha256 = uploaded.checksumSha256,
        )
        val createJson = json.encodeToString(createRequest)
        val finalizeJson = json.encodeToString(finalizeRequest)

        assertThat(session.contentUploadUrl).startsWith("/api/media/v1/upload-sessions/")
        assertThat(asset.variants.single().contentPath).startsWith("/api/media/v1/assets/")
        assertThat(createJson).contains("\"ownerType\":\"TASK_BOARD_ENTRY\"")
        assertThat(createJson).contains("\"context\":\"WORK_RESULT\"")
        assertThat(finalizeJson).contains("\"objectVersionId\":\"opaque-object-version\"")
    }

    @Test
    fun `dynamic media client calls reject private and foreign paths before Retrofit`() = runTest {
        val uploadPath =
            "/api/media/v1/upload-sessions/99999999-9999-9999-9999-999999999999/content"
        val originalPath =
            "/api/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/original?generation=1"
        assertThat(requireSameOriginApiPath(uploadPath)).isEqualTo(uploadPath)
        assertThat(requireSameOriginMediaReadPath(originalPath)).isEqualTo(originalPath)

        val calls = mutableListOf<String>()
        val api = proxyDriverApi { method ->
            calls += method.name
            error("Unsafe dynamic URL must be rejected before ${method.name}")
        }
        val client = DriverGatewayClient(api, json)
        val uploadFailure = runCatching {
            client.uploadMediaContent(
                sameOriginContentPath = "https://media-service:8080/private/object",
                idempotencyKey = "77777777-7777-7777-7777-777777777777",
                content = byteArrayOf(1).toRequestBody("image/jpeg".toMediaType()),
            )
        }.exceptionOrNull()
        val readFailure = runCatching {
            client.mediaContent(
                "/api/internal/media/v1/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/original",
            )
        }.exceptionOrNull()

        assertThat(uploadFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(readFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(calls).isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxyDriverApi(result: (Method) -> Any?): DriverGatewayApi =
        Proxy.newProxyInstance(
            DriverGatewayApi::class.java.classLoader,
            arrayOf(DriverGatewayApi::class.java),
        ) { _, method, _ -> result(method) } as DriverGatewayApi
}

/** One driver gateway operation and the canonical OpenAPI location that authorizes it. */
private data class DriverContractRoute(
    val verb: String,
    val gatewayPath: String,
    val canonicalSource: String,
)

private fun driverHttpRoute(method: Method): Pair<String, String> {
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

private fun normalizeDriverPlaceholderNames(path: String): String =
    path.replace(Regex("\\{[^/{}]+}"), "{parameter}")

private fun assertDriverPublicGatewayPath(
    methodName: String,
    path: String,
    canonicalSource: String,
) {
    assertWithMessage("$methodName must stay relative to the public gateway: $canonicalSource")
        .that(path.startsWith("/api/"))
        .isTrue()
    listOf("://", "localhost", "127.0.0.1", "/internal/", "/private/", "\\", "\n", "\r")
        .forEach { forbidden ->
            assertWithMessage("$methodName must not expose '$forbidden': $canonicalSource")
                .that(path.lowercase())
                .doesNotContain(forbidden)
        }
}

private fun expectedDriverRoutes(): Map<String, DriverContractRoute> {
    val taskBoard = "contracts/openapi/task-board-service.yaml"
    val logistics = "contracts/openapi/logistics-service.yaml"
    val media = "contracts/openapi/media-service.yaml"
    fun route(verb: String, gatewayPath: String, source: String): DriverContractRoute =
        DriverContractRoute(verb, gatewayPath, source)

    return linkedMapOf(
        "todayDriverShift" to route("GET", "/api/task-board/driver/v1/shift/today", "$taskBoard /driver/v1/shift/today"),
        "markShiftBriefingSeen" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/briefing/seen", "$taskBoard /driver/v1/shifts/{shiftId}/briefing/seen"),
        "confirmShiftMedicalCheck" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/medical-check", "$taskBoard /driver/v1/shifts/{shiftId}/medical-check"),
        "updateShiftInspectionItem" to route("PUT", "/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/items/{itemId}", "$taskBoard /driver/v1/shifts/{shiftId}/vehicle-inspection/items/{itemId}"),
        "completeShiftInspection" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/complete", "$taskBoard /driver/v1/shifts/{shiftId}/vehicle-inspection/complete"),
        "startShift" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/start", "$taskBoard /driver/v1/shifts/{shiftId}/start"),
        "startShiftClosing" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/closing/start", "$taskBoard /driver/v1/shifts/{shiftId}/closing/start"),
        "confirmShiftWarehouseReturn" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/return-to-warehouse", "$taskBoard /driver/v1/shifts/{shiftId}/return-to-warehouse"),
        "submitShiftClosingReport" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/closing-report", "$taskBoard /driver/v1/shifts/{shiftId}/closing-report"),
        "reserveShiftPhoto" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/photos/reservations", "$taskBoard /driver/v1/shifts/{shiftId}/photos/reservations"),
        "closeShift" to route("POST", "/api/task-board/driver/v1/shifts/{shiftId}/close", "$taskBoard /driver/v1/shifts/{shiftId}/close"),
        "driverContext" to route("GET", "/api/task-board/driver/v1/context", "$taskBoard /driver/v1/context"),
        "driverFeed" to route("GET", "/api/task-board/driver/v1/feed", "$taskBoard /driver/v1/feed"),
        "driverTaskDetail" to route("GET", "/api/task-board/driver/v1/entries/{entryId}", "$taskBoard /driver/v1/entries/{entryId}"),
        "logisticsDriverTask" to route("GET", "/api/logistics/v1/driver-tasks/{taskId}", "$logistics getDriverTask"),
        "claimFutureLogisticsTask" to route("POST", "/api/logistics/v1/driver-tasks/{taskId}/claim", "$logistics claimFutureDriverTask"),
        "applyAction" to route("POST", "/api/task-board/driver/v1/entries/{entryId}/actions", "$taskBoard /driver/v1/entries/{entryId}/actions"),
        "reserveEvidence" to route("POST", "/api/task-board/driver/v1/entries/{entryId}/evidence-reservations", "$taskBoard /driver/v1/entries/{entryId}/evidence-reservations"),
        "registerDevice" to route("PUT", "/api/task-board/driver/v1/devices/{installationId}", "$taskBoard /driver/v1/devices/{installationId}"),
        "unregisterDevice" to route("DELETE", "/api/task-board/driver/v1/devices/{installationId}", "$taskBoard /driver/v1/devices/{installationId}"),
        "createUploadSession" to route("POST", "/api/media/v1/upload-sessions", "$media /api/media/v1/upload-sessions"),
        "uploadMediaContent" to route("PUT", "", "$media /api/media/v1/upload-sessions/{uploadSessionId}/content via guarded @Url"),
        "finalizeUploadSession" to route("POST", "/api/media/v1/upload-sessions/{uploadSessionId}/complete", "$media /api/media/v1/upload-sessions/{uploadSessionId}/complete"),
        "mediaContent" to route("GET", "", "$media /api/media/v1/assets/{mediaId}/(original|variants/{variant}/content) via guarded @Url"),
    )
}

private fun canonicalDriverTaskDetailFixture(): String =
    """
    {
      "entryId":"44444444-4444-4444-4444-444444444444",
      "version":9,
      "taskId":"55555555-5555-5555-5555-555555555555",
      "source":null,
      "routeIndex":0,
      "title":"Inspect cabin",
      "description":null,
      "object":null,
      "taskText":null,
      "scheduledDate":"2026-08-09",
      "deadlineAt":null,
      "priority":3,
      "queuePosition":0,
      "status":"WAITING",
      "availabilityMode":"AVAILABLE",
      "plannedDurationMinutes":null,
      "activeStartedAt":null,
      "activeWorkSeconds":0,
      "timerSnapshot":null,
      "audienceSelectors":[],
      "assignments":[],
      "works":[],
      "materials":[],
      "comments":[],
      "sourceMedia":[],
      "evidence":[],
      "relatedSteps":[],
      "resultPhotoMinCount":0,
      "completionAllowed":true
    }
    """.trimIndent()
