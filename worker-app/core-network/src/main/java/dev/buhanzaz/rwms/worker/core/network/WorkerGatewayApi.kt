package dev.buhanzaz.rwms.worker.core.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.Streaming

/**
 * Public gateway-only API. Every path is rooted at /api or /auth; no service
 * origin can be placed in a Retrofit call site.
 */
interface WorkerGatewayApi {
    @GET("/api/task-board/worker/v1/context")
    suspend fun workerContext(): Response<WorkerContextDto>

    @GET("/api/task-board/worker/v1/feed")
    suspend fun workerFeed(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<WorkerFeedDto>

    @GET("/api/task-board/worker/v1/entries/{entryId}")
    suspend fun workerTaskDetail(@Path("entryId") entryId: String): Response<WorkerTaskDetailDto>

    @GET("/api/logistics/v1/driver-tasks/{taskId}")
    suspend fun logisticsDriverTask(
        @Path("taskId") taskId: String,
    ): Response<DriverTaskTripDetailsResponseDto>

    @POST("/api/task-board/worker/v1/entries/{entryId}/actions")
    suspend fun applyAction(
        @Path("entryId") entryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: WorkerActionRequestDto,
    ): Response<WorkerActionResultDto>

    @POST("/api/task-board/worker/v1/entries/{entryId}/evidence-reservations")
    suspend fun reserveEvidence(
        @Path("entryId") entryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: EvidenceReservationRequestDto,
    ): Response<TaskEvidenceDto>

    @PUT("/api/task-board/worker/v1/devices/{installationId}")
    suspend fun registerDevice(
        @Path("installationId") installationId: String,
        @Body request: WorkerDeviceRegistrationRequestDto,
    ): Response<WorkerDeviceRegistrationDto>

    @DELETE("/api/task-board/worker/v1/devices/{installationId}")
    suspend fun unregisterDevice(@Path("installationId") installationId: String): Response<Unit>

    @POST("/api/media/v1/upload-sessions")
    suspend fun createUploadSession(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateUploadSessionRequestDto,
    ): Response<UploadSessionDto>

    @PUT
    suspend fun uploadMediaContent(
        @Url sameOriginContentPath: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body content: RequestBody,
    ): Response<UploadedObjectDto>

    @POST("/api/media/v1/upload-sessions/{uploadSessionId}/complete")
    suspend fun finalizeUploadSession(
        @Path("uploadSessionId") uploadSessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: FinalizeUploadRequestDto,
    ): Response<MediaAssetDto>

    @Streaming
    @GET
    suspend fun mediaContent(@Url sameOriginMediaPath: String): Response<ResponseBody>
}
