import { describe, expect, it } from "vitest"

import type {
  OrdersModuleRole,
  OrdersModuleUser,
} from "@/features/orders/domain/orders-module"
import { canViewAllOrders } from "@/features/orders/permissions/orders-permissions"

function user(globalRole: OrdersModuleRole): OrdersModuleUser {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    globalRole,
  }
}

describe("orders permissions", () => {
  it.each(["SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER"] as const)(
    "allows %s to view all bookings",
    (role) => {
      expect(canViewAllOrders(user(role))).toBe(true)
    }
  )

  it.each(["RENTAL_MANAGER", "VIEWER"] as const)(
    "does not allow %s to view all bookings",
    (role) => {
      expect(canViewAllOrders(user(role))).toBe(false)
    }
  )
})
