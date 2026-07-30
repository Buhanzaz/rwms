package dev.buhanzaz.rwms.worker.core.network

import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response

class GatewayProblemException(
    val problem: ApiProblemDto,
) : RuntimeException(problem.detail ?: problem.title)

class GatewayProtocolException(message: String) : RuntimeException(message)

class GatewayUnavailableException(cause: Throwable? = null) : RuntimeException("Шлюз недоступен", cause)

fun <T> Response<T>.bodyOrProblem(json: Json): T {
    if (isSuccessful) {
        return body() ?: throw GatewayProtocolException("Пустой успешный ответ шлюза")
    }
    throw GatewayProblemException(errorBody().toApiProblem(json, code()))
}

fun ResponseBody?.toApiProblem(json: Json, status: Int): ApiProblemDto {
    val raw = this?.string().orEmpty()
    return runCatching { json.decodeFromString<ApiProblemDto>(raw) }.getOrElse {
        ApiProblemDto(
            type = "about:blank",
            title = "Ошибка шлюза",
            status = status,
            detail = raw.takeIf(String::isNotBlank),
            code = "HTTP_$status",
        )
    }
}
