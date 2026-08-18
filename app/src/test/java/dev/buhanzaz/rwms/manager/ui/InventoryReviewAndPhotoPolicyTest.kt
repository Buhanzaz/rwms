package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import org.junit.Test

/** Covers the local review transition and inventory-to-work-photo UI adapter. */
class InventoryReviewAndPhotoPolicyTest {
    @Test
    fun `supplement enables retained editor without replacing its draft`() {
        val review = InventoryEditorState(
            findingId = "finding-1",
            number = "160780",
            outcome = "MATCHED",
            readOnly = true,
            comment = "Сохранённый комментарий",
        )

        val supplement = review.beginInventorySupplement()

        assertThat(supplement.readOnly).isFalse()
        assertThat(supplement.comment).isEqualTo("Сохранённый комментарий")
    }

    @Test
    fun `work photo picker receives earlier local and persisted condition photos`() {
        val localUri = "file:///cache/condition-local.jpg"
        val persistedUri = "file:///cache/condition-saved.jpg"
        val persistedReference = MediaReferenceDto("condition-media", 4)
        val inventory = InventoryEditorState(
            findingId = "finding-1",
            number = "160780",
            outcome = "MATCHED",
            readOnly = true,
            photoUris = listOf(localUri, persistedUri),
            coverPhotoUri = persistedUri,
            persistedPhotoMedia = mapOf(persistedUri to persistedReference),
        )

        val plan = inventory.toMaintenancePlanEditor()

        assertThat(plan.readOnly).isTrue()
        assertThat(plan.photoUris).containsExactly(localUri)
        assertThat(plan.readyMedia).containsExactly(persistedReference)
        assertThat(plan.readyPhotoUris).containsExactly(
            persistedReference.mediaId,
            persistedUri,
        )
    }

    @Test
    fun `photos moved to work are removed from the condition-photo adapter state`() {
        val localUri = "file:///cache/condition-local.jpg"
        val persistedUri = "file:///cache/condition-saved.jpg"
        val persistedReference = MediaReferenceDto("condition-media", 4)
        val inventory = InventoryEditorState(
            findingId = "finding-1",
            number = "160780",
            outcome = "MATCHED",
            photoUris = listOf(localUri, persistedUri),
            coverPhotoUri = persistedUri,
            persistedPhotoMedia = mapOf(persistedUri to persistedReference),
        )
        val emptiedPlan = inventory.toMaintenancePlanEditor().copy(
            photoUris = emptyList(),
            readyMedia = emptyList(),
        )

        val updated = inventory.withMaintenancePlanEditor(emptiedPlan)

        assertThat(updated.photoUris).isEmpty()
        assertThat(updated.coverPhotoUri).isNull()
        assertThat(updated.persistedPhotoMedia).isEmpty()
    }
}
