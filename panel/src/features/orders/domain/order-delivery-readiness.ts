import type { OrderDetail } from "@/features/orders/domain/orders"

/**
 * Returns whether manager-owned contact data is complete before saving a draft.
 * Client-owned address, coordinates and additional contacts arrive through the
 * public presentation and are intentionally not part of this gate.
 */
export function isOrderDeliveryComplete(
  order: Pick<OrderDetail, "contactPhone">
) {
  return (
    typeof order.contactPhone === "string" &&
    order.contactPhone.trim() !== ""
  )
}
