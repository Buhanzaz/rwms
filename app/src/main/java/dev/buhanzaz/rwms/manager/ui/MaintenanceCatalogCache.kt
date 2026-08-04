package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A best-effort local snapshot of the active maintenance catalog.
 *
 * The snapshot is only a startup/resume optimisation. It is never a source of truth and callers
 * must refresh it against the service when their policy requires that.
 */
internal data class CachedMaintenanceCatalog(
    /** The snapshot must never be reused for another warehouse. */
    val warehouseId: String,
    val revision: ActiveMaintenanceCatalogRevision,
    /** Validator from the active-version response that verified this snapshot. */
    val activeVersionEtag: String? = null,
    val nodes: List<CatalogNodeDto>,
    val links: List<CatalogLinkDto>,
)

internal class MaintenanceCatalogCache(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    /**
     * Building a reflective Moshi adapter is expensive on a cold ART process. Keep that work,
     * SharedPreferences access and JSON encoding strictly behind the IO-dispatched public API.
     */
    private val storage by lazy {
        Storage(
            preferences = applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
            catalogAdapter = Moshi.Builder()
                .add(ExplicitNullJsonAdapterFactory)
                .addLast(KotlinJsonAdapterFactory())
                .build()
                .adapter(CachedMaintenanceCatalog::class.java)
                .serializeNulls(),
        )
    }

    suspend fun read(): CachedMaintenanceCatalog? = withContext(Dispatchers.IO) {
        storage.read()
    }

    suspend fun write(value: CachedMaintenanceCatalog) {
        withContext(Dispatchers.IO) {
            storage.write(value)
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            storage.clear()
        }
    }

    suspend fun lastAttemptSlot(): LocalDate? = withContext(Dispatchers.IO) {
        storage.lastAttemptSlot()
    }

    suspend fun markAttemptSlot(slot: LocalDate) {
        withContext(Dispatchers.IO) {
            storage.markAttemptSlot(slot)
        }
    }

    private class Storage(
        private val preferences: SharedPreferences,
        private val catalogAdapter: JsonAdapter<CachedMaintenanceCatalog>,
    ) {
        fun read(): CachedMaintenanceCatalog? {
            val payload = runCatching {
                preferences.getString(CATALOG_PAYLOAD_KEY, null)
            }.getOrNull() ?: return null

            return runCatching { catalogAdapter.fromJson(payload) }.getOrNull()
        }

        fun write(value: CachedMaintenanceCatalog) {
            val payload = runCatching { catalogAdapter.toJson(value) }.getOrNull() ?: return
            preferences.edit().putString(CATALOG_PAYLOAD_KEY, payload).apply()
        }

        fun clear() {
            preferences.edit()
                .remove(CATALOG_PAYLOAD_KEY)
                .remove(ATTEMPT_SLOT_KEY)
                .apply()
        }

        fun lastAttemptSlot(): LocalDate? {
            val value = runCatching {
                preferences.getString(ATTEMPT_SLOT_KEY, null)
            }.getOrNull() ?: return null

            return runCatching { LocalDate.parse(value) }.getOrNull()
        }

        fun markAttemptSlot(slot: LocalDate) {
            preferences.edit().putString(ATTEMPT_SLOT_KEY, slot.toString()).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "rwms_manager_maintenance_catalog"
        const val CATALOG_PAYLOAD_KEY = "catalog_payload"
        const val ATTEMPT_SLOT_KEY = "attempt_slot"
    }
}
