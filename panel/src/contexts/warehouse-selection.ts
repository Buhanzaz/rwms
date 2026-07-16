import type { WarehouseInfo } from "@/api/warehouse-api"

export const LEGACY_WAREHOUSE_SELECTIONS: Readonly<Record<string, string>> = {
  spb: "00000000-0000-0000-0000-000000000001",
  msk: "00000000-0000-0000-0000-000000000002",
}

export function resolveWarehouseSelection(
  savedWarehouseId: string | null,
  activeWarehouses: WarehouseInfo[]
) {
  const candidate =
    savedWarehouseId === null
      ? null
      : (LEGACY_WAREHOUSE_SELECTIONS[savedWarehouseId] ?? savedWarehouseId)

  return (
    activeWarehouses.find((warehouse) => warehouse.id === candidate)?.id ??
    activeWarehouses[0]?.id ??
    null
  )
}
