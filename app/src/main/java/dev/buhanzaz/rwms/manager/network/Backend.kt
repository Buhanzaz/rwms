package dev.buhanzaz.rwms.manager.network

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.auth.ManagerAuthConfiguration
import dev.buhanzaz.rwms.manager.auth.ManagerAuthRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RwmsBackend(
    context: Context,
    publicBaseUrl: String,
) {
    val auth = ManagerAuthRepository(
        context = context,
        configuration = ManagerAuthConfiguration(Uri.parse(publicBaseUrl)),
    )

    private val moshi = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val authenticatedClient = OkHttpClient.Builder()
        .addInterceptor(BearerTokenInterceptor(auth))
        // A camera original can be several megabytes.  The default ten-second write timeout is
        // too short for a normal 4G upload, and turns a completed server-side upload into an
        // indistinguishable "no connection" retry on the device.
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    val api: RwmsApi = Retrofit.Builder()
        .baseUrl("${publicBaseUrl.trimEnd('/')}/")
        .client(authenticatedClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(RwmsApi::class.java)

    fun problemMessage(exception: HttpException): String {
        val raw = runCatching { exception.response()?.errorBody()?.string() }.getOrNull()
        val detail = raw?.let {
            runCatching {
                moshi.adapter(ProblemDetailsDto::class.java).fromJson(it)?.detail
            }.getOrNull()
        }
        val message = detail?.takeIf(String::isNotBlank)
            ?: when (exception.code()) {
                401 -> "Сессия завершена. Войдите снова"
                403 -> "Недостаточно прав для этой операции"
                404 -> "Данные не найдены"
                409 -> "Данные уже изменились"
                422 -> "Данные не прошли проверку"
                503 -> "Сервис временно недоступен"
                else -> "Ошибка сервера (${exception.code()})"
            }
        return if (exception.code() == 409) {
            "$message. Обновите данные и повторите действие"
        } else {
            message
        }
    }
}

private class BearerTokenInterceptor(
    private val auth: ManagerAuthRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { auth.freshAccessToken() }
            ?: throw IOException("Authenticated session is unavailable")
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        val response = chain.proceed(request)
        if (response.code != 401) return response

        val refreshed = runBlocking { auth.freshAccessToken(forceRefresh = true) }
            ?: return response
        response.close()
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $refreshed")
                .build(),
        )
    }
}
