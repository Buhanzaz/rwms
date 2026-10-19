package dev.buhanzaz.rwms.manager.ui

import java.io.IOException
import retrofit2.HttpException
import retrofit2.Response

/** Transport-neutral result of a conditional HTTP read. */
internal data class ConditionalRead<T>(
    val value: T,
    val etag: String?,
)

/** Resolves a 200/304 response without assigning cache ownership to a coordinator runtime. */
internal fun <T> conditionalRead(
    response: Response<T>,
    cachedValue: T?,
    cachedEtag: String?,
    missingCacheMessage: String,
): ConditionalRead<T> = when (response.code()) {
    304 -> ConditionalRead(
        value = requireNotNull(cachedValue) { missingCacheMessage },
        etag = response.headers()["ETag"] ?: cachedEtag,
    )

    else -> {
        if (!response.isSuccessful) throw HttpException(response)
        ConditionalRead(
            value = requireNotNull(response.body()) { "RWMS вернул пустой ответ" },
            etag = response.headers()["ETag"],
        )
    }
}

/** Only offline and transient server failures may use the existing read cache. */
internal fun canUseCachedReadAfter(failure: Throwable): Boolean =
    failure is IOException || (failure is HttpException && failure.code() in 500..599)

/** Builds the account/warehouse cache scope from authoritative current UI selection. */
internal fun ManagerUiState.readCacheScope(warehouseId: String): ManagerReadCacheScope =
    ManagerReadCacheScope(
        accountId = requireNotNull(currentUser?.id) {
            "Не удалось определить пользователя для локального кэша"
        },
        warehouseId = warehouseId,
    )
