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
 * Maps typed Problem Details metadata to a fixed worker-facing Russian message.
 * Backend title, detail, and violation text are deliberately ignored; callers
 * may retain the original payload only for structured diagnostics.
 */
fun gatewayProblemUserMessage(problem: ApiProblemDto): String =
    when (gatewayFailureDisposition(problem.status)) {
        GatewayFailureDisposition.AUTHENTICATION_REQUIRED ->
            "Сессия истекла. Войдите снова."
        GatewayFailureDisposition.USER_ACTION_REQUIRED ->
            "Доступ к действию закрыт. Обновите права или обратитесь к руководителю."
        GatewayFailureDisposition.CONFLICT -> when (problem.code) {
            "MEDIA_UPLOAD_EXPIRED" ->
                "Срок загрузки фотографии истёк. Запустите синхронизацию ещё раз."
            "MEDIA_OBJECT_MISMATCH" ->
                "Фотография не прошла проверку. Сделайте снимок заново и повторите отправку."
            "MEDIA_NOT_READY", "MEDIA_REFERENCE_NOT_READY" ->
                "Фотография ещё обрабатывается. Повторите синхронизацию позже."
            else ->
                "Данные изменились. Обновите список заданий и повторите действие."
        }
        GatewayFailureDisposition.RETRYABLE -> if (problem.status == 429) {
            "Слишком много запросов. Подождите и повторите попытку."
        } else {
            "RWMS временно недоступен. Проверьте соединение и повторите попытку."
        }
        GatewayFailureDisposition.TERMINAL -> when {
            problem.status == 404 || problem.code in MISSING_GATEWAY_RESOURCE_CODES ->
                "Данные больше недоступны. Обновите список заданий."
            problem.status == 400 ||
                problem.status == 415 ||
                problem.code in INVALID_GATEWAY_REQUEST_CODES ||
                problem.code.startsWith("MEDIA_INVALID_") ->
                "Проверьте данные и повторите действие."
            else ->
                "Не удалось выполнить действие. Обновите данные и повторите попытку."
        }
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
 * Converts a failure into safe worker-facing text without exposing exception or
 * backend-controlled messages. Typed gateway problems keep their domain-aware
 * presentation, proven transport outages receive one fixed recovery hint, and
 * every other failure uses the caller's action-specific [fallback]. A
 * cancellation found anywhere in the cause chain is rethrown so presentation
 * mapping cannot break structured concurrency.
 */
fun Throwable.safeWorkerUserMessage(fallback: String): String {
    val causes = generateSequence(this) { it.cause }.toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    causes.filterIsInstance<GatewayProblemException>().firstOrNull()?.let { problem ->
        return gatewayProblemUserMessage(problem.problem)
    }
    return if (causes.any { it is IOException || it is GatewayUnavailableException }) {
        SAFE_GATEWAY_TRANSPORT_MESSAGE
    } else {
        fallback
    }
}

/**
 * A typed public-gateway Problem Details response. The response status remains
 * authoritative even if a malformed upstream body claimed another status.
 * Its exception message is safe for worker-facing surfaces; [problem] retains
 * the raw transport payload for typed handling and structured diagnostics.
 */
class GatewayProblemException(
    val problem: ApiProblemDto,
) : RuntimeException(gatewayProblemUserMessage(problem)) {
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

private val MISSING_GATEWAY_RESOURCE_CODES = setOf(
    "TASK_BOARD_NOT_FOUND",
    "MEDIA_NOT_FOUND",
)

private val INVALID_GATEWAY_REQUEST_CODES = setOf(
    "TASK_BOARD_INVALID_REQUEST",
    "TASK_BOARD_VALIDATION_FAILED",
    "MEDIA_UNSUPPORTED_TYPE",
)

private const val SAFE_GATEWAY_TRANSPORT_MESSAGE =
    "Нет соединения с RWMS. Проверьте сеть и повторите попытку."
