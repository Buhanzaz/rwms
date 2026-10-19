import { describe, expect, it } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import {
  getWarehouseAccessLevel,
  hasWarehouseAccess,
} from "@/features/auth/warehouse-access"

function currentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: "user-1",
    username: "operator",
    displayName: "Operator",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: "warehouse-1", level: "EDIT" }],
    ...overrides,
  }
}

describe("warehouse access", () => {
  it("denies missing users and warehouses", () => {
    expect(hasWarehouseAccess(null, "warehouse-1", "VIEW")).toBe(false)
    expect(hasWarehouseAccess(currentUser(), "warehouse-2", "VIEW")).toBe(false)
  })

  it("orders explicit VIEW, EDIT and MANAGE access", () => {
    const user = currentUser()

    expect(getWarehouseAccessLevel(user, "warehouse-1")).toBe("EDIT")
    expect(hasWarehouseAccess(user, "warehouse-1", "VIEW")).toBe(true)
    expect(hasWarehouseAccess(user, "warehouse-1", "EDIT")).toBe(true)
    expect(hasWarehouseAccess(user, "warehouse-1", "MANAGE")).toBe(false)
  })

  it("treats warehouseAccessAll as MANAGE", () => {
    const user = currentUser({
      warehouseAccessAll: true,
      warehouseAccesses: [],
    })

    expect(getWarehouseAccessLevel(user, "warehouse-2")).toBe("MANAGE")
    expect(hasWarehouseAccess(user, "warehouse-2", "MANAGE")).toBe(true)
  })
})
