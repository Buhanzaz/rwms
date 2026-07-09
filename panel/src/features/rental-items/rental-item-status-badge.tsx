import { Badge } from "@/components/ui/badge"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

type RentalItemStatusBadgeProps = {
  status: RentalItemStatus
}

export function RentalItemStatusBadge({ status }: RentalItemStatusBadgeProps) {
  return (
    <Badge variant="secondary" className="whitespace-nowrap">
      {RENTAL_ITEM_STATUS_LABEL[status]}
    </Badge>
  )
}