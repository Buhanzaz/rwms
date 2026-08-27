package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Verifies complete sequential paging and bounded ordered detail hydration for manager reads. */
class ManagerPaginationTest {
    @Test
    fun `every page advertised by page zero is read once in order`() = runTest {
        val requested = mutableListOf<Int>()

        val values = collectManagerPages(
            first = ManagerPageSlice(
                items = listOf("zero", "one"),
                page = 0,
                size = 2,
                totalElements = 5,
            ),
        ) { page ->
            requested += page
            when (page) {
                1 -> ManagerPageSlice(listOf("two", "three"), page, 2, 5)
                2 -> ManagerPageSlice(listOf("four"), page, 2, 5)
                else -> error("Unexpected page $page")
            }
        }

        assertThat(requested).containsExactly(1, 2).inOrder()
        assertThat(values).containsExactly("zero", "one", "two", "three", "four").inOrder()
    }

    @Test
    fun `detail hydration keeps order and never exceeds its batch bound`() = runTest {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        val hydrated = mapInBoundedBatches((0 until 11).toList(), parallelism = 3) { value ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            delay(10)
            active.decrementAndGet()
            "detail-$value"
        }

        assertThat(maximumActive.get()).isAtMost(3)
        assertThat(hydrated).containsExactlyElementsIn(
            (0 until 11).map { value -> "detail-$value" },
        ).inOrder()
    }
}
