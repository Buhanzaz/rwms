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

  it("explains domain conflicts instead of presenting them as stale data", () => {
    expect(
      getWarehouseMutationError(
        new ApiError(
          "Warehouse cannot become INACTIVE until readiness is confirmed",
          409
        )
      )
    ).toContain("не все сервисы подтвердили")
    expect(
      getWarehouseMutationError(
        new ApiError(
          "An operated warehouse timezone must be scheduled with an effective timestamp",
          409
        )
      )
    ).toContain("нужно назначить с даты")
  })
})
