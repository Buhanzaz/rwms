package dev.buhanzaz.rwms.worker.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        WorkerSessionEntity::class,
        WorkerGroupEntity::class,
        WorkerCategoryEntity::class,
        WorkerTaskEntity::class,
        WorkerAssignmentEntity::class,
        WorkerTaskDetailEntity::class,
        WorkerOutboxEntity::class,
        TaskEvidenceEntity::class,
        WorkerSyncProgressEntity::class,
        WorkerConflictEntity::class,
        WorkerInvalidationEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
abstract class WorkerDatabase : RoomDatabase() {
    abstract fun sessionDao(): WorkerSessionDao
    abstract fun groupDao(): WorkerGroupDao
    abstract fun categoryDao(): WorkerCategoryDao
    abstract fun taskDao(): WorkerTaskDao
    abstract fun assignmentDao(): WorkerAssignmentDao
    abstract fun detailDao(): WorkerTaskDetailDao
    abstract fun outboxDao(): WorkerOutboxDao
    abstract fun evidenceDao(): TaskEvidenceDao
    abstract fun syncProgressDao(): WorkerSyncProgressDao
    abstract fun conflictDao(): WorkerConflictDao
    abstract fun invalidationDao(): WorkerInvalidationDao

    companion object {
        const val DATABASE_NAME = "rwms-worker.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `worker_category` (
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
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_worker_category_userId_queueId`
                    ON `worker_category` (`userId`, `queueId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_worker_category_userId_sortOrder_queueId`
                    ON `worker_category` (`userId`, `sortOrder`, `queueId`)
                    """.trimIndent(),
                )
                // Version 1 only knew categories through task rows. Preserve
                // those visible sections until the next authenticated full
                // feed supplies complete metadata (including empty queues).
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `worker_category` (
                        `localId`,
                        `userId`,
                        `queueId`,
                        `code`,
                        `name`,
                        `type`,
                        `sortOrder`,
                        `audienceModesKey`,
                        `resultPhotoMinCount`,
                        `lastServerRevision`
                    )
                    SELECT
                        `userId` || ':' || `categoryId`,
                        `userId`,
                        `categoryId`,
                        MIN(`categoryCode`),
                        MIN(`categoryName`),
                        'UNKNOWN',
                        MIN(`categorySortOrder`),
                        '',
                        MAX(`resultPhotoMinCount`),
                        MAX(`lastServerRevision`)
                    FROM `worker_task`
                    GROUP BY `userId`, `categoryId`
                    """.trimIndent(),
                )
            }
        }

        /**
         * Recreates only tables whose persisted business-code columns are
         * removed. SQLite cannot drop a column in place, so every projection
         * row is copied explicitly and its Room indexes are recreated.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `worker_group_new` (
                        `localId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `workerClassId` TEXT NOT NULL,
                        `workerClassName` TEXT NOT NULL,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `worker_group_new` (
                        `localId`, `userId`, `groupId`, `name`, `workerClassId`, `workerClassName`
                    )
                    -- A legacy business code is not a worker-class name. Discard
                    -- it instead of presenting it as one; the authenticated
                    -- context sync supplies the canonical name.
                    SELECT
                        `localId`, `userId`, `groupId`, `name`, `workerClassId`, ''
                    FROM `worker_group`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `worker_group`")
                db.execSQL("ALTER TABLE `worker_group_new` RENAME TO `worker_group`")
                db.execSQL(
                    """
                    CREATE INDEX `index_worker_group_userId_name`
                    ON `worker_group` (`userId`, `name`)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE `worker_category_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `worker_category_new` (
                        `localId`, `userId`, `queueId`, `name`, `type`, `sortOrder`,
                        `audienceModesKey`, `resultPhotoMinCount`, `lastServerRevision`
                    )
                    SELECT
                        `localId`, `userId`, `queueId`, `name`, `type`, `sortOrder`,
                        `audienceModesKey`, `resultPhotoMinCount`, `lastServerRevision`
                    FROM `worker_category`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `worker_category`")
                db.execSQL("ALTER TABLE `worker_category_new` RENAME TO `worker_category`")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX `index_worker_category_userId_queueId`
                    ON `worker_category` (`userId`, `queueId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX `index_worker_category_userId_sortOrder_queueId`
                    ON `worker_category` (`userId`, `sortOrder`, `queueId`)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE `worker_task_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `worker_task_new` (
                        `localId`, `userId`, `entryId`, `taskId`, `version`, `categoryId`,
                        `categoryName`, `categorySortOrder`, `title`, `unitNumber`, `taskText`,
                        `scheduledDate`, `deadlineAt`, `priority`, `queuePosition`, `status`,
                        `availabilityMode`, `plannedDurationMinutes`, `activeStartedAt`,
                        `activeWorkSeconds`, `readyEvidenceCount`, `resultPhotoMinCount`,
                        `lastServerRevision`, `locallyPending`, `updatedAtEpochMillis`
                    )
                    SELECT
                        `localId`, `userId`, `entryId`, `taskId`, `version`, `categoryId`,
                        `categoryName`, `categorySortOrder`, `title`, `unitNumber`, `taskText`,
                        `scheduledDate`, `deadlineAt`, `priority`, `queuePosition`, `status`,
                        `availabilityMode`, `plannedDurationMinutes`, `activeStartedAt`,
                        `activeWorkSeconds`, `readyEvidenceCount`, `resultPhotoMinCount`,
                        `lastServerRevision`, `locallyPending`, `updatedAtEpochMillis`
                    FROM `worker_task`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `worker_task`")
                db.execSQL("ALTER TABLE `worker_task_new` RENAME TO `worker_task`")
                db.execSQL(
                    """
                    CREATE INDEX `index_worker_task_userId_categorySortOrder_queuePosition`
                    ON `worker_task` (`userId`, `categorySortOrder`, `queuePosition`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX `index_worker_task_userId_entryId`
                    ON `worker_task` (`userId`, `entryId`)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `worker_session` ADD COLUMN `currentGroupId` TEXT")
                db.execSQL("ALTER TABLE `worker_session` ADD COLUMN `currentGroupName` TEXT")
                db.execSQL(
                    """
                    ALTER TABLE `worker_session`
                    ADD COLUMN `operationalAvailability` TEXT NOT NULL DEFAULT 'AVAILABLE'
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerCountedActiveSeconds` INTEGER")
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerRemainingSeconds` INTEGER")
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerRemainingPercent` REAL")
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerState` TEXT")
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerNextTransitionAt` TEXT")
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `timerServerTime` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 4 categories predate stable queue purposes. They
                // were all ordinary task-board queues; the authenticated
                // context refresh can later replace this canonical purpose.
                db.execSQL(
                    """
                    ALTER TABLE `worker_category`
                    ADD COLUMN `queuePurpose` TEXT NOT NULL DEFAULT 'GENERAL'
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep the palette exactly as issued by task-board. A null
                // value deliberately means that the client must not invent
                // green/yellow/red thresholds of its own.
                db.execSQL("ALTER TABLE `worker_session` ADD COLUMN `kpiPaletteJson` TEXT")
                db.execSQL(
                    """
                    ALTER TABLE `worker_category`
                    ADD COLUMN `groupIdsKey` TEXT NOT NULL DEFAULT ''
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the server-issued driver audience used to split personal
         * logistics from warehouse-shared movements. Legacy cached rows stay
         * unclassified until the next authoritative full feed refresh.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `worker_task` ADD COLUMN `driverAudienceMode` TEXT")
            }
        }

        /** Removes the obsolete local driver-table classification while preserving worker tasks. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `worker_task_new` (
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
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `worker_task_new` (
                        `localId`, `userId`, `entryId`, `taskId`, `version`, `categoryId`,
                        `categoryName`, `categorySortOrder`, `title`, `unitNumber`, `taskText`,
                        `scheduledDate`, `deadlineAt`, `priority`, `queuePosition`, `status`,
                        `availabilityMode`, `plannedDurationMinutes`, `activeStartedAt`,
                        `activeWorkSeconds`, `readyEvidenceCount`, `resultPhotoMinCount`,
                        `lastServerRevision`, `locallyPending`, `updatedAtEpochMillis`,
                        `timerCountedActiveSeconds`, `timerRemainingSeconds`,
                        `timerRemainingPercent`, `timerState`, `timerNextTransitionAt`,
                        `timerServerTime`
                    )
                    SELECT
                        `localId`, `userId`, `entryId`, `taskId`, `version`, `categoryId`,
                        `categoryName`, `categorySortOrder`, `title`, `unitNumber`, `taskText`,
                        `scheduledDate`, `deadlineAt`, `priority`, `queuePosition`, `status`,
                        `availabilityMode`, `plannedDurationMinutes`, `activeStartedAt`,
                        `activeWorkSeconds`, `readyEvidenceCount`, `resultPhotoMinCount`,
                        `lastServerRevision`, `locallyPending`, `updatedAtEpochMillis`,
                        `timerCountedActiveSeconds`, `timerRemainingSeconds`,
                        `timerRemainingPercent`, `timerState`, `timerNextTransitionAt`,
                        `timerServerTime`
                    FROM `worker_task`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `worker_task`")
                db.execSQL("ALTER TABLE `worker_task_new` RENAME TO `worker_task`")
                db.execSQL(
                    """
                    CREATE INDEX `index_worker_task_userId_categorySortOrder_queuePosition`
                    ON `worker_task` (`userId`, `categorySortOrder`, `queuePosition`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX `index_worker_task_userId_entryId`
                    ON `worker_task` (`userId`, `entryId`)
                    """.trimIndent(),
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
object WorkerDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WorkerDatabase =
        Room.databaseBuilder(context, WorkerDatabase::class.java, WorkerDatabase.DATABASE_NAME)
            .addMigrations(
                WorkerDatabase.MIGRATION_1_2,
                WorkerDatabase.MIGRATION_2_3,
                WorkerDatabase.MIGRATION_3_4,
                WorkerDatabase.MIGRATION_4_5,
                WorkerDatabase.MIGRATION_5_6,
                WorkerDatabase.MIGRATION_6_7,
                WorkerDatabase.MIGRATION_7_8,
            )
            .build()
}
