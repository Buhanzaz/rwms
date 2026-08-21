package dev.buhanzaz.rwms.worker.core.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Runs in testDebugUnitTest, unlike the complementary device smoke test. */
@RunWith(RobolectricTestRunner::class)
class WorkerDatabaseCreateOpenRobolectricTest {
    @Test
    fun createsAndReopensCurrentSchema() {
        val context = RuntimeEnvironment.getApplication()
        val name = "worker-room-robolectric.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, WorkerDatabase::class.java, name).build().close()
        Room.databaseBuilder(context, WorkerDatabase::class.java, name).build().close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationOneToTwoBackfillsVisibleQueueMetadataFromExistingTasks() {
        val context = RuntimeEnvironment.getApplication()
        val name = "worker-room-migration.db"
        context.deleteDatabase(name)
        val versionOne = openHelper(
            context = context,
            name = name,
            version = 1,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_task` (
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
            INSERT INTO `worker_task` VALUES
                ('worker', 'repair', 'REPAIR', 'Ремонты', 10, 1, 7),
                ('worker', 'repair', 'REPAIR', 'Ремонты', 10, 1, 8)
            """.trimIndent(),
        )
        versionOne.close()
        val versionTwo = openHelper(
            context = context,
            name = name,
            version = 2,
            onCreate = { error("Expected the version 1 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_1_2.migrate(database) },
        )
        val database = versionTwo.writableDatabase

        database.query(
            """
            SELECT `localId`, `queueId`, `name`, `type`, `lastServerRevision`
            FROM `worker_category`
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("worker:repair")
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
        val name = "worker-room-uuid-migration.db"
        context.deleteDatabase(name)
        val versionTwo = openHelper(
            context = context,
            name = name,
            version = 2,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_group` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `workerClassId` TEXT NOT NULL,
                        `workerClassCode` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `worker_category` (
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
                    CREATE TABLE `worker_task` (
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
                INSERT INTO `worker_group` VALUES
                    ('worker:group', 'worker', 'group', 'Смена', 'class-id', 'LEGACY_CLASS')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `worker_category` VALUES
                    ('worker:queue', 'worker', 'queue', 'LEGACY_QUEUE', 'Ремонты', 'REPAIR', 3, 'AVAILABLE', 1, 7)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `worker_task` VALUES
                    ('worker:entry', 'worker', 'entry', 'task', 4, 'queue', 'LEGACY_QUEUE', 'Ремонты', 3,
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
            onUpgrade = { database -> WorkerDatabase.MIGRATION_2_3.migrate(database) },
        )
        val database = versionThree.writableDatabase

        assertThat(columns(database, "worker_group")).containsExactly(
            "localId", "userId", "groupId", "name", "workerClassId", "workerClassName",
        ).inOrder()
        assertThat(columns(database, "worker_category")).containsExactly(
            "localId", "userId", "queueId", "name", "type", "sortOrder", "audienceModesKey",
            "resultPhotoMinCount", "lastServerRevision",
        ).inOrder()
        assertThat(columns(database, "worker_task")).doesNotContain("categoryCode")
        database.query(
            "SELECT `workerClassName` FROM `worker_group` WHERE `localId`='worker:group'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEmpty()
        }
        database.query(
            "SELECT `categoryName`, `title` FROM `worker_task` WHERE `localId`='worker:entry'",
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
        val name = "worker-room-kpi-migration.db"
        context.deleteDatabase(name)
        val versionThree = openHelper(
            context = context,
            name = name,
            version = 3,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_session` (
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
                    CREATE TABLE `worker_task` (
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
                INSERT INTO `worker_session` VALUES
                    ('worker', 'Рабочий', 'worker', 'warehouse', NULL, NULL, NULL, NULL, 1, NULL, 0, 10)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `worker_task` VALUES
                    ('worker:entry', 'worker', 'entry', 'task', 1, 'queue', 'Очередь', 1,
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
            onUpgrade = { database -> WorkerDatabase.MIGRATION_3_4.migrate(database) },
        )
        val database = versionFour.writableDatabase

        database.query(
            """
            SELECT `currentGroupId`, `currentGroupName`, `operationalAvailability`
            FROM `worker_session`
            WHERE `userId`='worker'
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getString(2)).isEqualTo("AVAILABLE")
        }
        assertThat(columns(database, "worker_task")).containsAtLeast(
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
        val name = "worker-room-logistics-migration.db"
        context.deleteDatabase(name)
        val versionFour = openHelper(
            context = context,
            name = name,
            version = 4,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_category` (
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
            INSERT INTO `worker_category` VALUES
                ('worker:repair', 'worker', 'repair', 'Ремонты', 'REPAIR', 10, 'AVAILABLE', 1, 7)
            """.trimIndent(),
        )
        versionFour.close()

        val versionFive = openHelper(
            context = context,
            name = name,
            version = 5,
            onCreate = { error("Expected the version 4 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_4_5.migrate(database) },
        )
        versionFive.writableDatabase.query(
            "SELECT `queuePurpose` FROM `worker_category` WHERE `localId`='worker:repair'",
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
        val name = "worker-room-group-kpi-migration.db"
        context.deleteDatabase(name)
        val versionFive = openHelper(
            context = context,
            name = name,
            version = 5,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_session` (
                        `userId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `worker_category` (
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
            execSQL("INSERT INTO `worker_session` VALUES ('worker', 'Рабочий')")
            execSQL("INSERT INTO `worker_category` VALUES ('worker:repair', 'worker', 'repair')")
        }
        versionFive.close()

        val versionSix = openHelper(
            context = context,
            name = name,
            version = 6,
            onCreate = { error("Expected the version 5 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_5_6.migrate(database) },
        )
        val database = versionSix.writableDatabase

        database.query("SELECT `kpiPaletteJson` FROM `worker_session`").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        database.query("SELECT `groupIdsKey` FROM `worker_category`").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEmpty()
        }
        versionSix.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationSixToSevenAddsNullableDriverAudienceClassification() {
        val context = RuntimeEnvironment.getApplication()
        val name = "worker-room-driver-audience-migration.db"
        context.deleteDatabase(name)
        val versionSix = openHelper(
            context = context,
            name = name,
            version = 6,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_task` (
                        `localId` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
            },
        )
        versionSix.writableDatabase.execSQL(
            "INSERT INTO `worker_task` (`localId`) VALUES ('worker:entry')",
        )
        versionSix.close()

        val versionSeven = openHelper(
            context = context,
            name = name,
            version = 7,
            onCreate = { error("Expected the version 6 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_6_7.migrate(database) },
        )
        val database = versionSeven.writableDatabase

        assertThat(columns(database, "worker_task")).contains("driverAudienceMode")
        database.query(
            "SELECT `driverAudienceMode` FROM `worker_task` WHERE `localId`='worker:entry'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        versionSeven.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationSevenToEightRemovesDriverTableClassificationAndPreservesTask() {
        val context = RuntimeEnvironment.getApplication()
        val name = "worker-room-remove-driver-classification.db"
        context.deleteDatabase(name)
        val versionSeven = openHelper(
            context = context,
            name = name,
            version = 7,
            onCreate = { database ->
                database.execSQL(
                    """
                    CREATE TABLE `worker_task` (
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
                        `timerCountedActiveSeconds` INTEGER,
                        `timerRemainingSeconds` INTEGER,
                        `timerRemainingPercent` REAL,
                        `timerState` TEXT,
                        `timerNextTransitionAt` TEXT,
                        `timerServerTime` TEXT,
                        `driverAudienceMode` TEXT,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX `index_worker_task_userId_categorySortOrder_queuePosition`
                    ON `worker_task` (`userId`, `categorySortOrder`, `queuePosition`)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX `index_worker_task_userId_entryId`
                    ON `worker_task` (`userId`, `entryId`)
                    """.trimIndent(),
                )
            },
        )
        versionSeven.writableDatabase.execSQL(
            """
            INSERT INTO `worker_task` (
                `localId`, `userId`, `entryId`, `taskId`, `version`, `categoryId`,
                `categoryName`, `categorySortOrder`, `title`, `scheduledDate`, `priority`,
                `queuePosition`, `status`, `availabilityMode`, `activeWorkSeconds`,
                `readyEvidenceCount`, `resultPhotoMinCount`, `lastServerRevision`,
                `locallyPending`, `updatedAtEpochMillis`, `driverAudienceMode`
            ) VALUES (
                'worker:entry', 'worker', 'entry', 'task', 4, 'joint',
                'Совместная работа', 10, 'Погрузить бытовку', '2026-08-12', 3,
                0, 'IN_PROGRESS', 'REQUIRED_JOIN', 120,
                0, 1, 12, 0, 1, 'ASSIGNED_DRIVER'
            )
            """.trimIndent(),
        )
        versionSeven.close()

        val versionEight = openHelper(
            context = context,
            name = name,
            version = 8,
            onCreate = { error("Expected the version 7 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_7_8.migrate(database) },
        )
        val database = versionEight.writableDatabase

        assertThat(columns(database, "worker_task")).doesNotContain("driverAudienceMode")
        database.query(
            "SELECT `title`, `status`, `availabilityMode` FROM `worker_task` WHERE `localId`='worker:entry'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Погрузить бытовку")
            assertThat(cursor.getString(1)).isEqualTo("IN_PROGRESS")
            assertThat(cursor.getString(2)).isEqualTo("REQUIRED_JOIN")
        }
        versionEight.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationEightToNineAddsRouteAndDurableVariantManifestDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val name = "worker-room-webp-migration.db"
        context.deleteDatabase(name)
        val versionEight = openHelper(
            context = context,
            name = name,
            version = 8,
            onCreate = { database ->
                database.execSQL(
                    "CREATE TABLE `worker_task` (`localId` TEXT NOT NULL PRIMARY KEY)",
                )
                database.execSQL(
                    "CREATE TABLE `task_evidence` (`evidenceId` TEXT NOT NULL PRIMARY KEY)",
                )
                database.execSQL("INSERT INTO `worker_task` VALUES ('worker:entry')")
                database.execSQL("INSERT INTO `task_evidence` VALUES ('evidence')")
            },
        )
        versionEight.writableDatabase
        versionEight.close()

        val versionNine = openHelper(
            context = context,
            name = name,
            version = 9,
            onCreate = { error("Expected the version 8 database to exist") },
            onUpgrade = { database -> WorkerDatabase.MIGRATION_8_9.migrate(database) },
        )
        val database = versionNine.writableDatabase

        database.query(
            "SELECT `routeIndex`, `routeStepCount` FROM `worker_task` WHERE `localId`='worker:entry'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.getInt(1)).isEqualTo(1)
        }
        database.query(
            "SELECT `variantManifestJson` FROM `task_evidence` WHERE `evidenceId`='evidence'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("[]")
        }
        versionNine.close()
        context.deleteDatabase(name)
    }

    private fun columns(database: SupportSQLiteDatabase, table: String): List<String> =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

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
