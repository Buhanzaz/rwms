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
    val avatar: CustomerProfileAvatar? = null,
)

/** Exact current avatar generation exposed through authenticated media-service paths. */
@Serializable
data class CustomerProfileAvatar(
    val mediaId: String,
    val generation: Long,
    val warehouseId: String,
    val thumbnailUrl: String,
    val url: String,
)

/** Replaces mutable profile fields under logistics-service's optimistic version fence. */
@Serializable
data class UpdateCustomerProfileRequest(
    val expectedVersion: Long,
    val firstName: String? = null,
    val lastName: String? = null,
    val companyName: String? = null,
    val phone: String,
    val email: String? = null,
    val additionalInfo: String? = null,
)

/** Establishes the immutable warehouse scope used to authorize this profile's avatar media. */
@Serializable
data class PrepareCustomerProfileAvatarUploadRequest(
    val expectedVersion: Long,
    val warehouseId: String,
)

/** Subject-bound media owner scope returned by logistics before an avatar upload. */
@Serializable
data class CustomerProfileAvatarUploadScope(
    val profileVersion: Long,
    val ownerType: String,
    val ownerId: String,
    val warehouseId: String,
    val context: String,
)

/** Binds one exact READY media generation to the current customer profile. */
@Serializable
data class SetCustomerProfileAvatarRequest(
    val expectedVersion: Long,
    val mediaId: String,
    val generation: Long,
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
    val estimatedDeliveryDates: List<String> = emptyList(),
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

/** One selected cabin's server-owned rental duration. */
@Serializable
data class CustomerCabinRentalTerm(
    val cabinUnitId: String,
    val rentalMonths: Long,
)

/** Replaces every selected cabin's duration under the current cart version fence. */
@Serializable
data class ReplaceCustomerRentalTermsRequest(
    val expectedVersion: Long,
    val terms: List<CustomerCabinRentalTerm>,
)

/** Authoritative per-cabin rental terms and the resulting cart version. */
@Serializable
data class CustomerRentalTerms(
    val version: Long,
    val terms: List<CustomerCabinRentalTerm> = emptyList(),
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
    val rentalTerms: List<CustomerCabinRentalTerm> = emptyList(),
    val deliverySlotId: String? = null,
)

/** Address, map point, and declared per-arrival site capacity used for exact route simulation. */
@Serializable
data class DeliverySlotSearchRequest(
    val inquiryId: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val siteCabinCapacity: Int,
    val privateSiteAccessConfirmed: Boolean,
    val failedTripChargeAcknowledged: Boolean,
)

/** Frozen truck-and-trailer dimensions used by the routing service for one offer. */
@Serializable
data class CustomerRouteProfile(
    val combinationHeightMeters: Double,
    val combinationWidthMeters: Double,
    val combinationLengthMeters: Double,
    val combinationWeightTons: Double,
    val axleLoadTons: Double,
    val axleCount: Int,
)

/** Whether logistics fixes the arrival interval or lets the route planner choose within the day. */
@Serializable
enum class DeliverySlotKind {
    FIXED_WINDOW,
    DURING_DAY,
}

/** One route-proven delivery choice with server-owned price-zone and site-capacity facts. */
@Serializable
data class DeliverySlot(
    val slotId: String,
    val version: Long,
    val date: String,
    val kind: DeliverySlotKind,
    val start: String,
    val end: String,
    val travelZoneHours: Int,
    val capacityRemaining: Int,
    val deliveryPriceRubles: Int? = null,
    val priceZoneId: String? = null,
    val priceIsochroneMinutes: Int?,
    val siteCabinCapacity: Int,
    val roadRouteConfirmed: Boolean,
    val privateSiteAccessConfirmed: Boolean,
    val failedTripChargeAcknowledged: Boolean,
    val routeProfile: CustomerRouteProfile,
    val expiresAt: String,
    val state: String,
)

/**
 * Requests an expiring server hold after the customer confirms site capacity, private-site access,
 * and failed-trip responsibility on the slot step.
 */
@Serializable
data class HoldDeliverySlotRequest(
    val inquiryId: String,
    val expectedVersion: Long,
    val siteCabinCapacity: Int,
    val privateSiteAccessConfirmed: Boolean,
    val failedTripChargeAcknowledged: Boolean,
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
)

/** Cancels one completed booking under the exact authoritative booking version. */
@Serializable
data class CancelCustomerBookingRequest(
    val expectedVersion: Long,
    val changeQuoteId: String,
    val changeQuoteVersion: Long,
    val testPaymentRequested: Boolean = false,
)

/** Recalculates replacement offers from server-owned order contents and address facts. */
@Serializable
data class SearchCustomerBookingRescheduleRequest(val expectedVersion: Long)

/** Atomically swaps one booking to an exact freshly calculated slot/version. */
@Serializable
data class RescheduleCustomerBookingRequest(
    val expectedVersion: Long,
    val slotId: String,
    val slotVersion: Long,
    val changeQuoteId: String,
    val changeQuoteVersion: Long,
    val testPaymentRequested: Boolean = false,
)

/** Exact shipment-line media scope authorized for one delivered cabin. */
@Serializable
data class CustomerShipmentMediaOwner(
    val ownerType: String,
    val documentId: String,
    val lineId: String,
    val warehouseId: String,
    val context: String,
)

/** One normalized signature point retained by logistics rather than as an image. */
@Serializable
data class CustomerSignaturePoint(
    val x: Float,
    val y: Float,
    val elapsedMillis: Long,
)

/** One continuous stroke of the customer's acceptance signature. */
@Serializable
data class CustomerSignatureStroke(val points: List<CustomerSignaturePoint>)

/** Commits one arrived cabin's bounded vector signature. */
@Serializable
data class AcceptCustomerCabinRequest(val strokes: List<CustomerSignatureStroke>)

/** Durable acceptance returned for one arrived cabin. */
@Serializable
data class CustomerCabinAcceptance(
    val acceptanceId: String,
    val version: Long,
    val acceptedAt: String,
    val signaturePointCount: Int,
)

/** Opaque READY media generation attached to a customer problem report. */
@Serializable
data class CustomerProblemMediaReference(
    val mediaId: String,
    val generation: Long,
)

/** Immutable customer problem command for an arrived cabin. */
@Serializable
data class ReportCustomerCabinProblemRequest(
    val category: String,
    val description: String,
    val mediaReferences: List<CustomerProblemMediaReference>,
)

/** Durable before- or after-acceptance problem report. */
@Serializable
data class CustomerCabinProblem(
    val problemId: String,
    val category: String,
    val phase: String,
    val description: String,
    val mediaReferences: List<CustomerProblemMediaReference> = emptyList(),
    val reportedAt: String,
)

/** One cabin in a durable booking, including its arrival and reception state. */
@Serializable
data class CustomerBookingCabin(
    val cabinUnitId: String,
    val accountingNo: String,
    val rentalMonths: Long,
    val deliveryState: String,
    val arrivalEligible: Boolean,
    val mediaOwner: CustomerShipmentMediaOwner? = null,
    val acceptance: CustomerCabinAcceptance? = null,
    val problems: List<CustomerCabinProblem> = emptyList(),
)

/** Booking identity and durable downstream order state. */
@Serializable
data class CustomerBooking(
    val bookingId: String? = null,
    val version: Long = 0,
    val orderId: String? = null,
    val status: String,
    val errorCode: String? = null,
    val inquiryId: String,
    val slotId: String? = null,
    val warehouseId: String,
    val deliveryAddress: String? = null,
    val deliveryDate: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val cancellationFeeRubles: Long? = null,
    val cabins: List<CustomerBookingCabin> = emptyList(),
)

/** Same-origin source upload session issued by media-service. */
@Serializable
data class MediaUploadSession(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String? = null,
)

/** Creates one source upload bound to an exact structured or owner-ID media scope. */
@Serializable
data class CreateCustomerMediaUploadRequest(
    val ownerType: String,
    val ownerId: String? = null,
    val documentId: String? = null,
    val lineId: String? = null,
    val warehouseId: String,
    val context: String,
    val folderId: String,
    val fileName: String,
    val contentType: String,
    val contentLength: Long,
    val checksumSha256: String,
    val sortOrder: Int,
)

/** Immutable object metadata returned after a source PUT. */
@Serializable
data class UploadedMediaObject(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

/** Finalizes the exact object metadata returned by the content PUT. */
@Serializable
data class FinalizeCustomerMediaUploadRequest(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

/** Current media processing state used to wait for a READY report reference. */
@Serializable
data class CustomerMediaAsset(
    val id: String,
    val status: String,
    val generation: Long,
)

/** Bounded media page for one exact shipment-line owner. */
@Serializable
data class CustomerMediaPage(
    val items: List<CustomerMediaAsset> = emptyList(),
    val next: String? = null,
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
    val code: String? = null,
)
