package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploaderRetryTest {
    @Test
    fun `parallel owner batches share one three-upload transport limit`() = runTest {
        val permits = Semaphore(MEDIA_UPLOAD_PARALLELISM)
        val release = CompletableDeferred<Unit>()
        var activeUploads = 0
        var maximumActiveUploads = 0

        val batches = (0..1).map { batch ->
            async {
                uploadBoundedParallelOrdered(
                    inputs = (0..2).map { index -> batch to index },
                    permits = permits,
                    upload = { input ->
                        activeUploads += 1
                        maximumActiveUploads = maxOf(maximumActiveUploads, activeUploads)
                        try {
                            release.await()
                            input
                        } finally {
                            activeUploads -= 1
                        }
                    },
                    onReady = { _, _ -> },
                )
            }
        }
        runCurrent()

        assertThat(activeUploads).isEqualTo(MEDIA_UPLOAD_PARALLELISM)
        assertThat(maximumActiveUploads).isEqualTo(MEDIA_UPLOAD_PARALLELISM)

        release.complete(Unit)
        advanceUntilIdle()
        batches.forEach { it.await() }

        assertThat(maximumActiveUploads).isEqualTo(MEDIA_UPLOAD_PARALLELISM)
    }

    @Test
    fun `bounded uploads run three at a time but publish ready items in source order`() = runTest {
        val releaseFirstBatch = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val callbacks = mutableListOf<Int>()
        var activeUploads = 0
        var maximumActiveUploads = 0

        val results = async {
            uploadBoundedParallelOrdered(
                inputs = (0..3).toList(),
                upload = { index ->
                    started += index
                    activeUploads += 1
                    maximumActiveUploads = maxOf(maximumActiveUploads, activeUploads)
                    try {
                        if (index < MEDIA_UPLOAD_PARALLELISM) {
                            releaseFirstBatch.await()
                        }
                        "media-$index"
                    } finally {
                        activeUploads -= 1
                    }
                },
                onReady = { index, _ -> callbacks += index },
            )
        }
        runCurrent()

        assertThat(started).containsExactly(0, 1, 2).inOrder()
        assertThat(activeUploads).isEqualTo(MEDIA_UPLOAD_PARALLELISM)
        assertThat(maximumActiveUploads).isEqualTo(MEDIA_UPLOAD_PARALLELISM)

        releaseFirstBatch.complete(Unit)
        advanceUntilIdle()

        assertThat(results.await()).containsExactly(
            "media-0",
            "media-1",
            "media-2",
            "media-3",
        ).inOrder()
        assertThat(callbacks).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `one failed upload does not discard ready references from its peers`() = runTest {
        val callbacks = mutableListOf<Int>()

        val failure = runCatching {
            uploadBoundedParallelOrdered(
                inputs = listOf(0, 1, 2),
                upload = { index ->
                    if (index == 1) error("broken photo")
                    "media-$index"
                },
                onReady = { index, _ -> callbacks += index },
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("broken photo")
        assertThat(callbacks).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `upload identity is stable per local uri and does not collapse same bytes from another uri`() {
        val owner = MediaOwner(
            ownerType = "INVENTORY_FINDING",
            ownerId = "44444444-4444-4444-4444-444444444444",
            warehouseId = "33333333-3333-3333-3333-333333333333",
            context = "INSPECTION",
        )
        val payload = PhotoPayload.exactJpeg(
            fileName = "inspection.jpg",
            bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
        )

        val firstAttempt = mediaUploadIdentity(
            owner = owner,
            localUri = "file:///cache/first.jpg",
            photo = payload,
            sortOrder = 1,
        )
        val retry = mediaUploadIdentity(
            owner = owner,
            localUri = "file:///cache/first.jpg",
            photo = payload,
            sortOrder = 1,
        )
        val differentLocalPhotoWithSameBytes = mediaUploadIdentity(
            owner = owner,
            localUri = "file:///cache/second.jpg",
            photo = payload,
            sortOrder = 1,
        )

        assertThat(retry).isEqualTo(firstAttempt)
        assertThat(differentLocalPhotoWithSameBytes.folderId).isNotEqualTo(firstAttempt.folderId)
        assertThat(differentLocalPhotoWithSameBytes.createSessionKey)
            .isNotEqualTo(firstAttempt.createSessionKey)
        assertThat(firstAttempt.createSessionKey)
            .isNotEqualTo(firstAttempt.contentAndFinalizeKey)
        listOf(
            firstAttempt.folderId,
            firstAttempt.createSessionKey,
            firstAttempt.contentAndFinalizeKey,
        ).forEach(UUID::fromString)
    }

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
    fun `media command retries bounded transient transport and service failures`() = runTest {
        for (status in listOf(408, 409, 425, 429, 500, 502, 503, 504)) {
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
    fun `media command does not retry unrelated forbidden errors`() = runTest {
        for (failure in listOf(
            httpFailure(403, "FORBIDDEN"),
            httpFailure(403),
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
    fun `owner media read retries a temporary connection interruption`() = runTest {
        var attempts = 0

        val result = retryMediaReadAfterOwnerProof {
            attempts += 1
            if (attempts == 1) throw IOException("connection reset")
            "photo"
        }

        assertThat(result).isEqualTo("photo")
        assertThat(attempts).isEqualTo(2)
        assertThat(testScheduler.currentTime).isEqualTo(250L)
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
    fun `retry policy recognizes owner proof lag and transient transport statuses`() {
        assertThat(
            isRetryableMediaOwnerFailure(
                httpFailure(403, "MEDIA_OWNER_PROOF_REQUIRED"),
            ),
        ).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(403, "FORBIDDEN"))).isFalse()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(409))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(429))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(500))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(503))).isTrue()
        assertThat(isRetryableMediaOwnerFailure(httpFailure(400))).isFalse()
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
