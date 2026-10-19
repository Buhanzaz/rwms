package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundUploadExecutionGateTest {
    private val scope = BackgroundUploadScope("account-a", "warehouse-a")

    @Test
    fun `several cabins start together and a completed cabin admits the next one`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        val releases = List(4) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        var active = 0
        var maximumActive = 0
        val workers = releases.mapIndexed { index, release ->
            async {
                gate.execute(scope, "operation-$index", onFinished = { closed += index }) {
                    started += index
                    active += 1
                    maximumActive = maxOf(maximumActive, active)
                    try {
                        release.await()
                    } finally {
                        active -= 1
                    }
                }
            }
        }
        runCurrent()

        assertThat(started).containsExactly(0, 1, 2).inOrder()
        assertThat(active).isEqualTo(3)

        releases[1].complete(Unit)
        runCurrent()

        assertThat(started).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(active).isEqualTo(3)
        assertThat(maximumActive).isEqualTo(3)
        assertThat(closed).containsExactly(1)

        releases.forEach { it.complete(Unit) }
        workers.forEach { it.await() }
        gate.awaitIdle()
        assertThat(closed).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `duplicate retries wait for their own operation without blocking another cabin`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val first = async {
            gate.execute(scope, "same-operation", onFinished = {}) {
                started += "first"
                releaseFirst.await()
            }
        }
        val replacement = async {
            gate.execute(scope, "same-operation", onFinished = {}) {
                started += "replacement"
            }
        }
        val otherCabin = async {
            gate.execute(scope, "other-operation", onFinished = {}) {
                started += "other"
            }
        }
        runCurrent()

        assertThat(started).containsExactly("first", "other").inOrder()
        assertThat(replacement.isCompleted).isFalse()
        otherCabin.await()
        releaseFirst.complete(Unit)
        first.await()
        replacement.await()
        gate.awaitIdle()
        assertThat(started).containsExactly("first", "other", "replacement").inOrder()
    }

    @Test
    fun `logout drain waits for every cancellation cleanup including permit waiters`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        val cleanupReleases = List(3) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val workers = (0..3).map { index ->
            async {
                gate.execute(scope, "operation-$index", onFinished = { closed += index }) {
                    started += index
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanupReleases[index].await() }
                    }
                }
            }
        }
        runCurrent()
        assertThat(started).containsExactly(0, 1, 2)

        workers.forEach { it.cancel() }
        val drained = async { gate.awaitIdle() }
        runCurrent()

        assertThat(closed).containsExactly(3)
        assertThat(drained.isCompleted).isFalse()
        cleanupReleases[0].complete(Unit)
        runCurrent()
        assertThat(closed).containsExactly(3, 0)
        assertThat(drained.isCompleted).isFalse()
        cleanupReleases[1].complete(Unit)
        runCurrent()
        assertThat(drained.isCompleted).isFalse()
        cleanupReleases[2].complete(Unit)
        workers.forEach { it.join() }
        drained.await()
        assertThat(closed).containsExactly(0, 1, 2, 3)
        assertThat(started).doesNotContain(3)

        var newAccountStarted = false
        gate.execute(
            BackgroundUploadScope("account-b", "warehouse-a"),
            "operation-0",
            onFinished = {},
        ) { newAccountStarted = true }
        assertThat(newAccountStarted).isTrue()
    }

    @Test
    fun `single operation cancellation drains its cleanup without waiting for other cabins`() =
        runTest {
            val gate = BackgroundUploadExecutionGate()
            val cancelledCleanupRelease = CompletableDeferred<Unit>()
            val started = mutableListOf<Int>()
            val closed = mutableListOf<Int>()
            val workers = (0..2).map { index ->
                async {
                    gate.execute(scope, "operation-$index", onFinished = { closed += index }) {
                        started += index
                        try {
                            awaitCancellation()
                        } finally {
                            if (index == 0) {
                                withContext(NonCancellable) { cancelledCleanupRelease.await() }
                            }
                        }
                    }
                }
            }
            runCurrent()
            assertThat(started).containsExactly(0, 1, 2)

            workers[0].cancel()
            val operationDrained = async { gate.awaitOperationIdle(scope, "operation-0") }
            val allDrained = async { gate.awaitIdle() }
            runCurrent()
            assertThat(operationDrained.isCompleted).isFalse()
            assertThat(allDrained.isCompleted).isFalse()

            cancelledCleanupRelease.complete(Unit)
            runCurrent()
            operationDrained.await()
            assertThat(closed).containsExactly(0)
            assertThat(workers[1].isActive).isTrue()
            assertThat(workers[2].isActive).isTrue()
            assertThat(allDrained.isCompleted).isFalse()

            workers.drop(1).forEach { it.cancel() }
            workers.forEach { it.join() }
            allDrained.await()
            assertThat(closed).containsExactly(0, 1, 2)
        }

    @Test
    fun `operation drain follows a replacement registered after its previous entry completed`() =
        runTest {
            val gate = BackgroundUploadExecutionGate()
            val firstRelease = CompletableDeferred<Unit>()
            val replacementReady = CompletableDeferred<Unit>()
            val replacementRelease = CompletableDeferred<Unit>()
            var replacementStarted = false
            val first = async {
                gate.execute(scope, "operation", onFinished = { replacementReady.complete(Unit) }) {
                    firstRelease.await()
                }
            }
            val replacement = async {
                replacementReady.await()
                gate.execute(scope, "operation", onFinished = {}) {
                    replacementStarted = true
                    replacementRelease.await()
                }
            }
            runCurrent()
            val drained = async { gate.awaitOperationIdle(scope, "operation") }
            runCurrent()

            firstRelease.complete(Unit)
            runCurrent()
            first.await()
            assertThat(replacementStarted).isTrue()
            assertThat(drained.isCompleted).isFalse()

            replacementRelease.complete(Unit)
            replacement.await()
            drained.await()
        }

    @Test
    fun `cancelled duplicate never executes and does not strand later retries or drain`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        val release = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        var cancelledRetryStarted = false
        val first = async {
            gate.execute(scope, "operation", onFinished = { closed += "first" }) {
                release.await()
            }
        }
        val cancelledRetry = async {
            gate.execute(scope, "operation", onFinished = { closed += "cancelled" }) {
                cancelledRetryStarted = true
            }
        }
        runCurrent()
        cancelledRetry.cancelAndJoin()
        assertThat(cancelledRetryStarted).isFalse()
        assertThat(closed).containsExactly("cancelled")

        val cancelledDrain = async { gate.awaitIdle() }
        runCurrent()
        cancelledDrain.cancelAndJoin()
        release.complete(Unit)
        first.await()
        gate.awaitIdle()

        gate.execute(scope, "operation", onFinished = { closed += "next" }) { }
        assertThat(closed).containsExactly("cancelled", "first", "next").inOrder()
    }

    @Test
    fun `domain finalizations are serialized while other cabin uploads keep running`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        val firstCommitRelease = CompletableDeferred<Unit>()
        val uploaded = mutableListOf<Int>()
        val committed = mutableListOf<Int>()
        val workers = (0..2).map { index ->
            async {
                gate.execute(scope, "operation-$index", onFinished = {}) {
                    uploaded += index
                    gate.finalize {
                        if (index == 0) firstCommitRelease.await()
                        committed += index
                    }
                }
            }
        }
        runCurrent()

        assertThat(uploaded).containsExactly(0, 1, 2)
        assertThat(committed).isEmpty()

        firstCommitRelease.complete(Unit)
        advanceUntilIdle()
        workers.forEach { it.await() }
        gate.awaitIdle()
        assertThat(committed).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `failed operation closes its auth snapshot and releases all admission state`() = runTest {
        val gate = BackgroundUploadExecutionGate()
        var closed = 0
        val failure = runCatching {
            gate.execute(scope, "operation", onFinished = { closed += 1 }) {
                throw IllegalStateException("test failure")
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("test failure")
        gate.awaitIdle()
        gate.execute(scope, "operation", onFinished = { closed += 1 }) { }
        assertThat(closed).isEqualTo(2)
    }
}
