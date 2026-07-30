import { Fragment, useMemo, useState, type ReactNode } from "react"

import {
  GRID_CELL_CLASS,
  GRID_HEADER_CELL_CLASS,
  GRID_HEADER_CLASS,
  GRID_TABLE_ROW_CLASS,
  GridSortButton,
  type GridSortDirection,
} from "@/components/grid-sort-button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { cn } from "@/lib/utils"

type GridSortState = {
  columnId: string
  direction: GridSortDirection
} | null

type GridSortValue = number | string | null | undefined

export type OperationsListGridColumn<T> = {
  id: string
  label: string
  render: (item: T) => ReactNode
  getSortValue: (item: T) => GridSortValue
  className?: string
  cellClassName?: string
}

const stringCollator = new Intl.Collator("ru", {
  numeric: true,
  sensitivity: "base",
})

function compareGridValues(left: GridSortValue, right: GridSortValue) {
  if (left === null || left === undefined || left === "") {
    return right === null || right === undefined || right === "" ? 0 : 1
  }

  if (right === null || right === undefined || right === "") {
    return -1
  }

  if (typeof left === "number" && typeof right === "number") {
    return left - right
  }

  return stringCollator.compare(String(left), String(right))
}

export function OperationsListGrid<T extends { id: string }>({
  className,
  columns,
  items,
  getRowClassName,
  expandedItemId,
  renderExpandedRow,
}: {
  className?: string
  columns: OperationsListGridColumn<T>[]
  items: T[]
  getRowClassName?: (item: T) => string | undefined
  expandedItemId?: string | null
  renderExpandedRow?: (item: T) => ReactNode
}) {
  const [sortState, setSortState] = useState<GridSortState>(null)

  const sortedItems = useMemo(() => {
    if (sortState === null) {
      return items
    }

    const column = columns.find((item) => item.id === sortState.columnId)

    if (!column) {
      return items
    }

    const originalIndexById = new Map(
      items.map((item, index) => [item.id, index])
    )

    return items.slice().sort((left, right) => {
      const result = compareGridValues(
        column.getSortValue(left),
        column.getSortValue(right)
      )

      if (result !== 0) {
        return sortState.direction === "asc" ? result : -result
      }

      return (
        (originalIndexById.get(left.id) ?? 0) -
        (originalIndexById.get(right.id) ?? 0)
      )
    })
  }, [columns, items, sortState])

  function toggleSort(columnId: string) {
    setSortState((current) => {
      if (current?.columnId !== columnId) {
        return { columnId, direction: "asc" }
      }

      if (current.direction === "asc") {
        return { columnId, direction: "desc" }
      }

      return null
    })
  }

  return (
    <div
      data-slot="operations-list-grid"
      className={cn(
        "min-h-0 overflow-hidden rounded-lg border bg-card",
        className
      )}
    >
      <Table className="min-w-[46rem] border-separate border-spacing-0">
        <TableHeader className={GRID_HEADER_CLASS}>
          <TableRow className="hover:bg-transparent">
            {columns.map((column) => {
              const direction =
                sortState?.columnId === column.id ? sortState.direction : null

              return (
                <TableHead
                  key={column.id}
                  className={cn(
                    "h-auto",
                    GRID_HEADER_CELL_CLASS,
                    column.className
                  )}
                  aria-sort={
                    direction === "asc"
                      ? "ascending"
                      : direction === "desc"
                        ? "descending"
                        : "none"
                  }
                >
                  <GridSortButton
                    label={column.label}
                    direction={direction}
                    onClick={() => toggleSort(column.id)}
                  />
                </TableHead>
              )
            })}
          </TableRow>
        </TableHeader>

        <TableBody>
          {sortedItems.map((item) => {
            const expanded = expandedItemId === item.id && renderExpandedRow

            return (
              <Fragment key={item.id}>
                <TableRow
                  className={cn(
                    GRID_TABLE_ROW_CLASS,
                    "hover:bg-muted/40",
                    getRowClassName?.(item)
                  )}
                >
                  {columns.map((column) => (
                    <TableCell
                      key={column.id}
                      className={cn(GRID_CELL_CLASS, column.cellClassName)}
                    >
                      {column.render(item)}
                    </TableCell>
                  ))}
                </TableRow>

                {expanded ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell
                      colSpan={columns.length}
                      className="border-b bg-muted/20 p-3"
                    >
                      {renderExpandedRow(item)}
                    </TableCell>
                  </TableRow>
                ) : null}
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
