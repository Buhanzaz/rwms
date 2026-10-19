import { Badge } from "@/components/ui/badge"
import type { OrderDesiredEquipment } from "@/features/orders/domain/orders"
import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"
import { cn } from "@/lib/utils"

import {
  classifyCabinFurniture,
  type CabinFurnitureStatus,
} from "./cabin-furniture-status"

const STATUS_PRESENTATION: Record<
  CabinFurnitureStatus,
  { label: string; className: string }
> = {
  required: {
    label: "Требуется наполнение",
    className: "border-red-600/40 bg-red-600 text-white dark:bg-red-700",
  },
  action: {
    label: "Требуются действия",
    className:
      "border-orange-700/40 bg-orange-700 text-white dark:bg-orange-800",
  },
  loaded: {
    label: "Наполнение загружено",
    className:
      "border-emerald-700/40 bg-emerald-700 text-white dark:bg-emerald-800",
  },
  none: {
    label: "Нет наполнения",
    className: "border-border bg-muted text-muted-foreground",
  },
  unavailable: {
    label: "Наполнение недоступно",
    className: "border-border bg-background text-muted-foreground",
  },
}

type NamedFurnitureItem = {
  equipmentId?: string | null
  equipmentName?: string | null
  name?: string | null
  quantity: number
}

function compositionText(
  items: readonly NamedFurnitureItem[],
  unavailable: boolean
) {
  if (unavailable) return "данные недоступны"
  if (items.length === 0) return "нет"

  const labels = items
    .filter((item) => Number.isFinite(item.quantity) && item.quantity > 0)
    .map(
      (item) =>
        `${item.equipmentName ?? item.name ?? "Оборудование"} — ${item.quantity} шт.`
    )
  return labels.length > 0 ? labels.join(", ") : "нет"
}

export function CabinFurnitureStatusBadge({
  status,
  className,
}: {
  status: CabinFurnitureStatus
  className?: string
}) {
  const presentation = STATUS_PRESENTATION[status]
  return (
    <Badge
      variant="outline"
      className={cn(
        "max-w-full px-2.5 py-1 text-center whitespace-normal",
        presentation.className,
        className
      )}
    >
      {presentation.label}
    </Badge>
  )
}

export function CabinFurnitureSummary({
  desired,
  actual,
  unavailable = false,
  className,
}: {
  desired: readonly OrderDesiredEquipment[]
  actual: readonly RentalItemContentsItemDto[]
  unavailable?: boolean
  className?: string
}) {
  const status = classifyCabinFurniture(desired, actual, unavailable)

  return (
    <section
      aria-label="Наполнение бытовки"
      className={cn(
        "grid min-w-0 gap-2 rounded-lg border bg-muted/20 p-3",
        className
      )}
    >
      <CabinFurnitureStatusBadge status={status} />
      <dl className="grid min-w-0 gap-1 text-xs sm:text-sm">
        <div className="grid min-w-0 gap-0.5 sm:grid-cols-[7rem_minmax(0,1fr)] sm:gap-2">
          <dt className="font-medium text-muted-foreground">Требуется:</dt>
          <dd className="min-w-0 break-words">
            {compositionText(desired, unavailable)}
          </dd>
        </div>
        <div className="grid min-w-0 gap-0.5 sm:grid-cols-[7rem_minmax(0,1fr)] sm:gap-2">
          <dt className="font-medium text-muted-foreground">В бытовке:</dt>
          <dd className="min-w-0 break-words">
            {compositionText(actual, unavailable)}
          </dd>
        </div>
      </dl>
    </section>
  )
}
