package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryPersistedPhotoEvidencePolicyTest {
    @Test
    fun `saved finding media permits supplement without taking another photo`() {
        val persisted = MediaReferenceDto("persisted-media", 3)
        val editor = supplementEditor(media = listOf(persisted))

        assertThat(editor.inventoryPhotoValidationError()).isNull()
        assertThat(editor.inventoryPhotoUrisForUpload()).isEmpty()
        assertThat(editor.persistedInventoryMediaReferences()).containsExactly(persisted)
    }

    @Test
    fun `supplement with no local or persisted evidence stays blocked`() {
        val editor = supplementEditor(media = emptyList())

        assertThat(editor.inventoryPhotoValidationError())
            .isEqualTo("Добавьте хотя бы одну фотографию")
        assertThat(runCatching { editor.inventoryPhotoUrisForUpload() }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `existing cover can remain when supplement adds a non-cover local photo`() {
        val persisted = MediaReferenceDto("persisted-media", 3)
        val editor = supplementEditor(media = listOf(persisted)).copy(
            photoUris = listOf("file://new-photo"),
            coverPhotoUri = null,
        )

        assertThat(editor.inventoryPhotoValidationError()).isNull()
        assertThat(editor.inventoryPhotoUrisForUpload()).containsExactly("file://new-photo")
    }

    @Test
    fun `cached saved photo can be selected as title without being uploaded again`() {
        val persisted = MediaReferenceDto("persisted-media", 3)
        val cachedUri = "file://cache/inventory/persisted-media.jpg"
        val editor = supplementEditor(media = listOf(persisted)).copy(
            photoUris = listOf(cachedUri),
            coverPhotoUri = cachedUri,
            persistedPhotoMedia = mapOf(cachedUri to persisted),
        )

        assertThat(editor.inventoryPhotoValidationError()).isNull()
        assertThat(editor.inventoryPhotoUrisForUpload()).containsExactly(cachedUri)
        assertThat(editor.pendingInventoryPhotoUris()).isEmpty()
        assertThat(editor.coverPhotoUri?.let(editor.persistedPhotoMedia::get)).isEqualTo(persisted)
    }

    @Test
    fun `only new photo is queued when a saved cached photo is kept`() {
        val persisted = MediaReferenceDto("persisted-media", 3)
        val cachedUri = "file://cache/inventory/persisted-media.jpg"
        val newUri = "content://camera/new-photo"
        val editor = supplementEditor(media = listOf(persisted)).copy(
            photoUris = listOf(cachedUri, newUri),
            coverPhotoUri = cachedUri,
            persistedPhotoMedia = mapOf(cachedUri to persisted),
        )

        assertThat(editor.pendingInventoryPhotoUris()).containsExactly(newUri)
    }

    @Test
    fun `removing visible saved photo excludes only that reference and keeps unavailable evidence`() {
        val visible = MediaReferenceDto("visible-media", 3)
        val unavailable = MediaReferenceDto("unavailable-media", 4)
        val cachedUri = "file://cache/inventory/visible-media.jpg"
        val editor = supplementEditor(media = listOf(visible, unavailable)).copy(
            photoUris = listOf(cachedUri),
            coverPhotoUri = cachedUri,
            persistedPhotoMedia = mapOf(cachedUri to visible),
        )

        val afterRemoval = editor.removeInventoryPhoto(cachedUri)

        assertThat(afterRemoval.photoUris).isEmpty()
        assertThat(afterRemoval.coverPhotoUri).isNull()
        assertThat(afterRemoval.removedPersistedMediaIds).containsExactly(visible.mediaId)
        assertThat(afterRemoval.persistedInventoryMediaReferences()).containsExactly(unavailable)
        assertThat(
            inventoryExistingMediaReferences(
                persisted = afterRemoval.persistedInventoryMediaReferences(),
                uploadedByUri = afterRemoval.uploadedPhotoMedia,
            ),
        ).containsExactly(unavailable)
    }

    @Test
    fun `persisted and already uploaded media are retained once by media id`() {
        val persisted = MediaReferenceDto("persisted-media", 3)
        val duplicateUpload = MediaReferenceDto("persisted-media", 4)
        val newUpload = MediaReferenceDto("new-media", 1)

        assertThat(
            inventoryExistingMediaReferences(
                persisted = listOf(persisted, persisted),
                uploadedByUri = mapOf(
                    "file://duplicate" to duplicateUpload,
                    "file://new" to newUpload,
                ),
            ),
        ).containsExactly(persisted, newUpload).inOrder()
    }

    private fun supplementEditor(media: List<MediaReferenceDto>) = InventoryEditorState(
        findingId = "finding-1",
        number = "БЫТ-001",
        outcome = "MATCHED",
        finding = InventoryFindingDto(
            id = "finding-1",
            inventoryId = "inventory-1",
            findingRevision = 2,
            origin = "REGISTRY",
            inspection = "READY",
            reconciliation = "MATCHED",
            displayCanonicalNumber = "БЫТ-001",
            identityMatchKey = "быт-001",
            passportObservation = ObservationDto("PRESENT", emptyMap<String, Any?>()),
            equipmentObservation = ObservationDto("EXPLICIT_EMPTY", emptyList<Any?>()),
            mutationState = "UNCHANGED",
            comment = "",
            media = media,
            coverMediaId = media.firstOrNull()?.mediaId,
        ),
    )
}
