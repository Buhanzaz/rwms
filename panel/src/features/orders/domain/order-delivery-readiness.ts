import type { OrderDetail } from "@/features/orders/domain/orders"

/**
 * Returns whether an order already contains every delivery value needed before
 * a draft can be saved by the logistics service.
 */
export function isOrderDeliveryComplete(
  order: Pick<
    OrderDetail,
    | "deliveryAddress"
    | "latitude"
    | "longitude"
    | "contactPhone"
    | "acceptableDeliveryDates"
  >
) {
  return (
    typeof order.deliveryAddress === "string" &&
    order.deliveryAddress.trim() !== "" &&
    Number.isFinite(order.latitude) &&
    Number.isFinite(order.longitude) &&
    typeof order.contactPhone === "string" &&
    order.contactPhone.trim() !== "" &&
    (order.acceptableDeliveryDates?.length ?? 0) > 0
  )
}
