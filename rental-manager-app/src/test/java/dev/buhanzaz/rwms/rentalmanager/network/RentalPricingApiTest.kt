package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.rentalmanager.data.RentalPricingRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Covers exact wire amounts and complete set validation through the real Retrofit boundary. */
class RentalPricingApiTest {
    private val server = MockWebServer()
    private val moshi = Moshi.Builder().add(RentalMonthlyPriceAdapter())
        .addLast(KotlinJsonAdapterFactory()).build()
    private lateinit var repository: RentalPricingRepository

    @Before
    fun setUp() {
        server.start()
        repository = RentalPricingRepository(
            Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
                .create(RentalPricingApi::class.java),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `price read sends only warehouse and cabin ids without command idempotency`() = runTest {
        enqueue(payload("\"9223372036854775807\""))
        assertThat(repository.prices(WAREHOUSE, listOf(CABIN))).containsEntry(CABIN, Long.MAX_VALUE)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/logistics/v1/cabins/rental-prices")
        assertThat(request.getHeader("Idempotency-Key")).isNull()
        assertThat(request.body.readUtf8())
            .isEqualTo("""{"warehouseId":"$WAREHOUSE","ids":["$CABIN"]}""")
    }

    @Test
    fun `zero and long maximum round trip as strings without coercion`() {
        val adapter = moshi.adapter(RentalMonthlyPrice::class.java)
        for (amount in listOf(0L, 8_000L, Long.MAX_VALUE)) {
            assertThat(adapter.fromJson("\"$amount\"")?.rubles).isEqualTo(amount)
            assertThat(adapter.toJson(RentalMonthlyPrice(amount))).isEqualTo("\"$amount\"")
        }
    }

    @Test
    fun `numeric fractional negative overflow null and absent amounts fail closed`() = runTest {
        for (literal in listOf("0", "1.5", "null", "\"-1\"", "\"1.5\"", "\"1e3\"", "\"01\"", "\"9223372036854775808\"")) {
            enqueue(payload(literal))
            assertThat(runCatching { repository.prices(WAREHOUSE, listOf(CABIN)) }.isFailure).isTrue()
        }
        enqueue(payload("\"0\"").replace(",\"monthlyPriceRubles\":\"0\"", ""))
        assertThat(runCatching { repository.prices(WAREHOUSE, listOf(CABIN)) }.isFailure).isTrue()
    }

    @Test
    fun `foreign missing duplicate classifications and invalid versions are rejected`() = runTest {
        val valid = payload("\"8000\"")
        val row = valid.substringAfter("\"cabins\":[").substringBeforeLast("]}")
        for (invalid in listOf(
            valid.replace(WAREHOUSE, TYPE),
            valid.replace(CABIN, CATEGORY),
            valid.replace("[$row]", "[]"),
            valid.replace("[$row]", "[$row,$row]"),
            valid.replace(TYPE, "broken"),
            valid.replace("\"pricingVersion\":4", "\"pricingVersion\":-1"),
            valid.replace("\"rentalItemVersion\":2", "\"rentalItemVersion\":-1"),
        )) {
            enqueue(invalid)
            assertThat(runCatching { repository.prices(WAREHOUSE, listOf(CABIN)) }.isFailure).isTrue()
        }
    }

    @Test
    fun `invalid requests never call the service`() = runTest {
        for (ids in listOf(emptyList(), listOf(CABIN, CABIN), List(101) { CABIN })) {
            assertThat(runCatching { repository.prices(WAREHOUSE, ids) }.isFailure).isTrue()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `dependency failure is not a successful free price`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(runCatching { repository.prices(WAREHOUSE, listOf(CABIN)) }.isFailure).isTrue()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(body))
    }

    private fun payload(amount: String) =
        """{"warehouseId":"$WAREHOUSE","pricingVersion":4,"cabins":[{"rentalItemId":"$CABIN","rentalItemVersion":2,"rentalTypeId":"$TYPE","categoryId":"$CATEGORY","monthlyPriceRubles":$amount}]}"""

    companion object {
        private const val WAREHOUSE = "00000000-0000-4000-8000-000000000001"
        private const val CABIN = "00000000-0000-4000-8000-000000000002"
        private const val TYPE = "00000000-0000-4000-8000-000000000003"
        private const val CATEGORY = "00000000-0000-4000-8000-000000000004"
    }
}
