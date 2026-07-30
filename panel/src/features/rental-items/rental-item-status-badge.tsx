import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

type RentalItemStatusBadgeProps = {
  status: RentalItemStatus
}

const statusClassName: Record<RentalItemStatus, string> = {
  RENTED: "bg-[var(--status-rented-bg)] text-[var(--status-rented-fg)]",
  AFTER_RENT:
    "bg-[var(--status-after-rent-bg)] text-[var(--status-after-rent-fg)]",
  WAITING_ESTIMATE_CONFIRMATION:
    "bg-[var(--status-after-rent-bg)] text-[var(--status-after-rent-fg)]",
  BOOKED: "bg-[var(--status-booked-bg)] text-[var(--status-booked-fg)]",
  RESERVED: "bg-[var(--status-booked-bg)] text-[var(--status-booked-fg)]",
  REPAIR: "bg-[var(--status-repair-bg)] text-[var(--status-repair-fg)]",
  WAITING_REPAIR_CHECK:
    "bg-[var(--status-waiting-repair-check-bg)] text-[var(--status-waiting-repair-check-fg)]",
  WRITTEN_OFF:
    "bg-[var(--status-written-off-bg)] text-[var(--status-written-off-fg)]",
  CAPITAL_REPAIR:
    "bg-[var(--status-capital-repair-bg)] text-[var(--status-capital-repair-fg)]",
  SALE: "bg-[var(--status-sale-bg)] text-[var(--status-sale-fg)]",
  USED_SALE: "bg-[var(--status-sale-bg)] text-[var(--status-sale-fg)]",
  FREE: "bg-[var(--status-free-bg)] text-[var(--status-free-fg)]",
  WAREHOUSE:
    "bg-[var(--status-warehouse-bg)] text-[var(--status-warehouse-fg)]",
  OWN_NEEDS:
    "bg-[var(--status-own-needs-bg)] text-[var(--status-own-needs-fg)]",
  IN_TRANSFER: "bg-secondary text-secondary-foreground",
}

export function RentalItemStatusBadge({ status }: RentalItemStatusBadgeProps) {
  return (
    <Badge
      variant="secondary"
      className={cn(
        "border-transparent whitespace-nowrap",
        statusClassName[status]
      )}
    >
      {RENTAL_ITEM_STATUS_LABEL[status]}
    </Badge>
  )
}
