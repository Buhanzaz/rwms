package dev.buhanzaz.rwms.manager.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Minimal transport-neutral metadata needed to traverse one server-owned offset-paged read.
 * The owning API remains responsible for ordering and the authoritative total.
 */
internal data class ManagerPageSlice<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/**
 * Reads every advertised page sequentially so only one tail request is in flight at a time.
 * Metadata mismatches fail the refresh instead of publishing a silently truncated snapshot.
 */
internal suspend fun <T> collectManagerPages(
    first: ManagerPageSlice<T>,
    loadPage: suspend (page: Int) -> ManagerPageSlice<T>,
): List<T> {
    require(first.page == 0) { "Первая страница RWMS должна иметь индекс 0" }
    validateManagerPage(first, expectedPage = 0, expectedSize = first.size)
    require(first.items.isNotEmpty() || first.totalElements == 0L) {
        "RWMS вернул пустую первую страницу для непустого списка"
    }

    val collected = first.items.toMutableList()
    val pageCount = ((first.totalElements + first.size - 1L) / first.size).toInt()
    for (nextPage in 1 until pageCount) {
        val next = loadPage(nextPage)
        validateManagerPage(next, expectedPage = nextPage, expectedSize = first.size)
        collected += next.items
    }
    return collected
}

/**
 * Applies a suspending detail read in stable input order while limiting both live requests and
 * allocated child coroutines to [parallelism]. A failure cancels the whole batch and propagates.
 */
internal suspend fun <T, R> mapInBoundedBatches(
    values: List<T>,
    parallelism: Int,
    transform: suspend (T) -> R,
): List<R> {
    require(parallelism > 0) { "Параллелизм должен быть положительным" }
    return coroutineScope {
        values.chunked(parallelism).flatMap { batch ->
            batch.map { value -> async { transform(value) } }.awaitAll()
        }
    }
}

/** Rejects server metadata that could otherwise cause an infinite or lossy pagination loop. */
private fun <T> validateManagerPage(
    page: ManagerPageSlice<T>,
    expectedPage: Int,
    expectedSize: Int,
) {
    require(page.page == expectedPage) { "RWMS вернул страницу с неожиданным индексом" }
    require(page.size > 0 && page.size == expectedSize) {
        "RWMS изменил размер страницы во время чтения"
    }
    require(page.items.size <= page.size) { "RWMS вернул слишком много элементов страницы" }
    require(page.totalElements >= 0L && page.totalElements <= Int.MAX_VALUE.toLong()) {
        "RWMS вернул недопустимый размер списка"
    }
}
