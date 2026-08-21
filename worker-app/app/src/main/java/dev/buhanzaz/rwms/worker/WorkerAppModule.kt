package dev.buhanzaz.rwms.worker

import android.net.Uri
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.worker.core.auth.WorkerAuthConfiguration
import dev.buhanzaz.rwms.worker.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.worker.core.network.BearerTokenInterceptor
import dev.buhanzaz.rwms.worker.core.network.RefreshingAuthenticator
import dev.buhanzaz.rwms.worker.core.network.SessionCredentialStore
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApi
import dev.buhanzaz.rwms.worker.core.network.WorkerSseClient
import dev.buhanzaz.rwms.worker.core.sync.WorkerRealtimeInvalidationAlert
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
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
object WorkerAppModule {
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
    fun authConfiguration(): WorkerAuthConfiguration = WorkerAuthConfiguration(
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
    fun workerGatewayApi(client: OkHttpClient, baseUrl: HttpUrl, json: Json): WorkerGatewayApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkerGatewayApi::class.java)

    @Provides
    @Singleton
    fun photoBitmapSource(
        gateway: dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient,
    ): PhotoBitmapSource = GatewayPhotoBitmapSource(gateway)

    @Provides
    @Singleton
    fun workerSseClient(client: OkHttpClient, baseUrl: HttpUrl, json: Json): WorkerSseClient =
        WorkerSseClient(client, baseUrl, json)

    @Provides
    @Singleton
    fun workerRealtimeInvalidationAlert(
        implementation: WorkerRealtimeNotificationAlert,
    ): WorkerRealtimeInvalidationAlert = implementation
}
