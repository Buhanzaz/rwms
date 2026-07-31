package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class MaintenanceCatalogHttpParsingTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RwmsApi
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        moshi = Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RwmsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `active service catalog response parses with categories nodes and links`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "items":[{
                    "id":"11111111-1111-1111-1111-111111111111",
                    "warehouseId":"22222222-2222-2222-2222-222222222222",
                    "version":17,
                    "lifecycle":"ACTIVE",
                    "sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "counts":{"nodes":232,"links":255},
                    "validation":{
                      "valid":true,
                      "errorCount":0,
                      "warningCount":0,
                      "reportSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    },
                    "createdAt":"2026-07-19T18:18:25.770277Z",
                    "activatedAt":"2026-07-19T18:18:27.277386Z",
                    "routingSync":null
                  }],
                  "page":0,
                  "size":200,
                  "totalElements":1
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "id":"33333333-3333-3333-3333-333333333333",
                    "catalogVersionId":"11111111-1111-1111-1111-111111111111",
                    "nodeType":"CATEGORY",
                    "name":"Внешняя отделка",
                    "active":true,
                    "parentNodeId":null,
                    "furnitureCategory":false,
                    "furnitureEquipment":null,
                    "unit":null,
                    "unitPrice":null,
                    "durationMinutes":0,
                    "includeInEstimate":false,
                    "commonItem":false,
                    "showInMainMenu":false,
                    "canvasX":100,
                    "canvasY":100,
                    "routing":null,
                    "comment":null
                  },
                  {
                    "id":"44444444-4444-4444-4444-444444444444",
                    "catalogVersionId":"11111111-1111-1111-1111-111111111111",
                    "nodeType":"WORK",
                    "name":"Ремонт внешней отделки",
                    "active":true,
                    "parentNodeId":"33333333-3333-3333-3333-333333333333",
                    "furnitureCategory":false,
                    "furnitureEquipment":null,
                    "unit":"м²",
                    "unitPrice":"500.00",
                    "durationMinutes":30,
                    "includeInEstimate":true,
                    "commonItem":false,
                    "showInMainMenu":false,
                    "canvasX":200,
                    "canvasY":200,
                    "displayColor":"#0A5A8B",
                    "routing":{
                      "queueId":"55555555-5555-5555-5555-555555555555",
                      "queueName":"Внешняя отделка",
                      "queueType":"REPAIR"
                    },
                    "comment":null
                  }
                ]
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                [{
                  "id":"66666666-6666-6666-6666-666666666666",
                  "catalogVersionId":"11111111-1111-1111-1111-111111111111",
                  "fromNodeId":"33333333-3333-3333-3333-333333333333",
                  "toNodeId":"44444444-4444-4444-4444-444444444444",
                  "linkType":"FOLLOW_UP",
                  "sourceAnchor":null,
                  "targetAnchor":null,
                  "sortOrder":0
                }]
                """.trimIndent(),
            ),
        )

        val versions = requireNotNull(api.catalogVersions("warehouse-1").body())
        val nodes = api.catalogNodes(versions.items.single().id, "warehouse-1")
        val links = api.catalogLinks(versions.items.single().id, "warehouse-1")

        assertThat(versions.items.single().version).isEqualTo(17)
        assertThat(nodes.map(CatalogNodeDto::name))
            .containsExactly("Внешняя отделка", "Ремонт внешней отделки")
            .inOrder()
        assertThat(nodes.last().routing?.queueName).isEqualTo("Внешняя отделка")
        assertThat(nodes.last().displayColor).isEqualTo("#0A5A8B")
        assertThat(links.single().toNodeId).isEqualTo(nodes.last().id)
    }

    @Test
    fun `older responses parse when newer maintenance fields are omitted`() {
        val catalogNode = requireNotNull(
            moshi.adapter(CatalogNodeDto::class.java).fromJson(
                """
                {
                  "id":"node-1",
                  "catalogVersionId":"catalog-1",
                  "nodeType":"WORK",
                  "name":"Работа",
                  "active":true
                }
                """.trimIndent(),
            ),
        )
        val repair = requireNotNull(
            moshi.adapter(RepairDto::class.java).fromJson(
                """
                {
                  "id":"repair-1",
                  "rootRepairId":"repair-1",
                  "warehouseId":"warehouse-1",
                  "rentalItemId":"asset-1",
                  "origin":"DIRECT_REPAIR",
                  "kind":"PRIMARY",
                  "executionState":"DRAFT",
                  "acceptanceState":"NOT_REQUIRED",
                  "version":1,
                  "dispatchDate":"2026-07-27",
                  "plan":{
                    "repairId":"repair-1",
                    "repairVersion":1,
                    "stages":[{
                      "id":"stage-1",
                      "kind":"REPAIR_WORK",
                      "order":0,
                      "state":"PLANNED",
                      "routing":{
                        "queueId":"queue-1",
                        "queueName":"Ремонт",
                        "queueType":"REPAIR"
                      },
                      "workLines":[{
                        "id":"line-1",
                        "description":"Работа",
                        "quantity":"1",
                        "unitPrice":"100.00",
                        "lineTotal":"100.00"
                      }]
                    }]
                  },
                  "createdAt":"2026-07-27T09:00:00Z",
                  "updatedAt":"2026-07-27T09:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        assertThat(catalogNode.includeInEstimate).isTrue()
        assertThat(catalogNode.furnitureCategory).isFalse()
        assertThat(repair.priority).isEqualTo(3)
        assertThat(repair.plan.stages.single().groupComment).isEmpty()
        assertThat(repair.plan.stages.single().workLines.single().lineType).isEqualTo("WORK")
        assertThat(repair.plan.stages.single().workLines.single().normativeMinutes).isEqualTo(0)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
