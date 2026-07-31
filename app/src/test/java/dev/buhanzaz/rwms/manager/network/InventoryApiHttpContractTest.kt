package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InventoryApiHttpContractTest {
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
    fun `inventory reads parse canonical information and use public API paths`() = runTest {
        val warehouseId = "11111111-1111-1111-1111-111111111111"
        val inventoryId = "22222222-2222-2222-2222-222222222222"
        val findingId = "33333333-3333-3333-3333-333333333333"
        val assetId = "44444444-4444-4444-4444-444444444444"
        val equipmentId = "55555555-5555-5555-5555-555555555555"
        val mediaId = "66666666-6666-6666-6666-666666666666"
        val session = sessionJson(inventoryId, warehouseId)
        val finding = findingJson(
            inventoryId = inventoryId,
            findingId = findingId,
            assetId = assetId,
            equipmentId = equipmentId,
            mediaId = mediaId,
        )
        server.enqueue(json(session))
        server.enqueue(json(session))
        server.enqueue(
            json(
                """{"content":[$finding],"page":{"page":0,"size":200,"totalElements":1,"totalPages":1}}""",
            ),
        )
        server.enqueue(
            json(
                """[{"equipment":{"id":"$equipmentId","version":7,"name":"Стул","category":"Мебель","active":true}}]""",
            ),
        )
        server.enqueue(
            json(
                """{"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}""",
            ),
        )

        val active = api.activeInventory(
            warehouseId = warehouseId,
            ifNoneMatch = "W/\"inventory-revision-9\"",
        )
        val loaded = api.inventory(inventoryId)
        val findings = api.inventoryFindings(inventoryId, page = 0)
        val equipment = api.equipment(warehouseId)
        val assets = api.rentalItems(warehouseId)

        assertThat(active.isSuccessful).isTrue()
        assertThat(active.body()?.id).isEqualTo(inventoryId)
        assertThat(loaded.sessionRevision).isEqualTo(9)
        assertThat(findings.content.single().id).isEqualTo(findingId)
        assertThat(findings.content.single().media.single())
            .isEqualTo(MediaReferenceDto(mediaId, 2))
        assertThat(
            (findings.content.single().equipmentObservation.value as List<*>)
                .single(),
        ).isInstanceOf(Map::class.java)
        assertThat(equipment.single().equipment.name).isEqualTo("Стул")
        assertThat(assets.content).isEmpty()

        val requests = List(5) { takeRequest() }
        assertThat(requests.map { it.method })
            .containsExactly("GET", "GET", "GET", "GET", "GET")
            .inOrder()
        assertThat(requests.map { it.path }).containsExactly(
            "/api/inventory/v1/sessions/active?warehouseId=$warehouseId",
            "/api/inventory/v1/sessions/$inventoryId",
            "/api/inventory/v1/sessions/$inventoryId/findings?page=0&size=200&sort=createdAt%2Casc",
            "/api/asset/v1/equipment?warehouseId=$warehouseId",
            "/api/asset/v1/rental-items?warehouseId=$warehouseId&page=0&size=50",
        ).inOrder()
        assertThat(requests.first().getHeader("If-None-Match"))
            .isEqualTo("W/\"inventory-revision-9\"")
    }

    @Test
    fun `active inventory preserves a not modified response for the local snapshot`() = runTest {
        val warehouseId = "11111111-1111-1111-1111-111111111111"
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "W/\"inventory-revision-9\""),
        )

        val response = api.activeInventory(
            warehouseId = warehouseId,
            ifNoneMatch = "W/\"inventory-revision-9\"",
        )

        assertThat(response.code()).isEqualTo(304)
        assertThat(response.headers()["ETag"]).isEqualTo("W/\"inventory-revision-9\"")
        val request = takeRequest()
        assertThat(request.path)
            .isEqualTo("/api/inventory/v1/sessions/active?warehouseId=$warehouseId")
        assertThat(request.getHeader("If-None-Match")).isEqualTo("W/\"inventory-revision-9\"")
    }

    @Test
    fun `start and inspection send exact CAS media and furniture snapshots`() = runTest {
        val warehouseId = "11111111-1111-1111-1111-111111111111"
        val inventoryId = "22222222-2222-2222-2222-222222222222"
        val findingId = "33333333-3333-3333-3333-333333333333"
        val assetId = "44444444-4444-4444-4444-444444444444"
        val equipmentId = "55555555-5555-5555-5555-555555555555"
        val mediaId = "66666666-6666-6666-6666-666666666666"
        server.enqueue(json(sessionJson(inventoryId, warehouseId), status = 201))
        server.enqueue(
            json(
                findingJson(
                    inventoryId = inventoryId,
                    findingId = findingId,
                    assetId = assetId,
                    equipmentId = equipmentId,
                    mediaId = mediaId,
                ),
            ),
        )

        val started = api.startInventory(
            idempotencyKey = "inventory-start-contract",
            request = StartInventoryRequest(warehouseId),
        )
        val saved = api.saveInventoryInspection(
            inventoryId = inventoryId,
            findingId = findingId,
            request = SaveInspectionRequest(
                expectedSessionRevision = 9,
                expectedFindingRevision = 3,
                inspection = "READY",
                comment = "Мебель пересчитана",
                passportObservation = ObservationInput("ABSENT", null),
                equipmentObservation = ObservationInput(
                    "PRESENT",
                    listOf(
                        linkedMapOf(
                            "equipmentId" to equipmentId,
                            "equipmentName" to "Стул",
                            "equipmentCategory" to "Мебель",
                            "catalogVersion" to 7L,
                            "quantity" to 4L,
                        ),
                    ),
                ),
                media = listOf(MediaReferenceDto(mediaId, 2)),
                planSelection = null,
            ),
        )

        assertThat(started.id).isEqualTo(inventoryId)
        assertThat(saved.findingRevision).isEqualTo(3)
        val start = takeRequest()
        assertThat(start.method).isEqualTo("POST")
        assertThat(start.path).isEqualTo("/api/inventory/v1/sessions")
        assertThat(start.getHeader("Idempotency-Key"))
            .isEqualTo("inventory-start-contract")
        assertThat(start.body.readUtf8())
            .isEqualTo("""{"warehouseId":"$warehouseId"}""")

        val inspection = takeRequest()
        assertThat(inspection.method).isEqualTo("PUT")
        assertThat(inspection.path)
            .isEqualTo(
                "/api/inventory/v1/sessions/$inventoryId/findings/$findingId/inspection",
            )
        assertThat(inspection.body.readUtf8()).isEqualTo(
            """{"expectedSessionRevision":9,"expectedFindingRevision":3,"inspection":"READY","comment":"Мебель пересчитана","passportObservation":{"presence":"ABSENT","value":null},"equipmentObservation":{"presence":"PRESENT","value":[{"equipmentId":"$equipmentId","equipmentName":"Стул","equipmentCategory":"Мебель","catalogVersion":7,"quantity":4}]},"media":[{"mediaId":"$mediaId","generation":2}],"coverMediaId":null,"planSelection":null}""",
        )
    }

    @Test
    fun `inventory photo list and original bytes load through scoped media API`() = runTest {
        val warehouseId = "11111111-1111-1111-1111-111111111111"
        val findingId = "33333333-3333-3333-3333-333333333333"
        val mediaId = "66666666-6666-6666-6666-666666666666"
        val photo = byteArrayOf(
            0xff.toByte(),
            0xd8.toByte(),
            0xff.toByte(),
            0xe0.toByte(),
            0x00,
            0x10,
            0xff.toByte(),
            0xd9.toByte(),
        )
        server.enqueue(
            json(
                """{"items":[{"id":"$mediaId","folderId":"77777777-7777-7777-7777-777777777777","clientReferenceId":null,"fileName":"inspection.jpg","contentType":"image/jpeg","kind":"IMAGE","status":"READY","version":4,"generation":2,"rotationDegrees":0,"sortOrder":0,"sizeBytes":8,"createdAt":"2026-07-28T00:00:00Z","variants":[{"kind":"THUMBNAIL","contentType":"image/jpeg","contentPath":"/api/media/v1/assets/$mediaId/variants/thumbnail","width":320,"height":180}]}],"next":null}""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(photo)),
        )

        val media = api.ownerMedia(
            ownerType = "INVENTORY_FINDING",
            ownerId = findingId,
            warehouseId = warehouseId,
            context = "INSPECTION",
        )
        val original = api.originalMedia(
            mediaId = mediaId,
            generation = 2,
            ownerType = "INVENTORY_FINDING",
            ownerId = findingId,
            warehouseId = warehouseId,
            context = "INSPECTION",
        )

        assertThat(media.items.single().status).isEqualTo("READY")
        assertThat(media.items.single().variants.single().contentPath)
            .isEqualTo("/api/media/v1/assets/$mediaId/variants/thumbnail")
        assertThat(original.bytes().toList())
            .containsExactlyElementsIn(photo.toList())
            .inOrder()
        assertThat(takeRequest().path).isEqualTo(
            "/api/media/v1/assets?ownerType=INVENTORY_FINDING" +
                "&ownerId=$findingId&warehouseId=$warehouseId" +
                "&context=INSPECTION&limit=100",
        )
        assertThat(takeRequest().path).isEqualTo(
            "/api/media/v1/assets/$mediaId/original?generation=2" +
                "&ownerType=INVENTORY_FINDING&ownerId=$findingId" +
                "&warehouseId=$warehouseId&context=INSPECTION",
        )
    }

    private fun takeRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun json(body: String, status: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun sessionJson(inventoryId: String, warehouseId: String): String =
        """{"id":"$inventoryId","sessionRevision":9,"warehouseId":"$warehouseId","warehouseVersion":4,"warehouseTimeZone":"Europe/Moscow","businessDate":"2026-07-28","lifecycle":"ACTIVE","expectedCount":97,"findingCount":1,"inspectedCount":1,"startedAt":"2026-07-28T00:00:00Z","terminalAt":null,"publicationState":"NOT_REQUESTED"}"""

    private fun findingJson(
        inventoryId: String,
        findingId: String,
        assetId: String,
        equipmentId: String,
        mediaId: String,
    ): String =
        """{"id":"$findingId","inventoryId":"$inventoryId","findingRevision":3,"origin":"REGISTRY","inspection":"READY","reconciliation":"NONE","assetId":"$assetId","assetVersion":8,"displayCanonicalNumber":"БЫТ-001","identityMatchKey":"быт-001","passportObservation":{"presence":"ABSENT","value":null},"equipmentObservation":{"presence":"PRESENT","value":[{"equipmentId":"$equipmentId","equipmentName":"Стул","equipmentCategory":"Мебель","catalogVersion":7,"quantity":4}]},"mutationState":"IDLE","planFingerprintSha256":null,"comment":"Мебель пересчитана","expectedSnapshot":null,"inspectionBaseline":null,"currentSnapshot":null,"conflicts":[],"conflictResolution":null,"frozenPlan":null,"media":[{"mediaId":"$mediaId","generation":2}],"publication":null}"""
}
