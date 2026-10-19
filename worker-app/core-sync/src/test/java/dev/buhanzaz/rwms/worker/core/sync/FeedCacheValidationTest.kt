package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import org.junit.Test

class FeedCacheValidationTest {
    @Test
    fun revalidatesOnlyTheSameRevisionAndAuthorizedQueueProjection() {
        val remote = categoryDto("repair", "Ремонты", 10)
        val cached = categoryEntity(remote)

        assertThat(cachedFeedMatchesContext(session(), listOf(cached), 11, listOf(remote))).isTrue()
        assertThat(cachedFeedMatchesContext(session(), listOf(cached), 12, listOf(remote))).isFalse()
        assertThat(
            cachedFeedMatchesContext(
                session(),
                listOf(cached),
                11,
                listOf(categoryDto("furniture", "Перемещение мебели", 20)),
            ),
        ).isFalse()
        assertThat(
            cachedFeedMatchesContext(
                session(),
                listOf(cached),
                11,
                listOf(remote.copy(queuePurpose = "LOGISTICS_DRIVER")),
            ),
        ).isFalse()
    }

    @Test
    fun versionOneCacheWithoutIndependentQueueMetadataRequiresFullFeed() {
        assertThat(
            cachedFeedMatchesContext(
                session(),
                cachedCategories = emptyList(),
                contextRevision = 11,
                contextCategories = listOf(categoryDto("repair", "Ремонты", 10)),
            ),
        ).isFalse()
    }

    private fun session() = WorkerSessionEntity(
        userId = USER_ID,
        displayName = "Рабочий",
        login = "worker",
        warehouseId = "warehouse",
        leaseId = "lease",
        leaseExpiresAtEpochMillis = 2,
        serverEpochMillis = 1,
        elapsedRealtimeAtSyncMillis = 1,
        revision = 11,
        feedEtag = "\"feed-11\"",
        cacheHidden = false,
        updatedAtEpochMillis = 1,
    )

    private fun categoryDto(queueId: String, name: String, sortOrder: Int) = WorkerCategoryDto(
        queueId = queueId,
        name = name,
        type = "REPAIR",
        queuePurpose = "GENERAL",
        groupIds = listOf("group-repair"),
        sortOrder = sortOrder,
        audienceModes = listOf("MANDATORY", "AVAILABLE"),
        resultPhotoMinCount = 1,
    )

    private fun categoryEntity(dto: WorkerCategoryDto) = WorkerCategoryEntity(
        localId = "$USER_ID:${dto.queueId}",
        userId = USER_ID,
        queueId = dto.queueId,
        name = dto.name,
        type = dto.type,
        queuePurpose = dto.queuePurpose,
        groupIdsKey = dto.normalizedGroupIdsKey(),
        sortOrder = dto.sortOrder,
        audienceModesKey = dto.normalizedAudienceModesKey(),
        resultPhotoMinCount = dto.resultPhotoMinCount,
        lastServerRevision = 11,
    )

    private companion object {
        const val USER_ID = "worker"
    }
}
