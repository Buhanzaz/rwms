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
    val rememberWarehouse: Boolean = false,
) {
    init {
        UUID.fromString(warehouseId)
        UUID.fromString(createIdempotencyKey)
        inquiryId?.let(UUID::fromString)
    }
}

private val Context.customerWorkflowDataStore by preferencesDataStore(name = "customer_workflow_recovery")
private val workflowReferenceKey = stringPreferencesKey("active_inquiry_v1")
private val pendingIdempotencyOperationsKey = stringPreferencesKey("pending_idempotency_operations_v1")
private val bookingChangeReferencesKey = stringPreferencesKey("booking_change_references_v1")

/** Recovery identity only: neither payment, money, nor application state is persisted. */
@Serializable
internal data class CustomerBookingChangeReference(
    val bookingId: String,
    val quoteId: String,
    val commandFingerprint: String? = null,
)

/** Persists bounded, non-authoritative inquiry and mutation recovery identities atomically. */
@Singleton
class CustomerWorkflowStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val applicationContext = context.applicationContext

    /** Lists exact owner references that must be reloaded after process recreation. */
    internal suspend fun bookingChangeReferences(): List<CustomerBookingChangeReference> {
        val encoded = applicationContext.customerWorkflowDataStore.data.first()[bookingChangeReferencesKey]
            ?: return emptyList()
        return json.decodeFromString<List<CustomerBookingChangeReference>>(encoded)
    }

    /** Writes identity before a remotely effective command; it contains no authoritative result. */
    internal suspend fun rememberBookingChange(reference: CustomerBookingChangeReference) {
        require(reference.bookingId.isNotBlank() && reference.quoteId.isNotBlank())
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            val references = preferences[bookingChangeReferencesKey]
                ?.let { json.decodeFromString<List<CustomerBookingChangeReference>>(it) }.orEmpty()
            val updated = references.filterNot { it.quoteId == reference.quoteId } + reference
            if (updated.size > MAX_PENDING_CUSTOMER_IDEMPOTENCY_OPERATIONS) {
                throw CustomerApiException(null, "Слишком много незавершённых изменений. Проверьте их статус.")
            }
            preferences[bookingChangeReferencesKey] = json.encodeToString(updated)
        }
    }

    /**
     * Forgets an owner-confirmed offered/applied quote and its abandoned retry identity.
     * Callers must not use this for an uncertain or APPLYING effect.
     */
    internal suspend fun forgetBookingChange(quoteId: String) {
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            val references = preferences[bookingChangeReferencesKey]
                ?.let { json.decodeFromString<List<CustomerBookingChangeReference>>(it) }.orEmpty()
            references.firstOrNull { it.quoteId == quoteId }?.commandFingerprint?.let { operation ->
                val pending = decodePendingOperations(preferences[pendingIdempotencyOperationsKey]).toMutableMap()
                pending.remove(operation)
                writePendingOperations(preferences, pending)
            }
            preferences[bookingChangeReferencesKey] = json.encodeToString(references.filterNot { it.quoteId == quoteId })
        }
    }

    /**
     * Returns the durable UUID for an unresolved mutation, creating it atomically when necessary.
     *
     * Pending entries are never evicted to make room: losing an unresolved key could duplicate a
     * server effect. The ledger stores no command payload or server-owned result.
     */
    internal suspend fun beginIdempotentOperation(operation: String): String {
        require(operation.isNotBlank() && operation.length <= MAX_IDEMPOTENCY_OPERATION_LENGTH) {
            "Invalid idempotent operation identity"
        }
        var resolvedKey: String? = null
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            val pending = decodePendingOperations(
                preferences[pendingIdempotencyOperationsKey],
            ).toMutableMap()
            resolvedKey = pending[operation] ?: run {
                if (pending.size >= MAX_PENDING_CUSTOMER_IDEMPOTENCY_OPERATIONS) {
                    throw CustomerApiException(
                        status = null,
                        message = "Слишком много незавершённых операций. Повторите их после синхронизации.",
                    )
                }
                UUID.randomUUID().toString().also { created ->
                    pending[operation] = created
                    preferences[pendingIdempotencyOperationsKey] = json.encodeToString(pending)
                }
            }
        }
        return checkNotNull(resolvedKey)
    }

    /** Removes one pending key only after its exact server result has been confirmed. */
    internal suspend fun completeIdempotentOperation(operation: String, idempotencyKey: String) {
        UUID.fromString(idempotencyKey)
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            val pending = decodePendingOperations(
                preferences[pendingIdempotencyOperationsKey],
            ).toMutableMap()
            if (pending[operation] != idempotencyKey) return@edit
            pending.remove(operation)
            writePendingOperations(preferences, pending)
        }
    }

    /** Removes terminal variants only after an authoritative singleton result is confirmed. */
    internal suspend fun completeIdempotentOperations(operationPrefix: String) {
        require(operationPrefix.isNotBlank()) { "Invalid idempotent operation prefix" }
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            val pending = decodePendingOperations(
                preferences[pendingIdempotencyOperationsKey],
            ).toMutableMap()
            if (pending.keys.removeAll { operation -> operation.startsWith(operationPrefix) }) {
                writePendingOperations(preferences, pending)
            }
        }
    }

    /** Returns the validated recovery pointer, removing corrupted local state instead of using it. */
    internal suspend fun read(): CustomerWorkflowReference? {
        val encoded = applicationContext.customerWorkflowDataStore.data.first()[workflowReferenceKey]
            ?: return null
        val decoded = runCatching { json.decodeFromString<CustomerWorkflowReference>(encoded) }.getOrNull()
        if (decoded == null) clearWorkflowReference()
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
    internal suspend fun begin(
        warehouseId: String,
        rememberWarehouse: Boolean = false,
    ): CustomerWorkflowReference {
        UUID.fromString(warehouseId)
        read()?.takeIf { reference ->
            reference.warehouseId == warehouseId && reference.inquiryId == null
        }?.let { pending ->
            return updateRemember(pending, rememberWarehouse)
        }
        val reference = CustomerWorkflowReference(
            warehouseId = warehouseId,
            createIdempotencyKey = UUID.randomUUID().toString(),
            rememberWarehouse = rememberWarehouse,
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

    /** Updates only the local warehouse preference without changing the server inquiry identity. */
    internal suspend fun updateRemember(
        reference: CustomerWorkflowReference,
        rememberWarehouse: Boolean,
    ): CustomerWorkflowReference {
        val updated = reference.copy(rememberWarehouse = rememberWarehouse)
        if (updated != reference) write(updated)
        return updated
    }

    /**
     * Replaces a terminal bound inquiry with one durable, unbound create intent.
     *
     * The replacement is written before the remote create so a lost response is retried with the
     * same key instead of opening two carts. Server bookings referenced by the old inquiry are not
     * changed or deleted.
     */
    internal suspend fun restart(reference: CustomerWorkflowReference): CustomerWorkflowReference {
        require(reference.inquiryId != null) { "Only a bound inquiry can be restarted" }
        val replacement = CustomerWorkflowReference(
            warehouseId = reference.warehouseId,
            createIdempotencyKey = UUID.randomUUID().toString(),
            rememberWarehouse = reference.rememberWarehouse,
        )
        write(replacement)
        return replacement
    }

    /** Removes local recovery pointers on sign-out or when authoritative recovery proves stale. */
    internal suspend fun clear() {
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            preferences.remove(workflowReferenceKey)
            preferences.remove(pendingIdempotencyOperationsKey)
            preferences.remove(bookingChangeReferencesKey)
        }
    }

    private suspend fun write(reference: CustomerWorkflowReference) {
        val encoded = json.encodeToString(reference)
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            preferences[workflowReferenceKey] = encoded
        }
    }

    private suspend fun clearWorkflowReference() {
        applicationContext.customerWorkflowDataStore.edit { preferences ->
            preferences.remove(workflowReferenceKey)
        }
    }

    private fun decodePendingOperations(encoded: String?): Map<String, String> {
        val decoded = encoded?.let { value ->
            runCatching { json.decodeFromString<Map<String, String>>(value) }.getOrNull()
        }.orEmpty()
        return decoded.filter { (operation, key) ->
            operation.isNotBlank() &&
                operation.length <= MAX_IDEMPOTENCY_OPERATION_LENGTH &&
                runCatching { UUID.fromString(key) }.isSuccess
        }
    }

    private fun writePendingOperations(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        pending: Map<String, String>,
    ) {
        if (pending.isEmpty()) {
            preferences.remove(pendingIdempotencyOperationsKey)
        } else {
            preferences[pendingIdempotencyOperationsKey] = json.encodeToString(pending)
        }
    }
}

internal const val MAX_PENDING_CUSTOMER_IDEMPOTENCY_OPERATIONS = 32
private const val MAX_IDEMPOTENCY_OPERATION_LENGTH = 512
