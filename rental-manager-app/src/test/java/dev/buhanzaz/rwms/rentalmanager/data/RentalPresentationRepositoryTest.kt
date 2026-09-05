package dev.buhanzaz.rwms.rentalmanager.data

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.RentalMonthlyPriceAdapter
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationGroupRequest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RentalPresentationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RentalManagerApi
    private lateinit var mappedProblemCodes: MutableList<Int>
    private lateinit var repository: RentalPresentationRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(RentalMonthlyPriceAdapter())
            .addLast(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RentalManagerApi::class.java)
        mappedProblemCodes = mutableListOf()
        repository = RentalPresentationRepository(
            api = api,
            problemMessage = { exception ->
                mappedProblemCodes += exception.code()
                "problem-${exception.code()}"
            },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get treats only not found as an absent presentation`() = runTest {
        server.enqueue(problemResponse(404, "NOT_FOUND"))

        val presentation = repository.get(INQUIRY_ID)

        assertThat(presentation).isNull()
        assertThat(mappedProblemCodes).isEmpty()
    }

    @Test
    fun `legacy null price remains unknown but a half snapshot fails`() = runTest {
        server.enqueue(
            jsonResponse(
                presentationJson()
                    .replace("\"pricingVersion\":4", "\"pricingVersion\":null")
                    .replace("\"monthlyPriceRubles\":\"8500\"", "\"monthlyPriceRubles\":null"),
            ),
        )
        val legacy = requireNotNull(repository.get(INQUIRY_ID)).groups.single().cabins.single()
        assertThat(legacy.pricingVersion).isNull()
        assertThat(legacy.monthlyPriceRubles).isNull()

        server.enqueue(
            jsonResponse(
                presentationJson()
                    .replace("\"monthlyPriceRubles\":\"8500\"", "\"monthlyPriceRubles\":null"),
            ),
        )
        assertThat(runCatching { repository.get(INQUIRY_ID) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `conflict and dependency failures stay problem details failures`() = runTest {
        listOf(409 to "VERSION_CONFLICT", 503 to "DEPENDENCY_UNAVAILABLE").forEach { (status, code) ->
            server.enqueue(problemResponse(status, code))

            val failure = runCatching { repository.get(INQUIRY_ID) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(HttpException::class.java)
            assertThat(repository.userMessage(requireNotNull(failure))).isEqualTo("problem-$status")
        }
        assertThat(mappedProblemCodes).containsExactly(409, 503).inOrder()
    }

    @Test
    fun `foreign and malformed public paths are rejected`() = runTest {
        listOf(
            "https://evil.example/offer/$TOKEN",
            "/offer/not-a-signed-presentation-token",
        ).forEach { publicPath ->
            server.enqueue(jsonResponse(presentationJson(publicPath)))

            val failure = runCatching { repository.get(INQUIRY_ID) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("presentation")
        }
    }

    @Test
    fun `publish preserves external idempotency key and validates exact selection partition`() =
        runTest {
            server.enqueue(jsonResponse(presentationJson()))
            val groups = listOf(
                RentalPresentationGroupRequest(
                    key = "ldsp",
                    label = "ЛДСП",
                    rentalItemIds = listOf(CABIN_ID),
                ),
            )

            val presentation = repository.publish(
                inquiryId = INQUIRY_ID,
                idempotencyKey = UUID.fromString(IDEMPOTENCY_KEY),
                warehouseId = WAREHOUSE_ID,
                selectedRentalItemIds = listOf(CABIN_ID),
                groups = groups,
            )

            assertThat(presentation.groups.single().cabins.single().id).isEqualTo(CABIN_ID)
            val request = server.takeRequest()
            assertThat(request.getHeader("Idempotency-Key")).isEqualTo(IDEMPOTENCY_KEY)
            assertThat(request.body.readUtf8()).doesNotContain("mode")

            val failure = runCatching {
                repository.publish(
                    inquiryId = INQUIRY_ID,
                    idempotencyKey = UUID.fromString(OTHER_IDEMPOTENCY_KEY),
                    warehouseId = WAREHOUSE_ID,
                    selectedRentalItemIds = listOf(CABIN_ID, OTHER_CABIN_ID),
                    groups = groups,
                )
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("partition")
            assertThat(server.requestCount).isEqualTo(1)
        }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun problemResponse(status: Int, code: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","detail":"Ошибка"}""")
}

private const val PRESENTATION_ID = "11000000-0000-4000-8000-000000000001"
private const val INQUIRY_ID = "22000000-0000-4000-8000-000000000002"
private const val WAREHOUSE_ID = "33000000-0000-4000-8000-000000000003"
private const val CABIN_ID = "44000000-0000-4000-8000-000000000004"
private const val OTHER_CABIN_ID = "44000000-0000-4000-8000-000000000005"
private const val EQUIPMENT_ID = "55000000-0000-4000-8000-000000000005"
private const val MEDIA_ID = "66000000-0000-4000-8000-000000000006"
private const val IDEMPOTENCY_KEY = "77000000-0000-4000-8000-000000000007"
private const val OTHER_IDEMPOTENCY_KEY = "77000000-0000-4000-8000-000000000008"
private const val REVISION = 4L
private val TOKEN_PAYLOAD = Base64.getUrlEncoder().withoutPadding()
    .encodeToString("$PRESENTATION_ID:$REVISION".toByteArray(Charsets.UTF_8))
private val TOKEN = "$TOKEN_PAYLOAD.${"b".repeat(64)}"

private fun presentationJson(publicPath: String = "/offer/$TOKEN"): String = """
    {
      "id":"$PRESENTATION_ID",
      "version":5,
      "revision":$REVISION,
      "inquiryId":"$INQUIRY_ID",
      "warehouseId":"$WAREHOUSE_ID",
      "state":"ACTIVE",
      "expiresAt":"2026-09-02T12:00:00Z",
      "viewUntil":"2026-09-03T12:00:00Z",
      "canConfirm":true,
      "publicPath":"$publicPath",
      "bookedOrderId":null,
      "mode":"NORMAL",
      "replacementUnitIds":[],
      "requiredSelectionCount":null,
      "requiresDesiredDeliveryWindows":true,
      "desiredDeliveryWindows":[],
      "equipmentAvailability":[{
        "equipmentId":"$EQUIPMENT_ID",
        "equipmentName":"Стол",
        "availableQuantity":3,
        "maximumPerCabin":1
      }],
      "groups":[{
        "key":"ldsp",
        "label":"ЛДСП",
        "cabins":[{
          "id":"$CABIN_ID",
          "number":"БК-201",
          "pricingVersion":4,
          "monthlyPriceRubles":"8500",
          "rentalType":"LDSP",
          "dimensions":"6x2.4",
          "finishing":"ЛДСП",
          "category":null,
          "characteristics":null,
          "linoleum":true,
          "passport":{},
          "tags":[],
          "currentContents":[],
          "photos":[{
            "mediaId":"$MEDIA_ID",
            "generation":2,
            "sortOrder":0,
            "availableVariants":["SMALL","LARGE"],
            "thumbnailUrl":"/api/logistics/public/v1/client-presentations/$TOKEN/media/$CABIN_ID/$MEDIA_ID/2/SMALL",
            "contentUrl":"/api/logistics/public/v1/client-presentations/$TOKEN/media/$CABIN_ID/$MEDIA_ID/2/LARGE"
          }]
        }]
      }]
    }
""".trimIndent()
