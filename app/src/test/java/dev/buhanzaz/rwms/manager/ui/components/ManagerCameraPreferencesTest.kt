package dev.buhanzaz.rwms.manager.ui.components

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerCameraPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences("manager-camera-v2", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `round trips independent photo and video settings`() {
        val expected = ManagerCameraSettings(
            gridEnabled = true,
            aspectRatio = ManagerCameraAspectRatio.SixteenNine,
            requestedMegapixels = 12,
            ultraHdrEnabled = true,
            videoHdrEnabled = false,
            motionCaptureEnabled = true,
            exposureEvTenths = -7,
            videoQuality = ManagerVideoQuality.Uhd,
            videoFramesPerSecond = 60,
        )

        ManagerCameraPreferences(context).save(expected)

        assertThat(ManagerCameraPreferences(context).load()).isEqualTo(expected)
    }

    @Test
    fun `legacy automatic hdr preference migrates once to fast ordinary photos`() {
        context.getSharedPreferences("manager-camera-v2", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("ultra-hdr", true)
            .commit()

        val migrated = ManagerCameraPreferences(context).load()
        ManagerCameraPreferences(context).save(migrated.copy(ultraHdrEnabled = true))

        assertThat(migrated.ultraHdrEnabled).isFalse()
        assertThat(ManagerCameraPreferences(context).load().ultraHdrEnabled).isTrue()
    }
}
