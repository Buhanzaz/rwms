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
import okhttp3.Call
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

    /** Credential-free client for OAuth exchanges and the explicitly public rental catalog. */
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
        .callFactory(customerApiCallFactory(client))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CustomerApi::class.java)

    /** Public catalog requests never carry or refresh a customer credential. */
    @Provides
    @Singleton
    fun publicCatalogApi(
        @Named("raw") client: OkHttpClient,
        json: Json,
    ): PublicCustomerCatalogApi = Retrofit.Builder()
        .baseUrl("${BuildConfig.PUBLIC_BASE_URL}/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PublicCustomerCatalogApi::class.java)
}

private val CUSTOMER_DELIVERY_SEARCH_PATH = Regex(
    "^/api/logistics/customer/v1/(?:bookings/[^/]+/)?delivery-slots/search$",
)

/** Extends only route searches so the gateway can return its bounded calculation response. */
private fun customerApiCallFactory(client: OkHttpClient): Call.Factory {
    val routingClient = client.newBuilder()
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    return Call.Factory { request ->
        val selected = if (request.method == "POST" && CUSTOMER_DELIVERY_SEARCH_PATH.matches(request.url.encodedPath)) {
            routingClient
        } else client
        selected.newCall(request)
    }
}

private val PUBLIC_CATALOG_PHOTO_PATH = Regex(
    "^/api/logistics/public/v1/catalog/warehouses/[^/]+/cabins/[^/]+/photos/[^/]+$",
)

/** A stale public photo must not refresh credentials or invalidate a newly signed-in session. */
internal fun customerImageCallFactory(authenticated: Call.Factory, publicCatalog: Call.Factory): Call.Factory =
    Call.Factory { request ->
        val client = if (request.method == "GET" && PUBLIC_CATALOG_PHOTO_PATH.matches(request.url.encodedPath)) {
            publicCatalog
        } else {
            authenticated
        }
        client.newCall(request)
    }

/** Normalized API failure used by ViewModels to distinguish conflicts and unavailable services. */
class CustomerApiException(
    val status: Int?,
    override val message: String,
    val code: String? = null,
) : RuntimeException(message)

private val CYRILLIC_USER_TEXT = Regex("[А-Яа-яЁё]")
private val TECHNICAL_ERROR_TEXT = Regex(
    pattern = """(?i)(https?://|\bhttp\s*\d{3}\b|exception|traceback|stack\s+trace|sqlstate|""" +
        """\b(select|insert|update|delete)\s+.+\b(from|into|set)\b|java\.|org\.|dev\.|""" +
        """service\s+returned|manual\s+change\s+invalid|rms\s+logistics|\{\s*\"|<html|/api/)""",
)

/** Returns actionable Russian copy while retaining raw Problem Details only for control flow. */
internal fun customerProblemMessage(
    status: Int?,
    problem: ProblemDetails?,
    fallback: String = customerStatusFallback(status),
): String {
    customerProblemCodeMessage(problem?.code)?.let { return it }
    val detail = problem?.detail?.trim()
    if (detail != null &&
        detail.length <= 512 &&
        CYRILLIC_USER_TEXT.containsMatchIn(detail) &&
        !TECHNICAL_ERROR_TEXT.containsMatchIn(detail)
    ) {
        return detail
    }
    return fallback
}

