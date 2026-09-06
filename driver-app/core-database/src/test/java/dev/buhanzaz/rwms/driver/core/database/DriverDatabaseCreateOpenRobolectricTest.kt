package dev.buhanzaz.rwms.driver.core.database

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Runs in testDebugUnitTest, unlike the complementary device smoke test. */
@RunWith(RobolectricTestRunner::class)
class DriverDatabaseCreateOpenRobolectricTest {
    @Test
    fun createsAndReopensCurrentSchema() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-robolectric.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, DriverDatabase::class.java, name).build().close()
        Room.databaseBuilder(context, DriverDatabase::class.java, name).build().close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationOneToTwoBackfillsVisibleQueueMetadataFromExistingTasks() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-migration.db"
        context.deleteDatabase(name)
        val versionOne = openHelper(
            context = context,
            name = name,
            version = 1,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_task` (
                        `userId` TEXT NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `categoryCode` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `categorySortOrder` INTEGER NOT NULL,
                        `resultPhotoMinCount` INTEGER NOT NULL,
                        `lastServerRevision` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            },
        )
        versionOne.writableDatabase.execSQL(
            """
            INSERT INTO `driver_task` VALUES
                ('driver', 'repair', 'REPAIR', 'Ремонты', 10, 1, 7),
                ('driver', 'repair', 'REPAIR', 'Ремонты', 10, 1, 8)
            """.trimIndent(),
        )
        versionOne.close()
        val versionTwo = openHelper(
            context = context,
            name = name,
            version = 2,
            onCreate = { error("Expected the version 1 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_1_2.migrate(database) },
        )
        val database = versionTwo.writableDatabase

        database.query(
            """
            SELECT `localId`, `queueId`, `name`, `type`, `lastServerRevision`
            FROM `driver_category`
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("driver:repair")
            assertThat(cursor.getString(1)).isEqualTo("repair")
            assertThat(cursor.getString(2)).isEqualTo("Ремонты")
            assertThat(cursor.getString(3)).isEqualTo("UNKNOWN")
            assertThat(cursor.getLong(4)).isEqualTo(8)
            assertThat(cursor.moveToNext()).isFalse()
        }
        versionTwo.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationTwoToThreeRecreatesCodeBearingTablesAndDiscardsLegacyCodeValues() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-uuid-migration.db"
        context.deleteDatabase(name)
        val versionTwo = openHelper(
            context = context,
            name = name,
            version = 2,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_group` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `driverClassId` TEXT NOT NULL,
                        `driverClassCode` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `driver_category` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `queueId` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `audienceModesKey` TEXT NOT NULL,
                        `resultPhotoMinCount` INTEGER NOT NULL,
                        `lastServerRevision` INTEGER NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `driver_task` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `entryId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `categoryCode` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `categorySortOrder` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `unitNumber` TEXT,
                        `taskText` TEXT,
                        `scheduledDate` TEXT NOT NULL,
                        `deadlineAt` TEXT,
                        `priority` INTEGER NOT NULL,
                        `queuePosition` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `availabilityMode` TEXT NOT NULL,
                        `plannedDurationMinutes` INTEGER,
                        `activeStartedAt` TEXT,
                        `activeWorkSeconds` INTEGER NOT NULL,
                        `readyEvidenceCount` INTEGER NOT NULL,
                        `resultPhotoMinCount` INTEGER NOT NULL,
                        `lastServerRevision` INTEGER NOT NULL,
                        `locallyPending` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionTwo.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO `driver_group` VALUES
                    ('driver:group', 'driver', 'group', 'Смена', 'class-id', 'LEGACY_CLASS')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `driver_category` VALUES
                    ('driver:queue', 'driver', 'queue', 'LEGACY_QUEUE', 'Ремонты', 'REPAIR', 3, 'AVAILABLE', 1, 7)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `driver_task` VALUES
                    ('driver:entry', 'driver', 'entry', 'task', 4, 'queue', 'LEGACY_QUEUE', 'Ремонты', 3,
                     'Задание', NULL, NULL, '2026-07-27', NULL, 1, 2, 'WAITING', 'AVAILABLE', NULL, NULL,
                     0, 0, 1, 7, 0, 9)
                """.trimIndent(),
            )
        }
        versionTwo.close()

        val versionThree = openHelper(
            context = context,
            name = name,
            version = 3,
            onCreate = { error("Expected the version 2 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_2_3.migrate(database) },
        )
        val database = versionThree.writableDatabase

        assertThat(columns(database, "driver_group")).containsExactly(
            "localId", "userId", "groupId", "name", "driverClassId", "driverClassName",
        ).inOrder()
        assertThat(columns(database, "driver_category")).containsExactly(
            "localId", "userId", "queueId", "name", "type", "sortOrder", "audienceModesKey",
            "resultPhotoMinCount", "lastServerRevision",
        ).inOrder()
        assertThat(columns(database, "driver_task")).doesNotContain("categoryCode")
        database.query(
            "SELECT `driverClassName` FROM `driver_group` WHERE `localId`='driver:group'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEmpty()
        }
        database.query(
            "SELECT `categoryName`, `title` FROM `driver_task` WHERE `localId`='driver:entry'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Ремонты")
            assertThat(cursor.getString(1)).isEqualTo("Задание")
        }
        versionThree.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationThreeToFourAddsCurrentGroupAvailabilityAndServerTimerSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-kpi-migration.db"
        context.deleteDatabase(name)
        val versionThree = openHelper(
            context = context,
            name = name,
            version = 3,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_session` (
                        `userId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `login` TEXT,
                        `warehouseId` TEXT,
                        `leaseId` TEXT,
                        `leaseExpiresAtEpochMillis` INTEGER,
                        `serverEpochMillis` INTEGER,
                        `elapsedRealtimeAtSyncMillis` INTEGER,
                        `revision` INTEGER NOT NULL,
                        `feedEtag` TEXT,
                        `cacheHidden` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `driver_task` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `entryId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `categorySortOrder` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `unitNumber` TEXT,
                        `taskText` TEXT,
                        `scheduledDate` TEXT NOT NULL,
                        `deadlineAt` TEXT,
                        `priority` INTEGER NOT NULL,
                        `queuePosition` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `availabilityMode` TEXT NOT NULL,
                        `plannedDurationMinutes` INTEGER,
                        `activeStartedAt` TEXT,
                        `activeWorkSeconds` INTEGER NOT NULL,
                        `readyEvidenceCount` INTEGER NOT NULL,
                        `resultPhotoMinCount` INTEGER NOT NULL,
                        `lastServerRevision` INTEGER NOT NULL,
                        `locallyPending` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionThree.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO `driver_session` VALUES
                    ('driver', 'Рабочий', 'driver', 'warehouse', NULL, NULL, NULL, NULL, 1, NULL, 0, 10)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `driver_task` VALUES
                    ('driver:entry', 'driver', 'entry', 'task', 1, 'queue', 'Очередь', 1,
                     'Задание', NULL, NULL, '2026-07-30', NULL, 1, 0, 'WAITING', 'AVAILABLE',
                     60, NULL, 0, 0, 0, 1, 0, 10)
                """.trimIndent(),
            )
        }
        versionThree.close()

        val versionFour = openHelper(
            context = context,
            name = name,
            version = 4,
            onCreate = { error("Expected the version 3 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_3_4.migrate(database) },
        )
        val database = versionFour.writableDatabase

        database.query(
            """
            SELECT `currentGroupId`, `currentGroupName`, `operationalAvailability`
            FROM `driver_session`
            WHERE `userId`='driver'
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getString(2)).isEqualTo("AVAILABLE")
        }
        assertThat(columns(database, "driver_task")).containsAtLeast(
            "timerCountedActiveSeconds",
            "timerRemainingSeconds",
            "timerRemainingPercent",
            "timerState",
            "timerNextTransitionAt",
            "timerServerTime",
        )
        versionFour.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationFourToFiveMarksExistingCategoriesAsGeneralPurpose() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-logistics-migration.db"
        context.deleteDatabase(name)
        val versionFour = openHelper(
            context = context,
            name = name,
            version = 4,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_category` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `queueId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `audienceModesKey` TEXT NOT NULL,
                        `resultPhotoMinCount` INTEGER NOT NULL,
                        `lastServerRevision` INTEGER NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionFour.writableDatabase.execSQL(
            """
            INSERT INTO `driver_category` VALUES
                ('driver:repair', 'driver', 'repair', 'Ремонты', 'REPAIR', 10, 'AVAILABLE', 1, 7)
            """.trimIndent(),
        )
        versionFour.close()

        val versionFive = openHelper(
            context = context,
            name = name,
            version = 5,
            onCreate = { error("Expected the version 4 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_4_5.migrate(database) },
        )
        versionFive.writableDatabase.query(
            "SELECT `queuePurpose` FROM `driver_category` WHERE `localId`='driver:repair'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("GENERAL")
        }
        versionFive.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationFiveToSixAddsNullableKpiPaletteAndEmptyLegacyGroupBindings() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-group-kpi-migration.db"
        context.deleteDatabase(name)
        val versionFive = openHelper(
            context = context,
            name = name,
            version = 5,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_session` (
                        `userId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `driver_category` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `queueId` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionFive.writableDatabase.apply {
            execSQL("INSERT INTO `driver_session` VALUES ('driver', 'Рабочий')")
            execSQL("INSERT INTO `driver_category` VALUES ('driver:repair', 'driver', 'repair')")
        }
        versionFive.close()

        val versionSix = openHelper(
            context = context,
            name = name,
            version = 6,
            onCreate = { error("Expected the version 5 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_5_6.migrate(database) },
        )
        val database = versionSix.writableDatabase

        database.query("SELECT `kpiPaletteJson` FROM `driver_session`").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        database.query("SELECT `groupIdsKey` FROM `driver_category`").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEmpty()
        }
        versionSix.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationSixToSevenAddsNullableDriverAudienceClassification() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-driver-audience-migration.db"
        context.deleteDatabase(name)
        val versionSix = openHelper(
            context = context,
            name = name,
            version = 6,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_task` (
                        `localId` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionSix.writableDatabase.execSQL(
            "INSERT INTO `driver_task` (`localId`) VALUES ('driver:entry')",
        )
        versionSix.close()

        val versionSeven = openHelper(
            context = context,
            name = name,
            version = 7,
            onCreate = { error("Expected the version 6 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_6_7.migrate(database) },
        )
        val database = versionSeven.writableDatabase

        assertThat(columns(database, "driver_task")).contains("driverAudienceMode")
        database.query(
            "SELECT `driverAudienceMode` FROM `driver_task` WHERE `localId`='driver:entry'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        versionSeven.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationSevenToEightPreservesEvidenceAndAddsResumableShiftTables() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-shift-migration.db"
        context.deleteDatabase(name)
        val versionSeven = openHelper(
            context = context,
            name = name,
            version = 7,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `task_evidence` (
                        `evidenceId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `entryId` TEXT NOT NULL,
                        PRIMARY KEY(`evidenceId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionSeven.writableDatabase.execSQL(
            "INSERT INTO `task_evidence` VALUES ('evidence', 'driver', 'entry')",
        )
        versionSeven.close()

        val versionEight = openHelper(
            context = context,
            name = name,
            version = 8,
            onCreate = { error("Expected the version 7 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_7_8.migrate(database) },
        )
        val database = versionEight.writableDatabase

        assertThat(columns(database, "task_evidence")).containsAtLeast(
            "ownerType",
            "mediaContext",
            "photoRole",
            "defectId",
            "inspectionItemId",
        )
        database.query(
            "SELECT `ownerType`, `mediaContext`, `photoRole` FROM `task_evidence` WHERE `evidenceId`='evidence'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("TASK_BOARD_ENTRY")
            assertThat(cursor.getString(1)).isEqualTo("WORK_RESULT")
            assertThat(cursor.isNull(2)).isTrue()
        }
        assertThat(tableExists(database, "driver_shift_snapshot")).isTrue()
        assertThat(tableExists(database, "driver_shift_draft")).isTrue()
        versionEight.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationEightToNineAddsNullableAuthoritativeShiftVersion() {
        val context = RuntimeEnvironment.getApplication()
        val name = "driver-room-shift-authority-migration.db"
        context.deleteDatabase(name)
        val versionEight = openHelper(
            context = context,
            name = name,
            version = 8,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `driver_shift_snapshot` (
                        `userId` TEXT NOT NULL,
                        `shiftId` TEXT,
                        `workDate` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `nextRequiredAction` TEXT NOT NULL,
                        `serializedTodayShift` TEXT NOT NULL,
                        `serverTime` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionEight.writableDatabase.execSQL(
            """
            INSERT INTO `driver_shift_snapshot` VALUES
                ('driver', 'shift', '2026-08-30', 1, 'START_SHIFT', '{}', '2026-08-30T05:00:00Z', 1)
            """.trimIndent(),
        )
        versionEight.close()

        val versionNine = openHelper(
            context = context,
            name = name,
            version = 9,
            onCreate = { error("Expected the version 8 database to exist") },
            onUpgrade = { database -> DriverDatabase.MIGRATION_8_9.migrate(database) },
        )
        val database = versionNine.writableDatabase
        assertThat(columns(database, "driver_shift_snapshot")).contains("authoritativeShiftVersion")
        database.query("SELECT `authoritativeShiftVersion` FROM `driver_shift_snapshot`").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        versionNine.close()
        context.deleteDatabase(name)
    }

    @Test
    fun closingDraftSurvivesDatabaseReopen() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "driver-room-shift-resume.db"
            context.deleteDatabase(name)
            val first = Room.databaseBuilder(context, DriverDatabase::class.java, name).build()
            first.shiftDraftDao().upsert(
                DriverShiftDraftEntity(
                    localId = "driver:shift",
                    userId = "driver",
                    shiftId = "shift",
                    step = "ODOMETER",
                    vehicleCondition = "NO_NEW_DEFECTS",
                    endOdometerText = "128642",
                    fuelLevelPercent = null,
                    defectId = null,
                    defectDescription = "",
                    confirmSuspiciousOdometer = false,
                    updatedAtEpochMillis = 42,
                ),
            )
            first.close()

            val reopened = Room.databaseBuilder(context, DriverDatabase::class.java, name).build()
            val restored = reopened.shiftDraftDao().draft("driver", "shift")

            assertThat(restored?.step).isEqualTo("ODOMETER")
            assertThat(restored?.endOdometerText).isEqualTo("128642")
            assertThat(restored?.vehicleCondition).isEqualTo("NO_NEW_DEFECTS")
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    private fun columns(database: SupportSQLiteDatabase, table: String): List<String> =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun tableExists(database: SupportSQLiteDatabase, table: String): Boolean =
        database.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() }

    private fun openHelper(
        context: android.content.Context,
        name: String,
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate.invoke(db)

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = onUpgrade.invoke(db)
                },
            )
            .build(),
    )
}
