package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Exercises the actual anonymous transport, including the shared image loader's credential boundary. */
class PublicCustomerCatalogRepositoryTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val raw = CustomerNetworkModule.rawClient()
    private lateinit var repository: PublicCustomerCatalogRepository

    @Before
    fun setUp() {
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(raw)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(PublicCustomerCatalogApi::class.java)
        repository = PublicCustomerCatalogRepository(api, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        raw.connectionPool.evictAll()
        raw.dispatcher.executorService.shutdown()
    }

    @Test
    fun `guest reads warehouses filters and prices using only anonymous GETs`() = runBlocking {
        enqueue("""[{"id":"spb","name":"Склад","city":"Санкт-Петербург","timezone":"Europe/Moscow","depotLatitude":59.93,"depotLongitude":30.32}]""")
        enqueue("""{"warehouses":[{"warehouseId":"spb","name":"Склад","cabinTypes":["Офисная"]}]}""")
        enqueue("""{"content":[{"unitId":"cabin-1","version":1,"accountingNo":"42","pricingVersion":2,"monthlyPriceRubles":"18000"}],"page":0,"totalPages":1}""")

        assertThat(repository.warehouses().single().city).isEqualTo("Санкт-Петербург")
        assertThat(repository.facets("spb").warehouses).hasSize(1)
        assertThat(repository.cabins("spb", CabinFilters(), 0).content.single().monthlyPriceRubles).isEqualTo(18_000L)

        val requests = List(3) { server.takeRequest() }
        assertThat(requests.map { it.requestUrl?.encodedPath }).containsExactly(
            "/api/logistics/public/v1/catalog/warehouses",
            "/api/logistics/public/v1/catalog/warehouses/spb/facets",
            "/api/logistics/public/v1/catalog/warehouses/spb/cabins",
        ).inOrder()
        requests.forEach {
            assertThat(it.method).isEqualTo("GET")
            assertThat(it.getHeader("Authorization")).isNull()
            assertThat(it.getHeader("Idempotency-Key")).isNull()
            assertThat(it.bodySize).isEqualTo(0)
        }
    }

    @Test
    fun `filters preserve false values repeated characteristics and pagination`() = runBlocking {
        enqueue("{}")
        repository.cabins("warehouse-2", CabinFilters(
            cabinType = "Офисная", finish = "Дерево", dimensions = "6×2.4", category = "Стандарт",
            linoleum = false, characteristics = setOf("Свет", "Окно"),
        ), 2)

        val query = requireNotNull(server.takeRequest().requestUrl)
        assertThat(query.queryParameter("cabinType")).isEqualTo("Офисная")
        assertThat(query.queryParameter("finish")).isEqualTo("Дерево")
        assertThat(query.queryParameter("dimensions")).isEqualTo("6×2.4")
        assertThat(query.queryParameter("category")).isEqualTo("Стандарт")
        assertThat(query.queryParameter("linoleum")).isEqualTo("false")
        assertThat(query.queryParameterValues("characteristics")).containsExactly("Окно", "Свет").inOrder()
        assertThat(query.queryParameter("page")).isEqualTo("2")
        assertThat(query.queryParameter("size")).isEqualTo("20")
    }

    @Test
    fun `service failure remains an error instead of an empty catalog`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/problem+json")
            .setBody("""{"status":503,"detail":"Каталог временно недоступен"}"""))
        val failure = runCatching { repository.cabins("spb", CabinFilters(), 0) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat((failure as CustomerApiException).status).isEqualTo(503)
        assertThat(failure.message).isEqualTo("Каталог временно недоступен")
    }

    @Test
    fun `anonymous unauthorized response does not invent an expired customer session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { repository.warehouses() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CustomerApiException::class.java)
        assertThat((failure as CustomerApiException).status).isEqualTo(401)
        assertThat(failure.message).isEqualTo("Каталог без входа временно недоступен.")
    }

    @Test
    fun `public photo failure neither sends bearer nor invokes credential refresh`() {
        val refreshes = AtomicInteger()
        val authenticated = raw.newBuilder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer test-only").build()) }
            .authenticator { _, _ -> refreshes.incrementAndGet(); null }
            .build()
        val images = customerImageCallFactory(authenticated, raw)
        server.enqueue(MockResponse().setResponseCode(401))
        images.newCall(Request.Builder().url(server.url(
            "/api/logistics/public/v1/catalog/warehouses/spb/cabins/cabin-1/photos/photo-1?generation=2&variant=LARGE",
        )).build()).execute().use { assertThat(it.code).isEqualTo(401) }
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
        assertThat(refreshes.get()).isEqualTo(0)

        enqueue("image")
        images.newCall(Request.Builder().url(server.url("/api/media/v1/assets/photo-1/content")).build())
            .execute().close()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer test-only")
    }

    @Test
    fun `anonymous image routing is restricted to the exact public GET photo path`() {
        val protectedRequests = mutableListOf<Request>()
        val publicRequests = mutableListOf<Request>()
        val images = customerImageCallFactory(
            Call.Factory { request -> protectedRequests.add(request); raw.newCall(request) },
            Call.Factory { request -> publicRequests.add(request); raw.newCall(request) },
        )
        val path = "/api/logistics/public/v1/catalog/warehouses/spb/cabins/cabin-1/photos/photo-1"
        images.newCall(Request.Builder().url(server.url(path)).build())
        images.newCall(Request.Builder().url(server.url("$path/content")).build())
        images.newCall(Request.Builder().url(server.url(path)).post("".toRequestBody()).build())
        images.newCall(Request.Builder().url(server.url("/api/logistics/customer/v1/profile")).build())
        assertThat(publicRequests).hasSize(1)
        assertThat(protectedRequests).hasSize(3)
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }
}
