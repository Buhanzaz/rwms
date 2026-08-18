package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import org.junit.Test

class InventoryPlanUploadMediaPolicyTest {
    @Test
    fun `uploaded media is added only to work lines and stale material media is removed`() {
        val plan = InventoryPlanSelectionDto(
            mode = "MANUAL",
            priority = 5,
            movementToRepair = false,
            forceCapitalRepair = true,
            logisticsPlanningMode = null,
            logisticsScheduledDate = null,
            lines = listOf(
                line(media = listOf(MediaReferenceDto("old-work", 1))),
                line(media = listOf(MediaReferenceDto("stale-material", 1))),
            ),
            stages = emptyList(),
        )

        val result = plan.withUploadedMedia(
            coverMediaId = null,
            lineMedia = mapOf(
                "work-1" to listOf(MediaReferenceDto("new-work", 2)),
                "material-1" to listOf(MediaReferenceDto("wrong-material", 2)),
            ),
            lineIds = listOf("work-1", "material-1"),
            workLineIds = setOf("work-1"),
        )

        assertThat(result.lines[0].mediaReferences)
            .containsExactly(MediaReferenceDto("old-work", 1), MediaReferenceDto("new-work", 2))
            .inOrder()
        assertThat(result.lines[1].mediaReferences).isEmpty()
        assertThat(result.priority).isEqualTo(5)
        assertThat(result.forceCapitalRepair).isTrue()
        assertThat(result.movementToRepair).isFalse()
        assertThat(result.logisticsPlanningMode).isNull()
        assertThat(result.logisticsScheduledDate).isNull()
    }

    private fun line(
        media: List<MediaReferenceDto>,
    ) = InventoryPlanLineInputDto(
        aggregationKind = "MANUAL",
        catalogNodeId = null,
        routingCatalogNodeId = "routing-node",
        description = "Строка",
        type = "WORK",
        unit = "шт.",
        quantity = "1",
        unitPriceMinor = 100,
        normativeMinutes = "30",
        groupComment = null,
        mediaReferences = media,
    )
}
