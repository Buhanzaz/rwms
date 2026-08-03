package dev.buhanzaz.rwms.manager.ui.components

import android.view.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerPhotoRotationTest {
    @Test
    fun `camera capture keeps every current display rotation and safely defaults while attaching`() {
        listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ).forEach { rotation ->
            assertThat(managerCaptureTargetRotation(rotation)).isEqualTo(rotation)
        }
        assertThat(managerCaptureTargetRotation(null)).isEqualTo(Surface.ROTATION_0)
        assertThat(managerCaptureTargetRotation(42)).isEqualTo(Surface.ROTATION_0)
    }
}
