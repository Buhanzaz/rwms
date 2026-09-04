package dev.buhanzaz.rwms.rentalmanager.network

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthConfiguration
import dev.buhanzaz.rwms.rentalmanager.auth.RentalManagerAuthRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private val CYRILLIC_USER_TEXT = Regex("[А-Яа-яЁё]")
private val TECHNICAL_ERROR_TEXT = Regex(
    pattern = """(?i)(https?://|\bhttp\s*\d{3}\b|exception|traceback|stack\s+trace|sqlstate|""" +
        """java\.|org\.|dev\.|service\s+returned|\{\s*\"|<html|/api/)""",
)

internal fun rentalManagerProblemMessage(status: Int, problem: ProblemDetailsDto?): String {
    val byCode = when (problem?.code) {
        "UNAUTHORIZED", "AUTHENTICATION_REQUIRED", "INVALID_TOKEN" ->
            "Сессия завершена. Войдите снова."
        "FORBIDDEN", "ACCESS_DENIED" ->
            "Недостаточно прав для операции. Обратитесь к администратору."
        "VERSION_CONFLICT", "OPTIMISTIC_LOCK_CONFLICT" ->
            "Данные уже изменились. Обновите экран и повторите действие."
        "VALIDATION_FAILED" -> "Проверьте заполненные данные и повторите действие."
        else -> null
    }
    if (byCode != null) return byCode

    val detail = problem?.detail?.trim()
    if (detail != null &&
        detail.length <= 512 &&
        CYRILLIC_USER_TEXT.containsMatchIn(detail) &&
        !TECHNICAL_ERROR_TEXT.containsMatchIn(detail)
    ) {
        return if (status == 409 && !detail.contains("обнов", ignoreCase = true)) {
            "$detail Обновите данные и повторите действие."
        } else {
            detail
        }
    }
    return when (status) {
        401 -> "Сессия завершена. Войдите снова."
        403 -> "Недостаточно прав для операции. Обратитесь к администратору."
        404 -> "Данные не найдены. Обновите экран и повторите действие."
        409 -> "Данные уже изменились. Обновите экран и повторите действие."
        422 -> "Проверьте заполненные данные и повторите действие."
        429 -> "Слишком много запросов. Подождите и повторите действие."
        in 500..599 -> "Сервис временно недоступен. Повторите попытку позже."
        else -> "Не удалось выполнить операцию. Проверьте данные и повторите действие."
    }
}

/** Owns Android transport construction while auth-service remains the credential owner. */
class RentalManagerBackend(
    context: Context,
    publicBaseUrl: String,
) {
    private val applicationContext = context.applicationContext
    private val apiBaseUrl = "${publicBaseUrl.trimEnd('/')}/"
    private val moshi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    val auth = RentalManagerAuthRepository(
        context = applicationContext,
        configuration = RentalManagerAuthConfiguration(Uri.parse(publicBaseUrl)),
    )

    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttp.initialize(applicationContext)
        OkHttpClient.Builder()
            .addInterceptor(
                RentalManagerBearerInterceptor { forceRefresh, rejectedAccessToken ->
                    auth.freshAccessToken(forceRefresh, rejectedAccessToken)
                },
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val assistantClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttp.initialize(applicationContext)
        rentalManagerAssistantClient(
            RentalManagerBearerInterceptor(
                freshAccessToken = { forceRefresh, rejectedAccessToken ->
                    auth.freshAccessToken(forceRefresh, rejectedAccessToken)
                },
                retryUnauthorized = false,
            ),
        )
    }

    internal val assistantMoshi: Moshi
        get() = moshi

    val api: RentalManagerApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RentalManagerApi::class.java)
    }

    val assistantApi: AssistantApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(assistantClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AssistantApi::class.java)
    }

    fun problemMessage(exception: HttpException): String {
        val raw = runCatching { exception.response()?.errorBody()?.string() }.getOrNull()
        val problem = raw?.let {
            runCatching { moshi.adapter(ProblemDetailsDto::class.java).fromJson(it) }.getOrNull()
        }
        return rentalManagerProblemMessage(exception.code(), problem)
    }
}

internal fun rentalManagerAssistantClient(authInterceptor: Interceptor): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // A turn is a non-idempotent POST and must never be replayed after transport failure.
        .retryOnConnectionFailure(false)
        // assistant-service has a bounded 120-second SSE turn window.
        .readTimeout(130, TimeUnit.SECONDS)
        .callTimeout(135, TimeUnit.SECONDS)
        .build()

/** Retries one 401 exactly once with the process-serialized refresh token rotation. */
internal class RentalManagerBearerInterceptor(
    private val retryUnauthorized: Boolean = true,
    private val freshAccessToken: suspend (Boolean, String?) -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { freshAccessToken(false, null) }
            ?: throw IOException("Authenticated session is unavailable")
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        val response = chain.proceed(request)
        if (response.code != 401 || !retryUnauthorized) return response

        val refreshed = try {
            runBlocking { freshAccessToken(true, token) }
        } catch (failure: Throwable) {
            response.close()
            throw failure
        } ?: return response
        response.close()
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $refreshed").build(),
        )
    }
}
