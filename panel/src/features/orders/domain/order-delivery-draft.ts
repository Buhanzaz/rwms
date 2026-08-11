import type { OrderDeliveryInput } from "@/features/orders/api/orders-api"

/** Manager-editable contact data; the client supplies delivery facts in the presentation. */
export type OrderDeliveryDraft = {
  contactPhone: string
  comment: string
}

/** Field-level validation errors for manager-editable order data. */
export type OrderDeliveryErrors = Partial<Record<keyof OrderDeliveryDraft, string>>

const CONTACT_PHONE_PATTERN = /^(?:\+|8)[0-9() .-]{6,31}$/

export function emptyOrderDeliveryDraft(contactPhone = ""): OrderDeliveryDraft {
  return {
    contactPhone,
    comment: "",
  }
}

export function parseOrderDeliveryDraft(value: OrderDeliveryDraft): {
  input: OrderDeliveryInput | null
  errors: OrderDeliveryErrors
} {
  const errors: OrderDeliveryErrors = {}
  const contactPhone = value.contactPhone.trim()

  if (!contactPhone) {
    errors.contactPhone = "Укажите контактный телефон заказа."
  } else if (!CONTACT_PHONE_PATTERN.test(contactPhone)) {
    errors.contactPhone = "Укажите корректный контактный телефон заказа."
  }

  return {
    input:
      Object.keys(errors).length === 0
        ? {
            contactPhone,
            comment: value.comment.trim() || null,
          }
        : null,
    errors,
  }
}
