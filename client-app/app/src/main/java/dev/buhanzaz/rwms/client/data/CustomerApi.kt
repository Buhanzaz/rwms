package dev.buhanzaz.rwms.client.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Typed boundary for the customer-owned logistics API exposed by the public gateway. */
interface CustomerApi {
    @GET("api/logistics/customer/v1/profile")
    suspend fun profile(): CustomerProfile

    @POST("api/logistics/customer/v1/profile")
    suspend fun createProfile(@Body profile: CustomerProfile): CustomerProfile

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
        @Query("query") query: String? = null,
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
}
