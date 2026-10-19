import type { ReturnDocument } from "@/features/logistics/returns/model"
import type { ShipmentDocument } from "@/features/logistics/shipments/model"

function isDateDue(value: string | null) {
  return Boolean(value && value <= new Date().toISOString().slice(0, 10))
}

/**
 * Presents the logistics-owned rental lifecycle and retains compatibility with imported passport
 * facts that predate a shipment document. A live shipment or non-draft return state wins.
 */
export function rentalLifecycleLabel(
  shipmentState: ShipmentDocument["state"] | null,
  returnState: ReturnDocument["state"] | null,
  returnDate: string | null,
  passportShipmentDate: string | null,
  passportTenant: string | null
) {
  if (returnState === "ACCEPTED") return "Возвращено"
  if (returnState && returnState !== "DRAFT") {
    return "Возврат в процессе"
  }
  if (shipmentState === "SHIPPED" && isDateDue(returnDate)) {
    return "Требует возврата"
  }
  if (shipmentState === "SHIPPED") return "Отгружена"
  if (shipmentState === "DRAFT") return "Ожидает отгрузки"
  if (shipmentState) return "В процессе отгрузки"
  if (passportShipmentDate && passportTenant?.trim()) return "Отгружена"
  return "Не отгружена"
}
