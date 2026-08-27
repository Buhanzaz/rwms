package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers safe normalization of optional Android speech-recognition results. */
class DeliveryVoicePolicyTest {
    @Test
    fun `first non blank voice phrase becomes address`() {
        assertThat(DeliveryVoicePolicy.recognizedAddress(listOf(" ", " Невский проспект, 1 ")))
            .isEqualTo("Невский проспект, 1")
    }

    @Test
    fun `missing voice result does not fabricate address`() {
        assertThat(DeliveryVoicePolicy.recognizedAddress(null)).isNull()
        assertThat(DeliveryVoicePolicy.recognizedAddress(listOf(" "))).isNull()
    }
}
