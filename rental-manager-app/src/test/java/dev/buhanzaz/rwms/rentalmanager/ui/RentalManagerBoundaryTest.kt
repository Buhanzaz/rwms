package dev.buhanzaz.rwms.rentalmanager.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RentalManagerBoundaryTest {
    @Test
    fun `saved command fingerprint is deterministic digest without PII`() {
        val raw = "LEGAL_ENTITY\u001fООО Север\u001f+79991234567\u001fsecret@example.org"

        val first = commandFingerprintDigest(actorScopedCommandFingerprint("manager-a", raw))
        val second = commandFingerprintDigest(actorScopedCommandFingerprint("manager-a", raw))

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
        assertThat(first).matches("[0-9a-f]{64}")
        assertThat(first).doesNotContain("Север")
        assertThat(first).doesNotContain("7999")
        assertThat(first).doesNotContain("example.org")
        assertThat(
            commandFingerprintDigest(actorScopedCommandFingerprint("manager-b", raw)),
        ).isNotEqualTo(first)
    }

    @Test
    fun `final unauthorized invalidates encrypted session`() = runTest {
        val invalidations = mutableListOf<String>()
        val failure = HttpException(
            Response.error<String>(
                401,
                "{}".toResponseBody("application/problem+json".toMediaType()),
            ),
        )

        val message = handleRentalManagerFailure(
            failure = failure,
            messageFor = { "Сессия завершена. Войдите снова." },
            invalidate = { invalidations += it },
        )

        assertThat(message).isEqualTo("Сессия завершена. Войдите снова.")
        assertThat(invalidations).containsExactly(message)
    }

    @Test
    fun `transient transport failure keeps session`() = runTest {
        val invalidations = mutableListOf<String>()

        val message = handleRentalManagerFailure(
            failure = java.io.IOException("offline"),
            messageFor = { "Проверьте подключение." },
            invalidate = { invalidations += it },
        )

        assertThat(message).isEqualTo("Проверьте подключение.")
        assertThat(invalidations).isEmpty()
    }
}
