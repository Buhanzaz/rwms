type ApiFailureContext = Readonly<{
  status: number
  code: string | null
  detail: string
}>

const CODE_MESSAGES: Readonly<Record<string, string>> = {
  AUTHENTICATION_REQUIRED:
    "Сеанс завершён. Войдите в систему и повторите действие.",
  WAREHOUSE_ACCESS_FORBIDDEN:
    "У вас нет доступа к выбранному складу. Выберите доступный склад или обратитесь к администратору.",
  ENTRY_VERSION_CONFLICT:
    "Данные уже изменились. Обновите страницу и повторите действие.",
  ORDER_VERSION_CONFLICT:
    "Заказ уже изменился. Обновите данные и повторите действие.",
  UNIT_NOT_AVAILABLE: "Эта бытовка уже недоступна. Выберите другую бытовку.",
  EQUIPMENT_RESERVATION_CONFLICT:
    "Остатки уже изменились. Обновите данные и повторите действие.",
  MEDIA_CONFLICT:
    "Фотографии уже изменились. Обновите данные и повторите действие.",
  MEDIA_FORBIDDEN: "У вас нет доступа к этим фотографиям.",
  NETWORK_ERROR:
    "Не удалось связаться с сервером. Проверьте подключение и повторите попытку.",
  REQUEST_ABORTED: "Запрос отменён. Повторите действие при необходимости.",
  INVALID_API_RESPONSE:
    "Сервис вернул некорректные данные. Обновите страницу или повторите попытку позже.",
}

const CYRILLIC = /[А-Яа-яЁё]/
const TECHNICAL_DETAIL =
  /(?:\b(?:http|https|sql|json|jdbc|hibernate|postgres|exception|stack|trace|upstream|downstream|internal|gateway|service|timeout)\b|\b[A-Za-z][A-Za-z0-9_.]*(?:Exception|Error)\b|\/api\/|localhost|127\.0\.0\.1|<\/?(?:html|body)|[{}[\]])/i

/**
 * Returns copy that is safe to render to a business user. The original
 * transport detail remains available separately on {@link ApiError} for
 * diagnostics and must not be rendered directly.
 */
export function userFacingApiMessage(context: ApiFailureContext): string {
  const code = context.code?.trim().toUpperCase() ?? null
  const mapped = code ? CODE_MESSAGES[code] : undefined
  if (mapped) return mapped

  const detail = context.detail.trim()
  if (isTrustedRussianDetail(detail)) return detail

  if (code) {
    if (
      code.includes("VERSION") ||
      code.includes("CONFLICT") ||
      code.startsWith("STALE_")
    ) {
      return "Данные уже изменились. Обновите страницу и повторите действие."
    }
    if (code.endsWith("_FORBIDDEN")) {
      return "У вас нет прав для выполнения этого действия."
    }
    if (code.endsWith("_NOT_FOUND")) {
      return "Запрошенные данные не найдены. Обновите страницу."
    }
    if (code.endsWith("_UNAVAILABLE")) {
      return "Операция временно недоступна. Повторите попытку позже."
    }
  }

  if (context.status === 401) {
    return "Сеанс завершён. Войдите в систему и повторите действие."
  }
  if (context.status === 403) {
    return "У вас нет прав для выполнения этого действия."
  }
  if (context.status === 404) {
    return "Запрошенные данные не найдены. Обновите страницу."
  }
  if (context.status === 409) {
    return "Данные уже изменились. Обновите страницу и повторите действие."
  }
  if (context.status === 413) {
    return "Файл или запрос слишком большой. Уменьшите размер и повторите попытку."
  }
  if (context.status === 429) {
    return "Слишком много запросов. Подождите немного и повторите попытку."
  }
  if (context.status >= 500) {
    return "Сервис временно недоступен. Повторите попытку позже."
  }
  if (context.status === 400 || context.status === 422) {
    return "Не удалось выполнить действие. Проверьте введённые данные и повторите попытку."
  }
  return "Не удалось выполнить запрос. Повторите попытку."
}

function isTrustedRussianDetail(detail: string): boolean {
  return (
    detail.length > 0 &&
    detail.length <= 500 &&
    CYRILLIC.test(detail) &&
    !TECHNICAL_DETAIL.test(detail)
  )
}
