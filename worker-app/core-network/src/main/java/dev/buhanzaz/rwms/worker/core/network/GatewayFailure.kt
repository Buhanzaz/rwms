package dev.buhanzaz.rwms.worker.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Classifies a public-gateway response so callers distinguish authentication,
 * grant, conflict, retryable dependency, and terminal failures without
 * inferring status semantics from a message.
 */
enum class GatewayFailureDisposition {
    AUTHENTICATION_REQUIRED,
    USER_ACTION_REQUIRED,
    CONFLICT,
    RETRYABLE,
    TERMINAL,
}

/**
 * Returns the single client recovery disposition for an HTTP response status.
 * Only declared temporary gateway statuses can be retried automatically.
 */
fun gatewayFailureDisposition(status: Int): GatewayFailureDisposition = when (status) {
    401 -> GatewayFailureDisposition.AUTHENTICATION_REQUIRED
    403 -> GatewayFailureDisposition.USER_ACTION_REQUIRED
    409 -> GatewayFailureDisposition.CONFLICT
    in RETRYABLE_GATEWAY_STATUSES -> GatewayFailureDisposition.RETRYABLE
    else -> GatewayFailureDisposition.TERMINAL
}

/**
 * Identifies the narrow HTTP status set that may trigger automatic recovery.
 * A 500 or any other unexpected status is terminal until a user or operator
 * obtains fresh authoritative state.
 */
fun isRetryableGatewayStatus(status: Int): Boolean = status in RETRYABLE_GATEWAY_STATUSES

/**
 * Returns true only for a transport failure without a usable HTTP response.
 * Cancellation is intentionally excluded and must propagate to WorkManager.
 */
fun Throwable.isProvenGatewayTransportFailure(): Boolean =
    generateSequence(this) { it.cause }.none { it is CancellationException } &&
        generateSequence(this) { it.cause }
        .any { it is IOException || it is GatewayUnavailableException }

/**
 * A typed public-gateway Problem Details response. The response status remains
 * authoritative even if a malformed upstream body claimed another status.
 */
class GatewayProblemException(
    val problem: ApiProblemDto,
) : RuntimeException(problem.detail ?: problem.title) {
    val disposition: GatewayFailureDisposition = gatewayFailureDisposition(problem.status)
}

/**
 * Indicates an invalid gateway response that cannot safely be interpreted as a
 * successful worker API payload.
 */
class GatewayProtocolException(message: String) : RuntimeException(message)

/**
 * Marks a verified public-gateway transport outage for callers that need a
 * retryable distinction from a terminal HTTP Problem Details response.
 */
class GatewayUnavailableException(cause: Throwable? = null) : RuntimeException("Шлюз недоступен", cause)

/**
 * Returns the required success body or converts a non-success Retrofit
 * response into normalized, typed Problem Details using its actual HTTP code.
 */
fun <T> Response<T>.bodyOrProblem(json: Json): T {
    if (isSuccessful) {
        return body() ?: throw GatewayProtocolException("Пустой успешный ответ шлюза")
    }
    throw GatewayProblemException(errorBody().toApiProblem(json, code()))
}

/**
 * Decodes canonical Problem Details without trusting its embedded status. A
 * malformed body becomes a safe typed fallback instead of a parser failure.
 */
fun ResponseBody?.toApiProblem(json: Json, status: Int): ApiProblemDto {
    val raw = this?.string().orEmpty()
    return runCatching { json.decodeFromString<ApiProblemDto>(raw) }
        .map { it.copy(status = status) }
        .getOrElse {
            ApiProblemDto(
                type = "about:blank",
                title = "Ошибка шлюза",
                status = status,
                detail = null,
                code = "HTTP_$status",
            )
        }
}

private val RETRYABLE_GATEWAY_STATUSES = setOf(429, 502, 503, 504)
