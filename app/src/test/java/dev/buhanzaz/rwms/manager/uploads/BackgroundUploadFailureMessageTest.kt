package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE
import dev.buhanzaz.rwms.manager.media.MediaOwnerRetryExhaustedException
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class BackgroundUploadFailureMessageTest {
    @Test
    fun `known media exhaustion explains retry while confirming retained photos`() {
        val failure = MediaOwnerRetryExhaustedException(
            MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE,
            IllegalStateException("private diagnostic details"),
        )

        val message = backgroundUploadFailureMessage(failure) { error("Unexpected HTTP error") }

        assertThat(message).contains(MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE)
        assertThat(message).contains("Фотографии сохранены в очереди")
        assertThat(message).doesNotContain("private diagnostic details")
    }

    @Test
    fun `unknown exception messages remain hidden`() {
        val message = backgroundUploadFailureMessage(
            IllegalStateException("private file path or server details"),
        ) { error("Unexpected HTTP error") }

        assertThat(message).isEqualTo(
            "Не удалось отправить данные. Загрузка сохранена — повторите отправку позже.",
        )
    }

    @Test
    fun `connection failures retain the connection recovery instruction`() {
        val message = backgroundUploadFailureMessage(IOException("private endpoint")) {
            error("Unexpected HTTP error")
        }

        assertThat(message).isEqualTo(
            "Нет соединения. Загрузка сохранена — проверьте интернет и повторите отправку.",
        )
    }

    @Test
    fun `HTTP failures continue through the established Problem Details mapper`() {
        val failure = HttpException(Response.error<Any>(403, "{}".toResponseBody()))
        val message = backgroundUploadFailureMessage(failure) { mapped ->
            assertThat(mapped).isSameInstanceAs(failure)
            "Недостаточно прав"
        }

        assertThat(message).isEqualTo("Недостаточно прав")
    }
}
