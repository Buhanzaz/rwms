package dev.buhanzaz.rwms.manager.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ManagerCameraPolicyTest {
    @Test
    fun `zoom controls are derived from the bound CameraInfo range`() {
        assertThat(managerSupportedZoomStops(0.7f, 3.6f))
            .containsExactly(0.7f, 1f, 2f, 3.6f)
            .inOrder()
        assertThat(managerSupportedZoomStops(1.25f, 1.25f))
            .containsExactly(1.25f)
    }

    @Test
    fun `requested zoom is always constrained to the supported lens range`() {
        assertThat(managerCoerceZoom(0.5f, 0.7f, 3.6f)).isEqualTo(0.7f)
        assertThat(managerCoerceZoom(2f, 0.7f, 3.6f)).isEqualTo(2f)
        assertThat(managerCoerceZoom(9f, 0.7f, 3.6f)).isEqualTo(3.6f)
    }
}
