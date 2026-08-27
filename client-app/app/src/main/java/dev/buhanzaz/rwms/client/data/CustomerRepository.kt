package dev.buhanzaz.rwms.client.data

import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Cohesive client-side adapter for the server-owned customer booking workflow. */
@Singleton
class CustomerRepository @Inject constructor(
    private val api: CustomerApi,
    private val json: Json,
) {
    private val pendingIdempotencyKeys = ConcurrentHashMap<String, String>()

    /** Returns the current customer's profile or `null` only for a proven 404. */
    suspend fun profileOrNull(): CustomerProfile? = try {
        api.profile()
    } catch (failure: HttpException) {
        if (failure.code() == 404) null else throw failure.toCustomerApiException(json)
    } catch (failure: Throwable) {
        throw failure.toCustomerApiException(json)
    }

    /** Creates the one customer-owned profile. */
    suspend fun createProfile(profile: CustomerProfile): CustomerProfile = call { api.createProfile(profile) }

    /** Lists warehouses eligible for customer delivery. */
    suspend fun warehouses(): List<CustomerWarehouse> = call { api.warehouses() }

    /** Starts or resumes creation of one inquiry with a process-durable idempotency key. */
    suspend fun createInquiry(warehouseId: String, idempotencyKey: String): InquirySession = call {
        api.createInquiry(idempotencyKey, CreateInquiryRequest(warehouseId))
    }

    /** Reloads one known customer-owned inquiry after process recreation. */
    suspend fun inquiry(inquiryId: String): InquirySession = call { api.inquiry(inquiryId) }

    /** Loads free-cabin filters for an inquiry. */
    suspend fun facets(inquiryId: String): CabinFacets = call { api.facets(inquiryId) }

    /** Loads one page of currently free cabins under server-side filters. */
    suspend fun cabins(inquiryId: String, filters: CabinFilters, page: Int): CabinPage = call {
        api.cabins(
            inquiryId = inquiryId,
            query = filters.query.takeIf(String::isNotBlank),
            cabinType = filters.cabinType,
            finish = filters.finish,
            dimensions = filters.dimensions,
            category = filters.category,
            linoleum = filters.linoleum,
            characteristics = filters.characteristics.toList(),
            page = page,
        )
    }

    /** Replaces selected cabins under an optimistic version fence. */
    suspend fun updateCabins(
        inquiryId: String,
        version: Long,
        unitIds: Set<String>,
    ): CabinSelectionResponse {
        val fingerprint = unitIds.sorted().joinToString(",")
        return idempotent("selection:$inquiryId:$version:$fingerprint") { key ->
            api.updateSelection(inquiryId, key, UpdateCabinSelectionRequest(version, unitIds.toList()))
        }
    }

    /** Returns only equipment with a positive warehouse balance. */
    suspend fun equipment(inquiryId: String): List<AvailableEquipment> = call { api.equipment(inquiryId) }

    /** Replaces cabin equipment reservations under an optimistic fence. */
    suspend fun updateEquipment(
        inquiryId: String,
        version: Long,
        selections: List<EquipmentSelection>,
    ): EquipmentSelectionResponse = call {
        api.updateEquipment(inquiryId, UpdateEquipmentRequest(version, selections))
    }

    /** Reloads the authoritative cart after a mutation or conflict. */
    suspend fun cart(inquiryId: String): CustomerCart = call { api.cart(inquiryId) }

    /** Returns only delivery slots that the server planner proves feasible. */
    suspend fun searchSlots(request: DeliverySlotSearchRequest): List<DeliverySlot> =
        call { api.searchSlots(request) }

    /** Places an expiring optimistic hold on one chosen delivery slot. */
    suspend fun holdSlot(
        slotId: String,
        slotVersion: Long,
        inquiryId: String,
        cartVersion: Long,
    ): HeldDeliverySlot = call {
        api.holdSlot(slotId, slotVersion, HoldDeliverySlotRequest(inquiryId, cartVersion))
    }

    /** Creates the durable booking/order from the current selection and held slot. */
    suspend fun checkout(inquiryId: String, request: CheckoutRequest): CustomerBooking =
        idempotent(
            "checkout:$inquiryId:${request.expectedVersion}:${request.slotId}:${request.slotVersion}:${request.rentalMonths}",
        ) { key -> api.checkout(inquiryId, key, request) }

    /** Lists real customer bookings independently of any logistics simulator scenario. */
    suspend fun bookings(): List<CustomerBooking> = call { api.bookings() }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        throw failure.toCustomerApiException(json)
    }

    private suspend fun <T> idempotent(operation: String, block: suspend (String) -> T): T {
        val key = pendingIdempotencyKeys.computeIfAbsent(operation) { UUID.randomUUID().toString() }
        return try {
            block(key).also { pendingIdempotencyKeys.remove(operation, key) }
        } catch (failure: Throwable) {
            throw failure.toCustomerApiException(json)
        }
    }
}

/** Current free-cabin query; the server remains authoritative for availability. */
data class CabinFilters(
    val query: String = "",
    val cabinType: String? = null,
    val finish: String? = null,
    val dimensions: String? = null,
    val category: String? = null,
    val linoleum: Boolean? = null,
    val characteristics: Set<String> = emptySet(),
) {
    /** Number of non-text filters shown on the filter action badge. */
    val activeCount: Int
        get() = listOfNotNull(cabinType, finish, dimensions, category, linoleum).size + characteristics.size
}
