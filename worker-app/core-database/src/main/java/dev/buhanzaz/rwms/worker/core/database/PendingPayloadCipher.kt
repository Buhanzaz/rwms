package dev.buhanzaz.rwms.worker.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts unsent command/conflict bodies independently from Room's metadata.
 *
 * Production instances resolve their AES key from AndroidKeyStore. JVM tests can supply an
 * in-memory key through the internal constructor without replacing the platform provider.
 */
@Singleton
class PendingPayloadCipher private constructor(
    private val secretKeyProvider: () -> SecretKey,
) {
    @Inject
    constructor(
        @Suppress("UNUSED_PARAMETER")
        @ApplicationContext context: Context,
    ) : this(::loadOrCreateAndroidKeyStoreKey)

    /** Creates a cipher backed by a caller-scoped AES key for deterministic JVM tests. */
    internal constructor(secretKey: SecretKey) : this({ secretKey })

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted pending payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey = secretKeyProvider()

    private companion object {
        const val KEY_ALIAS = "rwms-worker-pending-v1"

        fun loadOrCreateAndroidKeyStoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
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
}
