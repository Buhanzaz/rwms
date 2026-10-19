package dev.buhanzaz.rwms.rentalmanager.network

/** Canonical current-user projection returned by auth-service through the public gateway. */
data class CurrentUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val principalType: String,
    val globalRole: String,
    val rentalAccess: Boolean,
    val warehouseAccessAll: Boolean,
    val warehouseAccesses: List<WarehouseAccessDto> = emptyList(),
)

data class WarehouseAccessDto(
    val warehouseId: String,
    val level: String,
)

/** Canonical warehouse directory item for the single RWMS installation. */
data class WarehouseDto(
    val id: String,
    val version: Long,
    val name: String,
    val city: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timeZone: String,
    val active: Boolean,
    val lifecycleState: String,
    val sortOrder: Int? = null,
    val representative: Boolean,
    val production: Boolean,
    val mainWarehouse: Boolean,
    val representativeParentWarehouseId: String? = null,
)

data class RentalClientDto(
    val id: String,
    val version: Long,
    val type: String,
    val displayName: String,
    val phone: String? = null,
    val contactPerson: String? = null,
    val email: String? = null,
    val responsibleManagerId: String,
    val responsibleManagerDisplayName: String? = null,
    val comment: String? = null,
    val source: String? = null,
    val updatedAt: String,
)

data class RentalClientPageDto(
    val content: List<RentalClientDto>,
    val page: Long,
    val size: Long,
    val totalElements: Long,
    val totalPages: Long,
)

data class CreateRentalClientRequest(
    val clientType: String,
    val displayName: String,
    val phone: String,
    val contactPerson: String? = null,
    val email: String? = null,
    val comment: String? = null,
    val source: String? = null,
)

data class OrderDto(
    val id: String,
    val version: Long,
    val number: String,
    val status: String,
    val client: RentalClientDto,
    val managerId: String,
    val managerDisplayName: String,
    val warehouseId: String? = null,
    val deliveryAddress: String? = null,
    val contactPhone: String? = null,
    val comment: String? = null,
    val desiredDeliveryWindows: List<DesiredDeliveryWindowDto> = emptyList(),
    val unitCount: Long,
    val updatedAt: String,
    val units: List<OrderUnitDto> = emptyList(),
    val permissions: OrderPermissionsDto? = null,
)

data class DesiredDeliveryWindowDto(
    val startDate: String,
    val endDate: String,
)

data class OrderUnitDto(
    val reservationId: String?,
    val added: Boolean,
    val reservationState: String?,
    val unit: OrderRentalItemDto,
    val desiredContents: List<OrderDesiredEquipmentDto>,
    val rentalTerm: OrderRentalTermDto?,
)

data class OrderRentalItemDto(
    val id: String,
    val version: Long,
    val warehouseId: String,
    val number: String,
    val status: String,
    val rentalType: String?,
    val dimensions: String?,
    val finishing: String?,
    val category: String?,
    val characteristics: String?,
    val linoleum: Boolean?,
    val tags: List<String>,
    val contents: List<OrderEquipmentContentDto>,
    val createdAt: String,
    val updatedAt: String,
)

data class OrderEquipmentContentDto(
    val equipmentId: String,
    val equipmentName: String?,
    val quantity: Long,
    val locationKind: String,
)

data class OrderDesiredEquipmentDto(
    val equipmentId: String,
    val equipmentName: String,
    val quantity: Long,
    val reservationState: String,
)

data class OrderRentalTermDto(
    val rentalMonths: Long,
    val shipmentDate: String?,
    val returnDate: String?,
)

data class OrderPermissionsDto(
    val canEdit: Boolean,
    val canReplaceUnits: Boolean,
    val canExtendRentalTerms: Boolean,
    val canViewOtherManagers: Boolean,
)

data class OrderPageDto(
    val content: List<OrderDto>,
    val page: Long,
    val size: Long,
    val totalElements: Long,
    val totalPages: Long,
)

data class CreateOrderRequest(
    val clientId: String,
    val contactPhone: String? = null,
    val comment: String? = null,
)

data class UpdateOrderRequest(
    val expectedVersion: Long,
    val clientId: String,
    val contactPhone: String? = null,
    val comment: String? = null,
)

/** Canonical server-owned payment-window projection; null evidence never means payment succeeded. */
data class OrderPaymentDto(
    val orderId: String,
    val orderVersion: Long,
    val orderStatus: String,
    val state: String?,
    val startedAt: String?,
    val expiresAt: String?,
    val resolvedAt: String?,
    val source: String?,
    val serverTime: String,
    val canConfirm: Boolean,
    val receipt: OrderPaymentReceiptDto?,
)

/** Immutable non-fiscal initial bill with whole-RUB strings that may exceed Long. */
data class OrderPaymentReceiptDto(
    val schemaVersion: Int,
    val orderId: String,
    val orderNumber: String,
    val issuedAt: String,
    val currency: String,
    val deliveryIncluded: Boolean,
    val lines: List<OrderPaymentReceiptLineDto>,
    val totalRubles: String,
)

/** Immutable rental factors or the one agreed delivery charge; never current catalog prices. */
data class OrderPaymentReceiptLineDto(
    val kind: String,
    val rentalItemId: String?,
    val equipmentId: String?,
    val label: String,
    val quantity: String,
    val rentalMonths: Long?,
    val unitPriceRubles: String,
    val amountRubles: String,
    val pricingVersion: Long?,
)

/** Explicit manager acknowledgement fenced to the displayed order version. */
data class ConfirmOrderPaymentRequest(val expectedVersion: Long)

data class ProblemDetailsDto(
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
)
