package dev.buhanzaz.rwms.client.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException

/** Cohesive client-side adapter for the server-owned customer booking workflow. */
@Singleton
class CustomerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
     * Uploads one system-selected image into the exact subject-bound profile owner and binds its
     * READY generation under the profile version returned by logistics-service.
     */
    suspend fun uploadProfileAvatar(
        profile: CustomerProfile,
        warehouseId: String,
        contentUri: Uri,
    ): CustomerProfile {
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
        val source = copyProfileAvatarToCache(contentUri)
        return try {
            val ready = uploadReadyProfileAvatar(scope, source)
            call {
                api.setProfileAvatar(
                    SetCustomerProfileAvatarRequest(
                        expectedVersion = scope.profileVersion,
                        mediaId = ready.id,
                        generation = ready.generation,
                    ),
                )
            }
        } finally {
            source.file.delete()
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

    /** Accepts one arrived cabin with a process-stable idempotency key. */
    suspend fun acceptCabin(
        bookingId: String,
        cabinId: String,
        strokes: List<CustomerSignatureStroke>,
    ): CustomerCabinAcceptance {
        val fingerprint = json.encodeToString(AcceptCustomerCabinRequest(strokes)).sha256()
        return idempotent("accept:$bookingId:$cabinId:$fingerprint") { key ->
            api.acceptCabin(bookingId, cabinId, key, AcceptCustomerCabinRequest(strokes))
        }
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
        val fingerprint = listOf(category, description.trim(), references.joinToString()).joinToString("|").sha256()
        return idempotent("problem:$bookingId:$cabinId:$fingerprint") { key ->
            api.reportProblem(
                bookingId,
                cabinId,
                key,
                ReportCustomerCabinProblemRequest(category, description.trim(), references),
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
        source: CustomerAvatarSource,
    ): CustomerMediaAsset {
        val checksum = source.file.sha256()
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
                    fileName = source.fileName.take(512),
                    contentType = source.contentType,
                    contentLength = source.file.length(),
                    checksumSha256 = checksum,
                    sortOrder = 0,
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

    private fun copyProfileAvatarToCache(contentUri: Uri): CustomerAvatarSource = try {
        copyProfileAvatarToCacheUnsafe(contentUri)
    } catch (failure: CustomerApiException) {
        throw failure
    } catch (_: SecurityException) {
        throw CustomerApiException(403, "Нет доступа к выбранному изображению")
    } catch (_: IOException) {
        throw CustomerApiException(null, "Не удалось прочитать выбранное изображение")
    } catch (_: Throwable) {
        throw CustomerApiException(null, "Не удалось подготовить выбранное изображение")
    }

    private fun copyProfileAvatarToCacheUnsafe(contentUri: Uri): CustomerAvatarSource {
        val contentType = context.contentResolver.getType(contentUri)
            ?.lowercase()
            ?.let { if (it == "image/jpg") "image/jpeg" else it }
            ?.takeIf(PROFILE_AVATAR_CONTENT_TYPES::contains)
            ?: throw CustomerApiException(415, "Выберите изображение JPEG, PNG или WebP")
        val displayName = context.contentResolver.query(
            contentUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf(String::isNotBlank)
            ?: "avatar.${contentType.substringAfter('/').replace("jpeg", "jpg")}"
        val suffix = ".${displayName.substringAfterLast('.', "jpg").take(10)}"
        val target = File.createTempFile("profile-avatar-", suffix, context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(contentUri)
                ?: throw CustomerApiException(404, "Выбранное изображение недоступно")
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_CUSTOMER_EVIDENCE_BYTES) {
                            throw CustomerApiException(422, "Изображение превышает 200 МБ")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() == 0L) throw CustomerApiException(422, "Выбранное изображение пустое")
            return CustomerAvatarSource(target, displayName, contentType)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
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

/** App-private normalized source copied from a temporary system picker grant. */
private data class CustomerAvatarSource(
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
private val PROFILE_AVATAR_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
