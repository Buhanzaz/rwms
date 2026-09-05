package dev.buhanzaz.rwms.rentalmanager.data

import dev.buhanzaz.rwms.rentalmanager.network.CabinRentalPricesRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalPricingApi
import java.util.UUID

internal fun interface RentalPricingDataSource {
    suspend fun prices(warehouseId: String, cabinIds: List<String>): Map<String, Long>
}

/** Validates the complete warehouse-scoped price response before any cabin displays a tariff. */
internal class RentalPricingRepository(private val api: RentalPricingApi) : RentalPricingDataSource {
    override suspend fun prices(warehouseId: String, cabinIds: List<String>): Map<String, Long> {
        requireUuid(warehouseId)
        require(cabinIds.size in 1..100 && cabinIds.toSet().size == cabinIds.size) {
            "Price lookup requires one to one hundred distinct cabins"
        }
        cabinIds.forEach(::requireUuid)
        val response = api.cabinPrices(CabinRentalPricesRequest(warehouseId, cabinIds))
        require(response.warehouseId == warehouseId && response.pricingVersion >= 0) {
            "Rental price response scope is invalid"
        }
        require(response.cabins.size == cabinIds.size &&
            response.cabins.map { it.rentalItemId }.toSet() == cabinIds.toSet()
        ) { "Rental price response must contain the complete requested cabin set" }
        return response.cabins.associate { cabin ->
            requireUuid(cabin.rentalTypeId)
            requireUuid(cabin.categoryId)
            require(cabin.rentalItemVersion >= 0) { "Cabin price version is invalid" }
            cabin.rentalItemId to cabin.monthlyPriceRubles.rubles
        }
    }

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString() == value) { "Price identity must be a UUID" }
    }
}
