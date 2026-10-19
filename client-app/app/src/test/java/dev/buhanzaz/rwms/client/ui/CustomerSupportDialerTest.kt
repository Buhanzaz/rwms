package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises real Android Intent construction without placing calls or requesting permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerSupportDialerTest {
    @Test
    fun `supplied formatted phone becomes only an action dial tel intent`() {
        val context = RecordingContext()

        val result = openCustomerSupportDialer(context, "+7 (495) 123-45-67")

        assertThat(result).isEqualTo(CustomerSupportDialResult.OPENED)
        val intent = requireNotNull(context.started)
        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.action).isNotEqualTo(Intent.ACTION_CALL)
        assertThat(intent.data?.scheme).isEqualTo("tel")
        assertThat(intent.data?.schemeSpecificPart).isEqualTo("+74951234567")
        assertThat(intent.component).isNull()
        assertThat(intent.extras).isNull()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `activity context keeps the caller task without an unnecessary new task flag`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()

            assertThat(openCustomerSupportDialer(ContextWrapper(activity), "+74951234567"))
                .isEqualTo(CustomerSupportDialResult.OPENED)

            val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `missing malformed and executable dial strings never launch an activity`() {
        val context = RecordingContext()
        listOf(null, "", " ", "tel:+74951234567", "https://example.com", "*100#", "+74951234567;123", "+74951234567\n123", "+7495abc1234567", "++74951234567", "123", "1234567890123456").forEach { phone ->
            assertThat(openCustomerSupportDialer(context, phone))
                .isEqualTo(CustomerSupportDialResult.PHONE_UNAVAILABLE)
        }
        assertThat(context.started).isNull()
    }

    @Test
    fun `missing dialer is explicit failure without an automatic call fallback`() {
        val context = RecordingContext(ActivityNotFoundException("Dialer unavailable"))

        assertThat(openCustomerSupportDialer(context, "+74951234567"))
            .isEqualTo(CustomerSupportDialResult.DIALER_UNAVAILABLE)
        assertThat(context.attempts).isEqualTo(1)
        assertThat(context.started?.action).isEqualTo(Intent.ACTION_DIAL)
    }

    @Test
    fun `platform security rejection is not reported as a successful dialer launch`() {
        val context = RecordingContext(SecurityException("Dialer blocked"))

        assertThat(openCustomerSupportDialer(context, "+74951234567"))
            .isEqualTo(CustomerSupportDialResult.DIALER_UNAVAILABLE)
        assertThat(context.attempts).isEqualTo(1)
    }

    @Test
    fun `normalization does not invent a country prefix`() {
        assertThat(customerSupportDialNumber("8 (495) 123-45-67")).isEqualTo("84951234567")
    }

    private class RecordingContext(
        private val failure: RuntimeException? = null,
    ) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var started: Intent? = null
        var attempts: Int = 0

        override fun startActivity(intent: Intent) {
            attempts++
            started = intent
            failure?.let { throw it }
        }
    }
}
