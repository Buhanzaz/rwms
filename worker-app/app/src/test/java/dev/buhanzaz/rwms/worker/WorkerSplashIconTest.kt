package dev.buhanzaz.rwms.worker

import android.R.attr.windowSplashScreenAnimatedIcon
import android.R.attr.windowSplashScreenBackground
import android.R.attr.windowBackground
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies that startup windows and adaptive launchers use the compact worker mark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WorkerSplashIconTest {
    @Test
    @Config(sdk = [28])
    fun `pre android 12 theme uses compact worker startup background`() {
        val application = RuntimeEnvironment.getApplication()
        val context = ContextThemeWrapper(application, R.style.Theme_RwmsWorker)

        assertThat(context.resolvedResource(windowBackground))
            .isEqualTo(R.drawable.worker_startup_background)
    }

    @Test
    fun `adaptive launcher foreground stays inside the safe zone`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val icon = resources.getDrawable(R.mipmap.ic_launcher_worker, null)

        assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
        val foreground = (icon as AdaptiveIconDrawable).foreground
        assertThat(foreground).isInstanceOf(LayerDrawable::class.java)
        assertThat(foreground.intrinsicWidth).isEqualTo(52.dp(resources))
        assertThat(foreground.intrinsicHeight).isEqualTo((49.05f * resources.displayMetrics.density).roundToInt())
    }

    @Test
    @Config(sdk = [23])
    fun `android 6 loads the new logo without unsupported vector gradients`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val icon = resources.getDrawable(R.mipmap.ic_launcher_worker, null)
        val startup = resources.getDrawable(R.drawable.worker_startup_background, null)

        assertThat(icon).isInstanceOf(BitmapDrawable::class.java)
        assertThat((icon as BitmapDrawable).bitmap.width).isEqualTo(564)
        assertThat(icon.bitmap.height).isEqualTo(532)
        assertThat(startup).isInstanceOf(LayerDrawable::class.java)
        assertThat((startup as LayerDrawable).getDrawable(1)).isInstanceOf(LayerDrawable::class.java)
        assertThat((startup.getDrawable(1) as LayerDrawable).getDrawable(0))
            .isInstanceOf(BitmapDrawable::class.java)
    }

    @Test
    fun `api 31 theme uses compact foreground on worker blue splash`() {
        val application = RuntimeEnvironment.getApplication()
        val context = ContextThemeWrapper(application, R.style.Theme_RwmsWorker)

        assertThat(context.resolvedResource(windowSplashScreenAnimatedIcon))
            .isEqualTo(R.drawable.ic_launcher_worker_foreground)
        assertThat(context.resolvedResource(windowSplashScreenBackground))
            .isEqualTo(R.color.ic_launcher_worker_background)
    }

    private fun ContextThemeWrapper.resolvedResource(attribute: Int): Int {
        val value = TypedValue()
        assertThat(theme.resolveAttribute(attribute, value, true)).isTrue()
        return value.resourceId
    }

    private fun Int.dp(resources: Resources): Int =
        (this * resources.displayMetrics.density).roundToInt()
}
