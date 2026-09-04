import { ApiError } from "@/lib/api-client"

export function isWarehouseConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409
}

export function getWarehouseMutationError(error: unknown) {
  if (isWarehouseConflict(error)) {
    if (error.message.includes("readiness")) {
      return "Вывод объекта ещё не завершён: не все сервисы подтвердили отсутствие активных ресурсов и операций."
    }

    if (error.message.includes("without operations")) {
      return "Объект ещё не работал. Исправьте временную зону через действие «Изменить»."
    }

    if (error.message.includes("operated warehouse timezone")) {
      return "Объект уже работал. Изменение временной зоны нужно назначить с даты через меню «Изменить»."
    }

    if (error.message.includes("already exists")) {
      return "Объект с таким кодом города уже существует."
    }

    return "Данные объекта изменились. Список обновлён; откройте запись заново."
  }

  return error instanceof Error
    ? error.message
    : "Операция с объектом не выполнена."
}
