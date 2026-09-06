package dev.buhanzaz.rwms.rentalmanager.network

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson
import retrofit2.http.Body
import retrofit2.http.POST

/** Read-only monthly tariffs from the public logistics boundary; never a booking command. */
interface RentalPricingApi {
    @POST("api/logistics/v1/cabins/rental-prices")
    suspend fun cabinPrices(@Body request: CabinRentalPricesRequest): CabinRentalPricesDto
}

/** Bounded cabin identities for a warehouse-scoped tariff lookup. */
data class CabinRentalPricesRequest(val warehouseId: String, val rentalItemIds: List<String>)

/** Complete price read from one logistics tariff revision. */
data class CabinRentalPricesDto(
    val warehouseId: String,
    val pricingVersion: Long,
    val cabins: List<CabinRentalPriceDto>,
)

/** Asset-owned classification proof with a logistics-owned monthly tariff, not a payment receipt. */
data class CabinRentalPriceDto(
    val rentalItemId: String,
    val rentalItemVersion: Long,
    val rentalTypeId: String,
    val categoryId: String,
    val monthlyPriceRubles: RentalMonthlyPrice,
)

/** Exact nonnegative whole rubles, distinct from delivery and other charges. */
data class RentalMonthlyPrice(val rubles: Long) {
    init {
        require(rubles >= 0) { "Rental price must be nonnegative" }
    }
}

/** Rejects numeric JSON tokens instead of letting Moshi coerce them to rounded strings. */
class RentalMonthlyPriceAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): RentalMonthlyPrice? {
        if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
        if (reader.peek() != JsonReader.Token.STRING) {
            throw JsonDataException("Rental price must be a whole-RUB string")
        }
        val text = reader.nextString()
        if (!text.matches(Regex("^(0|[1-9][0-9]{0,18})$"))) {
            throw JsonDataException("Invalid whole-RUB rental price")
        }
        val amount = text.toLongOrNull()
            ?: throw JsonDataException("Rental price exceeds the supported range")
        return RentalMonthlyPrice(amount)
    }

    @ToJson
    fun toJson(price: RentalMonthlyPrice): String = price.rubles.toString()
}
