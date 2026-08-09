import { describe, expect, it } from "vitest"

import {
  emptyOrderDeliveryDraft,
  parseOrderDeliveryDraft,
} from "@/features/orders/domain/order-delivery-draft"

describe("order delivery fields", () => {
  it("normalizes the required address, coordinates, phone and concrete dates", () => {
    expect(
      parseOrderDeliveryDraft({
        deliveryAddress: "  Москва, Складская, 1  ",
        latitude: "55.75",
        longitude: "37.62",
        contactPhone: " +7 999 000-00-00 ",
        comment: "  Позвонить за час  ",
        acceptableDeliveryDates: ["2026-08-15", "2026-08-17"],
      })
    ).toEqual({
      input: {
        deliveryAddress: "Москва, Складская, 1",
        latitude: 55.75,
        longitude: 37.62,
        contactPhone: "+7 999 000-00-00",
        comment: "Позвонить за час",
        acceptableDeliveryDates: ["2026-08-15", "2026-08-17"],
      },
      errors: {},
    })
  })

  it("rejects missing metadata, invalid coordinate bounds and an unspecified date", () => {
    const result = parseOrderDeliveryDraft({
      ...emptyOrderDeliveryDraft(),
      latitude: "91",
      longitude: "-181",
    })

    expect(result.input).toBeNull()
    expect(result.errors).toEqual({
      deliveryAddress: "Укажите адрес доставки.",
      latitude: "Широта должна быть числом от −90 до 90.",
      longitude: "Долгота должна быть числом от −180 до 180.",
      contactPhone: "Укажите контактный телефон заказа.",
      acceptableDeliveryDates: "Укажите хотя бы одну конкретную дату приёмки.",
    })
  })

  it("rejects duplicate dates and more than 31 acceptable delivery days", () => {
    const base = {
      deliveryAddress: "Москва",
      latitude: "55.75",
      longitude: "37.62",
      contactPhone: "+79990000000",
      comment: "",
    }
    expect(
      parseOrderDeliveryDraft({
        ...base,
        acceptableDeliveryDates: ["2026-08-15", "2026-08-15"],
      }).errors.acceptableDeliveryDates
    ).toBe("Даты приёмки не должны повторяться.")
    expect(
      parseOrderDeliveryDraft({
        ...base,
        acceptableDeliveryDates: Array.from({ length: 32 }, (_, index) =>
          new Date(Date.UTC(2026, 8, index + 1)).toISOString().slice(0, 10)
        ),
      }).errors.acceptableDeliveryDates
    ).toBe("Можно указать не более 31 даты.")
    expect(
      parseOrderDeliveryDraft({
        ...base,
        acceptableDeliveryDates: ["2026-02-31"],
      }).errors.acceptableDeliveryDates
    ).toBe("Укажите хотя бы одну конкретную дату приёмки.")
  })

  it("rejects a contact value that is not a logistics phone", () => {
    const result = parseOrderDeliveryDraft({
      deliveryAddress: "Москва",
      latitude: "55.75",
      longitude: "37.62",
      contactPhone: "позвонить Ивану",
      comment: "",
      acceptableDeliveryDates: ["2026-08-15"],
    })

    expect(result.input).toBeNull()
    expect(result.errors.contactPhone).toBe(
      "Укажите корректный контактный телефон заказа."
    )
  })
})
