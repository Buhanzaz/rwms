import { describe, expect, it } from "vitest"

import {
  CUSTOMER_DELIVERY_PURPOSE_LABELS,
  CUSTOMER_DELIVERY_PURPOSES,
} from "@/features/logistics/customer-delivery-purpose"

describe("customer delivery purpose", () => {
  it("keeps the supported purposes and their Russian labels explicit", () => {
    expect(CUSTOMER_DELIVERY_PURPOSES).toEqual([
      "RENTAL_DELIVERY",
      "SALE_DELIVERY",
      "CUSTOMER_RELOCATION",
    ])
    expect(CUSTOMER_DELIVERY_PURPOSE_LABELS).toEqual({
      RENTAL_DELIVERY: "Доставка в аренду",
      SALE_DELIVERY: "Доставка продажи",
      CUSTOMER_RELOCATION: "Перемещение клиента",
    })
  })
})
