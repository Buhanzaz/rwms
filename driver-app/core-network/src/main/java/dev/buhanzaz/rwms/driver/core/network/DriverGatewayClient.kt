package dev.buhanzaz.rwms.driver.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.RequestBody
import okhttp3.ResponseBody

/**
 * Encapsulates driver public-gateway transport/failure handling; it is never backend persistence.
 */
sealed interface DriverFeedResponse {
    /** Gateway returned a changed authoritative feed and optional cache validator. */
    data class Changed(val feed: DriverFeedDto, val etag: String?) : DriverFeedResponse
    /** Gateway confirmed that the locally cached feed validator is current. */
    data class NotModified(val etag: String?) : DriverFeedResponse
}

/**
 * The only network boundary consumed by sync/features. It centralizes Problem
 * Details conversion and makes it impossible to use a media dynamic URL before
 * proving it is a relative same-origin public path.
 */
@Singleton
class DriverGatewayClient @Inject constructor(
    private val api: DriverGatewayApi,
    private val json: Json,
) {
    suspend fun context(): DriverContextDto = api.driverContext().bodyOrProblem(json)

    suspend fun feed(cursor: String? = null, etag: String? = null): DriverFeedResponse {
        val response = api.driverFeed(cursor = cursor, ifNoneMatch = etag)
        return if (response.code() == 304) {
            DriverFeedResponse.NotModified(response.headers()["ETag"])
        } else {
            DriverFeedResponse.Changed(response.bodyOrProblem(json), response.headers()["ETag"])
        }
    }

    suspend fun detail(entryId: String): DriverTaskDetailDto = api.driverTaskDetail(entryId).bodyOrProblem(json)

    /** Reads live logistics-owned trip facts without copying them into the driver Room projection. */
    suspend fun logisticsTripDetails(taskId: String): DriverTripDetailsDto? =
        api.logisticsDriverTask(taskId).bodyOrProblem(json).tripDetails

    /** Reserves a shared future trip online without starting its task-board execution. */
    suspend fun claimFutureLogisticsTask(taskId: String): DriverTripDetailsDto? =
        api.claimFutureLogisticsTask(taskId).bodyOrProblem(json).tripDetails

    suspend fun action(entryId: String, request: DriverActionRequestDto): DriverActionResultDto =
        api.applyAction(entryId, request.operationId, request).bodyOrProblem(json)

    suspend fun reserveEvidence(entryId: String, request: EvidenceReservationRequestDto): TaskEvidenceDto =
        api.reserveEvidence(entryId, request.operationId, request).bodyOrProblem(json)

    suspend fun registerDevice(installationId: String, request: DriverDeviceRegistrationRequestDto): DriverDeviceRegistrationDto =
        api.registerDevice(installationId, request).bodyOrProblem(json)

    suspend fun unregisterDevice(installationId: String) {
        val response = api.unregisterDevice(installationId)
        if (!response.isSuccessful && response.code() != 404) response.bodyOrProblem(json)
    }

    suspend fun createUploadSession(
        idempotencyKey: String,
        request: CreateUploadSessionRequestDto,
    ): UploadSessionDto = api.createUploadSession(idempotencyKey, request).bodyOrProblem(json)

    suspend fun uploadMediaContent(
        sameOriginContentPath: String,
        idempotencyKey: String,
        content: RequestBody,
    ): UploadedObjectDto = api.uploadMediaContent(
        sameOriginContentPath = requireSameOriginApiPath(sameOriginContentPath),
        idempotencyKey = idempotencyKey,
        content = content,
    ).bodyOrProblem(json)

    suspend fun finalizeUploadSession(
        uploadSessionId: String,
        idempotencyKey: String,
        request: FinalizeUploadRequestDto,
    ): MediaAssetDto = api.finalizeUploadSession(uploadSessionId, idempotencyKey, request).bodyOrProblem(json)

    suspend fun mediaContent(sameOriginMediaPath: String): ResponseBody =
        api.mediaContent(requireSameOriginMediaReadPath(sameOriginMediaPath)).bodyOrProblem(json)
}
