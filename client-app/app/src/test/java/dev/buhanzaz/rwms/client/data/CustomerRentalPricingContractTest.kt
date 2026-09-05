package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Exact wire prices are mandatory; a missing or malformed tariff is never local zero rent. */
class CustomerRentalPricingContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `whole rubles retain the full long range and zero through round trip`() {
        for (amount in listOf(0L, 8_000L, Long.MAX_VALUE)) {
            val cabin = json.decodeFromString<CustomerCabin>(payload("\"$amount\""))
            assertThat(cabin.monthlyPriceRubles).isEqualTo(amount)
            assertThat(cabin.pricingVersion).isEqualTo(7)
            assertThat(json.encodeToString(cabin)).contains("\"monthlyPriceRubles\":\"$amount\"")
        }
    }

    @Test
    fun `numeric fractional negative overflow and missing prices cannot decode as success`() {
        for (literal in listOf("0", "null", "\"\"", "\"-1\"", "\"1.5\"", "\"1e3\"", "\"01\"", "\"9223372036854775808\"")) {
            assertThat(runCatching { json.decodeFromString<CustomerCabin>(payload(literal)) }.isFailure).isTrue()
        }
        assertThat(runCatching {
            json.decodeFromString<CustomerCabin>("""{"unitId":"cabin","version":1,"accountingNo":"БК-1","pricingVersion":7}""")
        }.isFailure).isTrue()
    }

    private fun payload(amount: String) = """{"unitId":"cabin","version":1,"accountingNo":"БК-1","pricingVersion":7,"monthlyPriceRubles":$amount}"""
}
