import type { WarehouseInfo, WarehouseWriteInput } from "@/api/warehouse-api"

export type WarehouseFormValues = {
  code: string
  name: string
  city: string
  address: string
  timeZone: string
  active: boolean
  sortOrder: string
}

type WarehouseFormResult =
  { input: WarehouseWriteInput; error: null } | { input: null; error: string }

const WAREHOUSE_CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{0,63}$/

export function createWarehouseFormValues(
  warehouse: WarehouseInfo | null
): WarehouseFormValues {
  return {
    code: warehouse?.code ?? "",
    name: warehouse?.name ?? "",
    city: warehouse?.city ?? "",
    address: warehouse?.address ?? "",
    timeZone: warehouse?.timeZone ?? "Europe/Moscow",
    active: warehouse?.active ?? true,
    sortOrder:
      warehouse?.sortOrder === null || warehouse?.sortOrder === undefined
        ? ""
        : String(warehouse.sortOrder),
  }
}

function isIanaTimeZone(value: string) {
  try {
    Intl.DateTimeFormat("ru-RU", { timeZone: value })
    return true
  } catch {
    return false
  }
}

export function parseWarehouseForm(
  values: WarehouseFormValues
): WarehouseFormResult {
  const code = values.code.trim().toUpperCase()
  const name = values.name.trim()
  const city = values.city.trim()
  const address = values.address.trim() || null
  const timeZone = values.timeZone.trim()
  const sortOrderText = values.sortOrder.trim()
  const sortOrder = sortOrderText === "" ? null : Number(sortOrderText)

  if (!WAREHOUSE_CODE_PATTERN.test(code)) {
    return {
      input: null,
      error:
        "Код: латинские A–Z, цифры, «_» или «-»; длина от 1 до 64 символов.",
    }
  }

  if (!name || name.length > 255 || !city || city.length > 255) {
    return {
      input: null,
      error: "Укажите название и город длиной не более 255 символов.",
    }
  }

  if (address !== null && address.length > 1000) {
    return { input: null, error: "Адрес не должен превышать 1000 символов." }
  }

  if (!timeZone || timeZone.length > 64 || !isIanaTimeZone(timeZone)) {
    return {
      input: null,
      error: "Укажите корректную временную зону IANA, например Europe/Moscow.",
    }
  }

  if (sortOrder !== null && (!Number.isInteger(sortOrder) || sortOrder < 0)) {
    return {
      input: null,
      error: "Порядок должен быть целым неотрицательным числом.",
    }
  }

  return {
    input: {
      code,
      name,
      city,
      address,
      timeZone,
      active: values.active,
      sortOrder,
    },
    error: null,
  }
}
