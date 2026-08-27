package dev.buhanzaz.rwms.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Non-authoritative recovery pointer for one CustomerApp inquiry.
 *
 * The server remains the source of every cart field. This record only retains the stable create
 * key and identities required to replay creation or reload the canonical inquiry after Android
 * recreates the process.
 */
@Serializable
internal data class CustomerWorkflowReference(
    val warehouseId: String,
    val createIdempotencyKey: String,
    val inquiryId: String? = null,
) {
    init {
        UUID.fromString(warehouseId)
        UUID.fromString(createIdempotencyKey)
        inquiryId?.let(UUID::fromString)
    }
}

private val Context.customerWorkflowDataStore by preferencesDataStore(name = "customer_workflow_recovery")
private val workflowReferenceKey = stringPreferencesKey("active_inquiry_v1")

/** Persists and clears the non-authoritative active-inquiry recovery pointer atomically. */
@Singleton
class CustomerWorkflowStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val applicationContext = context.applicationContext

    /** Returns the validated recovery pointer, removing corrupted local state instead of using it. */
    internal suspend fun read(): CustomerWorkflowReference? {
        val encoded = applicationContext.customerWorkflowDataStore.data.first()[workflowReferenceKey]
            ?: return null
        val decoded = runCatching { json.decodeFromString<CustomerWorkflowReference>(encoded) }.getOrNull()
        if (decoded == null) clear()
        return decoded
    }

    /**
     * Returns the pending create intent for this warehouse or saves a new one before the first
     * remotely effective inquiry request.
     *
     * Reusing an unbound intent is required when the server accepted a create but its response was
     * lost: a retry in the same Android process must carry the same idempotency key as recovery
     * after process recreation.
     */
    internal suspend fun begin(warehouseId: String): CustomerWorkflowReference {
        UUID.fromString(warehouseId)
        read()?.takeIf { reference ->
            reference.warehouseId == warehouseId && reference.inquiryId == null
        }?.let { pending ->
            return pending
        }
        val reference = CustomerWorkflowReference(
            warehouseId = warehouseId,
            createIdempotencyKey = UUID.randomUUID().toString(),
        )
        write(reference)
        return reference
    }

    /** Binds the server identity to the exact pending create intent that produced it. */
    internal suspend fun bind(
        reference: CustomerWorkflowReference,
        inquiry: InquirySession,
    ): CustomerWorkflowReference {
        require(inquiry.warehouseId == reference.warehouseId) {
            "Recovered inquiry belongs to another warehouse"
        }
        val bound = reference.copy(inquiryId = inquiry.inquiryId)
        write(bound)
        return bound
    }

    /** Removes the local pointer on sign-out or when authoritative recovery proves it stale. */
    internal suspend fun clear() {
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            preferences.remove(workflowReferenceKey)
        }
    }

    private suspend fun write(reference: CustomerWorkflowReference) {
        val encoded = json.encodeToString(reference)
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            preferences[workflowReferenceKey] = encoded
        }
    }
}
