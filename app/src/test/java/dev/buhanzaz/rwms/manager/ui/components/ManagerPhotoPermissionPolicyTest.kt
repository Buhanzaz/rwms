package dev.buhanzaz.rwms.manager.ui.components

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ManagerPhotoPermissionPolicyTest {
    @Test
    fun `photo capture permission asks only for the camera`() {
        assertThat(managerPhotoCapturePermissions().asList())
            .containsExactly(Manifest.permission.CAMERA)
    }

    @Test
    fun `video audio permission remains a separate microphone request`() {
        assertThat(managerVideoAudioPermissions().asList())
            .containsExactly(Manifest.permission.RECORD_AUDIO)
    }
}
