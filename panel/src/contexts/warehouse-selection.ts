import type { WarehouseInfo } from "@/api/warehouse-api"

/**
 * Keeps only a server-issued UUID preference. Obsolete slugs and any synthetic
 * service identifier are deliberately treated as stale and fall back to the
 * first ACTIVE or DRAINING warehouse returned by the public directory.
 */
export function resolveWarehouseSelection(
  savedWarehouseId: string | null,
  availableWarehouses: WarehouseInfo[]
) {
  return (
    availableWarehouses.find((warehouse) => warehouse.id === savedWarehouseId)
      ?.id ??
    availableWarehouses[0]?.id ??
    null
  )
}
