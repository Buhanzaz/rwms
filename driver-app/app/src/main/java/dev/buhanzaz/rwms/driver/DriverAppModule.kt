package dev.buhanzaz.rwms.driver

import android.net.Uri
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.driver.core.auth.DriverAuthConfiguration
import dev.buhanzaz.rwms.driver.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.driver.core.network.BearerTokenInterceptor
import dev.buhanzaz.rwms.driver.core.network.RefreshingAuthenticator
import dev.buhanzaz.rwms.driver.core.network.SessionCredentialStore
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayApi
import dev.buhanzaz.rwms.driver.core.network.DriverSseClient
import dev.buhanzaz.rwms.driver.core.sync.DriverRealtimeInvalidationAlert
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
object DriverAppModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun publicBaseUrl(): HttpUrl = "${BuildConfig.PUBLIC_BASE_URL}/".toHttpUrl()

    @Provides
    @Singleton
    fun authConfiguration(): DriverAuthConfiguration = DriverAuthConfiguration(
        publicBaseUrl = Uri.parse(BuildConfig.PUBLIC_BASE_URL),
    )

    @Provides
    @Singleton
    fun gatewayMonitor(): AuthenticatedGatewayMonitor = AuthenticatedGatewayMonitor()

    @Provides
    @Singleton
    fun gatewayClient(
        credentials: SessionCredentialStore,
        monitor: AuthenticatedGatewayMonitor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BearerTokenInterceptor(credentials, monitor))
        .authenticator(RefreshingAuthenticator(credentials))
        .build()

    @Provides
    @Singleton
    fun driverGatewayApi(client: OkHttpClient, baseUrl: HttpUrl, json: Json): DriverGatewayApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriverGatewayApi::class.java)

    @Provides
    @Singleton
    fun driverSseClient(client: OkHttpClient, baseUrl: HttpUrl, json: Json): DriverSseClient =
        DriverSseClient(client, baseUrl, json)

    @Provides
    @Singleton
    fun driverRealtimeInvalidationAlert(
        implementation: DriverRealtimeNotificationAlert,
    ): DriverRealtimeInvalidationAlert = implementation
}
