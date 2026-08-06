package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class InventoryCatalogLineSerializationTest {
    private lateinit var server: MockWebServer
    private lateinit var moshi: Moshi
    private lateinit var api: RwmsApi

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
    fun `restored catalog inventory plan sends a null routing catalog node`() = runTest {
        val catalogNodeId = "11111111-1111-1111-1111-111111111111"
        val restoredPlan = requireNotNull(
            moshi.adapter(InventoryPlanSelectionDto::class.java).fromJson(
                """
                {
                  "mode":"MANUAL",
                  "priority":3,
                  "movementToRepair":true,
                  "logisticsPlanningMode":"AUTO",
                  "lines":[{
                    "aggregationKind":"CATALOG",
                    "catalogNodeId":"$catalogNodeId",
                    "description":null,
                    "type":null,
                    "unit":null,
                    "quantity":"1",
                    "unitPriceMinor":null,
                    "normativeMinutes":null,
                    "groupComment":null,
                    "mediaReferences":[]
                  }],
                  "stages":[{
                    "catalogNodeId":"$catalogNodeId",
                    "kind":"REPAIR_WORK",
                    "order":0
                  }]
                }
                """.trimIndent(),
            ),
        )

        assertThat(restoredPlan.lines.single().routingCatalogNodeId).isNull()

        server.enqueue(MockResponse().setResponseCode(500))
        val result = runCatching {
            api.saveInventoryInspection(
                inventoryId = "22222222-2222-2222-2222-222222222222",
                findingId = "33333333-3333-3333-3333-333333333333",
                request = SaveInspectionRequest(
                    expectedSessionRevision = 7,
                    expectedFindingRevision = 4,
                    inspection = "WORK_STAGED",
                    comment = "Требуется ремонт",
                    passportObservation = ObservationInput("ABSENT", null),
                    equipmentObservation = ObservationInput("ABSENT", null),
                    media = emptyList(),
                    planSelection = restoredPlan,
                ),
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(HttpException::class.java)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo(
            "/api/inventory/v1/sessions/22222222-2222-2222-2222-222222222222/" +
                "findings/33333333-3333-3333-3333-333333333333/inspection",
        )
        assertThat(request.body.readUtf8()).contains(
            "\"routingCatalogNodeId\":null",
        )
    }
}
