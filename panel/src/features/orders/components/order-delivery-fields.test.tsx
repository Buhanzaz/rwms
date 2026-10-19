import { describe, expect, it } from "vitest"

import {
  emptyOrderDeliveryDraft,
  parseOrderDeliveryDraft,
} from "@/features/orders/domain/order-delivery-draft"

describe("order delivery fields", () => {
  it("normalizes the manager-owned contact phone and comment", () => {
    expect(
      parseOrderDeliveryDraft({
        contactPhone: " +7 999 000-00-00 ",
        comment: "  Позвонить за час  ",
      })
    ).toEqual({
      input: {
        contactPhone: "+7 999 000-00-00",
        comment: "Позвонить за час",
      },
      errors: {},
    })
  })

  it("does not create client-owned delivery fields", () => {
    const result = parseOrderDeliveryDraft({
      contactPhone: "+79990000000",
      comment: "",
    })

    expect(result.errors).toEqual({})
    expect(result.input).not.toHaveProperty("deliveryAddress")
    expect(result.input).not.toHaveProperty("latitude")
    expect(result.input).not.toHaveProperty("longitude")
    expect(result.input).not.toHaveProperty("additionalContacts")
    expect(result.input).not.toHaveProperty("desiredDeliveryWindows")
  })

  it("rejects a missing manager-owned contact phone", () => {
    const result = parseOrderDeliveryDraft({
      ...emptyOrderDeliveryDraft(),
    })

    expect(result.input).toBeNull()
    expect(result.errors).toEqual({
      contactPhone: "Укажите контактный телефон заказа.",
    })
  })

  it("rejects an invalid manager-owned contact phone", () => {
    const result = parseOrderDeliveryDraft({
      contactPhone: "позвонить Ивану",
      comment: "",
    })

    expect(result.input).toBeNull()
    expect(result.errors.contactPhone).toBe(
      "Укажите корректный контактный телефон заказа."
    )
  })
})
