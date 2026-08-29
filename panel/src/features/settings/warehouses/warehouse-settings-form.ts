import type { WarehouseInfo, WarehouseWriteInput } from "@/api/warehouse-api"

export type WarehouseFormValues = {
  name: string
  city: string
  address: string
  latitude: string
  longitude: string
  timeZone: string
  sortOrder: string
  representative: boolean
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
    latitude:
      warehouse?.latitude === null || warehouse === null
        ? ""
        : String(warehouse.latitude),
    longitude:
      warehouse?.longitude === null || warehouse === null
        ? ""
        : String(warehouse.longitude),
    timeZone: warehouse?.timeZone ?? "Europe/Moscow",
    sortOrder:
      warehouse?.sortOrder === null || warehouse?.sortOrder === undefined
        ? ""
        : String(warehouse.sortOrder),
    representative: warehouse?.representative ?? false,
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
  const latitudeText = values.latitude.trim().replace(",", ".")
  const longitudeText = values.longitude.trim().replace(",", ".")
  const latitude = latitudeText === "" ? null : Number(latitudeText)
  const longitude = longitudeText === "" ? null : Number(longitudeText)
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

  if ((latitude === null) !== (longitude === null)) {
    return {
      input: null,
      error: "Укажите широту и долготу вместе либо оставьте оба поля пустыми.",
    }
  }

  if (
    (latitude !== null &&
      (!Number.isFinite(latitude) || latitude < -90 || latitude > 90)) ||
    (longitude !== null &&
      (!Number.isFinite(longitude) || longitude < -180 || longitude > 180))
  ) {
    return { input: null, error: "Укажите корректные координаты WGS84." }
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
      latitude,
      longitude,
      timeZone,
      sortOrder,
      representative: values.representative,
    },
    error: null,
  }
}
