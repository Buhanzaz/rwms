package dev.buhanzaz.rwms.client.notifications

import android.app.Application
import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** OS notification navigation cannot redirect, disclose credentials, or supply an order capability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerNotificationIntentTest {
    @Test
    fun `notification target is explicit immutable and contains no domain payload`() {
        val context: Application = RuntimeEnvironment.getApplication()
        val pending = customerNotificationIntent(context)
        val shadow = shadowOf(pending)
        assertThat(shadow.flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        assertThat(shadow.savedIntent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(shadow.savedIntent.component?.packageName).isEqualTo(context.packageName)
        assertThat(shadow.savedIntent.data).isNull()
        assertThat(shadow.savedIntent.extras).isNull()
    }
}
