import { ApiError } from "@/lib/api-client"

export function isTaskBoardSettingsConflict(error: unknown) {
  const structuralStatus =
    typeof error === "object" && error !== null && "status" in error
      ? (error as { status: unknown }).status
      : null
  return (
    (error instanceof ApiError && error.status === 409) ||
    structuralStatus === 409
  )
}

export function taskBoardSettingsErrorMessage(
  error: unknown,
  staleSelection = false
) {
  if (staleSelection && isTaskBoardSettingsConflict(error)) {
    const detail =
      error instanceof Error ? error.message : "Настройки доски были изменены."
    return `${detail} Данные обновлены с сервера. Откройте форму или действие заново.`
  }
  return error instanceof Error ? error.message : "Операция не выполнена."
}
