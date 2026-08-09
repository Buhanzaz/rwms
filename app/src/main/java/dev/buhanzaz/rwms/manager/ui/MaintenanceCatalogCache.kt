package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An encrypted local snapshot of the active maintenance catalog for one verified account and
 * warehouse.
 *
 * The snapshot is only a startup/resume optimisation. It is never a source of truth and callers
 * must refresh it against the service when their policy requires that.
 */
internal data class CachedMaintenanceCatalog(
    /** `/me` account that was authoritative when this snapshot was fetched. */
    val ownerAccountId: String,
    /** The snapshot must never be reused for another warehouse. */
    val warehouseId: String,
    val revision: ActiveMaintenanceCatalogRevision,
    /** Validator from the active-version response that verified this snapshot. */
    val activeVersionEtag: String? = null,
    val nodes: List<CatalogNodeDto>,
    val links: List<CatalogLinkDto>,
    val schemaVersion: Int = CURRENT_MAINTENANCE_CATALOG_SCHEMA_VERSION,
)

/**
 * Authenticated encryption boundary for maintenance catalog cache values. Associated data binds
 * ciphertext to its account-and-warehouse preference key so entries cannot be swapped.
 */
internal interface MaintenanceCatalogCipher {
    fun encrypt(plainText: String, associatedData: String): String

    fun decrypt(encoded: String, associatedData: String): String
}

/**
 * Stores only AES-GCM ciphertext under account-and-warehouse-derived keys. Legacy plaintext
 * entries are encrypted into an ownerless quarantine and removed before any scoped cache read.
 */
