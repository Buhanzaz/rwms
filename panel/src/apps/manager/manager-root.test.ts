import { describe, expect, it } from "vitest"

import { canUseManagerApplication } from "@/apps/manager/manager-access"
import type { CurrentUser, UserGlobalRole } from "@/features/auth/auth-model"

function currentUser(
  globalRole: UserGlobalRole,
  rentalAccess = true
): CurrentUser {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    username: "user",
    displayName: "User",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole,
    rentalAccess,
    warehouseAccessAll: false,
    warehouseAccesses: [],
  }
}

describe("manager application access", () => {
  it.each([
    "SYSTEM_ADMIN",
    "WMS_ADMIN",
    "WAREHOUSE_MANAGER",
    "RENTAL_MANAGER",
    "VIEWER",
  ] as const)("accepts rental-entitled %s staff", (role) => {
    expect(canUseManagerApplication(currentUser(role))).toBe(true)
  })

  it("rejects staff without rental access and customer accounts", () => {
    expect(canUseManagerApplication(currentUser("SYSTEM_ADMIN", false))).toBe(
      false
    )
    expect(canUseManagerApplication(currentUser("CUSTOMER"))).toBe(false)
  })
})
