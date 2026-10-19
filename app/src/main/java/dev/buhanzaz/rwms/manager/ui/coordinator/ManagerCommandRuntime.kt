package dev.buhanzaz.rwms.manager.ui

import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Executes manager UI commands against the one authoritative state flow.
 *
 * <p>This runtime intentionally owns only coroutine launch, busy/message reductions and common
 * transport-error presentation. Domain coordinators receive their own backend, cache, upload and
 * cross-workflow ports directly; this type neither routes domain calls nor retains domain state.
 */
internal class ManagerCommandRuntime(
    internal val mutableState: MutableStateFlow<ManagerUiState>,
    internal val scope: CoroutineScope,
    private val problemMessage: (HttpException) -> String,
    private val invalidateSession: suspend (String) -> Unit,
) {
    internal fun command(block: suspend () -> Unit) {
        scope.launch {
            setBusy(true)
            try {
                block()
            } catch (failure: Throwable) {
                handleFailure(failure)
            } finally {
                setBusy(false)
            }
        }
    }

    internal suspend fun handleFailure(failure: Throwable) {
        val text = when (failure) {
            is HttpException -> problemMessage(failure)
            is SocketTimeoutException ->
                "Не удалось передать фотографию вовремя. Проверьте сеть и повторите сохранение"
            is IOException -> "Нет связи с RWMS. Проверьте подключение"
            is JsonDataException ->
                "RWMS вернул данные старого формата. Обновите страницу и повторите операцию"
            is IllegalArgumentException, is IllegalStateException ->
                failure.message ?: "Операция не выполнена"
            else -> "Операция не выполнена"
        }
        if (failure is HttpException && failure.code() == 401) {
            invalidateSession("Сессия завершена. Войдите снова")
        }
        message(text)
    }

    internal fun setBusy(value: Boolean) {
        mutableState.update { it.copy(busy = value) }
    }

    internal fun message(value: String) {
        mutableState.update { it.copy(message = value) }
    }

    internal fun requireWarehouseId(): String =
        requireNotNull(mutableState.value.selectedWarehouseId) {
            "Склад не выбран"
        }
}
