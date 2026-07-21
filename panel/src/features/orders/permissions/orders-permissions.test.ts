import { describe, expect, it } from "vitest"

import type {
  OrdersModuleRole,
  OrdersModuleUser,
} from "@/features/orders/domain/orders-module"
import {
  canViewAllOrders,
  getOrdersListNavigationLabel,
} from "@/features/orders/permissions/orders-permissions"

function user(globalRole: OrdersModuleRole): OrdersModuleUser {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    globalRole,
  }
}

describe("orders permissions", () => {
  it.each(["SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER"] as const)(
    "shows the administrative list label for %s",
    (role) => {
      expect(canViewAllOrders(user(role))).toBe(true)
      expect(getOrdersListNavigationLabel(user(role))).toBe("Все заказы")
    }
  )

  it.each(["RENTAL_MANAGER", "VIEWER"] as const)(
    "shows the manager list label for %s",
    (role) => {
      expect(canViewAllOrders(user(role))).toBe(false)
      expect(getOrdersListNavigationLabel(user(role))).toBe("Мои заказы")
    }
  )
})
