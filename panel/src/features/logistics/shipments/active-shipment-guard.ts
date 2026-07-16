import type { ShipmentEnvelope } from "@/features/logistics/shipments/model"
import { SHIPMENTS_STORAGE_KEY } from "@/features/logistics/shipments/storage"

export function hasActiveShipmentForRentalItemLowLevel(rentalItemId: string) {
  if (typeof window === "undefined") return false
  try {
    const envelope = JSON.parse(
      window.localStorage.getItem(SHIPMENTS_STORAGE_KEY) ?? "null"
    ) as ShipmentEnvelope | null
    return Boolean(
      envelope?.shipments?.some(
        (shipment) =>
          (shipment.evidence ?? "PROVEN") === "PROVEN" &&
          shipment.status !== "SHIPPED" &&
          shipment.status !== "CANCELLED" &&
          shipment.status !== "LEGACY_QUARANTINE" &&
          shipment.items.some((item) => item.rentalItemId === rentalItemId)
      )
    )
  } catch {
    return false
  }
}
