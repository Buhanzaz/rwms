package dev.buhanzaz.rwms.rentalmanager.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Public-gateway assistant transport for the rental-manager credential. */
interface AssistantApi {
    @GET("api/assistant/v1/conversations")
    suspend fun conversations(
        @Query("rentalOrderId") rentalOrderId: String? = null,
    ): List<AssistantConversation>

    @POST("api/assistant/v1/conversations")
    suspend fun createConversation(
        @Body request: CreateAssistantConversationRequest,
    ): CreateAssistantConversationResponse

    @GET("api/assistant/v1/conversations/{conversationId}")
    suspend fun conversation(
        @Path("conversationId") conversationId: String,
    ): AssistantConversationDetail

    @DELETE("api/assistant/v1/conversations/{conversationId}")
    suspend fun archiveConversation(
        @Path("conversationId") conversationId: String,
    ): Response<Unit>

    @PUT("api/assistant/v1/conversations/{conversationId}/selection")
    suspend fun replaceSelection(
        @Path("conversationId") conversationId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AssistantCabinSelectionRequest,
    ): AssistantCabinSelection

    @Streaming
    @Headers("Accept: text/event-stream")
    @POST("api/assistant/v1/conversations/{conversationId}/turns")
    suspend fun turn(
        @Path("conversationId") conversationId: String,
        @Body request: AssistantTurnRequest,
    ): Response<ResponseBody>
}
