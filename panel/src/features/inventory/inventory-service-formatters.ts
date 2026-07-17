export const inspectionLabel = {
  NOT_INSPECTED: "Не проверена",
  READY: "Готова",
  WORK_STAGED: "Работы запланированы",
} as const

export const reconciliationLabel = {
  MATCHED: "Совпадает",
  MISSING: "Не найдена",
  CONFLICT: "Конфликт",
} as const

export function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

export function formatMoney(minor: number) {
  return new Intl.NumberFormat("ru-RU", {
    style: "currency",
    currency: "RUB",
  }).format(minor / 100)
}
