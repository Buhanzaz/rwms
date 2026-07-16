import type { WarehouseTransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { WAREHOUSE_TRANSFERS_STORAGE_KEY } from "@/features/logistics/warehouse-transfers/adapters/local-storage-warehouse-transfer-store"

const ACTIVE_TRANSFER_LINE_STATUSES = new Set([
  "PREPARING",
  "READY_TO_DEPART",
  "IN_TRANSIT",
  "CONFLICT",
])

/**
 * Synchronous browser-only guard used by other mock aggregates.
 *
 * Keep this module independent from the transfer API/client: both of those
 * mutate rental items and importing them from the rental-item boundary would
 * create a circular dependency.
 */
export function hasActiveWarehouseTransferLowLevel(rentalItemId: string) {
  if (typeof window === "undefined") return false
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(WAREHOUSE_TRANSFERS_STORAGE_KEY) ?? "null"
    ) as { documents?: WarehouseTransferDocument[] } | null
    return Boolean(
      parsed?.documents?.some((document) =>
        document.lines.some(
          (line) =>
            line.rentalItemId === rentalItemId &&
            ACTIVE_TRANSFER_LINE_STATUSES.has(line.status)
        )
      )
    )
  } catch {
    return false
  }
}
