package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers the ManagerApp boundary that separates diagnostics from user-visible failure copy. */
class ManagerProblemMessageTest {
    @Test
    fun `technical backend detail is replaced with an actionable Russian message`() {
        val message = managerProblemMessage(
            400,
            ProblemDetailsDto(
                detail = "RMS Logistics Service returned HTTP 400: java.lang.IllegalStateException",
                code = "UNKNOWN",
            ),
        )

        assertThat(message)
            .isEqualTo("Не удалось выполнить операцию. Проверьте данные и повторите действие.")
        assertThat(message).doesNotContain("HTTP")
        assertThat(message).doesNotContain("Exception")
    }

    @Test
    fun `safe Russian conflict explains the next action`() {
        val message = managerProblemMessage(
            409,
            ProblemDetailsDto(detail = "Смена уже изменена другим логистом."),
        )

        assertThat(message)
            .isEqualTo("Смена уже изменена другим логистом. Обновите данные и повторите действие.")
    }

    @Test
    fun `known authorization code is mapped independently of raw detail`() {
        val message = managerProblemMessage(
            403,
            ProblemDetailsDto(detail = "Access denied", code = "ACCESS_DENIED"),
        )

        assertThat(message)
            .isEqualTo("Недостаточно прав для операции. Обратитесь к администратору.")
    }

    @Test
    fun `unknown server failure does not expose status code`() {
        val message = managerProblemMessage(502, null)

        assertThat(message)
            .isEqualTo("Сервис временно недоступен. Повторите попытку позже.")
        assertThat(message).doesNotContain("502")
    }
}
