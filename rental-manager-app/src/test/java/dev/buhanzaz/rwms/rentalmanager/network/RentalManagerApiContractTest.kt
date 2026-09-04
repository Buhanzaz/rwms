package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RentalManagerApiContractTest {
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
    fun `current user decodes exact manager facts`() = runTest {
        server.enqueue(jsonResponse(CURRENT_USER_JSON))

        val user = api.currentUser()

        assertThat(user.globalRole).isEqualTo("RENTAL_MANAGER")
        assertThat(user.rentalAccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/auth/api/users/me")
    }

    @Test
    fun `warehouse directory decodes current classifications`() = runTest {
        server.enqueue(jsonResponse(WAREHOUSE_JSON))

        val warehouse = api.warehouses().single()

        assertThat(warehouse.production).isTrue()
        assertThat(warehouse.mainWarehouse).isFalse()
        assertThat(warehouse.representative).isFalse()
        assertThat(warehouse.representativeParentWarehouseId).isNull()
        assertThat(server.takeRequest().path).isEqualTo("/api/warehouse/v1/warehouses")
    }

    @Test
    fun `create client keeps canonical body and idempotency header`() = runTest {
        server.enqueue(jsonResponse(CLIENT_JSON))

        val client = api.createClient(
            idempotencyKey = COMMAND_ID,
            request = CreateRentalClientRequest(
                clientType = "SOLE_PROPRIETOR",
                displayName = "ИП Петров",
                phone = "+79991234567",
                contactPerson = "Петров Пётр",
            ),
        )

        assertThat(client.id).isEqualTo(CLIENT_ID)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/logistics/v1/clients")
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
        val body = request.body.readUtf8()
        assertThat(body).contains("\"clientType\":\"SOLE_PROPRIETOR\"")
    }

    @Test
    fun `order list uses server pagination and update sends current version`() = runTest {
        server.enqueue(jsonResponse("""{"content":[$ORDER_JSON],"page":1,"size":100,"totalElements":101,"totalPages":2}"""))
        server.enqueue(jsonResponse(orderDetailJson()))

        val page = api.orders(search = "Петров", page = 1)
        val updated = api.updateOrder(
            orderId = ORDER_ID,
            idempotencyKey = COMMAND_ID,
            request = UpdateOrderRequest(
                expectedVersion = page.content.single().version,
                clientId = CLIENT_ID,
                contactPhone = "+79991234567",
                comment = "Позвонить заранее",
            ),
        )

        assertThat(page.totalPages).isEqualTo(2)
        assertThat(page.content.single().units).isEmpty()
        assertThat(updated.permissions?.canEdit).isTrue()
        val selectedUnit = updated.units.single()
        assertThat(selectedUnit.added).isTrue()
        assertThat(selectedUnit.reservationState).isEqualTo("ACTIVE")
        assertThat(selectedUnit.unit.number).isEqualTo("БК-101")
        assertThat(selectedUnit.rentalTerm?.rentalMonths).isEqualTo(3L)
        assertThat(selectedUnit.rentalTerm?.shipmentDate).isEqualTo("2026-09-05")
        assertThat(selectedUnit.rentalTerm?.returnDate).isEqualTo("2026-12-05")
        val listRequest = server.takeRequest()
        assertThat(listRequest.requestUrl?.encodedPath).isEqualTo("/api/logistics/v1/orders")
        assertThat(listRequest.requestUrl?.queryParameter("search")).isEqualTo("Петров")
        assertThat(listRequest.requestUrl?.queryParameter("page")).isEqualTo("1")
        assertThat(listRequest.requestUrl?.queryParameter("size")).isEqualTo("100")
        val updateRequest = server.takeRequest()
        assertThat(updateRequest.method).isEqualTo("PUT")
        assertThat(updateRequest.path).isEqualTo("/api/logistics/v1/orders/$ORDER_ID")
        assertThat(updateRequest.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
        val body = updateRequest.body.readUtf8()
        assertThat(body).contains("\"expectedVersion\":4")
        assertThat(body).contains("\"clientId\":\"$CLIENT_ID\"")
    }

    @Test
    fun `order detail preserves a required nullable rental term`() = runTest {
        server.enqueue(jsonResponse(orderDetailJson(rentalTerm = "null")))

        val detail = api.order(ORDER_ID)

        assertThat(detail.units.single().rentalTerm).isNull()
        assertThat(server.takeRequest().path).isEqualTo("/api/logistics/v1/orders/$ORDER_ID")
    }

    @Test
    fun `cancel draft sends current version and idempotency fence`() = runTest {
        server.enqueue(jsonResponse(orderDetailJson(status = "CANCELLED")))

        val cancelled = api.cancelOrder(
            orderId = ORDER_ID,
            expectedVersion = 4,
            idempotencyKey = COMMAND_ID,
        )

        assertThat(cancelled.status).isEqualTo("CANCELLED")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/api/logistics/v1/orders/$ORDER_ID")
        assertThat(request.requestUrl?.queryParameter("expectedVersion")).isEqualTo("4")
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
        assertThat(request.body.size).isEqualTo(0L)
    }

    @Test
    fun `save draft sends current version and idempotency fence`() = runTest {
        server.enqueue(jsonResponse(orderDetailJson(status = "SAVED")))

        val saved = api.saveOrder(
            orderId = ORDER_ID,
            expectedVersion = 4,
            idempotencyKey = COMMAND_ID,
        )

        assertThat(saved.status).isEqualTo("SAVED")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.requestUrl?.encodedPath)
            .isEqualTo("/api/logistics/v1/orders/$ORDER_ID/save")
        assertThat(request.requestUrl?.queryParameter("expectedVersion")).isEqualTo("4")
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
        assertThat(request.body.size).isEqualTo(0L)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

private const val USER_ID = "00000000-0000-0000-0000-000000000010"
private const val WAREHOUSE_ID = "00000000-0000-0000-0000-000000000101"
private const val CLIENT_ID = "00000000-0000-0000-0000-000000000201"
private const val ORDER_ID = "00000000-0000-0000-0000-000000000301"
private const val COMMAND_ID = "00000000-0000-0000-0000-000000000401"
private const val RESERVATION_ID = "00000000-0000-0000-0000-000000000501"
private const val RENTAL_ITEM_ID = "00000000-0000-0000-0000-000000000601"
private const val EQUIPMENT_ID = "00000000-0000-0000-0000-000000000701"

private val CURRENT_USER_JSON = """
    {
      "id":"$USER_ID",
      "username":"manager",
      "displayName":"Менеджер",
      "principalType":"USER",
      "globalRole":"RENTAL_MANAGER",
      "rentalAccess":true,
      "warehouseAccessAll":false,
      "warehouseAccesses":[{"warehouseId":"$WAREHOUSE_ID","level":"EDIT"}]
    }
""".trimIndent()

private val WAREHOUSE_JSON = """
    [
      {
        "id":"$WAREHOUSE_ID",
        "version":2,
        "name":"Производство СПБ",
        "city":"Санкт-Петербург",
        "address":null,
        "latitude":59.9386,
        "longitude":30.3141,
        "timeZone":"Europe/Moscow",
        "active":true,
        "lifecycleState":"ACTIVE",
        "sortOrder":1,
        "representative":false,
        "production":true,
        "mainWarehouse":false,
        "representativeParentWarehouseId":null
      }
    ]
""".trimIndent()

private val CLIENT_JSON = """
    {
      "id":"$CLIENT_ID",
      "version":2,
      "type":"SOLE_PROPRIETOR",
      "displayName":"ИП Петров",
      "phone":"+79991234567",
      "contactPerson":"Петров Пётр",
      "email":null,
      "responsibleManagerId":"$USER_ID",
      "responsibleManagerDisplayName":"Менеджер",
      "comment":null,
      "source":"ANDROID_MANAGER",
      "additionalContacts":[],
      "createdAt":"2026-09-01T10:00:00Z",
      "updatedAt":"2026-09-02T10:00:00Z"
    }
""".trimIndent()

private val ORDER_JSON = """
    {
      "id":"$ORDER_ID",
      "version":4,
      "number":"A-100",
      "status":"DRAFT",
      "client":$CLIENT_JSON,
      "managerId":"$USER_ID",
      "managerDisplayName":"Менеджер",
      "warehouseId":"$WAREHOUSE_ID",
      "deliveryAddress":null,
      "contactPhone":"+79991234567",
      "comment":null,
      "desiredDeliveryWindows":[],
      "unitCount":0,
      "updatedAt":"2026-09-02T10:00:00Z"
    }
""".trimIndent()

private fun orderDetailJson(
    status: String = "DRAFT",
    rentalTerm: String =
        """{"rentalMonths":3,"shipmentDate":"2026-09-05","returnDate":"2026-12-05"}""",
): String = """
    {
      "id":"$ORDER_ID",
      "version":5,
      "number":"A-100",
      "status":"$status",
      "client":$CLIENT_JSON,
      "managerId":"$USER_ID",
      "managerDisplayName":"Менеджер",
      "warehouseId":"$WAREHOUSE_ID",
      "deliveryAddress":null,
      "contactPhone":"+79991234567",
      "comment":"Позвонить заранее",
      "desiredDeliveryWindows":[],
      "unitCount":1,
      "updatedAt":"2026-09-02T10:10:00Z",
      "units":[{
        "reservationId":"$RESERVATION_ID",
        "added":true,
        "reservationState":"ACTIVE",
        "unit":{
          "id":"$RENTAL_ITEM_ID",
          "version":7,
          "warehouseId":"$WAREHOUSE_ID",
          "number":"БК-101",
          "status":"RENTED",
          "rentalType":"LDSP",
          "dimensions":"6 × 2,4 м",
          "finishing":"Чистовая",
          "category":"Стандарт",
          "characteristics":"С окнами",
          "linoleum":true,
          "tags":[],
          "contents":[],
          "createdAt":"2026-08-01T10:00:00Z",
          "updatedAt":"2026-09-02T09:00:00Z"
        },
        "desiredContents":[{
          "equipmentId":"$EQUIPMENT_ID",
          "equipmentName":"Стол",
          "quantity":1,
          "reservationState":"ACTIVE"
        }],
        "rentalTerm":$rentalTerm
      }],
      "permissions":{
        "canEdit":true,
        "canReplaceUnits":false,
        "canExtendRentalTerms":false,
        "canViewOtherManagers":false
      }
    }
""".trimIndent()
