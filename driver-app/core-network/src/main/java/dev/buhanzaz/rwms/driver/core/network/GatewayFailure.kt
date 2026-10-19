package dev.buhanzaz.rwms.driver.core.network

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
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
 * Maps typed Problem Details metadata to an actionable driver-facing Russian
 * message. Raw titles, details, violations, exception names, and response
 * bodies remain available only through [ApiProblemDto] for structured
 * diagnostics and must not cross the presentation or durable-outbox boundary.
 *
 * A short Russian domain message is preserved only for explicitly trusted
 * public domain codes and only after rejecting technical payload markers.
 */
fun gatewayProblemUserMessage(problem: ApiProblemDto): String {
    val code = problem.code.uppercase()
    return when (gatewayFailureDisposition(problem.status)) {
        GatewayFailureDisposition.AUTHENTICATION_REQUIRED ->
            "Сессия истекла. Войдите снова."
        GatewayFailureDisposition.USER_ACTION_REQUIRED ->
            "Доступ к действию закрыт. Обновите права или обратитесь к логисту."
        GatewayFailureDisposition.CONFLICT ->
            specificProblemMessage(code)
                ?: trustedRussianDomainMessage(problem)
                ?: "Данные изменились. Синхронизируйте список заданий и повторите действие."
        GatewayFailureDisposition.RETRYABLE -> if (problem.status == 429) {
            "Слишком много запросов. Подождите и повторите попытку."
        } else {
            "RWMS временно недоступен. Проверьте соединение и повторите попытку."
        }
        GatewayFailureDisposition.TERMINAL -> specificProblemMessage(code) ?: when {
            problem.status == 404 || code in MISSING_GATEWAY_RESOURCE_CODES ->
                "Задание больше недоступно. Синхронизируйте список заданий."
            problem.status == 400 ||
                problem.status == 415 ||
                code in INVALID_GATEWAY_REQUEST_CODES ||
                code.startsWith("MEDIA_INVALID_") ->
                trustedRussianDomainMessage(problem)
                    ?: "Проверьте данные задания и повторите действие."
            else ->
                "Не удалось выполнить действие. Синхронизируйте данные и повторите попытку."
        }
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
 * Converts a caught driver failure into text that is safe to render. Typed
 * public Problem Details keep their approved domain mapping, a proven
 * transport outage gets one actionable connectivity message, and every other
 * throwable uses the operation-specific [fallback] supplied by its caller.
 *
 * Cancellation is rethrown so coroutine shutdown never becomes a visible
 * failure or a persisted upload error. The original throwable remains intact
 * for structured diagnostics and is never copied into presentation state.
 */
fun Throwable.toDriverUserMessage(fallback: String): String {
    require(fallback.isNotBlank()) { "Driver-facing fallback must not be blank" }
    val causes = causeChain().toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    causes.filterIsInstance<GatewayProblemException>().firstOrNull()?.let { failure ->
        return gatewayProblemUserMessage(failure.problem)
    }
    return if (causes.any { it is IOException || it is GatewayUnavailableException }) {
        DRIVER_CONNECTIVITY_MESSAGE
    } else {
        fallback
    }
}

/**
 * A typed public-gateway Problem Details response. The response status remains
 * authoritative even if a malformed upstream body claimed another status.
 * Its exception message is safe for driver-facing surfaces; [problem] retains
 * the raw transport payload for typed handling and structured diagnostics.
 */
class GatewayProblemException(
    val problem: ApiProblemDto,
) : RuntimeException(gatewayProblemUserMessage(problem)) {
    val disposition: GatewayFailureDisposition = gatewayFailureDisposition(problem.status)
}

/**
 * Indicates an invalid gateway response that cannot safely be interpreted as a
 * successful driver API payload.
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

private const val DRIVER_CONNECTIVITY_MESSAGE =
    "Нет связи с RWMS. Проверьте интернет и повторите действие."

private val MISSING_GATEWAY_RESOURCE_CODES = setOf(
    "TASK_BOARD_NOT_FOUND",
    "LOGISTICS_DOCUMENT_NOT_FOUND",
    "MEDIA_NOT_FOUND",
)

private val INVALID_GATEWAY_REQUEST_CODES = setOf(
    "TASK_BOARD_INVALID_REQUEST",
    "TASK_BOARD_VALIDATION_FAILED",
    "LOGISTICS_INVALID_REQUEST",
    "LOGISTICS_VALIDATION_FAILED",
    "MEDIA_UNSUPPORTED_TYPE",
)

private val TRUSTED_RUSSIAN_DOMAIN_DETAIL_CODES = setOf(
    "TASK_BOARD_CONFLICT",
    "TASK_BOARD_INVALID_REQUEST",
    "TASK_BOARD_VALIDATION_FAILED",
    "LOGISTICS_CONFLICT",
    "LOGISTICS_DEPENDENCY_CONFLICT",
    "LOGISTICS_INVALID_REQUEST",
    "LOGISTICS_VALIDATION_FAILED",
)

private val TECHNICAL_MESSAGE_MARKERS = listOf(
    "http://",
    "https://",
    "/api/",
    "exception",
    "stack",
    "trace",
    "sql",
    "select ",
    "insert ",
    "delete ",
    "java.",
    "kotlin.",
    "org.",
    "com.",
    "dev.",
    ".kt:",
    ".java:",
    "http 4",
    "http 5",
    "gateway",
    "service returned",
    "rms logistics service",
    "{",
    "}",
    "<",
    ">",
)

private fun specificProblemMessage(code: String): String? = when {
    code.contains("LEASE") ->
        "Офлайн-допуск истёк. Подключитесь к сети и синхронизируйте задания."
    code.contains("CAPACITY") || code.contains("OVERLOAD") ->
        "Действие невозможно: превышена вместимость транспорта. Обратитесь к логисту."
    code.contains("WINDOW") || code.contains("APPOINTMENT") || code.contains("SLOT") ->
        "Действие не помещается во временное окно. Обратитесь к логисту."
    code.contains("SHIFT") ->
        "Состояние смены изменилось. Синхронизируйте данные и повторите действие."
    code.contains("ROUTE") || code.contains("TRIP") || code.contains("CYCLE") ->
        "Маршрут изменился или действие нарушает его порядок. Синхронизируйте задания."
    code.contains("STALE") || code.contains("VERSION_CONFLICT") ->
        "Данные задания изменились. Синхронизируйте список и повторите действие."
    code == "MEDIA_UPLOAD_EXPIRED" ->
        "Срок загрузки фотографии истёк. Запустите синхронизацию ещё раз."
    code == "MEDIA_OBJECT_MISMATCH" ->
        "Фотография не прошла проверку. Сделайте снимок заново и повторите отправку."
    code == "MEDIA_NOT_READY" || code == "MEDIA_REFERENCE_NOT_READY" ->
        "Фотография ещё обрабатывается. Повторите синхронизацию позже."
    else -> null
}

private fun trustedRussianDomainMessage(problem: ApiProblemDto): String? {
    if (problem.code.uppercase() !in TRUSTED_RUSSIAN_DOMAIN_DETAIL_CODES) return null
    val candidate = problem.detail?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (candidate.length > MAX_TRUSTED_DOMAIN_MESSAGE_LENGTH || '\n' in candidate || '\r' in candidate) {
        return null
    }
    if (candidate.none { it in 'А'..'я' || it == 'Ё' || it == 'ё' }) return null
    val normalized = candidate.lowercase()
    if (TECHNICAL_MESSAGE_MARKERS.any(normalized::contains)) return null
    return candidate
}

/** Traverses wrapped failures once even if a malformed throwable graph contains a cause cycle. */
private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causeChain
    while (current != null && visited.add(current)) {
        yield(current)
        current = current.cause
    }
}

private const val MAX_TRUSTED_DOMAIN_MESSAGE_LENGTH = 240
