package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.time.LocalDate

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

internal class MaintenanceCatalogCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val catalogAdapter: JsonAdapter<CachedMaintenanceCatalog> = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(CachedMaintenanceCatalog::class.java)
        .serializeNulls()

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

    private companion object {
        const val PREFERENCES_NAME = "rwms_manager_maintenance_catalog"
        const val CATALOG_PAYLOAD_KEY = "catalog_payload"
        const val ATTEMPT_SLOT_KEY = "attempt_slot"
    }
}