internal class MaintenanceCatalogCache(
    context: Context,
    cipher: MaintenanceCatalogCipher = AndroidKeystoreMaintenanceCatalogCipher(),
) {
    private val applicationContext = context.applicationContext

    /**
     * Building a reflective Moshi adapter is expensive on a cold ART process. Keep that work,
     * SharedPreferences access and JSON encoding strictly behind the IO-dispatched public API.
     */
    private val storage by lazy {
        Storage(
            securePreferences = applicationContext.getSharedPreferences(
                SECURE_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
            legacyPreferences = applicationContext.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
            catalogAdapter = Moshi.Builder()
                .add(ExplicitNullJsonAdapterFactory)
                .addLast(KotlinJsonAdapterFactory())
                .build()
                .adapter(CachedMaintenanceCatalog::class.java)
                .serializeNulls(),
            cipher = cipher,
        )
    }

    /** Decrypts a snapshot only from the exact verified account-and-warehouse partition. */
    suspend fun read(
        ownerAccountId: String,
        warehouseId: String,
    ): CachedMaintenanceCatalog? = withContext(Dispatchers.IO) {
        storage.read(BackgroundCatalogScope(ownerAccountId, warehouseId))
    }

    /** Authenticates and encrypts one scoped snapshot before committing it atomically. */
    suspend fun write(value: CachedMaintenanceCatalog) {
        withContext(Dispatchers.IO) {
            storage.write(value)
        }
    }

    /** Removes the catalog and scheduler slot for exactly one verified storage partition. */
    suspend fun clear(
        ownerAccountId: String,
        warehouseId: String,
    ) {
        withContext(Dispatchers.IO) {
            storage.clear(BackgroundCatalogScope(ownerAccountId, warehouseId))
        }
    }

    /** Reads the encrypted scheduler slot for exactly one account and warehouse. */
    suspend fun lastAttemptSlot(
        ownerAccountId: String,
        warehouseId: String,
    ): LocalDate? = withContext(Dispatchers.IO) {
        storage.lastAttemptSlot(BackgroundCatalogScope(ownerAccountId, warehouseId))
    }

    /** Encrypts the latest scheduler slot independently for one account and warehouse. */
    suspend fun markAttemptSlot(
        ownerAccountId: String,
        warehouseId: String,
        slot: LocalDate,
    ) {
        withContext(Dispatchers.IO) {
            storage.markAttemptSlot(BackgroundCatalogScope(ownerAccountId, warehouseId), slot)
        }
    }

    /** Verified storage partition used only to derive cache keys and AES-GCM associated data. */
    private data class BackgroundCatalogScope(
        val ownerAccountId: String,
        val warehouseId: String,
    ) {
        init {
            require(ownerAccountId.isNotBlank()) { "Не указан владелец кэша каталога" }
            require(warehouseId.isNotBlank()) { "Не указан склад кэша каталога" }
        }

        val storageSuffix: String
            get() = stableHash("$ownerAccountId\u0000$warehouseId")
    }

    /** Serializes migration, authenticated reads and preference commits for the local cache. */
    private class Storage(
        private val securePreferences: SharedPreferences,
        private val legacyPreferences: SharedPreferences,
        private val catalogAdapter: JsonAdapter<CachedMaintenanceCatalog>,
        private val cipher: MaintenanceCatalogCipher,
    ) {
        private var legacyChecked = false

        @Synchronized
        fun read(scope: BackgroundCatalogScope): CachedMaintenanceCatalog? {
            quarantineLegacyPlaintext()
            val key = catalogKey(scope)
            val payload = runCatching {
                securePreferences.getString(key, null)
            }.getOrNull() ?: return null
            val decoded = runCatching {
                catalogAdapter.fromJson(cipher.decrypt(payload, key))
            }.getOrNull() ?: return null
            return decoded.takeIf {
                it.schemaVersion == CURRENT_MAINTENANCE_CATALOG_SCHEMA_VERSION &&
                    it.ownerAccountId == scope.ownerAccountId &&
                    it.warehouseId == scope.warehouseId
            }
        }

        @Synchronized
        fun write(value: CachedMaintenanceCatalog) {
            quarantineLegacyPlaintext()
            require(value.schemaVersion == CURRENT_MAINTENANCE_CATALOG_SCHEMA_VERSION)
            val scope = BackgroundCatalogScope(value.ownerAccountId, value.warehouseId)
            val key = catalogKey(scope)
            val encrypted = cipher.encrypt(catalogAdapter.toJson(value), key)
            check(securePreferences.edit().putString(key, encrypted).commit()) {
                "Не удалось сохранить защищённый кэш каталога"
            }
        }

        @Synchronized
        fun clear(scope: BackgroundCatalogScope) {
            quarantineLegacyPlaintext()
            check(
                securePreferences.edit()
                    .remove(catalogKey(scope))
                    .remove(attemptKey(scope))
                    .commit(),
            ) { "Не удалось очистить защищённый кэш каталога" }
        }

        @Synchronized
        fun lastAttemptSlot(scope: BackgroundCatalogScope): LocalDate? {
            quarantineLegacyPlaintext()
            val key = attemptKey(scope)
            val value = runCatching {
                securePreferences.getString(key, null)
            }.getOrNull() ?: return null
            return runCatching {
                LocalDate.parse(cipher.decrypt(value, key))
            }.getOrNull()
        }

        @Synchronized
        fun markAttemptSlot(scope: BackgroundCatalogScope, slot: LocalDate) {
            quarantineLegacyPlaintext()
            val key = attemptKey(scope)
            val encrypted = cipher.encrypt(slot.toString(), key)
            check(securePreferences.edit().putString(key, encrypted).commit()) {
                "Не удалось сохранить слот обновления каталога"
            }
        }

        /**
         * Preserves schema-1 values only as opaque authenticated ciphertext. They are never
         * parsed or assigned to the account that happens to be signed in during the upgrade.
         */
        private fun quarantineLegacyPlaintext() {
            if (legacyChecked) return
            val catalog = legacyPreferences.getString(LEGACY_CATALOG_PAYLOAD_KEY, null)
            val attempt = legacyPreferences.getString(LEGACY_ATTEMPT_SLOT_KEY, null)
            if (catalog == null && attempt == null) {
                legacyChecked = true
                return
            }

            val secureEdit = securePreferences.edit()
            if (catalog != null &&
                !securePreferences.contains(QUARANTINED_LEGACY_CATALOG_KEY)
            ) {
                secureEdit.putString(
                    QUARANTINED_LEGACY_CATALOG_KEY,
                    cipher.encrypt(catalog, QUARANTINED_LEGACY_CATALOG_KEY),
                )
            }
            if (attempt != null &&
                !securePreferences.contains(QUARANTINED_LEGACY_ATTEMPT_KEY)
            ) {
                secureEdit.putString(
                    QUARANTINED_LEGACY_ATTEMPT_KEY,
                    cipher.encrypt(attempt, QUARANTINED_LEGACY_ATTEMPT_KEY),
                )
            }
            check(secureEdit.commit()) { "Не удалось поместить старый кэш в карантин" }
            check(
                legacyPreferences.edit()
                    .remove(LEGACY_CATALOG_PAYLOAD_KEY)
                    .remove(LEGACY_ATTEMPT_SLOT_KEY)
                    .commit(),
            ) { "Не удалось удалить открытый текст старого кэша" }
            legacyChecked = true
        }

        private fun catalogKey(scope: BackgroundCatalogScope) =
            "catalog_v2_${scope.storageSuffix}"

        private fun attemptKey(scope: BackgroundCatalogScope) =
            "attempt_v2_${scope.storageSuffix}"
    }

    /** Preference names for scoped ciphertext and the ownerless plaintext quarantine. */
    private companion object {
        const val LEGACY_PREFERENCES_NAME = "rwms_manager_maintenance_catalog"
        const val SECURE_PREFERENCES_NAME = "rwms_manager_maintenance_catalog_v2"
        const val LEGACY_CATALOG_PAYLOAD_KEY = "catalog_payload"
        const val LEGACY_ATTEMPT_SLOT_KEY = "attempt_slot"
        const val QUARANTINED_LEGACY_CATALOG_KEY = "quarantine_ownerless_catalog_v1"
        const val QUARANTINED_LEGACY_ATTEMPT_KEY = "quarantine_ownerless_attempt_v1"
    }
}

/** AES-GCM implementation backed by a non-exportable Android Keystore key. */
private class AndroidKeystoreMaintenanceCatalogCipher : MaintenanceCatalogCipher {
    override fun encrypt(plainText: String, associatedData: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
    }

    override fun decrypt(encoded: String, associatedData: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted catalog cache" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            StandardCharsets.UTF_8,
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return synchronized(KEY_CREATION_LOCK) {
            val refreshed = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (refreshed.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER,
            ).apply {
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

    /** Cryptographic parameters and the process-local key creation lock. */
    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "rwms-manager-maintenance-catalog-v2"
        const val GCM_TAG_BITS = 128
        val KEY_CREATION_LOCK = Any()
    }
}

private const val CURRENT_MAINTENANCE_CATALOG_SCHEMA_VERSION = 2

private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
