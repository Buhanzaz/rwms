package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RentalManagerProblemMessageTest {
    @Test
    fun `known conflict code is shown only in Russian`() {
        val message = rentalManagerProblemMessage(
            409,
            ProblemDetailsDto(status = 409, code = "VERSION_CONFLICT"),
        )

        assertThat(message).isEqualTo("Данные уже изменились. Обновите экран и повторите действие.")
        assertThat(message).doesNotContain("VERSION_CONFLICT")
    }

    @Test
    fun `technical backend detail never reaches the user`() {
        val message = rentalManagerProblemMessage(
            500,
            ProblemDetailsDto(
                status = 500,
                code = "INTERNAL",
                detail = "java.lang.IllegalStateException /api/internal/orders",
            ),
        )

        assertThat(message).isEqualTo("Сервис временно недоступен. Повторите попытку позже.")
        assertThat(message).doesNotContain("java")
        assertThat(message).doesNotContain("/api/")
    }
}
