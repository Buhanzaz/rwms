import type { WarehouseInfo, WarehouseWriteInput } from "@/api/warehouse-api"

export type WarehouseFormValues = {
  name: string
  city: string
  address: string
  latitude: string
  longitude: string
  timeZone: string
  sortOrder: string
  production: boolean
  mainWarehouse: boolean
  representative: boolean
  representativeParentWarehouseId: string
}

type WarehouseFormResult =
  { input: WarehouseWriteInput; error: null } | { input: null; error: string }

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function createWarehouseFormValues(
  warehouse: WarehouseInfo | null
): WarehouseFormValues {
  const representativeParentWarehouseId =
    warehouse?.representativeParentWarehouseId ?? ""
  const representative = warehouse?.representative ?? false

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
    production: representative
      ? false
      : warehouse === null
        ? true
        : warehouse.production,
    mainWarehouse: representative ? false : warehouse?.mainWarehouse ?? false,
    representative,
    representativeParentWarehouseId: representative
      ? representativeParentWarehouseId
      : "",
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
  const representativeParentWarehouseId =
    values.representativeParentWarehouseId.trim()

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

  if (latitude === 0 && longitude === 0) {
    return {
      input: null,
      error:
        "Координаты 0, 0 не определяют местоположение объекта. Укажите фактические координаты или оставьте оба поля пустыми.",
    }
  }

  if (!timeZone || timeZone.length > 64 || !isIanaTimeZone(timeZone)) {
    return {
      input: null,
      error: "Выберите корректную временную зону, например Europe/Moscow · +3.",
    }
  }

  if (sortOrder !== null && (!Number.isInteger(sortOrder) || sortOrder < 0)) {
    return {
      input: null,
      error: "Порядок должен быть целым неотрицательным числом.",
    }
  }

  if (values.representative) {
    if (!UUID_PATTERN.test(representativeParentWarehouseId)) {
      return {
        input: null,
        error:
          "Выберите объект с производством или основным складом для представительского объекта.",
      }
    }
  } else if (!values.production && !values.mainWarehouse) {
    return {
      input: null,
      error: "Выберите производство или основной склад.",
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
      production: values.representative ? false : values.production,
      mainWarehouse: values.representative ? false : values.mainWarehouse,
      representativeParentWarehouseId: values.representative
        ? representativeParentWarehouseId
        : null,
      representative: values.representative,
    },
    error: null,
  }
}
