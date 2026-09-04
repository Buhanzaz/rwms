package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RentalPresentationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RentalManagerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RentalManagerApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get decodes the complete canonical client presentation`() = runTest {
        server.enqueue(jsonResponse(presentationJson()))

        val response = api.clientPresentation(INQUIRY_ID)

        assertThat(response.isSuccessful).isTrue()
        val presentation = requireNotNull(response.body())
        assertThat(presentation.id).isEqualTo(PRESENTATION_ID)
        assertThat(presentation.mode).isEqualTo(RentalPresentationMode.NORMAL)
        assertThat(presentation.groups.single().cabins.single().photos.single().mediaId)
            .isEqualTo(MEDIA_ID)
        assertThat(presentation.equipmentAvailability.single().availableQuantity).isEqualTo(7)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo(
            "/api/logistics/v1/rental-inquiries/$INQUIRY_ID/client-presentation",
        )
    }

    @Test
    fun `put sends exact local path unchanged key and only normal request fields`() = runTest {
        server.enqueue(jsonResponse(presentationJson()))

        val presentation = api.publishClientPresentation(
            inquiryId = INQUIRY_ID,
            idempotencyKey = IDEMPOTENCY_KEY,
            request = PublishRentalPresentationRequest(
                warehouseId = WAREHOUSE_ID,
                groups = listOf(
                    RentalPresentationGroupRequest(
                        key = "ldsp",
                        label = "ЛДСП",
                        rentalItemIds = listOf(CABIN_ID),
                    ),
                ),
            ),
        )

        assertThat(presentation.publicPath).isEqualTo("/offer/$TOKEN")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo(
            "/api/logistics/v1/rental-inquiries/$INQUIRY_ID/client-presentation",
        )
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(IDEMPOTENCY_KEY)
        assertThat(request.body.readUtf8()).isEqualTo(
            "{\"warehouseId\":\"$WAREHOUSE_ID\",\"groups\":[" +
                "{\"key\":\"ldsp\",\"label\":\"ЛДСП\"," +
                "\"rentalItemIds\":[\"$CABIN_ID\"]}]}",
        )
    }

    @Test
    fun `missing required response field is rejected`() = runTest {
        server.enqueue(
            jsonResponse(
                presentationJson().lineSequence()
                    .filterNot { it.contains("\"publicPath\":") }
                    .joinToString("\n"),
            ),
        )

        val failure = runCatching { api.clientPresentation(INQUIRY_ID) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(JsonDataException::class.java)
        assertThat(failure).hasMessageThat().contains("publicPath")
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

private const val PRESENTATION_ID = "10000000-0000-4000-8000-000000000001"
private const val INQUIRY_ID = "20000000-0000-4000-8000-000000000002"
private const val WAREHOUSE_ID = "30000000-0000-4000-8000-000000000003"
private const val CABIN_ID = "40000000-0000-4000-8000-000000000004"
private const val EQUIPMENT_ID = "50000000-0000-4000-8000-000000000005"
private const val MEDIA_ID = "60000000-0000-4000-8000-000000000006"
private const val IDEMPOTENCY_KEY = "70000000-0000-4000-8000-000000000007"
private const val REVISION = 3L
private val TOKEN_PAYLOAD = Base64.getUrlEncoder().withoutPadding()
    .encodeToString("$PRESENTATION_ID:$REVISION".toByteArray(Charsets.UTF_8))
private val TOKEN = "$TOKEN_PAYLOAD.${"a".repeat(64)}"

private fun presentationJson(publicPath: String = "/offer/$TOKEN"): String = """
    {
      "id":"$PRESENTATION_ID",
      "version":2,
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
        "availableQuantity":7,
        "maximumPerCabin":2
      }],
      "groups":[{
        "key":"ldsp",
        "label":"ЛДСП",
        "cabins":[{
          "id":"$CABIN_ID",
          "number":"БК-101",
          "rentalType":"LDSP",
          "dimensions":"6x2.4",
          "finishing":"ЛДСП",
          "category":null,
          "characteristics":null,
          "linoleum":true,
          "passport":{"color":"серый"},
          "tags":["утепленная"],
          "currentContents":[{
            "equipmentId":"$EQUIPMENT_ID",
            "equipmentName":"Стол",
            "quantity":1,
            "locationKind":"CABIN"
          }],
          "photos":[{
            "mediaId":"$MEDIA_ID",
            "generation":4,
            "sortOrder":0,
            "availableVariants":["SMALL","LARGE"],
            "thumbnailUrl":"/api/logistics/public/v1/client-presentations/$TOKEN/media/$CABIN_ID/$MEDIA_ID/4/SMALL",
            "contentUrl":"/api/logistics/public/v1/client-presentations/$TOKEN/media/$CABIN_ID/$MEDIA_ID/4/LARGE"
          }]
        }]
      }]
    }
""".trimIndent()
