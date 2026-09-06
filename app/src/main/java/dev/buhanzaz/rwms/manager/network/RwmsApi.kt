package dev.buhanzaz.rwms.manager.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Streaming
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Encapsulates the manager public-gateway transport boundary; it is not a backend domain or persistence type.
 */
interface RwmsApi {
    @GET("auth/api/users/me")
    suspend fun currentUser(): CurrentUserDto

    @GET("api/warehouse/v1/warehouses")
    suspend fun warehouses(): List<WarehouseDto>

    @GET("api/inventory/v1/sessions/active")
    suspend fun activeInventory(
        @Query("warehouseId") warehouseId: String,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<InventorySessionDto>

    @GET("api/inventory/v1/sessions/{inventoryId}")
    suspend fun inventory(
        @Path("inventoryId") inventoryId: String,
    ): InventorySessionDto

    @GET("api/inventory/v1/sessions/{inventoryId}/findings")
    suspend fun inventoryFindings(
        @Path("inventoryId") inventoryId: String,
        @Query("page") page: Int,
        @Query("size") size: Int = 200,
        @Query("sort") sort: String = "createdAt,asc",
    ): InventoryFindingPageDto

    @POST("api/inventory/v1/sessions/{inventoryId}/number-resolutions")
    suspend fun resolveInventoryNumber(
        @Path("inventoryId") inventoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ResolveNumberRequest,
    ): NumberResolutionDto

    @POST("api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/assets")
    suspend fun createInventoryAsset(
        @Path("inventoryId") inventoryId: String,
        @Path("findingId") findingId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateFindingAssetRequest,
    ): InventoryFindingDto

    @PUT("api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/inspection")
    suspend fun saveInventoryInspection(
        @Path("inventoryId") inventoryId: String,
        @Path("findingId") findingId: String,
        @Body request: SaveInspectionRequest,
    ): InventoryFindingDto

    @PUT("api/inventory/v1/sessions/{inventoryId}/findings/{findingId}/conflict-resolution")
    suspend fun resolveInventoryConflict(
        @Path("inventoryId") inventoryId: String,
        @Path("findingId") findingId: String,
        @Body request: ResolveInventoryConflictRequest,
    ): InventoryFindingDto

    @GET("api/asset/v1/rental-items")
    suspend fun rentalItems(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50,
        @Query("search") search: String? = null,
        @Query("excludeStatus") excludeStatuses: List<String> = emptyList(),
    ): RentalItemPageDto

    @GET("api/asset/v1/rental-items/{rentalItemId}")
    suspend fun rentalItem(
        @Path("rentalItemId") rentalItemId: String,
    ): RentalItemDto

    @GET("api/asset/v1/rental-items/creation-options")
    suspend fun rentalItemCreationOptions(
        @Query("warehouseId") warehouseId: String,
    ): RentalItemCreationOptionsDto

    @GET("api/asset/v1/equipment")
    suspend fun equipment(
        @Query("warehouseId") warehouseId: String,
    ): List<EquipmentItemViewDto>

    @GET("api/logistics/v1/returns")
    suspend fun returns(
        @Query("warehouseId") warehouseId: String,
    ): List<LogisticsDocumentDto>

    @GET("api/logistics/v1/returns/{documentId}")
    suspend fun returnDocument(
        @Path("documentId") documentId: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/returns/{documentId}/accept-undamaged")
    suspend fun acceptReturn(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AcceptReturnRequest,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/returns/{documentId}/start-estimates")
    suspend fun startReturnEstimates(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: StartReturnEstimatesRequest,
    ): LogisticsDocumentDto

    @GET("api/logistics/v1/shipments")
    suspend fun shipments(
        @Query("warehouseId") warehouseId: String,
    ): List<LogisticsDocumentDto>

    @GET("api/logistics/v1/shipments/{documentId}")
    suspend fun shipment(
        @Path("documentId") documentId: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/shipments")
    suspend fun createShipment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateShipmentRequest,
    ): LogisticsDocumentDto

    @PUT("api/logistics/v1/shipments/{documentId}/plan")
    suspend fun replaceShipmentPlan(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ShipmentPlanRequest,
    ): LogisticsDocumentDto

    @GET("api/logistics/v1/shipments/{documentId}/furniture-readiness")
    suspend fun shipmentFurnitureReadiness(
        @Path("documentId") documentId: String,
    ): ShipmentFurnitureReadinessDto

    @POST("api/logistics/v1/shipments/{documentId}/furniture-tasks")
    suspend fun createShipmentFurnitureTasks(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ShipmentFurnitureTaskResultDto

    @POST("api/logistics/v1/rental-items/{rentalItemId}/furniture-tasks")
    suspend fun createCabinFurnitureTask(
        @Path("rentalItemId") rentalItemId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateCabinFurnitureTaskRequest,
    ): CabinFurnitureTaskResultDto

    @POST("api/logistics/v1/shipments/{documentId}/confirm-preparation")
    suspend fun confirmShipmentPreparation(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/shipments/{documentId}/cancel")
    suspend fun cancelShipment(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @GET("api/logistics/v1/transfers")
    suspend fun transfers(
        @Query("warehouseId") warehouseId: String,
    ): List<LogisticsDocumentDto>

    @GET("api/logistics/v1/transfers/{documentId}")
    suspend fun transfer(
        @Path("documentId") documentId: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/transfers")
    suspend fun createTransfer(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateTransferRequest,
    ): LogisticsDocumentDto

    @GET("api/logistics/v1/transfers/{documentId}/furniture-readiness")
    suspend fun transferFurnitureReadiness(
        @Path("documentId") documentId: String,
    ): TransferFurnitureReadinessDto

    @POST("api/logistics/v1/transfers/{documentId}/depart")
    suspend fun departTransfer(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/transfers/{documentId}/arrive")
    suspend fun arriveTransfer(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/transfers/{documentId}/lines/{lineId}/depart")
    suspend fun departTransferLine(
        @Path("documentId") documentId: String,
        @Path("lineId") lineId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Query("expectedLineVersion") expectedLineVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @GET("api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrival-preflight")
    suspend fun transferArrivalPreflight(
        @Path("documentId") documentId: String,
        @Path("lineId") lineId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Query("expectedLineVersion") expectedLineVersion: Long,
    ): TransferArrivalPreflightDto

    @POST("api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrive")
    suspend fun arriveTransferLine(
        @Path("documentId") documentId: String,
        @Path("lineId") lineId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Query("expectedLineVersion") expectedLineVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ArriveTransferLineRequest,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/transfers/{documentId}/cancel")
    suspend fun cancelTransfer(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): LogisticsDocumentDto

    @POST("api/logistics/v1/transfers/{documentId}/reconcile")
    suspend fun reconcileTransfer(
        @Path("documentId") documentId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ReconcileLogisticsRequest,
    ): LogisticsDocumentDto

    @GET("api/maintenance/v1/estimates")
    suspend fun estimates(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("lifecycle") lifecycle: String? = null,
        @Query("rentalItemId") rentalItemId: String? = null,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<EstimatePageDto>

    @GET("api/maintenance/v1/estimates/return-sources")
    suspend fun returnEstimateSources(
        @Query("warehouseId") warehouseId: String,
        @Query("returnId") returnId: String,
    ): List<ReturnEstimateSourceDto>

    @POST("api/maintenance/v1/estimates")
    suspend fun createEstimate(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateEstimateRequest,
    ): EstimateDto

    @GET("api/maintenance/v1/estimates/{estimateId}")
    suspend fun estimate(
        @Path("estimateId") estimateId: String,
        @Query("warehouseId") warehouseId: String,
    ): EstimateDto

    @PUT("api/maintenance/v1/estimates/{estimateId}")
    suspend fun replaceEstimate(
        @Path("estimateId") estimateId: String,
        @Query("warehouseId") warehouseId: String,
        @Body request: ReplaceEstimateRequest,
    ): EstimateDto

    @POST("api/maintenance/v1/estimates/{estimateId}/amendments")
    suspend fun amendEstimate(
        @Path("estimateId") estimateId: String,
        @Query("warehouseId") warehouseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AmendEstimateRequest,
    ): EstimateCommandResultDto

    @POST("api/maintenance/v1/estimates/{estimateId}/complete")
    suspend fun completeEstimate(
        @Path("estimateId") estimateId: String,
        @Query("warehouseId") warehouseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CompleteEstimateRequest,
    ): EstimateCommandResultDto

    @GET("api/maintenance/v1/repairs")
    suspend fun repairs(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("executionState") executionState: String? = null,
        @Query("acceptanceState") acceptanceState: String? = null,
        @Query("rentalItemId") rentalItemId: String? = null,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<RepairPageDto>

    @GET("api/maintenance/v1/repairs/capital")
    suspend fun activeCapitalRepairs(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): RepairPageDto

    @GET("api/maintenance/v1/acceptance")
    suspend fun acceptance(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("state") state: String? = null,
    ): AcceptanceProjectionPageDto

    @POST("api/maintenance/v1/repairs/direct")
    suspend fun createDirectRepair(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateDirectRepairRequest,
    ): RepairDto

    @GET("api/maintenance/v1/repairs/{repairId}")
    suspend fun repair(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
    ): RepairDto

    @PUT("api/maintenance/v1/repairs/{repairId}/plan")
    suspend fun replaceRepairPlan(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
        @Body request: ReplaceRepairPlanRequest,
    ): RepairDto

    @POST("api/maintenance/v1/repairs/{repairId}/plan")
    suspend fun queueRepairPlan(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: PriorityVersionRequest,
    ): RepairCommandResultDto

    @POST("api/maintenance/v1/repairs/{repairId}/reworks")
    suspend fun createRework(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateReworkRequest,
    ): RepairDto

    @GET("api/maintenance/v1/repairs/{repairId}/rework-candidates")
    suspend fun reworkCandidates(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
    ): ReworkCandidatesDto

    @POST("api/maintenance/v1/repairs/{repairId}/accept")
    suspend fun acceptRepair(
        @Path("repairId") repairId: String,
        @Query("warehouseId") warehouseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: RepairDecisionRequest,
    ): RepairCommandResultDto

    /** Reads the warehouse's single public aggregate ordinary-board representation. */
    @GET("api/task-board/warehouses/{warehouseId}/task-board")
    suspend fun taskBoard(
        @Path("warehouseId") warehouseId: String,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<TaskBoardSnapshotDto>

    @GET("api/maintenance/v1/catalog/versions")
    suspend fun catalogVersions(
        @Query("warehouseId") warehouseId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 200,
        @Query("lifecycle") lifecycle: String = "ACTIVE",
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<CatalogVersionPageDto>

    @GET("api/maintenance/v1/catalog/versions/{catalogVersionId}/nodes")
    suspend fun catalogNodes(
        @Path("catalogVersionId") catalogVersionId: String,
        @Query("warehouseId") warehouseId: String,
    ): List<CatalogNodeDto>

    @GET("api/maintenance/v1/catalog/versions/{catalogVersionId}/links")
    suspend fun catalogLinks(
        @Path("catalogVersionId") catalogVersionId: String,
        @Query("warehouseId") warehouseId: String,
    ): List<CatalogLinkDto>

    @POST("api/media/v1/upload-sessions")
    suspend fun createUploadSession(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateUploadSessionRequest,
    ): UploadSessionDto

    @PUT
    suspend fun uploadContent(
        @Url contentPath: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody,
    ): UploadedObjectDto

    @POST("api/media/v1/upload-sessions/{uploadSessionId}/complete")
    suspend fun finalizeUpload(
        @Path("uploadSessionId") uploadSessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: FinalizeUploadRequest,
    ): MediaAssetDto

    @GET("api/media/v1/assets")
    suspend fun ownerMedia(
        @Query("ownerType") ownerType: String,
        @Query("ownerId") ownerId: String? = null,
        @Query("documentId") documentId: String? = null,
        @Query("lineId") lineId: String? = null,
        @Query("warehouseId") warehouseId: String,
        @Query("context") context: String,
        @Query("limit") limit: Int = 100,
    ): MediaPageDto

    @Streaming
    @GET("api/media/v1/assets/{mediaId}/original")
    suspend fun originalMedia(
        @Path("mediaId") mediaId: String,
        @Query("generation") generation: Long,
        @Query("ownerType") ownerType: String,
        @Query("ownerId") ownerId: String? = null,
        @Query("documentId") documentId: String? = null,
        @Query("lineId") lineId: String? = null,
        @Query("warehouseId") warehouseId: String,
        @Query("context") context: String,
    ): ResponseBody

    @Streaming
    @GET
    suspend fun mediaVariantContent(
        @Url contentPath: String,
    ): ResponseBody
}
