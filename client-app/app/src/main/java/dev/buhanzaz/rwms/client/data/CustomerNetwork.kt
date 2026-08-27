package dev.buhanzaz.rwms.client.data

import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.auth.CustomerBearerInterceptor
import dev.buhanzaz.rwms.client.auth.CustomerTokenAuthenticator
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Provides one explicit, inspectable dependency graph for auth and customer API transports. */
@Module
@InstallIn(SingletonComponent::class)
object CustomerNetworkModule {
    /** Tolerant response decoder while all outbound models remain strictly typed. */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Credential-free client used only by OAuth, CSRF, and registration exchanges. */
    @Provides
    @Singleton
    @Named("raw")
    fun rawClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Authenticated API client with one refresh retry and no body/token logging. */
    @Provides
    @Singleton
    @Named("customer")
    fun customerClient(
        bearerInterceptor: CustomerBearerInterceptor,
        authenticator: CustomerTokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(bearerInterceptor)
        .authenticator(authenticator)
        .build()

    /** Retrofit implementation rooted only at the configured public gateway. */
    @Provides
    @Singleton
    fun customerApi(
        @Named("customer") client: OkHttpClient,
        json: Json,
    ): CustomerApi = Retrofit.Builder()
        .baseUrl("${BuildConfig.PUBLIC_BASE_URL}/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CustomerApi::class.java)
}

/** Normalized API failure used by ViewModels to distinguish conflicts and unavailable services. */
class CustomerApiException(
    val status: Int?,
    override val message: String,
) : RuntimeException(message)

/** Converts Retrofit/transport exceptions into localized, non-fabricated UI failures. */
fun Throwable.toCustomerApiException(json: Json): CustomerApiException = when (this) {
    is CustomerApiException -> this
    is HttpException -> {
        val raw = response()?.errorBody()?.string().orEmpty().take(16_384)
        val detail = runCatching { json.decodeFromString<ProblemDetails>(raw).detail }.getOrNull()
        val fallback = when (code()) {
            401 -> "Сессия завершена. Войдите снова"
            403 -> "Недостаточно прав для действия"
            404 -> "Данные не найдены"
            409 -> "Данные изменились. Обновите экран и повторите действие"
            422 -> "Проверьте заполненные данные"
            429 -> "Слишком много запросов. Повторите позже"
            in 500..599 -> "Сервис временно недоступен"
            else -> "Не удалось выполнить запрос"
        }
        CustomerApiException(code(), detail?.takeIf(String::isNotBlank) ?: fallback)
    }
    is IOException -> CustomerApiException(null, "Нет соединения с RWMS")
    else -> CustomerApiException(null, "Не удалось выполнить запрос")
}
