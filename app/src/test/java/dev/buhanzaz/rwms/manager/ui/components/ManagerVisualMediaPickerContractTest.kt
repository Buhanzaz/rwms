package dev.buhanzaz.rwms.manager.ui.components

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerVisualMediaPickerContractTest {
    @Test
    fun `gallery request opens the default-limit system photo picker`() {
        val request = managerVisualMediaPickerRequest()
        val intent = ActivityResultContracts.PickMultipleVisualMedia()
            .createIntent(RuntimeEnvironment.getApplication(), request)

        assertThat(request.mediaType)
            .isEqualTo(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        assertThat(intent.action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
        assertThat(intent.action).isNotEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        assertThat(intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1))
            .isEqualTo(MediaStore.getPickImagesMaxLimit())
        assertThat(intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)).isGreaterThan(1)
    }

    @Test
    @Config(sdk = [28], manifest = Config.NONE)
    fun `unavailable legacy gallery does not invoke picker launch`() {
        var launchCount = 0

        val launched = launchManagerVisualMediaPicker(RuntimeEnvironment.getApplication()) {
            launchCount++
        }

        assertThat(launched).isFalse()
        assertThat(launchCount).isEqualTo(0)
    }
}
