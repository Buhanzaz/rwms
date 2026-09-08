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
            .contains("латинские")
        assertThat(RegistrationValidator.validate(".client", "password-123", "password-123"))
            .contains("начинаться")
        assertThat(RegistrationValidator.validate("клиент", "password-123", "password-123"))
            .contains("латинские")
    }

    @Test
    fun `registration requires both names before creating the account`() {
        assertThat(registrationProfileValidationMessage(" ", "Петров", "client@example.test", "+79990000000"))
            .isEqualTo("Введите имя")
        assertThat(registrationProfileValidationMessage("Иван", " ", "client@example.test", "+79990000000"))
            .isEqualTo("Введите фамилию")
    }

    @Test
    fun `registration rejects facts that exceed the logistics profile contract`() {
        assertThat(registrationProfileValidationMessage("И".repeat(256), "Петров", "client@example.test", "+79990000000"))
            .isNotNull()
        assertThat(registrationProfileValidationMessage("Иван", "П".repeat(256), "client@example.test", "+79990000000"))
            .isNotNull()
        assertThat(registrationProfileValidationMessage("Иван", "Петров", "a".repeat(314) + "@b.test", "+79990000000"))
            .isNotNull()
        assertThat(registrationProfileValidationMessage("Иван", "Петров", "client@example.test", "1".repeat(33)))
            .isNotNull()
    }

    @Test
    fun `registration accepts trimmed facts at the logistics contract limits`() {
        assertThat(registrationProfileValidationMessage(
            " ${"И".repeat(255)} ",
            " ${"П".repeat(255)} ",
            " ${"a".repeat(313)}@b.test ",
            " ${"1".repeat(32)} ",
        )).isNull()
    }
}
