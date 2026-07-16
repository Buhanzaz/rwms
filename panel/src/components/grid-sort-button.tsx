import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react"

import { cn } from "@/lib/utils"

export type GridSortDirection = "asc" | "desc"

export const GRID_HEADER_CLASS = "sticky top-0 z-20 bg-muted shadow-sm"
export const GRID_HEADER_CELL_CLASS =
  "border-b px-3 py-3 text-left text-xs font-medium text-muted-foreground"
export const GRID_CELL_CLASS = "border-b px-3 py-2 align-middle"
export const GRID_TABLE_ROW_CLASS = "h-[49px]"
export const GRID_FLEX_CELL_CLASS = "md:px-3 md:py-2 md:align-middle"
export const GRID_FLEX_ROW_CLASS = "md:min-h-[49px]"

export function GridSortButton({
  label,
  direction,
  onClick,
  className,
}: {
  label: string
  direction: GridSortDirection | null
  onClick: () => void
  className?: string
}) {
  return (
    <button
      type="button"
      className={cn(
        "inline-flex min-w-0 items-center gap-1 text-left font-medium whitespace-nowrap transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
        className
      )}
      onClick={onClick}
    >
      <span className="truncate">{label}</span>

      {direction === "asc" ? (
        <ArrowUp className="size-3.5 shrink-0" aria-hidden="true" />
      ) : direction === "desc" ? (
        <ArrowDown className="size-3.5 shrink-0" aria-hidden="true" />
      ) : (
        <ArrowUpDown
          className="size-3.5 shrink-0 opacity-50"
          aria-hidden="true"
        />
      )}
    </button>
  )
}
