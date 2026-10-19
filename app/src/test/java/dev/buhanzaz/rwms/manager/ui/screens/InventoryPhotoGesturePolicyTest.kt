package dev.buhanzaz.rwms.manager.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InventoryPhotoGesturePolicyTest {
    @Test
    fun `inventory uses the ordinary photographs step title`() {
        assertThat(INVENTORY_PHOTOS_TITLE).isEqualTo("Фотографии")
    }

    @Test
    fun `ordinary thumbnail tap selects the cover photo`() {
        assertThat(inventoryPhotoGestureAction(holdTimedOut = false))
            .isEqualTo(InventoryPhotoGestureAction.SelectCover)
    }

    @Test
    fun `one and a half second hold opens the fullscreen preview`() {
        assertThat(INVENTORY_PHOTO_PREVIEW_HOLD_MILLIS).isEqualTo(1_500L)
        assertThat(inventoryPhotoGestureAction(holdTimedOut = true))
            .isEqualTo(InventoryPhotoGestureAction.OpenPreview)
    }
}
