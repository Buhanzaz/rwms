package dev.buhanzaz.rwms.rentalmanager.network

/** The only writable NORMAL-presentation fields accepted by logistics-service. */
data class PublishRentalPresentationRequest(
    val warehouseId: String,
    val groups: List<RentalPresentationGroupRequest>,
)

data class RentalPresentationGroupRequest(
    val key: String,
    val label: String,
    val rentalItemIds: List<String>,
)

enum class RentalPresentationState {
    ACTIVE,
    BOOKING_PENDING,
    BOOKED,
    REVOKED,
}

enum class RentalPresentationMode {
    NORMAL,
    REPLACEMENT,
}

/** Complete manager projection of one version-fenced public client presentation. */
data class RentalPresentationDto(
    val id: String,
    val version: Long,
    val revision: Long,
    val inquiryId: String,
    val warehouseId: String,
    val state: RentalPresentationState,
    val expiresAt: String,
    val viewUntil: String,
    val canConfirm: Boolean,
    val publicPath: String,
    val bookedOrderId: String?,
    val mode: RentalPresentationMode,
    val replacementUnitIds: List<String>,
    val requiredSelectionCount: Int?,
    val requiresDesiredDeliveryWindows: Boolean,
    val desiredDeliveryWindows: List<DesiredDeliveryWindowDto>,
    val equipmentAvailability: List<RentalPresentationEquipmentAvailabilityDto>,
    val groups: List<RentalPresentationGroupDto>,
)

data class RentalPresentationGroupDto(
    val key: String,
    val label: String,
    val cabins: List<RentalPresentationCabinDto>,
)

data class RentalPresentationCabinDto(
    val id: String,
    val number: String,
    val pricingVersion: Long?,
    val monthlyPriceRubles: RentalMonthlyPrice?,
    val rentalType: String?,
    val dimensions: String?,
    val finishing: String?,
    val category: String?,
    val characteristics: String?,
    val linoleum: Boolean?,
    val passport: Map<String, Any?>,
    val tags: List<String>,
    val currentContents: List<RentalPresentationEquipmentContentDto>,
    val photos: List<RentalPresentationPhotoDto>,
)

data class RentalPresentationEquipmentContentDto(
    val equipmentId: String,
    val equipmentName: String?,
    val quantity: Long,
    val locationKind: String,
)

data class RentalPresentationPhotoDto(
    val mediaId: String,
    val generation: Long,
    val sortOrder: Int,
    val availableVariants: List<String>,
    val thumbnailUrl: String,
    val contentUrl: String,
)

data class RentalPresentationEquipmentAvailabilityDto(
    val equipmentId: String,
    val equipmentName: String,
    val availableQuantity: Long,
    val maximumPerCabin: Int?,
)