private fun customerProblemCodeMessage(code: String?): String? = when (code) {
    "INQUIRY_ARCHIVED" -> "Этот заказ уже завершён. Начните новый заказ."
    "CUSTOMER_CART_BUSY" -> "Заказ уже обрабатывается. Обновите экран перед повторным действием."
    "CUSTOMER_CART_VERSION_CONFLICT" ->
        "Состав заказа изменился. Обновите экран и повторите действие."
    "CUSTOMER_CABINS_REQUIRED" -> "Добавьте хотя бы одну бытовку перед выбором доставки."
    "CUSTOMER_DELIVERY_SLOT_EXPIRED" ->
        "Выбранное время уже недоступно. Выберите другой слот."
    "CUSTOMER_DELIVERY_SITE_CAPACITY_CHANGED" ->
        "Условия разгрузки изменились. Рассчитайте доступное время заново."
    "CUSTOMER_ROUTE_ATTESTATIONS_REQUIRED" ->
        "Подтвердите условия проезда на участок и повторите действие."
    "CUSTOMER_DELIVERY_ROUTE_NOT_FOUND" ->
        "Безопасный маршрут до адреса не найден. Проверьте точку доставки или выберите другой адрес."
    "CUSTOMER_ROUTING_UNAVAILABLE" ->
        "Расчёт маршрута временно недоступен. Повторите попытку позже."
    "CUSTOMER_BOOKING_NOT_EDITABLE" ->
        "Заказ уже передан в работу. Отменить или перенести доставку больше нельзя."
    "CUSTOMER_BOOKING_VERSION_CONFLICT" ->
        "Заказ изменился. Обновите его и повторите действие."
    "CUSTOMER_CHANGE_QUOTE_REQUIRED" -> "Сначала рассчитайте условия изменения заказа."
    "CUSTOMER_CHANGE_QUOTE_STALE" -> "Условия изменения устарели. Рассчитайте доступное время и неустойку заново."
    "CUSTOMER_CHANGE_QUOTE_NOT_FOUND" -> "Условия изменения не найдены. Обновите заказ."
    "CUSTOMER_CHANGE_QUOTE_INVALID" -> "Не удалось подтвердить условия изменения. Рассчитайте их заново."
    "CUSTOMER_CHANGE_POLICY_UNCONFIGURED" -> "Условия изменения пока не настроены. Свяжитесь с менеджером."
    "CUSTOMER_CHANGE_PAYMENT_REQUIRED" -> "Для изменения нужна оплата неустойки. Проверьте условия или свяжитесь с менеджером."
    "CUSTOMER_BOOKING_CANCELLATION_PENDING" ->
        "Отмена заказа уже выполняется. Обновите статус немного позже."
    "ORDER_MUTATION_PENDING" ->
        "Изменение заказа уже выполняется. Обновите статус немного позже."
    "CUSTOMER_BOOKING_RECONCILIATION_REQUIRED" ->
        "Отмена требует проверки сотрудником RWMS. Текущий статус сохранён."
    "ORDER_MUTATION_RECONCILIATION_REQUIRED" ->
        "Изменение заказа требует проверки сотрудником RWMS. Текущий статус сохранён."
    "CUSTOMER_BOOKING_CANCELLATION_FAILED" ->
        "Не удалось завершить отмену. Обновите статус или повторите попытку позже."
    "CUSTOMER_BOOKING_MUTATION_FAILED", "ORDER_MUTATION_RECOVERY_FAILED",
    "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED" ->
        "Не удалось завершить изменение заказа. Обновите статус и повторите действие позже."
    "CUSTOMER_BOOKING_NOT_FOUND" ->
        "Заказ не найден. Обновите список заказов."
    "CUSTOMER_DELIVERY_SLOT_NOT_FOUND" ->
        "Выбранное время уже недоступно. Рассчитайте варианты доставки заново."
    "CUSTOMER_DELIVERY_SLOT_TAKEN" ->
        "Выбранное время уже занято. Рассчитайте доступные варианты заново."
    "IDEMPOTENCY_KEY_REUSED" ->
        "Повтор команды не совпадает с исходным действием. Обновите заказ и повторите попытку."
    "UNAUTHORIZED", "AUTHENTICATION_REQUIRED", "INVALID_TOKEN" ->
        "Сессия завершена. Войдите снова."
    "FORBIDDEN", "ACCESS_DENIED" ->
        "Недостаточно прав для действия. Обратитесь к администратору."
    else -> null
}

private fun customerStatusFallback(status: Int?): String = when (status) {
    401 -> "Сессия завершена. Войдите снова."
    403 -> "Недостаточно прав для действия. Обратитесь к администратору."
    404 -> "Данные не найдены. Обновите экран и повторите действие."
    409 -> "Данные изменились. Обновите экран и повторите действие."
    422 -> "Проверьте заполненные данные и повторите действие."
    429 -> "Слишком много запросов. Подождите и повторите действие."
    else -> if (status != null && status in 500..599) {
        "Сервис временно недоступен. Повторите попытку позже."
    } else {
        "Не удалось выполнить запрос. Проверьте данные и повторите действие."
    }
}

/** Converts Retrofit/transport exceptions into localized, non-fabricated UI failures. */
fun Throwable.toCustomerApiException(json: Json): CustomerApiException = when (this) {
    is CustomerApiException -> this
    is HttpException -> {
        val raw = response()?.errorBody()?.string().orEmpty().take(16_384)
        val problem = runCatching { json.decodeFromString<ProblemDetails>(raw) }.getOrNull()
        CustomerApiException(
            status = code(),
            message = customerProblemMessage(code(), problem),
            code = problem?.code?.takeIf(String::isNotBlank),
        )
    }
    is IOException -> CustomerApiException(
        null,
        "Нет соединения. Проверьте интернет и повторите действие.",
    )
    else -> CustomerApiException(
        null,
        "Не удалось выполнить запрос. Проверьте данные и повторите действие.",
    )
}
