package dev.buhanzaz.rwms.driver.core.network

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
interface DriverGatewayApi {
    @GET("/api/task-board/driver/v1/shift/today")
    suspend fun todayDriverShift(): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/briefing/seen")
    suspend fun markShiftBriefingSeen(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShiftTransitionRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/medical-check")
    suspend fun confirmShiftMedicalCheck(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ConfirmMedicalCheckRequestDto,
    ): Response<TodayDriverShiftDto>

    @PUT("/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/items/{itemId}")
    suspend fun updateShiftInspectionItem(
        @Path("shiftId") shiftId: String,
        @Path("itemId") itemId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: UpdateInspectionItemRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/vehicle-inspection/complete")
    suspend fun completeShiftInspection(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShiftTransitionRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/start")
    suspend fun startShift(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShiftTransitionRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/closing/start")
    suspend fun startShiftClosing(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShiftTransitionRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/return-to-warehouse")
    suspend fun confirmShiftWarehouseReturn(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ReturnToWarehouseRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/closing-report")
    suspend fun submitShiftClosingReport(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: SubmitClosingReportRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/photos/reservations")
    suspend fun reserveShiftPhoto(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ReserveShiftPhotoRequestDto,
    ): Response<TodayDriverShiftDto>

    @POST("/api/task-board/driver/v1/shifts/{shiftId}/close")
    suspend fun closeShift(
        @Path("shiftId") shiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShiftTransitionRequestDto,
    ): Response<TodayDriverShiftDto>

    @GET("/api/task-board/driver/v1/context")
    suspend fun driverContext(): Response<DriverContextDto>

    @GET("/api/task-board/driver/v1/feed")
    suspend fun driverFeed(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<DriverFeedDto>

    @GET("/api/task-board/driver/v1/entries/{entryId}")
    suspend fun driverTaskDetail(@Path("entryId") entryId: String): Response<DriverTaskDetailDto>

    @GET("/api/logistics/v1/driver-tasks/{taskId}")
    suspend fun logisticsDriverTask(
        @Path("taskId") taskId: String,
    ): Response<DriverTaskTripDetailsResponseDto>

    /** Assigns an eligible future shared trip without starting its task-board execution. */
    @POST("/api/logistics/v1/driver-tasks/{taskId}/claim")
    suspend fun claimFutureLogisticsTask(
        @Path("taskId") taskId: String,
    ): Response<DriverTaskTripDetailsResponseDto>

    @POST("/api/task-board/driver/v1/entries/{entryId}/actions")
    suspend fun applyAction(
        @Path("entryId") entryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: DriverActionRequestDto,
    ): Response<DriverActionResultDto>

    @POST("/api/task-board/driver/v1/entries/{entryId}/evidence-reservations")
    suspend fun reserveEvidence(
        @Path("entryId") entryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: EvidenceReservationRequestDto,
    ): Response<TaskEvidenceDto>

    @PUT("/api/task-board/driver/v1/devices/{installationId}")
    suspend fun registerDevice(
        @Path("installationId") installationId: String,
        @Body request: DriverDeviceRegistrationRequestDto,
    ): Response<DriverDeviceRegistrationDto>

    @DELETE("/api/task-board/driver/v1/devices/{installationId}")
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
