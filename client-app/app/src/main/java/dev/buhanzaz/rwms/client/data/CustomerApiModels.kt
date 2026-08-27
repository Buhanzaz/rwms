package dev.buhanzaz.rwms.client.data

import kotlinx.serialization.Serializable

/** Distinguishes a private customer from a legal entity without duplicating profile ownership. */
@Serializable
enum class CustomerEntityType {
    INDIVIDUAL,
    LEGAL,
}

/** Customer-owned contact and invoicing identity returned by logistics. */
@Serializable
data class CustomerProfile(
    val id: String? = null,
    val version: Long? = null,
    val entityType: CustomerEntityType,
    val firstName: String? = null,
    val lastName: String? = null,
    val companyName: String? = null,
    val phone: String,
    val email: String? = null,
    val additionalInfo: String? = null,
)

/** Public warehouse choice available for a new customer inquiry. */
@Serializable
data class CustomerWarehouse(
    val id: String,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val timezone: String,
    val depotLatitude: Double,
    val depotLongitude: Double,
)

/** Starts one server-owned cabin selection scope at a warehouse. */
@Serializable
data class CreateInquiryRequest(val warehouseId: String)

/** Identifies the server selection and its optimistic version. */
@Serializable
data class InquirySession(
    val inquiryId: String,
    val warehouseId: String,
    val selectionVersion: Long,
    val state: String,
)

/** Exact type/dimension relation returned by the warehouse-owned facets projection. */
@Serializable
data class CabinTypeDimensions(
    val cabinType: String,
    val dimensions: List<String> = emptyList(),
)

/** Filter values derived from currently free cabins at one authorized warehouse. */
@Serializable
data class CabinFacetWarehouse(
    val warehouseId: String,
    val name: String,
    val city: String? = null,
    val cabinTypes: List<String> = emptyList(),
    val finishes: List<String> = emptyList(),
    val dimensions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val characteristics: List<String> = emptyList(),
    val typeDimensions: List<CabinTypeDimensions> = emptyList(),
)

/** Available-cabin facets grouped by the server-authorized warehouse. */
@Serializable
data class CabinFacets(val warehouses: List<CabinFacetWarehouse> = emptyList()) {
    /** Facets for the inquiry warehouse, or an empty safe projection while loading. */
    val selected: CabinFacetWarehouse
        get() = warehouses.firstOrNull() ?: CabinFacetWarehouse("", "")
}

/** One protected cabin photo with preview and full-size gateway URLs. */
@Serializable
data class CabinPhoto(
    val photoId: String,
    val generation: Long,
    val url: String,
    val thumbnailUrl: String,
)

/** One currently free cabin presented without a passport navigation target. */
@Serializable
data class CustomerCabin(
    val unitId: String,
    val version: Long,
    val accountingNo: String,
    val type: String? = null,
    val finish: String? = null,
    val dimensions: String? = null,
    val category: String? = null,
    val linoleum: Boolean? = null,
    val characteristics: List<String> = emptyList(),
    val facts: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val photos: List<CabinPhoto> = emptyList(),
)

/** Paged free-cabin result returned by the customer API. */
@Serializable
data class CabinPage(
    val content: List<CustomerCabin> = emptyList(),
    val page: Long = 0,
    val size: Long = 20,
    val totalElements: Long = 0,
    val totalPages: Long = 0,
)

/** Replaces a cabin selection using an optimistic version fence. */
@Serializable
data class UpdateCabinSelectionRequest(
    val expectedVersion: Long,
    val cabinUnitIds: List<String>,
)

/** Current authoritative cabin hold set and resulting cart version. */
@Serializable
data class CabinSelectionResponse(
    val version: Long,
    val expiresAt: String? = null,
    val cabins: List<CustomerCabin> = emptyList(),
)

/** One furniture or equipment inventory position currently available at the warehouse. */
@Serializable
data class AvailableEquipment(
    val inventoryItemId: String,
    val name: String,
    val category: String? = null,
    val availableQuantity: Long,
    val maximumPerCabin: Int? = null,
)

/** Assigns a positive available inventory quantity to one selected cabin. */
@Serializable
data class EquipmentSelection(
    val cabinUnitId: String,
    val inventoryItemId: String,
    val quantity: Long,
)

/** Replaces all equipment selections for an inquiry under one optimistic fence. */
@Serializable
data class UpdateEquipmentRequest(
    val expectedVersion: Long,
    val selections: List<EquipmentSelection>,
)

/** Persisted furniture intent and resulting cart version. */
@Serializable
data class EquipmentSelectionResponse(
    val version: Long,
    val selections: List<EquipmentSelection> = emptyList(),
)

/** Authoritative cart summary and mutation version. */
@Serializable
data class CustomerCart(
    val inquiryId: String,
    val warehouseId: String,
    val version: Long,
    val state: String,
    val cabins: List<CustomerCabin> = emptyList(),
    val equipment: List<EquipmentSelection> = emptyList(),
    val deliverySlotId: String? = null,
)

/** Address and selected map point used to calculate real delivery capacity. */
@Serializable
data class DeliverySlotSearchRequest(
    val inquiryId: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** One server-approved delivery window; clients never manufacture availability. */
@Serializable
data class DeliverySlot(
    val slotId: String,
    val version: Long,
    val date: String,
    val start: String,
    val end: String,
    val travelZoneHours: Int,
    val capacityRemaining: Int,
    val expiresAt: String,
    val state: String,
)

/** Requests an expiring server hold for one slot. */
@Serializable
data class HoldDeliverySlotRequest(
    val inquiryId: String,
    val expectedVersion: Long,
)

/** Confirms which slot/version is held for checkout. */
@Serializable
data class HeldDeliverySlot(
    val cartVersion: Long,
    val slot: DeliverySlot,
)

/** Completes a customer inquiry using the current cart and held delivery slot. */
@Serializable
data class CheckoutRequest(
    val expectedVersion: Long,
    val slotId: String,
    val slotVersion: Long,
    val rentalMonths: Long,
)

/** Booking identity and durable downstream order state. */
@Serializable
data class CustomerBooking(
    val bookingId: String? = null,
    val orderId: String? = null,
    val status: String,
    val errorCode: String? = null,
    val inquiryId: String,
    val slotId: String? = null,
)

/** Username/password registration request whose confirmation is rechecked by the server. */
@Serializable
data class RegistrationRequest(
    val username: String,
    val password: String,
    val passwordConfirmation: String,
)

/** Registration result returned before the app starts the PKCE login. */
@Serializable
data class RegistrationResponse(
    val subjectId: String,
    val username: String,
)

/** Spring Security CSRF metadata used for JSON registration and form login. */
@Serializable
data class CsrfPayload(
    val parameterName: String,
    val headerName: String = "X-CSRF-TOKEN",
    val token: String,
)

/** OAuth token response retained only inside encrypted session storage. */
@Serializable
data class OAuthTokenPayload(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long,
    val refresh_token: String? = null,
    val scope: String? = null,
)

/** RFC 9457 fields that can be safely shown or logged without exposing a response body. */
@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
)
