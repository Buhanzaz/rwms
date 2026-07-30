package dev.buhanzaz.rwms.worker.core.auth

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.worker.core.network.SessionCredentialStore
import javax.inject.Singleton
import javax.inject.Qualifier
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerAuthBindings {
    @Binds
    @Singleton
    abstract fun bindWorkerAuthRepository(implementation: AppAuthWorkerAuthRepository): WorkerAuthRepository

    @Binds
    @Singleton
    abstract fun bindSessionCredentialStore(implementation: AppAuthWorkerAuthRepository): SessionCredentialStore
}

@Module
@InstallIn(SingletonComponent::class)
object WorkerAuthModule {
    @Provides
    @Singleton
    fun authorizationService(@ApplicationContext context: Context): AuthorizationService = AuthorizationService(context)

    @Provides
    @Singleton
    @RevocationClient
    fun revocationClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    @NativeLoginClient
    fun nativeLoginClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RevocationClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NativeLoginClient
