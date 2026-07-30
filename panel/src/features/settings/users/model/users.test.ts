import { describe, expect, it } from "vitest"

import type { UserGlobalRole } from "@/features/auth/auth-model"
import { isManagerAppEligibleRole } from "@/features/settings/users/model/users"

describe("manager app role eligibility", () => {
  it.each([
    ["SYSTEM_ADMIN", true],
    ["WMS_ADMIN", true],
    ["WAREHOUSE_MANAGER", true],
    ["RENTAL_MANAGER", false],
    ["VIEWER", false],
  ] satisfies [UserGlobalRole, boolean][])(
    "maps %s to %s",
    (role, expected) => {
      expect(isManagerAppEligibleRole(role)).toBe(expected)
    }
  )
})
