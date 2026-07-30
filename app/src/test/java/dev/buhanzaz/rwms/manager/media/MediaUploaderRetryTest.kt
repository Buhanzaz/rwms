package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploaderRetryTest {
    @Test
    fun `media reference is returned only after owner projection reports ready generation`() =
        runTest {
            val states = ArrayDeque(
                listOf(
                    media(status = "PROCESSING"),
                    null,
                    media(status = "READY", generation = 4),
                ),
            )

            val reference = awaitReadyMediaReference("media-1") {
                states.removeFirst()
            }

            assertThat(reference).isEqualTo(MediaReferenceDto("media-1", 4))
            assertThat(testScheduler.currentTime).isEqualTo(1_750L)
        }

    @Test
    fun `media readiness fails fast for terminal processing failure`() = runTest {
        val failure = runCatching {
            awaitReadyMediaReference("media-1") {
                media(status = "FAILED")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(MEDIA_PROCESSING_FAILED_MESSAGE)
        assertThat(testScheduler.currentTime).isEqualTo(250L)
    }

    @Test
    fun `media readiness polling has bounded backoff and russian timeout`() = runTest {
        var attempts = 0

        val failure = runCatching {
            awaitReadyMediaReference("media-1") {
                attempts += 1
                media(status = "PROCESSING")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(MEDIA_READY_TIMEOUT_MESSAGE)
        assertThat(attempts).isEqualTo(MEDIA_READY_ATTEMPTS)
        assertThat(testScheduler.currentTime).isEqualTo(35_750L)
    }

    @Test
    fun `media readiness tolerates transient owner projection lag`() = runTest {
        var attempts = 0

        val reference = awaitReadyMediaReference("media-1") {
            attempts += 1
            when (attempts) {
                1 -> throw httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED")
                2 -> throw httpFailure(503)
                else -> media(status = "READY", generation = 6)
            }
        }

        assertThat(reference).isEqualTo(MediaReferenceDto("media-1", 6))
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `media readiness does not hide unrelated authorization failure`() = runTest {
        val failure = runCatching {
            awaitReadyMediaReference("media-1") {
                throw httpFailure(403, "FORBIDDEN")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HttpException::class.java)
        assertThat(testScheduler.currentTime).isEqualTo(250L)
    }

    @Test
    fun `inventory media lag retries only exact not ready problem`() = runTest {
        var attempts = 0

        val result = retryInventoryCommitAfterMediaReady {
            attempts += 1
            if (attempts <= 3) {
                throw httpFailure(422, "INVENTORY_MEDIA_NOT_READY")
            }
            "saved"
        }

        assertThat(result).isEqualTo("saved")
        assertThat(attempts).isEqualTo(4)
        assertThat(testScheduler.currentTime).isEqualTo(1_750L)
    }

    @Test
    fun `inventory media lag timeout is actionable and bounded`() = runTest {
        var attempts = 0

        val failure = runCatching {
            retryInventoryCommitAfterMediaReady {
                attempts += 1
                throw httpFailure(422, "INVENTORY_MEDIA_NOT_READY")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(INVENTORY_MEDIA_READY_TIMEOUT_MESSAGE)
        assertThat(attempts).isEqualTo(INVENTORY_MEDIA_READY_MAX_RETRIES + 1)
        assertThat(testScheduler.currentTime).isEqualTo(11_750L)
    }

    @Test
    fun `inventory media lag does not hide unrelated failures`() = runTest {
        for (failure in listOf(
            httpFailure(422, "INVENTORY_VALIDATION_FAILED"),
            httpFailure(409, "INVENTORY_MEDIA_NOT_READY"),
            httpFailure(500),
        )) {
            var attempts = 0
            val result = runCatching {
                retryInventoryCommitAfterMediaReady {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()

            assertThat(result).isSameInstanceAs(failure)
            assertThat(attempts).isEqualTo(1)
        }
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `media command retries bounded owner proof window`() = runTest {
        var attempts = 0

        val result = retryMediaCommandAfterOwnerProof {
            attempts += 1
            if (attempts <= OWNER_PROOF_MAX_RETRIES) {
                throw httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED")
            }
            "ready"
        }

        assertThat(result).isEqualTo("ready")
        assertThat(attempts).isEqualTo(9)
        assertThat(testScheduler.currentTime).isEqualTo(11_750L)
    }

    @Test
    fun `media command surfaces actionable russian message after retry exhaustion`() = runTest {
        var attempts = 0

        val failure = runCatching {
            retryMediaCommandAfterOwnerProof {
                attempts += 1
                throw httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE)
        assertThat(attempts).isEqualTo(9)
    }

    @Test
    fun `media command retries conflicts and service unavailability`() = runTest {
        for (status in listOf(409, 503)) {
            var attempts = 0
            val result = retryMediaCommandAfterOwnerProof {
                attempts += 1
                if (attempts == 1) throw httpFailure(status)
                "ready"
            }

            assertThat(result).isEqualTo("ready")
            assertThat(attempts).isEqualTo(2)
        }
    }

    @Test
    fun `media command does not retry unrelated forbidden or server errors`() = runTest {
        for (failure in listOf(
            httpFailure(403, "FORBIDDEN"),
            httpFailure(403),
            httpFailure(500),
        )) {
            var attempts = 0
            val result = runCatching {
                retryMediaCommandAfterOwnerProof {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()

            assertThat(result).isInstanceOf(HttpException::class.java)
            assertThat(attempts).isEqualTo(1)
        }
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `owner media read recovers from transient proof lag and service unavailability`() =
        runTest {
            var attempts = 0

            val result = retryMediaReadAfterOwnerProof {
                attempts += 1
                when (attempts) {
                    1 -> throw httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED")
                    2 -> throw httpFailure(503)
                    else -> "photo"
                }
            }

            assertThat(result).isEqualTo("photo")
            assertThat(attempts).isEqualTo(3)
            assertThat(testScheduler.currentTime).isEqualTo(750L)
        }

    @Test
    fun `owner media read does not retry unrelated forbidden`() = runTest {
        var attempts = 0

        val failure = runCatching {
            retryMediaReadAfterOwnerProof {
                attempts += 1
                throw httpFailure(403, "FORBIDDEN")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HttpException::class.java)
        assertThat(attempts).isEqualTo(1)
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `retry policy recognizes only owner proof forbidden plus conflict and unavailable`() {
        assertThat(
            isRetryableMediaOwnerFailure(
                httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED"),
            ),
        ).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(403, "FORBIDDEN"))).isFalse()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(409))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(503))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(500))).isFalse()
    }

    private fun httpFailure(status: Int, code: String? = null): HttpException {
        val body = if (code == null) "{}" else """{"code":"$code"}"""
        return HttpException(
            Response.error<Any>(
                status,
                body.toResponseBody("application/problem+json".toMediaType()),
            ),
        )
    }

    private fun media(
        status: String,
        generation: Long = 0,
    ) = MediaAssetDto(
        id = "media-1",
        folderId = "folder-1",
        clientReferenceId = null,
        fileName = "photo.jpg",
        contentType = "image/jpeg",
        kind = "PHOTO",
        status = status,
        version = 1,
        generation = generation,
        rotationDegrees = 0,
        sortOrder = 0,
        sizeBytes = 10,
        createdAt = "2026-07-28T00:00:00Z",
    )
}
