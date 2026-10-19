package dev.buhanzaz.rwms.rentalmanager.data

import dev.buhanzaz.rwms.rentalmanager.network.PublishRentalPresentationRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerApi
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationCabinDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationGroupDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationGroupRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationMode
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationPhotoDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationState
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import retrofit2.HttpException

internal interface RentalPresentationDataSource {
    suspend fun get(inquiryId: String): RentalPresentationDto?

    suspend fun publish(
        inquiryId: String,
        idempotencyKey: UUID,
        warehouseId: String,
        selectedRentalItemIds: Collection<String>,
        groups: List<RentalPresentationGroupRequest>,
    ): RentalPresentationDto

    fun userMessage(failure: Throwable): String
}

/**
 * Validates the manager-facing client-presentation boundary. The caller remains responsible for
 * selection grouping and for persisting one stable idempotency key across a retried publication.
 */
class RentalPresentationRepository(
    private val api: RentalManagerApi,
    private val problemMessage: (HttpException) -> String,
) : RentalPresentationDataSource {
    constructor(backend: RentalManagerBackend) : this(
        api = backend.api,
        problemMessage = backend::problemMessage,
    )

    override suspend fun get(inquiryId: String): RentalPresentationDto? {
        val canonicalInquiryId = canonicalUuid(inquiryId, "inquiry id")
        val response = api.clientPresentation(canonicalInquiryId)
        if (response.code() == 404) return null
        if (!response.isSuccessful) throw HttpException(response)
        val presentation = response.body()
            ?: throw IllegalStateException("Client presentation response has no body")
        validatePresentation(presentation)
        require(presentation.inquiryId == canonicalInquiryId) {
            "Client presentation inquiry does not match"
        }
        return presentation
    }

    override suspend fun publish(
        inquiryId: String,
        idempotencyKey: UUID,
        warehouseId: String,
        selectedRentalItemIds: Collection<String>,
        groups: List<RentalPresentationGroupRequest>,
    ): RentalPresentationDto {
        val canonicalInquiryId = canonicalUuid(inquiryId, "inquiry id")
        val canonicalWarehouseId = canonicalUuid(warehouseId, "warehouse id")
        val canonicalSelectedIds = selectedRentalItemIds.map {
            canonicalUuid(it, "selected rental item id")
        }
        require(canonicalSelectedIds.isNotEmpty()) { "Presentation selection must not be empty" }
        require(canonicalSelectedIds.size <= MAX_PRESENTATION_ITEMS) {
            "Presentation selection is too large"
        }
        require(canonicalSelectedIds.toSet().size == canonicalSelectedIds.size) {
            "Selected rental item ids must be unique"
        }
        val canonicalGroups = canonicalGroups(groups)
        val groupedIds = canonicalGroups.flatMap(RentalPresentationGroupRequest::rentalItemIds)
        require(groupedIds.toSet() == canonicalSelectedIds.toSet() &&
            groupedIds.size == canonicalSelectedIds.size
        ) {
            "Presentation groups must partition the selected rental item ids exactly once"
        }

        val presentation = api.publishClientPresentation(
            inquiryId = canonicalInquiryId,
            idempotencyKey = idempotencyKey.toString(),
            request = PublishRentalPresentationRequest(
                warehouseId = canonicalWarehouseId,
                groups = canonicalGroups,
            ),
        )
        validatePresentation(presentation)
        require(presentation.inquiryId == canonicalInquiryId) {
            "Client presentation inquiry does not match"
        }
        require(presentation.warehouseId == canonicalWarehouseId) {
            "Client presentation warehouse does not match"
        }
        require(presentation.mode == RentalPresentationMode.NORMAL) {
            "Manager presentation publication returned a non-normal mode"
        }
        require(
            presentation.groups.map(RentalPresentationGroupDto::key) ==
                canonicalGroups.map(RentalPresentationGroupRequest::key),
        ) { "Client presentation group order does not match" }
        presentation.groups.zip(canonicalGroups).forEach { (actual, expected) ->
            require(actual.label == expected.label) { "Client presentation group label does not match" }
            require(actual.cabins.map(RentalPresentationCabinDto::id) == expected.rentalItemIds) {
                "Client presentation group cabins do not match"
            }
        }
        return presentation
    }

    override fun userMessage(failure: Throwable): String = when (failure) {
        is CancellationException -> throw failure
        is HttpException -> problemMessage(failure)
        else -> "Не удалось подготовить предложение клиенту. Проверьте подключение и повторите попытку."
    }

    private fun canonicalGroups(
        groups: List<RentalPresentationGroupRequest>,
    ): List<RentalPresentationGroupRequest> {
        require(groups.size in 1..MAX_PRESENTATION_GROUPS) {
            "Presentation must contain from one to five groups"
        }
        val keys = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        return groups.map { group ->
            val key = group.key.trim()
            val label = group.label.trim()
            require(key.isNotEmpty() && key.length <= MAX_GROUP_KEY_LENGTH) {
                "Presentation group key is invalid"
            }
            require(label.isNotEmpty() && label.length <= MAX_GROUP_LABEL_LENGTH) {
                "Presentation group label is invalid"
            }
            require(keys.add(key)) { "Presentation group keys must be unique" }
            require(group.rentalItemIds.size in 1..MAX_GROUP_ITEMS) {
                "Presentation group must contain from one to thirty cabins"
            }
            val cabinIds = group.rentalItemIds.map { id ->
                canonicalUuid(id, "presentation cabin id").also { canonicalId ->
                    require(ids.add(canonicalId)) {
                        "A selected rental item can occur in exactly one presentation group"
                    }
                }
            }
            RentalPresentationGroupRequest(key, label, cabinIds)
        }.also {
            require(ids.size <= MAX_PRESENTATION_ITEMS) { "Presentation contains too many cabins" }
        }
    }

    private fun validatePresentation(presentation: RentalPresentationDto) {
        val presentationId = canonicalUuid(presentation.id, "presentation id")
        require(presentation.version >= 0) { "Client presentation version is invalid" }
        require(presentation.revision >= 1) { "Client presentation revision is invalid" }
        canonicalUuid(presentation.inquiryId, "presentation inquiry id")
        canonicalUuid(presentation.warehouseId, "presentation warehouse id")
        presentation.bookedOrderId?.let { canonicalUuid(it, "booked order id") }

        val expiresAt = requireOffsetDateTime(presentation.expiresAt, "presentation expiry")
        val viewUntil = requireOffsetDateTime(presentation.viewUntil, "presentation view deadline")
        require(!viewUntil.isBefore(expiresAt)) {
            "Client presentation view deadline precedes its expiry"
        }
        if (presentation.canConfirm) {
            require(presentation.state == RentalPresentationState.ACTIVE) {
                "Only an active client presentation can be confirmed"
            }
        }
        val token = requirePresentationPath(
            path = presentation.publicPath,
            presentationId = presentationId,
            revision = presentation.revision,
        )

        validateMode(presentation)
        presentation.desiredDeliveryWindows.forEach { window ->
            val start = requireLocalDate(window.startDate, "desired delivery start date")
            val end = requireLocalDate(window.endDate, "desired delivery end date")
            require(!end.isBefore(start)) { "Desired delivery window is reversed" }
        }
        require(presentation.desiredDeliveryWindows.size <= MAX_DESIRED_WINDOWS) {
            "Client presentation has too many desired delivery windows"
        }

        val availabilityIds = mutableSetOf<String>()
        presentation.equipmentAvailability.forEach { availability ->
            val equipmentId = canonicalUuid(availability.equipmentId, "available equipment id")
            require(availabilityIds.add(equipmentId)) {
                "Client presentation equipment availability contains duplicate equipment"
            }
            require(availability.equipmentName.isNotBlank() &&
                availability.equipmentName.length <= MAX_EQUIPMENT_NAME_LENGTH
            ) { "Client presentation equipment name is invalid" }
            require(availability.availableQuantity >= 0) {
                "Client presentation equipment availability is invalid"
            }
            require(availability.maximumPerCabin == null || availability.maximumPerCabin >= 1) {
                "Client presentation per-cabin equipment limit is invalid"
            }
        }
        validateResponseGroups(presentation.groups, token)
    }

    private fun validateMode(presentation: RentalPresentationDto) {
        val replacementIds = presentation.replacementUnitIds.map {
            canonicalUuid(it, "replacement rental item id")
        }
        require(replacementIds.toSet().size == replacementIds.size) {
            "Replacement rental item ids must be unique"
        }
        require(replacementIds.size <= MAX_PRESENTATION_ITEMS) {
            "Client presentation has too many replacement units"
        }
        when (presentation.mode) {
            RentalPresentationMode.NORMAL -> {
                require(replacementIds.isEmpty() && presentation.requiredSelectionCount == null) {
                    "Normal client presentation contains replacement constraints"
                }
                require(presentation.requiresDesiredDeliveryWindows) {
                    "Normal client presentation must request delivery dates"
                }
                require(presentation.desiredDeliveryWindows.isEmpty()) {
                    "Normal client presentation cannot prescribe delivery dates"
                }
            }
            RentalPresentationMode.REPLACEMENT -> {
                require(replacementIds.isNotEmpty()) {
                    "Replacement client presentation has no replacement targets"
                }
                require(presentation.requiredSelectionCount == replacementIds.size) {
                    "Replacement selection count does not match its targets"
                }
                require(!presentation.requiresDesiredDeliveryWindows) {
                    "Replacement client presentation cannot request new delivery dates"
                }
            }
        }
    }

    private fun validateResponseGroups(
        groups: List<RentalPresentationGroupDto>,
        token: String,
    ) {
        require(groups.size in 1..MAX_PRESENTATION_GROUPS) {
            "Client presentation group count is invalid"
        }
        val keys = mutableSetOf<String>()
        val cabinIds = mutableSetOf<String>()
        groups.forEach { group ->
            require(group.key.isNotBlank() && group.key.length <= MAX_GROUP_KEY_LENGTH) {
                "Client presentation group key is invalid"
            }
            require(keys.add(group.key)) { "Client presentation group keys are not unique" }
            require(group.label.isNotBlank() && group.label.length <= MAX_GROUP_LABEL_LENGTH) {
                "Client presentation group label is invalid"
            }
            require(group.cabins.size in 1..MAX_GROUP_ITEMS) {
                "Client presentation group cabin count is invalid"
            }
            group.cabins.forEach { cabin ->
                val cabinId = canonicalUuid(cabin.id, "presentation cabin id")
                require(cabinIds.add(cabinId)) {
                    "A cabin occurs more than once in the client presentation"
                }
                validateCabin(cabin, token, cabinId)
            }
        }
        require(cabinIds.size <= MAX_PRESENTATION_ITEMS) {
            "Client presentation contains too many cabins"
        }
    }

    private fun validateCabin(cabin: RentalPresentationCabinDto, token: String, cabinId: String) {
        require(
            (cabin.pricingVersion == null) == (cabin.monthlyPriceRubles == null) &&
                (cabin.pricingVersion == null || cabin.pricingVersion >= 0),
        ) {
            "Client presentation price snapshot is invalid"
        }
        cabin.currentContents.forEach { content ->
            canonicalUuid(content.equipmentId, "cabin equipment id")
            require(content.quantity >= 0) { "Cabin equipment quantity is invalid" }
            require(content.locationKind.isNotBlank()) { "Cabin equipment location is invalid" }
        }
        val mediaIds = mutableSetOf<String>()
        cabin.photos.forEach { photo ->
            val mediaId = canonicalUuid(photo.mediaId, "presentation media id")
            require(mediaIds.add(mediaId)) { "Presentation cabin contains duplicate media" }
            validatePhoto(photo, token, cabinId, mediaId)
        }
    }

    private fun validatePhoto(
        photo: RentalPresentationPhotoDto,
        token: String,
        cabinId: String,
        mediaId: String,
    ) {
        require(photo.generation >= 1) { "Presentation photo generation is invalid" }
        require(photo.sortOrder >= 0) { "Presentation photo sort order is invalid" }
        require(photo.availableVariants.isNotEmpty() &&
            photo.availableVariants.toSet().size == photo.availableVariants.size &&
            photo.availableVariants.all(String::isNotBlank)
        ) { "Presentation photo variants are invalid" }
        val base = "/api/logistics/public/v1/client-presentations/$token/media/" +
            "$cabinId/$mediaId/${photo.generation}/"
        validatePhotoPath(photo.thumbnailUrl, base, photo.availableVariants)
        validatePhotoPath(photo.contentUrl, base, photo.availableVariants)
    }

    private fun validatePhotoPath(path: String, base: String, variants: List<String>) {
        require(path.startsWith(base)) { "Presentation photo path is not local to its offer" }
        val variant = path.removePrefix(base)
        require(variant in variants && variant in ALLOWED_PHOTO_VARIANTS) {
            "Presentation photo path has an invalid variant"
        }
    }

    private fun requirePresentationPath(
        path: String,
        presentationId: String,
        revision: Long,
    ): String {
        require(path.startsWith(PRESENTATION_PATH_PREFIX) &&
            path.length > PRESENTATION_PATH_PREFIX.length
        ) { "Client presentation publicPath must be a local /offer/{token} path" }
        val token = path.removePrefix(PRESENTATION_PATH_PREFIX)
        require('/' !in token && '?' !in token && '#' !in token) {
            "Client presentation publicPath must contain exactly one local token"
        }
        val separator = token.indexOf('.')
        require(separator > 0 && separator == token.lastIndexOf('.')) {
            "Client presentation token has an invalid shape"
        }
        val encoded = token.substring(0, separator)
        val signature = token.substring(separator + 1)
        require(ENCODED_TOKEN_PATTERN.matches(encoded)) {
            "Client presentation token payload has an invalid shape"
        }
        require(SIGNATURE_PATTERN.matches(signature)) {
            "Client presentation token signature has an invalid shape"
        }
        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrElse { throw IllegalStateException("Client presentation token payload is invalid") }
        val payloadSeparator = payload.lastIndexOf(':')
        require(payloadSeparator > 0 && payloadSeparator == payload.indexOf(':')) {
            "Client presentation token payload has an invalid shape"
        }
        val tokenPresentationId = canonicalUuid(
            payload.substring(0, payloadSeparator),
            "presentation token id",
        )
        val tokenRevision = payload.substring(payloadSeparator + 1).toLongOrNull()
        require(tokenPresentationId == presentationId && tokenRevision == revision) {
            "Client presentation token is not bound to its response revision"
        }
        return token
    }

    private fun canonicalUuid(value: String, field: String): String {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalStateException("Invalid $field") }
        require(parsed.toString().equals(value, ignoreCase = true)) { "Invalid $field" }
        return parsed.toString()
    }

    private fun requireOffsetDateTime(value: String, field: String): OffsetDateTime =
        runCatching { OffsetDateTime.parse(value) }
            .getOrElse { throw IllegalStateException("Invalid $field") }

    private fun requireLocalDate(value: String, field: String): LocalDate =
        runCatching { LocalDate.parse(value) }
            .getOrElse { throw IllegalStateException("Invalid $field") }

    private companion object {
        const val MAX_PRESENTATION_GROUPS = 5
        const val MAX_GROUP_ITEMS = 30
        const val MAX_PRESENTATION_ITEMS = 100
        const val MAX_GROUP_KEY_LENGTH = 128
        const val MAX_GROUP_LABEL_LENGTH = 255
        const val MAX_EQUIPMENT_NAME_LENGTH = 512
        const val MAX_DESIRED_WINDOWS = 4
        const val PRESENTATION_PATH_PREFIX = "/offer/"
        val ENCODED_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")
        val SIGNATURE_PATTERN = Regex("[0-9a-f]{64}")
        val ALLOWED_PHOTO_VARIANTS = setOf("SMALL", "LARGE")
    }
}
