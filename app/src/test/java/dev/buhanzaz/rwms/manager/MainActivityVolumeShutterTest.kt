package dev.buhanzaz.rwms.manager

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MainActivityVolumeShutterTest {
    @Test
    fun `volume buttons trigger one shutter action and consume key release`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        var shutterCount = 0
        activity.setVolumeShutterHandler { shutterCount += 1 }

        assertThat(
            activity.onKeyDown(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0),
            ),
        ).isTrue()
        assertThat(
            activity.onKeyDown(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 1),
            ),
        ).isTrue()
        assertThat(
            activity.onKeyUp(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0),
            ),
        ).isTrue()
        assertThat(
            activity.onKeyDown(
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0),
            ),
        ).isTrue()
        assertThat(
            activity.onKeyUp(
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0),
            ),
        ).isTrue()
        assertThat(shutterCount).isEqualTo(2)
    }
}
