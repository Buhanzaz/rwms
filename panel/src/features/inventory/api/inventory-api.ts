import { getUserManager } from "@/features/auth/oidc-client"
import { getActiveInventorySession } from "@/features/inventory/adapters/http-inventory-adapter"
import type { InventoryActorSnapshot } from "@/features/inventory/model/inventory"

export * from "@/features/inventory/adapters/http-inventory-adapter"

export const INVENTORY_QUERY_KEY = ["inventory-service"] as const

export function inventoryActiveQueryKey(warehouseId: string) {
  return [...INVENTORY_QUERY_KEY, "active", warehouseId] as const
}

export function inventoryDetailQueryKey(inventoryId: string) {
  return [...INVENTORY_QUERY_KEY, "detail", inventoryId] as const
}

/**
 * Sidebar-compatible active-session query. Authentication remains OIDC-owned;
 * no token or inventory state is copied into browser feature storage.
 */
export async function getActiveInventory(params: {
  warehouseId: string
  actor: InventoryActorSnapshot
}) {
  const user = await getUserManager().getUser()
  return getActiveInventorySession(
    user?.access_token ?? null,
    params.warehouseId
  )
}

/**
 * Refreshes the server query when the operator returns to the panel. This is a
 * browser lifecycle signal only; inventory mutations never use storage events.
 */
export function subscribeInventory(listener: () => void) {
  const handleVisibility = () => {
    if (document.visibilityState === "visible") listener()
  }
  window.addEventListener("focus", listener)
  document.addEventListener("visibilitychange", handleVisibility)
  return () => {
    window.removeEventListener("focus", listener)
    document.removeEventListener("visibilitychange", handleVisibility)
  }
}
