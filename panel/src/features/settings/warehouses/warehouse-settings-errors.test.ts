import { describe, expect, it } from "vitest"

import { ApiError } from "@/lib/api-client"
import {
  getWarehouseMutationError,
  isWarehouseConflict,
} from "@/features/settings/warehouses/warehouse-settings-errors"

describe("warehouse settings conflict handling", () => {
  it("marks a 409 as a refresh-only outcome rather than a local overwrite", () => {
    const conflict = new ApiError("Конфликт версий", 409)

    expect(isWarehouseConflict(conflict)).toBe(true)
    expect(getWarehouseMutationError(conflict)).toContain("Список обновлён")
  })

  it("keeps an ordinary API error actionable", () => {
    expect(getWarehouseMutationError(new ApiError("Нет доступа", 403))).toBe(
      "Нет доступа"
    )
  })
})
