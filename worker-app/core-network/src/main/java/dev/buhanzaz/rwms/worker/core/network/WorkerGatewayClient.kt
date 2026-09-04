package dev.buhanzaz.rwms.worker.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.RequestBody
import okhttp3.ResponseBody

/**
 * Encapsulates worker public-gateway transport/failure handling; it is never backend persistence.
 */
sealed interface WorkerFeedResponse {
    data class Changed(val feed: WorkerFeedDto, val etag: String?) : WorkerFeedResponse
    data class NotModified(val etag: String?) : WorkerFeedResponse
}

/**
 * The only network boundary consumed by sync/features. It centralizes Problem
 * Details conversion and makes it impossible to use a media dynamic URL before
 * proving it is a relative same-origin public path.
 */
@Singleton
class WorkerGatewayClient @Inject constructor(
    private val api: WorkerGatewayApi,
    private val json: Json,
) {
    suspend fun context(): WorkerContextDto = api.workerContext().bodyOrProblem(json)

    suspend fun prepareWorkerProfileAvatarScope(): WorkerProfileAvatarScopeDto =
        api.prepareWorkerProfileAvatarScope().bodyOrProblem(json)

    suspend fun feed(cursor: String? = null, etag: String? = null): WorkerFeedResponse {
        val response = api.workerFeed(cursor = cursor, ifNoneMatch = etag)
        return if (response.code() == 304) {
            WorkerFeedResponse.NotModified(response.headers()["ETag"])
        } else {
            WorkerFeedResponse.Changed(response.bodyOrProblem(json), response.headers()["ETag"])
        }
    }

    suspend fun detail(entryId: String): WorkerTaskDetailDto = api.workerTaskDetail(entryId).bodyOrProblem(json)

    suspend fun action(entryId: String, request: WorkerActionRequestDto): WorkerActionResultDto =
        api.applyAction(entryId, request.operationId, request).bodyOrProblem(json)

    suspend fun reserveEvidence(entryId: String, request: EvidenceReservationRequestDto): TaskEvidenceDto =
        api.reserveEvidence(entryId, request.operationId, request).bodyOrProblem(json)

    suspend fun registerDevice(installationId: String, request: WorkerDeviceRegistrationRequestDto): WorkerDeviceRegistrationDto =
        api.registerDevice(installationId, request).bodyOrProblem(json)

    suspend fun unregisterDevice(installationId: String) {
        val response = api.unregisterDevice(installationId)
        if (!response.isSuccessful && response.code() != 404) response.bodyOrProblem(json)
    }

    suspend fun createUploadSession(
        idempotencyKey: String,
        request: CreateUploadSessionRequestDto,
    ): UploadSessionDto = api.createUploadSession(idempotencyKey, request).bodyOrProblem(json)

    suspend fun mediaAssets(scope: WorkerProfileAvatarScopeDto): MediaAssetPageDto =
        api.mediaAssets(
            ownerType = scope.ownerType,
            ownerId = scope.ownerId,
            warehouseId = scope.warehouseId,
            context = scope.context,
        ).bodyOrProblem(json)

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
