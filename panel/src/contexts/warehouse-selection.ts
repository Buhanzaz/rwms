import type { WarehouseInfo } from "@/api/warehouse-api"

/**
 * Keeps only a server-issued UUID preference. Obsolete slugs and any synthetic
 * service identifier are deliberately treated as stale and fall back to the
 * first active warehouse returned by the service.
 */
export function resolveWarehouseSelection(
  savedWarehouseId: string | null,
  activeWarehouses: WarehouseInfo[]
) {
  return (
    activeWarehouses.find((warehouse) => warehouse.id === savedWarehouseId)
      ?.id ??
    activeWarehouses[0]?.id ??
    null
  )
}
