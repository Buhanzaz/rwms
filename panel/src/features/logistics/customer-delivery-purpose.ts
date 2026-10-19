/** Commercial purpose of a customer-facing shipment, separate from TRANSFER. */
export const CUSTOMER_DELIVERY_PURPOSES = [
  "RENTAL_DELIVERY",
  "SALE_DELIVERY",
  "CUSTOMER_RELOCATION",
] as const

export type CustomerDeliveryPurpose =
  (typeof CUSTOMER_DELIVERY_PURPOSES)[number]

export const CUSTOMER_DELIVERY_PURPOSE_LABELS: Record<
  CustomerDeliveryPurpose,
  string
> = {
  RENTAL_DELIVERY: "Доставка в аренду",
  SALE_DELIVERY: "Доставка продажи",
  CUSTOMER_RELOCATION: "Перемещение клиента",
}
