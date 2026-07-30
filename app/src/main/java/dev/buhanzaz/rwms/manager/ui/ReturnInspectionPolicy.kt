package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto

internal const val RETURN_INSPECTION_REQUIRED = "INSPECTION_REQUIRED"

/**
 * Return acceptance and estimate requests are valid only after the logistics
 * registration saga has reached the inspection state. Keeping this rule in one
 * place prevents the mobile UI from issuing a command against a stale list
 * projection and exposing a backend lifecycle error to the operator.
 */
internal fun LogisticsDocumentDto.returnInspectionActionError(): String? = when (state) {
    RETURN_INSPECTION_REQUIRED -> null
    "REGISTERING" -> "Возврат ещё регистрируется. Дождитесь завершения и обновите данные."
    "RECONCILIATION_REQUIRED", "CONFLICT" ->
        "Возврат требует сверки в RWMS. Принять или создать смету пока нельзя."
    "ACCEPTING", "ESTIMATE_PENDING" ->
        "Возврат уже обрабатывается. Дождитесь завершения операции."
    else -> "Возврат пока не готов к осмотру. Обновите данные и повторите действие."
}
