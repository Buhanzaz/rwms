import type { InventoryFurnitureReconciliationState } from "@/features/inventory/model/inventory"

export type InventoryFurnitureReconciliationPresentation = {
  label: string
  badgeVariant: "default" | "secondary" | "outline" | "destructive"
  problem: string | null
}

const furnitureReconciliationPresentation: Record<
  InventoryFurnitureReconciliationState,
  InventoryFurnitureReconciliationPresentation
> = {
  NOT_REQUIRED: {
    label: "Сверка мебели не требуется",
    badgeVariant: "outline",
    problem: null,
  },
  READY: {
    label: "Ожидает применения мебельных остатков",
    badgeVariant: "secondary",
    problem: null,
  },
  PENDING: {
    label: "Применение мебельных остатков выполняется",
    badgeVariant: "secondary",
    problem: null,
  },
  SUCCEEDED: {
    label: "Мебельные остатки применены",
    badgeVariant: "default",
    problem: null,
  },
  TRANSIENT_FAILED: {
    label: "Не удалось применить мебельные остатки",
    badgeVariant: "destructive",
    problem:
      "Применение мебельных остатков не выполнено. Проверьте этот статус в результате инвентаризации.",
  },
  BLOCKED: {
    label: "Применение мебельных остатков заблокировано",
    badgeVariant: "destructive",
    problem:
      "Применение мебельных остатков заблокировано конфликтом актуальных данных.",
  },
}

export function inventoryFurnitureReconciliationPresentation(
  state: InventoryFurnitureReconciliationState
) {
  return furnitureReconciliationPresentation[state]
}

export function inventoryFurnitureReconciliationCompletionNotice(
  state: InventoryFurnitureReconciliationState
) {
  const presentation = inventoryFurnitureReconciliationPresentation(state)
  if (presentation.problem) {
    return { kind: "error" as const, message: presentation.problem }
  }
  if (state === "READY") {
    return {
      kind: "warning" as const,
      message:
        "Мебельные остатки ожидают применения. Проверьте статус в результате инвентаризации.",
    }
  }
  if (state === "PENDING") {
    return {
      kind: "warning" as const,
      message:
        "Применение мебельных остатков ещё выполняется. Проверьте статус в результате инвентаризации.",
    }
  }
  return null
}
