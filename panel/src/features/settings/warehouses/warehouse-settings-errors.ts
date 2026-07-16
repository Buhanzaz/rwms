import { ApiError } from "@/lib/api-client"

export function isWarehouseConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409
}

export function getWarehouseMutationError(error: unknown) {
  if (isWarehouseConflict(error)) {
    return "Данные склада изменились. Список обновлён; откройте запись заново."
  }

  return error instanceof Error
    ? error.message
    : "Операция со складом не выполнена."
}
