package dev.buhanzaz.rwms.rentalmanager.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Public-gateway transport used only by the dedicated rental-manager Android credential. */
interface RentalManagerApi {
    @GET("auth/api/users/me")
    suspend fun currentUser(): CurrentUserDto

    @GET("api/warehouse/v1/warehouses")
    suspend fun warehouses(): List<WarehouseDto>

    @GET("api/logistics/v1/clients")
    suspend fun clients(
        @Query("search") search: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): RentalClientPageDto

    @GET("api/logistics/v1/clients/{clientId}")
    suspend fun client(@Path("clientId") clientId: String): RentalClientDto

    @POST("api/logistics/v1/clients")
    suspend fun createClient(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateRentalClientRequest,
    ): RentalClientDto

    @GET("api/logistics/v1/clients/{clientId}/orders")
    suspend fun clientOrders(
        @Path("clientId") clientId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("sort") sort: String = "updatedAt",
        @Query("direction") direction: String = "DESC",
    ): OrderPageDto

    @GET("api/logistics/v1/orders")
    suspend fun orders(
        @Query("search") search: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("sort") sort: String = "updatedAt",
        @Query("direction") direction: String = "DESC",
    ): OrderPageDto

    @GET("api/logistics/v1/orders/{orderId}")
    suspend fun order(@Path("orderId") orderId: String): OrderDto

    @POST("api/logistics/v1/orders")
    suspend fun createOrder(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateOrderRequest,
    ): OrderDto

    @PUT("api/logistics/v1/orders/{orderId}")
    suspend fun updateOrder(
        @Path("orderId") orderId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: UpdateOrderRequest,
    ): OrderDto

    @DELETE("api/logistics/v1/orders/{orderId}")
    suspend fun cancelOrder(
        @Path("orderId") orderId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): OrderDto

    @POST("api/logistics/v1/orders/{orderId}/save")
    suspend fun saveOrder(
        @Path("orderId") orderId: String,
        @Query("expectedVersion") expectedVersion: Long,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): OrderDto

    @GET("api/logistics/v1/rental-inquiries/{inquiryId}/client-presentation")
    suspend fun clientPresentation(
        @Path("inquiryId") inquiryId: String,
    ): Response<RentalPresentationDto>

    @PUT("api/logistics/v1/rental-inquiries/{inquiryId}/client-presentation")
    suspend fun publishClientPresentation(
        @Path("inquiryId") inquiryId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: PublishRentalPresentationRequest,
    ): RentalPresentationDto
}
