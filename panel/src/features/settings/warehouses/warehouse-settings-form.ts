import type { WarehouseInfo, WarehouseWriteInput } from "@/api/warehouse-api"

export type WarehouseFormValues = {
  name: string
  city: string
  address: string
  timeZone: string
  active: boolean
  sortOrder: string
}

type WarehouseFormResult =
  { input: WarehouseWriteInput; error: null } | { input: null; error: string }

export function createWarehouseFormValues(
  warehouse: WarehouseInfo | null
): WarehouseFormValues {
  return {
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
  const name = values.name.trim()
  const city = values.city.trim()
  const address = values.address.trim() || null
  const timeZone = values.timeZone.trim()
  const sortOrderText = values.sortOrder.trim()
  const sortOrder = sortOrderText === "" ? null : Number(sortOrderText)

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
