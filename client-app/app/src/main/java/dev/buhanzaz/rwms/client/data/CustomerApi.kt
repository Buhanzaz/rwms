package dev.buhanzaz.rwms.client.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import okhttp3.RequestBody

/** Typed boundary for the customer-owned logistics API exposed by the public gateway. */
interface CustomerApi {
    @GET("api/logistics/customer/v1/profile")
    suspend fun profile(): CustomerProfile

    @POST("api/logistics/customer/v1/profile")
    suspend fun createProfile(@Body profile: CustomerProfile): CustomerProfile

    @PUT("api/logistics/customer/v1/profile")
    suspend fun updateProfile(@Body request: UpdateCustomerProfileRequest): CustomerProfile

    @POST("api/logistics/customer/v1/profile/avatar-upload")
    suspend fun prepareProfileAvatarUpload(
        @Body request: PrepareCustomerProfileAvatarUploadRequest,
    ): CustomerProfileAvatarUploadScope

    @PUT("api/logistics/customer/v1/profile/avatar")
    suspend fun setProfileAvatar(@Body request: SetCustomerProfileAvatarRequest): CustomerProfile

    @GET("api/logistics/customer/v1/warehouses")
    suspend fun warehouses(): List<CustomerWarehouse>

    @POST("api/logistics/customer/v1/inquiries")
    suspend fun createInquiry(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateInquiryRequest,
    ): InquirySession

    @GET("api/logistics/customer/v1/inquiries/{inquiryId}")
    suspend fun inquiry(@Path("inquiryId") inquiryId: String): InquirySession

    @GET("api/logistics/customer/v1/inquiries/{inquiryId}/facets")
    suspend fun facets(@Path("inquiryId") inquiryId: String): CabinFacets

    @GET("api/logistics/customer/v1/inquiries/{inquiryId}/cabins")
    suspend fun cabins(
        @Path("inquiryId") inquiryId: String,
        @Query("cabinType") cabinType: String? = null,
        @Query("finish") finish: String? = null,
        @Query("dimensions") dimensions: String? = null,
        @Query("category") category: String? = null,
        @Query("linoleum") linoleum: Boolean? = null,
        @Query("characteristics") characteristics: List<String> = emptyList(),
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): CabinPage

    @PUT("api/logistics/customer/v1/inquiries/{inquiryId}/selection")
    suspend fun updateSelection(
        @Path("inquiryId") inquiryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: UpdateCabinSelectionRequest,
    ): CabinSelectionResponse

    @GET("api/logistics/customer/v1/inquiries/{inquiryId}/equipment")
    suspend fun equipment(@Path("inquiryId") inquiryId: String): List<AvailableEquipment>

    @PUT("api/logistics/customer/v1/inquiries/{inquiryId}/equipment")
    suspend fun updateEquipment(
        @Path("inquiryId") inquiryId: String,
        @Body request: UpdateEquipmentRequest,
    ): EquipmentSelectionResponse

    @PUT("api/logistics/customer/v1/inquiries/{inquiryId}/rental-terms")
    suspend fun updateRentalTerms(
        @Path("inquiryId") inquiryId: String,
        @Body request: ReplaceCustomerRentalTermsRequest,
    ): CustomerRentalTerms

    @GET("api/logistics/customer/v1/inquiries/{inquiryId}/cart")
    suspend fun cart(@Path("inquiryId") inquiryId: String): CustomerCart

    @POST("api/logistics/customer/v1/delivery-slots/search")
    suspend fun searchSlots(@Body request: DeliverySlotSearchRequest): List<DeliverySlot>

    @POST("api/logistics/customer/v1/delivery-slots/{slotId}/hold")
    suspend fun holdSlot(
        @Path("slotId") slotId: String,
        @Query("expectedSlotVersion") expectedSlotVersion: Long,
        @Body request: HoldDeliverySlotRequest,
    ): HeldDeliverySlot

    @POST("api/logistics/customer/v1/inquiries/{inquiryId}/checkout")
    suspend fun checkout(
        @Path("inquiryId") inquiryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CheckoutRequest,
    ): CustomerBooking

    @GET("api/logistics/customer/v1/bookings")
    suspend fun bookings(): List<CustomerBooking>

    @POST("api/logistics/customer/v1/bookings/{bookingId}/cabins/{cabinId}/acceptance")
    suspend fun acceptCabin(
        @Path("bookingId") bookingId: String,
        @Path("cabinId") cabinId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AcceptCustomerCabinRequest,
    ): CustomerCabinAcceptance

    @POST("api/logistics/customer/v1/bookings/{bookingId}/cabins/{cabinId}/problems")
    suspend fun reportProblem(
        @Path("bookingId") bookingId: String,
        @Path("cabinId") cabinId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: ReportCustomerCabinProblemRequest,
    ): CustomerCabinProblem

    @POST("api/media/v1/upload-sessions")
    suspend fun createMediaUpload(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateCustomerMediaUploadRequest,
    ): MediaUploadSession

    @PUT
    suspend fun uploadMediaContent(
        @Url contentPath: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody,
    ): UploadedMediaObject

    @POST("api/media/v1/upload-sessions/{uploadSessionId}/complete")
    suspend fun finalizeMediaUpload(
        @Path("uploadSessionId") uploadSessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: FinalizeCustomerMediaUploadRequest,
    ): CustomerMediaAsset

    @GET("api/media/v1/assets")
    suspend fun mediaAssets(
        @Query("ownerType") ownerType: String,
        @Query("ownerId") ownerId: String? = null,
        @Query("documentId") documentId: String? = null,
        @Query("lineId") lineId: String? = null,
        @Query("warehouseId") warehouseId: String,
        @Query("context") context: String,
        @Query("limit") limit: Int = 100,
    ): CustomerMediaPage
}
