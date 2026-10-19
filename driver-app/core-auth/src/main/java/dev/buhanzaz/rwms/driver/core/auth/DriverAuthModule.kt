package dev.buhanzaz.rwms.driver.core.auth

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.driver.core.network.SessionCredentialStore
import javax.inject.Singleton
import javax.inject.Qualifier
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
abstract class DriverAuthBindings {
    @Binds
    @Singleton
    abstract fun bindDriverAuthRepository(implementation: AppAuthDriverAuthRepository): DriverAuthRepository

    @Binds
    @Singleton
    abstract fun bindSessionCredentialStore(implementation: AppAuthDriverAuthRepository): SessionCredentialStore
}

@Module
@InstallIn(SingletonComponent::class)
/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
object DriverAuthModule {
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
/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
annotation class RevocationClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
annotation class NativeLoginClient
