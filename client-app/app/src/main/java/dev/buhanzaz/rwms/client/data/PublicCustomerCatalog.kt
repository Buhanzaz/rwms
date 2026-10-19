package dev.buhanzaz.rwms.client.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Anonymous reads expose rentable cabins without creating a customer, inquiry or hold. */
interface PublicCustomerCatalogApi {
    @GET("api/logistics/public/v1/catalog/warehouses")
    suspend fun warehouses(): List<CustomerWarehouse>

    @GET("api/logistics/public/v1/catalog/warehouses/{warehouseId}/facets")
    suspend fun facets(@Path("warehouseId") warehouseId: String): CabinFacets

    @GET("api/logistics/public/v1/catalog/warehouses/{warehouseId}/cabins")
    suspend fun cabins(
        @Path("warehouseId") warehouseId: String,
        @Query("cabinType") cabinType: String? = null,
        @Query("finish") finish: String? = null,
        @Query("dimensions") dimensions: String? = null,
        @Query("category") category: String? = null,
        @Query("linoleum") linoleum: Boolean? = null,
        @Query("characteristics") characteristics: List<String> = emptyList(),
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): CabinPage
}

/** Read-only catalog adapter; it has no session storage, mutation API or idempotency ledger. */
@Singleton
class PublicCustomerCatalogRepository @Inject constructor(
    private val api: PublicCustomerCatalogApi,
    private val json: Json,
) {
    suspend fun warehouses(): List<CustomerWarehouse> = read { api.warehouses() }

    suspend fun facets(warehouseId: String): CabinFacets = read { api.facets(warehouseId) }

    suspend fun cabins(warehouseId: String, filters: CabinFilters, page: Int): CabinPage = read {
        api.cabins(
            warehouseId = warehouseId,
            cabinType = filters.cabinType,
            finish = filters.finish,
            dimensions = filters.dimensions,
            category = filters.category,
            linoleum = filters.linoleum,
            characteristics = filters.characteristics.sorted(),
            page = page,
        )
    }

    private suspend fun <T> read(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HttpException) {
        if (failure.code() == 401) {
            throw CustomerApiException(401, "Каталог без входа временно недоступен.")
        }
        throw failure.toCustomerApiException(json)
    } catch (failure: Exception) {
        throw failure.toCustomerApiException(json)
    }
}
