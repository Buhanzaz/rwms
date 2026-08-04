import { describe, expect, it } from "vitest"

import {
  inventoryFurnitureReconciliationCompletionNotice,
  inventoryFurnitureReconciliationPresentation,
} from "@/features/inventory/inventory-furniture-reconciliation-presentation"

describe("inventory furniture reconciliation presentation", () => {
  it.each([
    [
      "NOT_REQUIRED",
      "Сверка мебели не требуется",
      "outline",
      null,
      null,
    ],
    [
      "READY",
      "Ожидает применения мебельных остатков",
      "secondary",
      null,
      {
        kind: "warning",
        message:
          "Мебельные остатки ожидают применения. Проверьте статус в результате инвентаризации.",
      },
    ],
    [
      "PENDING",
      "Применение мебельных остатков выполняется",
      "secondary",
      null,
      {
        kind: "warning",
        message:
          "Применение мебельных остатков ещё выполняется. Проверьте статус в результате инвентаризации.",
      },
    ],
    [
      "SUCCEEDED",
      "Мебельные остатки применены",
      "default",
      null,
      null,
    ],
    [
      "TRANSIENT_FAILED",
      "Не удалось применить мебельные остатки",
      "destructive",
      "Применение мебельных остатков не выполнено. Проверьте этот статус в результате инвентаризации.",
      {
        kind: "error",
        message:
          "Применение мебельных остатков не выполнено. Проверьте этот статус в результате инвентаризации.",
      },
    ],
    [
      "BLOCKED",
      "Применение мебельных остатков заблокировано",
      "destructive",
      "Применение мебельных остатков заблокировано конфликтом актуальных данных.",
      {
        kind: "error",
        message:
          "Применение мебельных остатков заблокировано конфликтом актуальных данных.",
      },
    ],
  ] as const)(
    "presents %s",
    (state, label, badgeVariant, problem, completionNotice) => {
      expect(inventoryFurnitureReconciliationPresentation(state)).toEqual({
        label,
        badgeVariant,
        problem,
      })
      expect(inventoryFurnitureReconciliationCompletionNotice(state)).toEqual(
        completionNotice
      )
    }
  )
})
