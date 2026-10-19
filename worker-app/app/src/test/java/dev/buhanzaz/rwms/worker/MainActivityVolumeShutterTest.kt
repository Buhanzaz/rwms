package dev.buhanzaz.rwms.worker

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies that WorkerApp exposes the same one-shot volume shutter behavior as ManagerApp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MainActivityVolumeShutterTest {
    @Test
    fun `volume buttons capture once per press only while camera handler is active`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        var shutterCount = 0

        assertThat(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, keyDown(KeyEvent.KEYCODE_VOLUME_UP)))
            .isFalse()
        assertThat(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, keyUp(KeyEvent.KEYCODE_VOLUME_UP)))
            .isFalse()

        activity.setVolumeShutterHandler { shutterCount += 1 }

        assertThat(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, keyDown(KeyEvent.KEYCODE_VOLUME_UP)))
            .isTrue()
        assertThat(
            activity.onKeyDown(
                KeyEvent.KEYCODE_VOLUME_UP,
                keyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 1),
            ),
        ).isTrue()
        assertThat(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, keyUp(KeyEvent.KEYCODE_VOLUME_UP)))
            .isTrue()
        assertThat(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, keyDown(KeyEvent.KEYCODE_VOLUME_DOWN)))
            .isTrue()
        assertThat(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, keyUp(KeyEvent.KEYCODE_VOLUME_DOWN)))
            .isTrue()
        assertThat(activity.onKeyDown(KeyEvent.KEYCODE_SPACE, keyDown(KeyEvent.KEYCODE_SPACE)))
            .isFalse()
        assertThat(shutterCount).isEqualTo(2)

        activity.setVolumeShutterHandler(null)

        assertThat(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, keyDown(KeyEvent.KEYCODE_VOLUME_DOWN)))
            .isFalse()
        assertThat(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, keyUp(KeyEvent.KEYCODE_VOLUME_DOWN)))
            .isFalse()
        assertThat(shutterCount).isEqualTo(2)
    }

    private fun keyDown(keyCode: Int, repeatCount: Int = 0): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeatCount)

    private fun keyUp(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0)
}
