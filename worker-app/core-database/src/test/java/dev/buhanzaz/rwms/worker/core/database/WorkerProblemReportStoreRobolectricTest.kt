package dev.buhanzaz.rwms.worker.core.database

import android.os.SystemClock
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkerProblemReportStoreRobolectricTest {
    private lateinit var database: WorkerDatabase
    private lateinit var store: WorkerProblemReportStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = WorkerProblemReportStore(
            database,
            PendingPayloadCipher(SecretKeySpec(ByteArray(32) { 0x2a }, "AES")),
            Json,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `draft photos are excluded until immutable report submit atomically activates them`() = runTest {
        seedSession()
        val draft = store.openOrCreateDraft(USER, ENTRY, routeIndex = 3)
        store.updateDraftComment(USER, draft.reportId, "Broken light")
        val photoId = UUID.randomUUID().toString()
        store.attachPreparedPhoto(USER, draft.reportId, evidenceInput(photoId))

        assertThat(database.evidenceDao().pending(USER)).isEmpty()
        assertThat(store.observeDraft(USER, ENTRY).first()?.attachments).hasSize(1)

        store.submitDraft(USER, draft.reportId)

        val submitted = store.observeDraft(USER, ENTRY).first()
        assertThat(submitted?.reportId).isEqualTo(draft.reportId)
        assertThat(submitted?.state).isEqualTo(WorkerProblemReportStore.OUTBOX_PENDING)
        assertThat(database.evidenceDao().pending(USER).map(TaskEvidenceEntity::evidenceId)).containsExactly(photoId)
        assertThat(database.evidenceDao().evidence(USER, photoId)?.state).isEqualTo("CAPTURED")
    }

    @Test
    fun `submitted unresolved report is reopened as immutable instead of creating a second draft`() = runTest {
        seedSession()
        val draft = store.openOrCreateDraft(USER, ENTRY, routeIndex = 1)
        store.updateDraftComment(USER, draft.reportId, "Broken door")
        store.submitDraft(USER, draft.reportId)

        val reopened = store.openOrCreateDraft(USER, ENTRY, routeIndex = 9)

        assertThat(reopened.reportId).isEqualTo(draft.reportId)
        assertThat(reopened.routeIndex).isEqualTo(1)
        assertThat(reopened.state).isEqualTo(WorkerProblemReportStore.OUTBOX_PENDING)
    }

    @Test
    fun `reported manual submission clears the draft observer and permits a new declaration`() = runTest {
        seedSession()
        val submitted = store.openOrCreateDraft(USER, ENTRY, routeIndex = 1)
        store.updateDraftComment(USER, submitted.reportId, "Broken door")
        store.submitDraft(USER, submitted.reportId)
        database.outboxDao().updateState(
            operationId = submitted.reportId,
            state = WorkerProblemReportStore.OUTBOX_REPORTED,
            retryCount = 0,
            lastError = null,
            now = System.currentTimeMillis(),
        )

        assertThat(store.observeDraft(USER, ENTRY).first()).isNull()
        assertThat(store.openOrCreateDraft(USER, ENTRY, routeIndex = 2).reportId)
            .isNotEqualTo(submitted.reportId)
    }

    @Test
    fun `draft photo removal returns metadata only after local declaration is updated`() = runTest {
        seedSession()
        val draft = store.openOrCreateDraft(USER, ENTRY, routeIndex = 0)
        val photoId = UUID.randomUUID().toString()
        store.attachPreparedPhoto(USER, draft.reportId, evidenceInput(photoId))

        val removed = store.removeDraftPhoto(USER, draft.reportId, photoId)

        assertThat(removed.evidenceId).isEqualTo(photoId)
        assertThat(database.evidenceDao().evidence(USER, photoId)).isNull()
        assertThat(store.observeDraft(USER, ENTRY).first()?.attachments).isEmpty()
    }

    private suspend fun seedSession() {
        val now = System.currentTimeMillis()
        database.sessionDao().upsert(
            WorkerSessionEntity(
                userId = USER,
                displayName = "Worker",
                login = "worker",
                warehouseId = "warehouse",
                leaseId = "lease",
                leaseExpiresAtEpochMillis = now + 86_400_000,
                serverEpochMillis = now,
                elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(),
                revision = 1,
                feedEtag = null,
                cacheHidden = false,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private fun evidenceInput(evidenceId: String) = DraftProblemReportEvidenceInput(
        evidenceId = evidenceId,
        encryptedFilePath = "encrypted/$evidenceId",
        fileName = "$evidenceId.webp",
        routeIndex = 3,
        capturedAt = "2026-09-09T00:00:00Z",
        sizeBytes = 42,
        sha256 = "a".repeat(64),
        variantManifestJson = "[]",
    )

    private companion object {
        const val USER = "worker"
        const val ENTRY = "entry"
    }
}
