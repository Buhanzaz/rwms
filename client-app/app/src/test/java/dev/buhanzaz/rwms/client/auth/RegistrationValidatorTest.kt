package dev.buhanzaz.rwms.client.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies that credentials are rejected locally before registration transport starts. */
class RegistrationValidatorTest {
    @Test
    fun `valid username and matching strong passwords pass`() {
        assertThat(RegistrationValidator.validate("client_01", "password-123", "password-123")).isNull()
    }

    @Test
    fun `mismatched confirmation is rejected`() {
        assertThat(RegistrationValidator.validate("client_01", "password-123", "password-124"))
            .isEqualTo("Пароли не совпадают")
    }

    @Test
    fun `unsafe username characters are rejected`() {
        assertThat(RegistrationValidator.validate("client name", "password-123", "password-123"))
            .contains("разрешены")
    }
}
