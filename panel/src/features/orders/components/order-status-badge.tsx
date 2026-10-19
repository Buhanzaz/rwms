import { Badge } from "@/components/ui/badge"
import { ORDER_STATUS_LABELS, type OrderStatus } from "../domain/orders"

const statusVariants = {
  DRAFT: "muted",
  SAVED: "info",
  FULFILLED: "success",
  CLOSED: "success",
  CANCELLED: "muted",
} as const satisfies Record<OrderStatus, string>

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return (
    <Badge variant={statusVariants[status]}>
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}
