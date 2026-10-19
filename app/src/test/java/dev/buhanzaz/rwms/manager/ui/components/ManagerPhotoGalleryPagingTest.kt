package dev.buhanzaz.rwms.manager.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManagerPhotoGalleryPagingTest {
    @Test
    fun `initial virtual page resolves to requested logical photo`() {
        val page = managerPhotoGalleryInitialPage(initialIndex = 2, photoCount = 5)

        assertThat(managerPhotoGalleryLogicalIndex(page, 5)).isEqualTo(2)
        assertThat(page).isGreaterThan(1_000_000)
    }

    @Test
    fun `logical photo index wraps in both directions`() {
        assertThat(managerPhotoGalleryLogicalIndex(5, 5)).isEqualTo(0)
        assertThat(managerPhotoGalleryLogicalIndex(6, 5)).isEqualTo(1)
        assertThat(managerPhotoGalleryLogicalIndex(-1, 5)).isEqualTo(4)
    }

    @Test
    fun `single photo stays at the only valid page`() {
        assertThat(managerPhotoGalleryInitialPage(initialIndex = 999, photoCount = 1)).isEqualTo(0)
        assertThat(managerPhotoGalleryLogicalIndex(0, 1)).isEqualTo(0)
    }
}
