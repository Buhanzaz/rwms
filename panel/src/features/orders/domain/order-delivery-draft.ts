import type { OrderDeliveryInput } from "@/features/orders/api/orders-api"

export type OrderDeliveryDraft = {
  deliveryAddress: string
  latitude: string
  longitude: string
  contactPhone: string
  comment: string
  acceptableDeliveryDates: string[]
}

export type OrderDeliveryErrors = Partial<
  Record<keyof OrderDeliveryDraft, string>
>

const CONTACT_PHONE_PATTERN = /^(?:\+|8)[0-9() .-]{6,31}$/

export function emptyOrderDeliveryDraft(contactPhone = ""): OrderDeliveryDraft {
  return {
    deliveryAddress: "",
    latitude: "",
    longitude: "",
    contactPhone,
    comment: "",
    acceptableDeliveryDates: [""],
  }
}

function isConcreteLocalDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const parsed = new Date(Date.UTC(year, month - 1, day))
  return (
    parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 &&
    parsed.getUTCDate() === day
  )
}

export function parseOrderDeliveryDraft(value: OrderDeliveryDraft): {
  input: OrderDeliveryInput | null
  errors: OrderDeliveryErrors
} {
  const errors: OrderDeliveryErrors = {}
  const deliveryAddress = value.deliveryAddress.trim()
  const contactPhone = value.contactPhone.trim()
  const latitude = Number(value.latitude)
  const longitude = Number(value.longitude)
  const dates = value.acceptableDeliveryDates.map((date) => date.trim())

  if (!deliveryAddress) errors.deliveryAddress = "Укажите адрес доставки."
  if (
    !value.latitude.trim() ||
    !Number.isFinite(latitude) ||
    latitude < -90 ||
    latitude > 90
  ) {
    errors.latitude = "Широта должна быть числом от −90 до 90."
  }
  if (
    !value.longitude.trim() ||
    !Number.isFinite(longitude) ||
    longitude < -180 ||
    longitude > 180
  ) {
    errors.longitude = "Долгота должна быть числом от −180 до 180."
  }
  if (!contactPhone) {
    errors.contactPhone = "Укажите контактный телефон заказа."
  } else if (!CONTACT_PHONE_PATTERN.test(contactPhone)) {
    errors.contactPhone = "Укажите корректный контактный телефон заказа."
  }
  if (dates.length === 0 || dates.some((date) => !isConcreteLocalDate(date))) {
    errors.acceptableDeliveryDates =
      "Укажите хотя бы одну конкретную дату приёмки."
  } else if (dates.length > 31) {
    errors.acceptableDeliveryDates = "Можно указать не более 31 даты."
  } else if (new Set(dates).size !== dates.length) {
    errors.acceptableDeliveryDates = "Даты приёмки не должны повторяться."
  }

  return {
    input:
      Object.keys(errors).length === 0
        ? {
            deliveryAddress,
            latitude,
            longitude,
            contactPhone,
            comment: value.comment.trim() || null,
            acceptableDeliveryDates: dates,
          }
        : null,
    errors,
  }
}
