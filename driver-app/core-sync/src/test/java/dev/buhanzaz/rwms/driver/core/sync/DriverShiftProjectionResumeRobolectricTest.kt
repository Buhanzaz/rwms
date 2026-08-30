package dev.buhanzaz.rwms.driver.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverOutboxEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.network.DriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.DriverShiftPhotoDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleDefectDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleInspectionDto
import dev.buhanzaz.rwms.driver.core.network.DriverVehicleInspectionItemDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Verifies process-resumable shift overlays across partial and final command acknowledgements. */
@RunWith(RobolectricTestRunner::class)
class DriverShiftProjectionResumeRobolectricTest {
    private lateinit var database: DriverDatabase
    private lateinit var json: Json
    private lateinit var writer: DriverProjectionWriter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DriverDatabase::class.java,
        ).allowMainThreadQueries().build()
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
        writer = DriverProjectionWriter(database, json)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `partial acknowledgement preserves newer resume overlay and final acknowledgement restores authority`() = runTest {
        val optimistic = today(
            serverTime = "2026-08-30T05:00:03Z",
            shiftVersion = 13,
            inspectionVersion = 13,
            secondItem = inspectionItem(
                id = SECOND_ITEM_ID,
                version = 2,
                state = "DEFECT",
                defect = localDefect(),
            ),
            photos = listOf(localPendingPhoto()),
        )
        database.shiftSnapshotDao().upsert(snapshot(optimistic))
        database.outboxDao().insert(pendingCommand(FIRST_OPERATION_ID, expectedVersion = 10, createdAt = 1))
        database.outboxDao().insert(pendingCommand(SECOND_OPERATION_ID, expectedVersion = 11, createdAt = 2))
        database.outboxDao().insert(pendingCommand(PHOTO_OPERATION_ID, expectedVersion = 12, createdAt = 3))

        writer.commitShiftCommandResult(
            userId = USER_ID,
            operationId = FIRST_OPERATION_ID,
            today = today(
                serverTime = "2026-08-30T05:00:04Z",
                shiftVersion = 11,
                inspectionVersion = 11,
                secondItem = inspectionItem(
                    id = SECOND_ITEM_ID,
                    version = 0,
                    state = "NOT_CHECKED",
                ),
                photos = emptyList(),
            ),
        )

        val afterFirst = cachedToday()
        assertThat(afterFirst.shift?.version).isEqualTo(13)
        assertThat(afterFirst.inspection?.version).isEqualTo(13)
        assertThat(afterFirst.inspection?.checkedRequired).isEqualTo(2)
        assertThat(afterFirst.inspection?.blockingDefectCount).isEqualTo(1)
        assertThat(afterFirst.inspection?.items?.single { it.id == SECOND_ITEM_ID }?.state)
            .isEqualTo("DEFECT")
        assertThat(afterFirst.photos.single().evidenceId).isEqualTo(PHOTO_OPERATION_ID)
        assertThat(afterFirst.photos.single().state).isEqualTo("PENDING_SYNC")
        assertThat(database.outboxDao().operationCount(FIRST_OPERATION_ID)).isEqualTo(0)
        assertThat(database.outboxDao().pending(USER_ID).map { it.operationId })
            .containsExactly(SECOND_OPERATION_ID, PHOTO_OPERATION_ID)
            .inOrder()

        writer.commitShiftCommandResult(
            userId = USER_ID,
            operationId = SECOND_OPERATION_ID,
            today = today(
                serverTime = "2026-08-30T05:00:05Z",
                shiftVersion = 12,
                inspectionVersion = 12,
                secondItem = inspectionItem(
                    id = SECOND_ITEM_ID,
                    version = 2,
                    state = "DEFECT",
                    defect = serverDefect(photoIds = emptyList()),
                ),
                photos = emptyList(),
            ),
        )

        val beforeFinal = cachedToday()
        assertThat(beforeFinal.photos.single().state).isEqualTo("PENDING_SYNC")
        assertThat(database.outboxDao().pending(USER_ID).map { it.operationId })
            .containsExactly(PHOTO_OPERATION_ID)

        val finalAuthoritative = today(
            serverTime = "2026-08-30T05:00:06Z",
            shiftVersion = 14,
            inspectionVersion = 12,
            secondItem = inspectionItem(
                id = SECOND_ITEM_ID,
                version = 2,
                state = "DEFECT",
                defect = serverDefect(photoIds = listOf(PHOTO_OPERATION_ID)),
            ),
            photos = listOf(serverReadyPhoto()),
        )
        writer.commitShiftCommandResult(
            userId = USER_ID,
            operationId = PHOTO_OPERATION_ID,
            today = finalAuthoritative,
        )

        val afterFinal = cachedToday()
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        assertThat(afterFinal).isEqualTo(finalAuthoritative)
        assertThat(afterFinal.shift?.version).isEqualTo(14)
        assertThat(afterFinal.inspection?.version).isEqualTo(12)
        assertThat(afterFinal.photos.single().state).isEqualTo("READY")
        assertThat(afterFinal.photos.single().mediaId).isEqualTo("media-photo-1")
    }

    private suspend fun cachedToday(): TodayDriverShiftDto {
        val snapshot = checkNotNull(database.shiftSnapshotDao().snapshot(USER_ID))
        return json.decodeFromString(snapshot.serializedTodayShift)
    }

    private fun snapshot(today: TodayDriverShiftDto): DriverShiftSnapshotEntity = DriverShiftSnapshotEntity(
        userId = USER_ID,
        shiftId = SHIFT_ID,
        workDate = WORK_DATE,
        enabled = true,
        nextRequiredAction = today.nextRequiredAction,
        serializedTodayShift = json.encodeToString(today),
        serverTime = today.serverTime,
        updatedAtEpochMillis = 1,
    )

    private fun pendingCommand(
        operationId: String,
        expectedVersion: Long,
        createdAt: Long,
    ): DriverOutboxEntity = DriverOutboxEntity(
        operationId = operationId,
        userId = USER_ID,
        entryId = SHIFT_ID,
        kind = DriverLocalStore.OUTBOX_SHIFT_COMMAND,
        encryptedPayload = "not-read-by-overlay",
        expectedVersion = expectedVersion,
        state = DriverLocalStore.OUTBOX_PENDING,
        retryCount = 0,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        lastError = null,
    )

    private fun today(
        serverTime: String,
        shiftVersion: Long,
        inspectionVersion: Long,
        secondItem: DriverVehicleInspectionItemDto,
        photos: List<DriverShiftPhotoDto>,
    ): TodayDriverShiftDto {
        val items = listOf(
            inspectionItem(id = FIRST_ITEM_ID, version = 1, state = "OK"),
            secondItem,
        )
        return TodayDriverShiftDto(
            enabled = true,
            serverTime = serverTime,
            nextRequiredAction = "COMPLETE_VEHICLE_INSPECTION",
            shift = DriverShiftDto(
                id = SHIFT_ID,
                version = shiftVersion,
                driverId = USER_ID,
                driverName = "Тестовый водитель",
                warehouseId = "warehouse-1",
                workDate = WORK_DATE,
                timeZone = "Europe/Moscow",
                status = "VEHICLE_INSPECTION_REQUIRED",
            ),
            inspection = DriverVehicleInspectionDto(
                id = INSPECTION_ID,
                version = inspectionVersion,
                totalRequired = 2,
                checkedRequired = items.count { it.state != "NOT_CHECKED" },
                blockingDefectCount = items.count { item ->
                    item.defect?.let { defect ->
                        defect.severity == "BLOCKING" && defect.status == "OPEN"
                    } == true
                },
                items = items,
            ),
            photos = photos,
        )
    }

    private fun inspectionItem(
        id: String,
        version: Long,
        state: String,
        defect: DriverVehicleDefectDto? = null,
    ): DriverVehicleInspectionItemDto = DriverVehicleInspectionItemDto(
        id = id,
        version = version,
        templateItemCode = id.uppercase(),
        section = "TRUCK",
        label = if (id == FIRST_ITEM_ID) "Тормозная система" else "Шины и колёса",
        required = true,
        sortOrder = if (id == FIRST_ITEM_ID) 1 else 2,
        state = state,
        defect = defect,
    )

    private fun localDefect(): DriverVehicleDefectDto = DriverVehicleDefectDto(
        id = DEFECT_ID,
        shiftId = SHIFT_ID,
        vehicleId = "vehicle-1",
        inspectionItemId = SECOND_ITEM_ID,
        description = "Повреждение шины",
        severity = "BLOCKING",
        status = "OPEN",
        photoIds = listOf(PHOTO_OPERATION_ID),
        createdAt = "2026-08-30T05:00:02Z",
    )

    private fun serverDefect(photoIds: List<String>): DriverVehicleDefectDto = localDefect().copy(
        description = "Повреждение правой передней шины",
        photoIds = photoIds,
    )

    private fun localPendingPhoto(): DriverShiftPhotoDto = DriverShiftPhotoDto(
        id = "local-photo-1",
        clientReferenceId = PHOTO_OPERATION_ID,
        evidenceId = PHOTO_OPERATION_ID,
        role = "PRE_SHIFT_DEFECT",
        defectId = DEFECT_ID,
        inspectionItemId = SECOND_ITEM_ID,
        state = "PENDING_SYNC",
        capturedAt = "2026-08-30T05:00:02Z",
        contentType = "image/jpeg",
        sizeBytes = 512,
        sha256 = "a".repeat(64),
    )

    private fun serverReadyPhoto(): DriverShiftPhotoDto = localPendingPhoto().copy(
        id = "server-photo-1",
        state = "READY",
        mediaId = "media-photo-1",
        mediaGeneration = 2,
    )

    /** Stable aggregate and operation identities shared by the resume scenario. */
    private companion object {
        const val USER_ID = "driver-1"
        const val SHIFT_ID = "shift-1"
        const val WORK_DATE = "2026-08-30"
        const val INSPECTION_ID = "inspection-1"
        const val FIRST_ITEM_ID = "brakes"
        const val SECOND_ITEM_ID = "tires"
        const val DEFECT_ID = "defect-1"
        const val FIRST_OPERATION_ID = "operation-item-1"
        const val SECOND_OPERATION_ID = "operation-item-2"
        const val PHOTO_OPERATION_ID = "operation-photo-1"
    }
}
