package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MaintenanceCatalogCacheTest {
    private lateinit var context: Context
    private lateinit var cache: MaintenanceCatalogCache
    private lateinit var cipher: TestMaintenanceCatalogCipher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SECURE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        cipher = TestMaintenanceCatalogCipher()
        cache = MaintenanceCatalogCache(context, cipher)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SECURE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `round trips catalog data including null node and link fields`() = runBlocking {
        val catalog = CachedMaintenanceCatalog(
            ownerAccountId = ACCOUNT_A,
            warehouseId = "warehouse-1",
            revision = ActiveMaintenanceCatalogRevision("catalog-1", 7),
            activeVersionEtag = "W/\"catalog-7\"",
            nodes = listOf(
                CatalogNodeDto(
                    id = "node-1",
                    catalogVersionId = "catalog-1",
                    nodeType = "WORK",
                    name = "Repair work",
                    active = true,
                    parentNodeId = null,
                    furnitureCategory = false,
                    furnitureEquipment = null,
                    unit = null,
                    unitPrice = null,
                    durationMinutes = 30,
                    includeInEstimate = true,
                    commonItem = false,
                    showInMainMenu = true,
                    canvasX = null,
                    canvasY = null,
                    routing = null,
                    comment = null,
                ),
            ),
            links = listOf(
                CatalogLinkDto(
                    id = "link-1",
                    catalogVersionId = "catalog-1",
                    fromNodeId = "node-1",
                    toNodeId = "node-2",
                    linkType = "CONTAINS",
                    sourceAnchor = null,
                    targetAnchor = null,
                    sortOrder = 1,
                ),
            ),
        )

        cache.write(catalog)

        assertThat(cache.read(ACCOUNT_A, "warehouse-1")).isEqualTo(catalog)
        val encrypted = securePreferences().all.values.single() as String
        assertThat(encrypted).doesNotContain("warehouse-1")
        assertThat(encrypted).doesNotContain("Repair work")
        assertThat(encrypted).doesNotContain("parentNodeId")
    }

    @Test
    fun `records attempt slot and clears both cache values`() = runBlocking {
        val slot = LocalDate.of(2026, 7, 27)

        cache.markAttemptSlot(ACCOUNT_A, "warehouse-1", slot)

        assertThat(cache.lastAttemptSlot(ACCOUNT_A, "warehouse-1")).isEqualTo(slot)
        assertThat(cache.lastAttemptSlot(ACCOUNT_B, "warehouse-1")).isNull()

        cache.write(sampleCatalog())
        cache.markAttemptSlot(ACCOUNT_A, "warehouse-1", slot)
        cache.clear(ACCOUNT_A, "warehouse-1")

        assertThat(cache.read(ACCOUNT_A, "warehouse-1")).isNull()
        assertThat(cache.lastAttemptSlot(ACCOUNT_A, "warehouse-1")).isNull()
    }

    @Test
    fun `account and warehouse partitions never reuse another snapshot`() = runBlocking {
        val accountA = sampleCatalog()
        val accountB = sampleCatalog(ownerAccountId = ACCOUNT_B)
        val otherWarehouse = sampleCatalog(warehouseId = "warehouse-2")

        cache.write(accountA)
        cache.write(accountB)
        cache.write(otherWarehouse)

        assertThat(cache.read(ACCOUNT_A, "warehouse-1")).isEqualTo(accountA)
        assertThat(cache.read(ACCOUNT_B, "warehouse-1")).isEqualTo(accountB)
        assertThat(cache.read(ACCOUNT_A, "warehouse-2")).isEqualTo(otherWarehouse)
        assertThat(cache.read("account-c", "warehouse-1")).isNull()
    }

    @Test
    fun `returns null for tampered authenticated ciphertext`() = runBlocking {
        cache.write(sampleCatalog())
        val preferences = securePreferences()
        val key = preferences.all.keys.single()
        val encrypted = requireNotNull(preferences.getString(key, null))
        val tampered = encrypted.dropLast(1) + if (encrypted.last() == 'A') "B" else "A"
        preferences.edit().putString(key, tampered).commit()

        assertThat(cache.read(ACCOUNT_A, "warehouse-1")).isNull()
    }

    @Test
    fun `ownerless plaintext cache is encrypted into quarantine and never restored`() = runBlocking {
        val legacyPayload =
            """{"revision":{"id":"catalog-1","version":1},"nodes":[],"links":[]}"""
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LEGACY_CATALOG_PAYLOAD_KEY, legacyPayload)
            .putString(LEGACY_ATTEMPT_SLOT_KEY, "2026-07-27")
            .commit()

        assertThat(cache.read(ACCOUNT_A, "warehouse-1")).isNull()
        val legacy = context.getSharedPreferences(
            LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        assertThat(legacy.contains(LEGACY_CATALOG_PAYLOAD_KEY)).isFalse()
        assertThat(legacy.contains(LEGACY_ATTEMPT_SLOT_KEY)).isFalse()
        val quarantined = requireNotNull(
            securePreferences().getString(QUARANTINED_LEGACY_CATALOG_KEY, null),
        )
        assertThat(quarantined).doesNotContain("catalog-1")
        assertThat(
            cipher.decrypt(quarantined, QUARANTINED_LEGACY_CATALOG_KEY),
        ).isEqualTo(legacyPayload)
    }

    private fun sampleCatalog(
        ownerAccountId: String = ACCOUNT_A,
        warehouseId: String = "warehouse-1",
    ) = CachedMaintenanceCatalog(
        ownerAccountId = ownerAccountId,
        warehouseId = warehouseId,
        revision = ActiveMaintenanceCatalogRevision("catalog-1", 1),
        nodes = emptyList(),
        links = emptyList(),
    )

    private fun securePreferences() = context.getSharedPreferences(
        SECURE_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** JVM AES-GCM implementation used to exercise authenticated storage without Android Keystore. */
    private class TestMaintenanceCatalogCipher : MaintenanceCatalogCipher {
        private val key = SecretKeySpec(ByteArray(32) { index -> index.toByte() }, "AES")

        override fun encrypt(plainText: String, associatedData: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(cipher.iv) + ":" +
                Base64.getEncoder().encodeToString(
                    cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)),
                )
        }

        override fun decrypt(encoded: String, associatedData: String): String {
            val parts = encoded.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])),
            )
            cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
            return String(
                cipher.doFinal(Base64.getDecoder().decode(parts[1])),
                StandardCharsets.UTF_8,
            )
        }
    }

    /** Stable fixture identities and storage keys shared by the focused cache tests. */
    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val LEGACY_PREFERENCES_NAME = "rwms_manager_maintenance_catalog"
        const val SECURE_PREFERENCES_NAME = "rwms_manager_maintenance_catalog_v2"
        const val LEGACY_CATALOG_PAYLOAD_KEY = "catalog_payload"
        const val LEGACY_ATTEMPT_SLOT_KEY = "attempt_slot"
        const val QUARANTINED_LEGACY_CATALOG_KEY = "quarantine_ownerless_catalog_v1"
    }
}
