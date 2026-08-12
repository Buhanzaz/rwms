package dev.buhanzaz.rwms.driver.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import net.openid.appauth.AuthState

private val Context.driverAuthDataStore by preferencesDataStore(name = "driver_auth_state")
private val encryptedStateKey = stringPreferencesKey("encrypted_auth_state")
private val encryptedDriverIdKey = stringPreferencesKey("encrypted_driver_id")
private val encryptedInstallationIdKey = stringPreferencesKey("encrypted_installation_id")
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val AUTH_KEY_ALIAS = "dev.buhanzaz.rwms.driver-state-v1"

/**
 * AppAuth state is encrypted before DataStore sees it. The AES key is
 * non-exportable and bound to Android Keystore; plaintext tokens never reach
 * backup or a browser-owned store.
 */
@Singleton
class EncryptedAuthStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun read(): AuthState? {
        val encrypted = context.driverAuthDataStore.data.first()[encryptedStateKey] ?: return null
        return runCatching { AuthState.jsonDeserialize(decrypt(encrypted)) }.getOrNull()
    }

    suspend fun write(state: AuthState) {
        val encrypted = encrypt(state.jsonSerializeString())
        context.driverAuthDataStore.edit { preferences -> preferences[encryptedStateKey] = encrypted }
    }

    suspend fun clear() {
        context.driverAuthDataStore.edit { preferences ->
            preferences.remove(encryptedStateKey)
            preferences.remove(encryptedDriverIdKey)
        }
    }

    suspend fun readBoundDriverId(): String? = context.driverAuthDataStore.data.first()[encryptedDriverIdKey]
        ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrNull() }

    suspend fun writeBoundDriverId(driverId: String) {
        context.driverAuthDataStore.edit { preferences ->
            preferences[encryptedDriverIdKey] = encrypt(driverId)
        }
    }

    suspend fun clearBoundDriverId() {
        context.driverAuthDataStore.edit { preferences -> preferences.remove(encryptedDriverIdKey) }
    }

    /**
     * The application installation identity is a device credential, not a
     * browser/session cache. It deliberately survives local logout so a later
     * authenticated session can renew the same server-side registration.
     */
    suspend fun readInstallationId(): String? = context.driverAuthDataStore.data.first()[encryptedInstallationIdKey]
        ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrNull() }

    suspend fun writeInstallationId(installationId: String) {
        context.driverAuthDataStore.edit { preferences ->
            preferences[encryptedInstallationIdKey] = encrypt(installationId)
        }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted auth state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            javax.crypto.spec.GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(AUTH_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    AUTH_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
