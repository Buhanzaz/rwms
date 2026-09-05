package dev.buhanzaz.rwms.client.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** Cohesive client-side adapter for the server-owned customer booking workflow. */
@Singleton
class CustomerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: CustomerApi,
    private val json: Json,
    private val workflowStore: CustomerWorkflowStore,
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

    /** Updates mutable customer profile fields while preserving its immutable entity kind. */
    suspend fun updateProfile(profile: CustomerProfile): CustomerProfile {
        val expectedVersion = profile.version
            ?: throw CustomerApiException(409, "Версия профиля неизвестна. Обновите экран")
        return call {
            api.updateProfile(
                UpdateCustomerProfileRequest(
                    expectedVersion = expectedVersion,
                    firstName = profile.firstName,
                    lastName = profile.lastName,
                    companyName = profile.companyName,
                    phone = profile.phone,
                    email = profile.email,
                    additionalInfo = profile.additionalInfo,
                ),
            )
        }
    }

    /**
     * Uploads a locally cropped JPEG into the exact subject-bound profile owner and binds its
     * READY generation under the profile version returned by logistics-service. Copies the bounded
     * bytes before suspending so the upload body and its idempotency checksum cannot diverge.
     */
    suspend fun uploadProfileAvatar(
        profile: CustomerProfile,
        warehouseId: String,
        jpegBytes: ByteArray,
    ): CustomerProfile {
        if (jpegBytes.isEmpty()) throw CustomerApiException(422, "Изображение пустое")
        if (jpegBytes.size > MAX_PROFILE_AVATAR_BYTES) {
            throw CustomerApiException(422, "Аватар превышает 8 МБ")
        }
        val source = jpegBytes.copyOf()
        val expectedVersion = profile.version
            ?: throw CustomerApiException(409, "Версия профиля неизвестна. Обновите экран")
        val profileId = profile.id
            ?: throw CustomerApiException(409, "Профиль ещё не создан")
        val scope = call {
            api.prepareProfileAvatarUpload(
                PrepareCustomerProfileAvatarUploadRequest(expectedVersion, warehouseId),
            )
        }
        if (
            scope.ownerType != PROFILE_AVATAR_OWNER_TYPE ||
            scope.ownerId != profileId ||
            scope.warehouseId != warehouseId ||
            scope.context != PROFILE_AVATAR_CONTEXT
        ) {
            throw CustomerApiException(503, "Сервис вернул неверную область загрузки аватара")
        }
        val ready = uploadReadyProfileAvatar(scope, source)
        return call {
            api.setProfileAvatar(
                SetCustomerProfileAvatarRequest(
                    expectedVersion = scope.profileVersion,
                    mediaId = ready.id,
                    generation = ready.generation,
                ),
            )
        }
    }

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

    /** Replaces every selected cabin's rental duration under one optimistic fence. */
    suspend fun updateRentalTerms(
        inquiryId: String,
        version: Long,
        terms: Map<String, Long>,
    ): CustomerRentalTerms = call {
        api.updateRentalTerms(
            inquiryId,
            ReplaceCustomerRentalTermsRequest(
                expectedVersion = version,
                terms = terms.toSortedMap().map { (cabinId, months) ->
                    CustomerCabinRentalTerm(cabinId, months)
                },
            ),
        )
    }

    /** Reloads the authoritative cart after a mutation or conflict. */
    suspend fun cart(inquiryId: String): CustomerCart = call { api.cart(inquiryId) }

    /** Returns only delivery slots that the server planner proves feasible. */
    suspend fun searchSlots(request: DeliverySlotSearchRequest): List<DeliverySlot> =
        call { api.searchSlots(request) }

    /** Places an expiring optimistic hold with the customer's current access attestations. */
    suspend fun holdSlot(
        slotId: String,
        slotVersion: Long,
        inquiryId: String,
        cartVersion: Long,
        siteCabinCapacity: Int,
        privateSiteAccessConfirmed: Boolean,
        failedTripChargeAcknowledged: Boolean,
    ): HeldDeliverySlot = call {
        api.holdSlot(
            slotId,
            slotVersion,
            HoldDeliverySlotRequest(
                inquiryId = inquiryId,
                expectedVersion = cartVersion,
                siteCabinCapacity = siteCabinCapacity,
                privateSiteAccessConfirmed = privateSiteAccessConfirmed,
                failedTripChargeAcknowledged = failedTripChargeAcknowledged,
            ),
        )
    }

    /** Creates the durable booking/order from the current selection and held slot. */
    suspend fun checkout(inquiryId: String, request: CheckoutRequest): CustomerBooking =
        idempotent(
            "checkout:$inquiryId:${request.expectedVersion}:${request.slotId}:${request.slotVersion}",
        ) { key -> api.checkout(inquiryId, key, request) }

    /** Lists real customer bookings independently of any logistics simulator scenario. */
    suspend fun bookings(): List<CustomerBooking> = call { api.bookings() }

    /** Reads only the bill belonging to the selected server booking/order. */
    suspend fun payment(booking: CustomerBooking): CustomerOrderPayment = call {
        api.payment(requireNotNull(booking.bookingId)).validated(requireNotNull(booking.orderId))
    }

    /** Reuses a durable key after an unknown response; never silently pays a newer version. */
    suspend fun confirmTestPayment(
        booking: CustomerBooking,
        payment: CustomerOrderPayment,
    ): CustomerOrderPayment {
        val bookingId = requireNotNull(booking.bookingId)
        val orderId = requireNotNull(booking.orderId)
        payment.validated(orderId)
        if (!payment.canConfirm) throw CustomerApiException(409, "Оплата недоступна. Обновите счёт")
        return durableIdempotent(
            operation = "initial-payment:$bookingId:${payment.orderVersion}",
            reconcile = {
                api.payment(bookingId).validated(orderId).takeIf {
                    it.state == "CONFIRMED" && it.receipt == payment.receipt
                }
            },
        ) { key ->
            api.confirmTestPayment(bookingId, key, ConfirmCustomerPaymentRequest(payment.orderVersion))
                .validated(orderId)
        }
    }

    /** Fetches unread messages from the server; an empty list is never a transport fallback. */
    suspend fun notifications(): List<CustomerNotification> = call { api.notifications() }

    /** Idempotently acknowledges exactly the selected owned inbox message. */
    suspend fun readNotification(id: String): CustomerNotification = call { api.readNotification(id) }

    /** Creates an exact owner quote without changing the booking or confirming any payment. */
    suspend fun createBookingChangeQuote(
        booking: CustomerBooking,
        operation: BookingChangeOperation,
        slot: DeliverySlot? = null,
    ): CustomerBookingChangeQuote {
        val bookingId = booking.bookingId
            ?: throw CustomerApiException(409, "Заказ ещё не готов к изменению")
        requireBookingVersion(booking)
        if ((operation == BookingChangeOperation.RESCHEDULE) != (slot != null)) {
            throw CustomerApiException(422, "Выберите новое время доставки")
        }
        val request = CreateBookingChangeQuoteRequest(booking.version, operation, slot?.slotId, slot?.version)
        return durableIdempotent(
            operation = "booking-change-quote:$bookingId:${booking.version}:${operation.name}:${slot?.slotId}:${slot?.version}",
            reconcile = { null },
        ) { key ->
            api.createBookingChangeQuote(bookingId, key, request).validated().also { quote ->
                requireChangeQuote(booking, quote, operation, slot?.slotId, slot?.version)
                workflowStore.rememberBookingChange(CustomerBookingChangeReference(bookingId, quote.quoteId))
            }
        }
    }

    /** Reads only the exact owner quote; booking status is never used as payment evidence. */
    suspend fun bookingChangeQuote(bookingId: String, quoteId: String): CustomerBookingChangeQuote = call {
        exactBookingChangeQuote(bookingId, quoteId).also { quote ->
            if (quote.applicationState != BookingChangeApplicationState.OFFERED) {
                workflowStore.bookingChangeReferences().firstOrNull { it.quoteId == quoteId }
                    ?.commandFingerprint?.let { workflowStore.completeIdempotentOperations(it) }
            }
        }
    }

    /** Applies quoted cancellation and verifies its exact outcome before clearing the durable key. */
    suspend fun cancelBooking(
        booking: CustomerBooking,
        quote: CustomerBookingChangeQuote,
        testPaymentRequested: Boolean,
    ): CustomerBookingChangeResult {
        val bookingId = booking.bookingId
            ?: throw CustomerApiException(409, "Заказ ещё не готов к отмене")
        requireBookingVersion(booking)
        requireChangeQuote(booking, quote, BookingChangeOperation.CANCEL)
        val operation = "booking-cancel:$bookingId:${booking.version}:${quote.quoteId}:${quote.version}:$testPaymentRequested"
        return quotedBookingMutation(operation, quote) { key ->
            api.cancelBooking(
                bookingId,
                key,
                CancelCustomerBookingRequest(booking.version, quote.quoteId, quote.version, testPaymentRequested),
            )
        }
    }

    /** Retains command identity until exact APPLIED/APPLYING owner state proves the POST outcome. */
    private suspend fun quotedBookingMutation(
        operation: String,
        quote: CustomerBookingChangeQuote,
        command: suspend (String) -> CustomerBooking,
    ): CustomerBookingChangeResult = durableIdempotent(
        operation = operation,
        reconcile = {
            exactBookingChangeQuote(quote.bookingId, quote.quoteId)
                .takeIf { it.applicationState != BookingChangeApplicationState.OFFERED }
                ?.let { CustomerBookingChangeResult(null, it) }
        },
    ) { key ->
        workflowStore.rememberBookingChange(CustomerBookingChangeReference(quote.bookingId, quote.quoteId, operation))
        val booking = command(key)
        val confirmed = exactBookingChangeQuote(quote.bookingId, quote.quoteId)
        if (confirmed.applicationState == BookingChangeApplicationState.OFFERED) {
            throw CustomerApiException(502, "Изменение ещё не подтверждено. Обновите его статус.")
        }
        CustomerBookingChangeResult(booking, confirmed)
    }

    private suspend fun exactBookingChangeQuote(bookingId: String, quoteId: String): CustomerBookingChangeQuote =
        api.bookingChangeQuote(bookingId, quoteId).validated().also { quote ->
            if (quote.bookingId != bookingId || quote.quoteId != quoteId) {
                throw CustomerApiException(502, "Сервис вернул условия другого изменения. Обновите заказ.")
            }
        }

    private fun requireChangeQuote(
        booking: CustomerBooking,
        quote: CustomerBookingChangeQuote,
        operation: BookingChangeOperation,
        slotId: String? = null,
        slotVersion: Long? = null,
    ) {
        quote.validated()
        if (quote.bookingId != booking.bookingId || quote.bookingVersion != booking.version ||
            quote.oldSlotId != booking.slotId || quote.operation != operation ||
            quote.slotId != slotId || quote.slotVersion != slotVersion
        ) {
            throw CustomerApiException(409, "Условия изменения устарели. Рассчитайте их заново.", "CUSTOMER_CHANGE_QUOTE_STALE")
        }
    }

    /** Recalculates replacement offers using only the exact server-owned booking version. */
    suspend fun searchBookingRescheduleSlots(booking: CustomerBooking): List<DeliverySlot> {
        val bookingId = booking.bookingId
            ?: throw CustomerApiException(409, "Заказ ещё не готов к переносу")
        requireBookingVersion(booking)
        return call {
            api.searchBookingRescheduleSlots(
                bookingId,
                SearchCustomerBookingRescheduleRequest(booking.version),
            )
        }
    }

    /** Applies the exact quoted slot identity, including after process death, without fabricating an offer. */
    suspend fun rescheduleBooking(
        booking: CustomerBooking,
        quote: CustomerBookingChangeQuote,
        testPaymentRequested: Boolean,
    ): CustomerBookingChangeResult {
        val bookingId = booking.bookingId
            ?: throw CustomerApiException(409, "Заказ ещё не готов к переносу")
        requireBookingVersion(booking)
        requireChangeQuote(booking, quote, BookingChangeOperation.RESCHEDULE, quote.slotId, quote.slotVersion)
        val slotId = requireNotNull(quote.slotId)
        val slotVersion = requireNotNull(quote.slotVersion)
        val operation =
            "booking-reschedule:$bookingId:${booking.version}:$slotId:$slotVersion:" +
                "${quote.quoteId}:${quote.version}:$testPaymentRequested"
        return quotedBookingMutation(operation, quote) { key ->
            api.rescheduleBooking(
                bookingId,
                key,
                RescheduleCustomerBookingRequest(
                    expectedVersion = booking.version,
                    slotId = slotId,
                    slotVersion = slotVersion,
                    changeQuoteId = quote.quoteId,
                    changeQuoteVersion = quote.version,
                    testPaymentRequested = testPaymentRequested,
                ),
            )
        }
    }

    /** Accepts one arrived cabin with a process-stable idempotency key. */
    suspend fun acceptCabin(
        bookingId: String,
        cabinId: String,
        strokes: List<CustomerSignatureStroke>,
    ): CustomerCabinAcceptance {
        val fingerprint = json.encodeToString(AcceptCustomerCabinRequest(strokes)).sha256()
        val operationPrefix = "accept:$bookingId:$cabinId:"
        return durableIdempotent(
            operation = "$operationPrefix$fingerprint",
            complete = { workflowStore.completeIdempotentOperations(operationPrefix) },
            reconcile = { authoritativeCabin(bookingId, cabinId)?.acceptance },
        ) { key -> api.acceptCabin(bookingId, cabinId, key, AcceptCustomerCabinRequest(strokes)) }
    }

    /** Uploads customer evidence, waits for READY generations, and commits one immutable report. */
    suspend fun reportProblem(
        bookingId: String,
        cabinId: String,
        owner: CustomerShipmentMediaOwner,
        category: String,
        description: String,
        evidence: List<CustomerEvidenceFile>,
    ): CustomerCabinProblem {
        require(evidence.size <= 20)
        val folderId = UUID.nameUUIDFromBytes(
            "${owner.documentId}:${owner.lineId}:${evidence.joinToString { it.localId }}".toByteArray(),
        ).toString()
        val references = evidence.mapIndexed { index, source ->
            uploadReadyEvidence(owner, folderId, source, index)
        }
        val request = ReportCustomerCabinProblemRequest(category, description.trim(), references)
        val operation = "problem:$bookingId:$cabinId:${json.encodeToString(request).sha256()}"
        return durableIdempotent(
            operation = operation,
            reconcile = {
                authoritativeCabin(bookingId, cabinId)?.problems?.firstOrNull { problem ->
                    problem.category == request.category &&
                        problem.description == request.description &&
                        problem.mediaReferences == request.mediaReferences
                }
            },
        ) { key ->
            api.reportProblem(
                bookingId,
                cabinId,
                key,
                request,
            )
        }
    }

    private suspend fun uploadReadyEvidence(
        owner: CustomerShipmentMediaOwner,
        folderId: String,
        source: CustomerEvidenceFile,
        sortOrder: Int,
    ): CustomerProblemMediaReference {
        if (!source.file.isFile || source.file.length() !in 1..MAX_CUSTOMER_EVIDENCE_BYTES) {
            throw CustomerApiException(422, "Фото или видео пустое либо превышает 200 МБ")
        }
        val checksum = source.file.sha256()
        val logicalKey = UUID.nameUUIDFromBytes(
            "${owner.documentId}:${owner.lineId}:${source.localId}:$checksum".toByteArray(),
        ).toString()
        val session = call {
            api.createMediaUpload(
                logicalKey,
                CreateCustomerMediaUploadRequest(
                    ownerType = owner.ownerType,
                    documentId = owner.documentId,
                    lineId = owner.lineId,
                    warehouseId = owner.warehouseId,
                    context = owner.context,
                    folderId = folderId,
                    fileName = source.fileName.take(512),
                    contentType = source.contentType,
                    contentLength = source.file.length(),
                    checksumSha256 = checksum,
                    sortOrder = sortOrder,
                ),
            )
        }
        val path = session.contentUploadUrl
            ?: throw CustomerApiException(503, "Медиа-сервис не выдал путь загрузки")
        val uploaded = call {
            api.uploadMediaContent(path, logicalKey, source.file.asRequestBody(source.contentType.toMediaType()))
        }
        var asset = call {
            api.finalizeMediaUpload(
                session.uploadSessionId,
                logicalKey,
                FinalizeCustomerMediaUploadRequest(
                    uploaded.objectVersionId,
                    uploaded.etag,
                    uploaded.checksumSha256,
                ),
            )
        }
        repeat(45) {
            if (asset.status == "READY") {
                return CustomerProblemMediaReference(asset.id, asset.generation)
            }
            if (asset.status == "FAILED") {
                throw CustomerApiException(422, "Медиа не удалось обработать")
            }
            delay(1_000)
            asset = call {
                api.mediaAssets(
                    ownerType = owner.ownerType,
                    documentId = owner.documentId,
                    lineId = owner.lineId,
                    warehouseId = owner.warehouseId,
                    context = owner.context,
                ).items.firstOrNull { it.id == session.mediaId }
                    ?: throw CustomerApiException(404, "Загруженное медиа не найдено")
            }
        }
        throw CustomerApiException(503, "Медиа ещё обрабатывается. Повторите отправку позже")
    }

    private suspend fun uploadReadyProfileAvatar(
        scope: CustomerProfileAvatarUploadScope,
        source: ByteArray,
    ): CustomerMediaAsset {
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(source)
            .joinToString("") { byte -> "%02x".format(byte) }
        val logicalKey = UUID.nameUUIDFromBytes(
            "${scope.ownerId}:profile-avatar:$checksum".toByteArray(),
        ).toString()
        val folderId = UUID.nameUUIDFromBytes("profile-avatar:${scope.ownerId}".toByteArray()).toString()
        val session = call {
            api.createMediaUpload(
                logicalKey,
                CreateCustomerMediaUploadRequest(
                    ownerType = scope.ownerType,
                    ownerId = scope.ownerId,
                    warehouseId = scope.warehouseId,
                    context = scope.context,
                    folderId = folderId,
                    fileName = "avatar.jpg",
                    contentType = "image/jpeg",
                    contentLength = source.size.toLong(),
                    checksumSha256 = checksum,
                    sortOrder = 0,
                ),
            )
        }
        val path = session.contentUploadUrl
            ?: throw CustomerApiException(503, "Медиа-сервис не выдал путь загрузки")
        val uploaded = call {
            api.uploadMediaContent(path, logicalKey, source.toRequestBody("image/jpeg".toMediaType()))
        }
        var asset = call {
            api.finalizeMediaUpload(
                session.uploadSessionId,
                logicalKey,
                FinalizeCustomerMediaUploadRequest(
                    objectVersionId = uploaded.objectVersionId,
                    etag = uploaded.etag,
                    checksumSha256 = uploaded.checksumSha256,
                ),
            )
        }
        repeat(PROFILE_AVATAR_READY_POLL_ATTEMPTS) {
            if (asset.status == "READY") return asset
            if (asset.status == "FAILED") {
                throw CustomerApiException(422, "Аватар не удалось обработать")
            }
            delay(PROFILE_AVATAR_READY_POLL_DELAY_MILLIS)
            asset = call {
                api.mediaAssets(
                    ownerType = scope.ownerType,
                    ownerId = scope.ownerId,
                    warehouseId = scope.warehouseId,
                    context = scope.context,
                ).items.firstOrNull { it.id == session.mediaId }
                    ?: throw CustomerApiException(404, "Загруженный аватар не найден")
            }
        }
        throw CustomerApiException(503, "Аватар ещё обрабатывается. Повторите позже")
    }

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

    private suspend fun <T> durableIdempotent(
        operation: String,
        complete: suspend (String) -> Unit = { key ->
            workflowStore.completeIdempotentOperation(operation, key)
        },
        reconcile: suspend () -> T?,
        block: suspend (String) -> T,
    ): T {
        val key = workflowStore.beginIdempotentOperation(operation)
        return try {
            block(key).also { complete(key) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val reconciled = try {
                reconcile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (reconciled != null) {
                complete(key)
                reconciled
            } else {
                throw failure.toCustomerApiException(json)
            }
        }
    }

    private suspend fun authoritativeCabin(
        bookingId: String,
        cabinId: String,
    ): CustomerBookingCabin? = api.bookings()
        .firstOrNull { booking -> booking.bookingId == bookingId }
        ?.cabins
        ?.firstOrNull { cabin -> cabin.cabinUnitId == cabinId }

    private fun requireBookingVersion(booking: CustomerBooking) {
        if (booking.version <= 0) {
            throw CustomerApiException(409, "Версия заказа неизвестна. Обновите список заказов")
        }
    }
}

/** Current free-cabin query; the server remains authoritative for availability. */
data class CabinFilters(
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

/** App-private photo or video file queued for a customer problem report. */
data class CustomerEvidenceFile(
    val localId: String,
    val file: File,
    val fileName: String,
    val contentType: String,
)

private fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

private const val MAX_CUSTOMER_EVIDENCE_BYTES = 200L * 1024L * 1024L
private const val PROFILE_AVATAR_OWNER_TYPE = "LOGISTICS_CUSTOMER_PROFILE"
private const val PROFILE_AVATAR_CONTEXT = "PROFILE_AVATAR"
private const val PROFILE_AVATAR_READY_POLL_ATTEMPTS = 45
private const val PROFILE_AVATAR_READY_POLL_DELAY_MILLIS = 1_000L
private const val MAX_PROFILE_AVATAR_BYTES = 8 * 1024 * 1024
