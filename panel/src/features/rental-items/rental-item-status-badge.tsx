import { Badge } from "@/components/ui/badge"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

type RentalItemStatusBadgeProps = {
  status: RentalItemStatus
}

export function RentalItemStatusBadge({ status }: RentalItemStatusBadgeProps) {
  const colorVariable = `--cabin-status-${status.toLowerCase().replaceAll("_", "-")}`
  return (
    <Badge
      variant="secondary"
      className="border-transparent whitespace-nowrap text-foreground"
      style={{
        backgroundColor: `color-mix(in srgb, var(${colorVariable}) 24%, var(--card))`,
      }}
    >
      {RENTAL_ITEM_STATUS_LABEL[status]}
    </Badge>
  )
}
