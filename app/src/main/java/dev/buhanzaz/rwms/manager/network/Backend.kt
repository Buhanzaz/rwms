package dev.buhanzaz.rwms.manager.network

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.auth.ManagerAuthConfiguration
import dev.buhanzaz.rwms.manager.auth.ManagerAuthRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private val MANAGER_CYRILLIC_USER_TEXT = Regex("[А-Яа-яЁё]")
private val MANAGER_TECHNICAL_ERROR_TEXT = Regex(
    pattern = """(?i)(https?://|\bhttp\s*\d{3}\b|exception|traceback|stack\s+trace|sqlstate|""" +
        """\b(select|insert|update|delete)\s+.+\b(from|into|set)\b|java\.|org\.|dev\.|""" +
        """service\s+returned|manual\s+change\s+invalid|rms\s+logistics|\{\s*\"|<html|/api/)""",
)

/** Maps Problem Details to actionable copy without exposing transport or implementation text. */
internal fun managerProblemMessage(
    status: Int,
    problem: ProblemDetailsDto?,
): String {
    managerProblemCodeMessage(problem?.code)?.let { return it }
    val detail = problem?.detail?.trim()
    if (detail != null &&
        detail.length <= 512 &&
        MANAGER_CYRILLIC_USER_TEXT.containsMatchIn(detail) &&
        !MANAGER_TECHNICAL_ERROR_TEXT.containsMatchIn(detail)
    ) {
        return if (status == 409 && !detail.contains("обнов", ignoreCase = true)) {
            "$detail Обновите данные и повторите действие."
        } else {
            detail
        }
    }
    return managerStatusFallback(status)
}

private fun managerProblemCodeMessage(code: String?): String? = when (code) {
    "UNAUTHORIZED", "AUTHENTICATION_REQUIRED", "INVALID_TOKEN" ->
        "Сессия завершена. Войдите снова."
    "FORBIDDEN", "ACCESS_DENIED" ->
        "Недостаточно прав для операции. Обратитесь к администратору."
    "VERSION_CONFLICT", "OPTIMISTIC_LOCK_CONFLICT" ->
        "Данные уже изменились. Обновите экран и повторите действие."
    "VALIDATION_FAILED" ->
        "Проверьте заполненные данные и повторите действие."
    else -> null
}

private fun managerStatusFallback(status: Int): String = when (status) {
    401 -> "Сессия завершена. Войдите снова."
    403 -> "Недостаточно прав для операции. Обратитесь к администратору."
    404 -> "Данные не найдены. Обновите экран и повторите действие."
    409 -> "Данные уже изменились. Обновите экран и повторите действие."
    422 -> "Проверьте заполненные данные и повторите действие."
    429 -> "Слишком много запросов. Подождите и повторите действие."
    in 500..599 -> "Сервис временно недоступен. Повторите попытку позже."
    else -> "Не удалось выполнить операцию. Проверьте данные и повторите действие."
}

/**
 * Encapsulates the manager public-gateway transport boundary; it is not a backend domain or persistence type.
 */
class RwmsBackend(
    context: Context,
    publicBaseUrl: String,
) {
    private val applicationContext = context.applicationContext
    private val apiBaseUrl = "${publicBaseUrl.trimEnd('/')}/"

    val auth = ManagerAuthRepository(
        context = applicationContext,
        configuration = ManagerAuthConfiguration(Uri.parse(publicBaseUrl)),
    )

    /*
     * The signed-out screen needs only [auth.state].  Building Moshi, OkHttp and the Retrofit
     * proxy here used to make their class verification and platform setup part of the first
     * Compose frame.  Keep the transport identical, but defer it until an authenticated action
     * actually needs it.
     */
    private val moshi: Moshi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }
    private val authenticatedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // The manifest initializer is intentionally removed from the signed-out cold path.  The
        // public OkHttp hook keeps its Android application context available for real requests
        // (including the public-suffix asset reader) when the transport is first needed.
        OkHttp.initialize(applicationContext)
        OkHttpClient.Builder()
            .addInterceptor(
                BearerTokenInterceptor { forceRefresh, rejectedAccessToken ->
                    auth.freshAccessToken(
                        forceRefresh = forceRefresh,
                        rejectedAccessToken = rejectedAccessToken,
                    )
                },
            )
            // Video source uploads can still be several megabytes. The default ten-second write
            // timeout is too short for normal 4G and can turn an accepted idempotent upload into
            // an indistinguishable "no connection" retry on the device.
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
    }

    val api: RwmsApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(authenticatedClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RwmsApi::class.java)
    }

    suspend fun warmUpTransport() {
        withContext(Dispatchers.IO) { api }
    }

    fun problemMessage(exception: HttpException): String {
        val raw = runCatching { exception.response()?.errorBody()?.string() }.getOrNull()
        val problem = raw?.let {
            runCatching {
                moshi.adapter(ProblemDetailsDto::class.java).fromJson(it)
            }.getOrNull()
        }
        return managerProblemMessage(exception.code(), problem)
    }
}

/**
 * Retries one unauthorized gateway request with a serialized refreshed token. Refresh transport
 * failures are propagated instead of exposing a misleading 401 that would clear a valid session.
 */
internal class BearerTokenInterceptor(
    private val freshAccessToken: suspend (Boolean, String?) -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking {
            freshAccessToken(false, null)
        }
            ?: throw IOException("Authenticated session is unavailable")
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        val response = chain.proceed(request)
        if (response.code != 401) return response

        val refreshed = try {
            runBlocking { freshAccessToken(true, token) }
        } catch (failure: Throwable) {
            response.close()
            throw failure
        } ?: return response
        response.close()
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $refreshed")
                .build(),
        )
    }
}
