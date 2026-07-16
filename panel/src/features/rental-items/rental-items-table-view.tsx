/* eslint-disable react-hooks/incompatible-library -- TanStack Table returns imperative helpers that React Compiler intentionally skips. */
import { useEffect, useMemo, useState } from "react"
import {
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type Column,
  type ColumnDef,
  type ColumnOrderState,
  type ColumnSizingState,
  type OnChangeFn,
  type SortingState,
  type VisibilityState,
} from "@tanstack/react-table"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Exchange01Icon,
  Image01Icon,
  MinusSignIcon,
  PlusSignIcon,
  WarehouseIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  GRID_CELL_CLASS,
  GRID_HEADER_CELL_CLASS,
  GRID_HEADER_CLASS,
  GRID_TABLE_ROW_CLASS,
  GridSortButton,
} from "@/components/grid-sort-button"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { AddContentsDialog } from "@/features/rental-items/add-contents-dialog"
import { MoveContentsToRentalItemDialog } from "@/features/rental-items/move-contents-to-rental-item-dialog"
import { MoveContentsToStockDialog } from "@/features/rental-items/move-contents-to-stock-dialog"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import {
  formatRentalItemContents,
  formatRentalItemFieldValue,
  getRentalItemSortValue,
  type RentalItemDto,
  type RentalItemsColumnConfig,
  type RentalItemsTableSchema,
} from "@/features/rental-items/model/rental-item"

type RentalItemsTableViewProps = {
  schema: RentalItemsTableSchema
  items: RentalItemDto[]
  sorting: SortingState
  onSortingChange: OnChangeFn<SortingState>
  onOpenPhotos: (item: RentalItemDto) => void
  onOpenItem: (item: RentalItemDto) => void
  loading?: boolean
  isLoading?: boolean
  columnsConfig: RentalItemsColumnConfig[]
}

type PersistedRentalItemsTableState = {
  columnSizing?: ColumnSizingState
}

const RENTAL_ITEMS_TABLE_STATE_STORAGE_KEY = "wms:rental-items-table-state:v6"

function readPersistedTableState(): PersistedRentalItemsTableState {
  if (typeof window === "undefined") {
    return {}
  }

  const rawValue = window.localStorage.getItem(
    RENTAL_ITEMS_TABLE_STATE_STORAGE_KEY
  )

  if (!rawValue) {
    return {}
  }

  try {
    const parsed = JSON.parse(rawValue)

    if (!parsed || typeof parsed !== "object") {
      return {}
    }

    return parsed as PersistedRentalItemsTableState
  } catch {
    return {}
  }
}

function persistTableState(state: PersistedRentalItemsTableState) {
  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(
    RENTAL_ITEMS_TABLE_STATE_STORAGE_KEY,
    JSON.stringify(state)
  )
}

function columnsConfigToVisibility(
  columnsConfig: RentalItemsColumnConfig[]
): VisibilityState {
  return Object.fromEntries(
    columnsConfig.map((column) => [
      column.id,
      column.locked ? true : column.visible,
    ])
  )
}

function columnsConfigToOrder(
  columnsConfig: RentalItemsColumnConfig[]
): ColumnOrderState {
  return columnsConfig.map((column) => column.id)
}

function getColumnSize(column: RentalItemsColumnConfig) {
  return column.size ?? 160
}

function getColumnMinSize(column: RentalItemsColumnConfig) {
  return column.minSize ?? 80
}

function getColumnMaxSize(column: RentalItemsColumnConfig) {
  return column.maxSize ?? 900
}

function formatPrice(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return null
  }

  return `${new Intl.NumberFormat("ru-RU").format(value)} ₽`
}

function SortableHeader<TData>({
  column,
  label,
}: {
  column: Column<TData, unknown>
  label: string
}) {
  const sorted = column.getIsSorted()

  return (
    <GridSortButton
      label={label}
      direction={sorted || null}
      onClick={() => column.toggleSorting()}
    />
  )
}

function BooleanText({ value }: { value: boolean | null | undefined }) {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">—</span>
  }

  return <span>{value ? "Да" : "Нет"}</span>
}

function EmptyValue() {
  return <span className="text-muted-foreground">—</span>
}

export function ExpandableTextCell({
  value,
  label,
}: {
  value: string
  label: string
}) {
  if (value === "—") {
    return <EmptyValue />
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={`${label}: ${value}. Показать полностью`}
          className="block max-w-full cursor-pointer truncate rounded-sm text-left underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
          onClick={(event) => {
            event.stopPropagation()
          }}
          onPointerDown={(event) => {
            event.stopPropagation()
          }}
          onKeyDown={(event) => {
            event.stopPropagation()
          }}
          onDoubleClick={(event) => {
            event.stopPropagation()
          }}
        >
          {value}
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className="max-h-[min(20rem,var(--radix-popover-content-available-height))] w-80 max-w-[calc(100vw-2rem)] overflow-y-auto text-sm wrap-break-word whitespace-normal"
        onClick={(event) => {
          event.stopPropagation()
        }}
        onPointerDown={(event) => {
          event.stopPropagation()
        }}
        onDoubleClick={(event) => {
          event.stopPropagation()
        }}
      >
        {value}
      </PopoverContent>
    </Popover>
  )
}

function ContentsPopoverBody({
  item,
  onMoveToRentalItem,
  onMoveToStock,
}: {
  item: RentalItemDto
  onMoveToRentalItem: () => void
  onMoveToStock: () => void
}) {
  const [draftRows, setDraftRows] = useState(() =>
    item.contentsItems.map((row) => ({
      ...row,
    }))
  )

  const hasChanges = draftRows.some((draftRow) => {
    const originalRow = item.contentsItems.find((row) => {
      return row.name === draftRow.name
    })

    return originalRow?.quantity !== draftRow.quantity
  })

  function decreaseQuantity(name: string) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          quantity: Math.max(0, row.quantity - 1),
        }
      })
    )
  }

  function increaseQuantity(name: string) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          quantity: row.quantity + 1,
        }
      })
    )
  }

  function applyChanges() {
    console.log("Изменить наполнение", {
      rentalItemId: item.id,
      contentsItems: draftRows,
    })
  }

  return (
    <div className="flex max-h-[380px] flex-col">
      <div className="border-b px-3 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        Наполнение
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        {draftRows.map((row) => (
          <div
            key={`${item.id}-${row.name}`}
            className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-4 border-b px-3 py-2 text-sm last:border-b-0"
          >
            <div className="truncate font-medium">{row.name}</div>

            <div className="flex items-center gap-2 whitespace-nowrap">
              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="size-7"
                aria-label={`Уменьшить количество: ${row.name}`}
                disabled={row.quantity <= 0}
                onClick={(event) => {
                  event.stopPropagation()
                  decreaseQuantity(row.name)
                }}
              >
                <HugeiconsIcon icon={MinusSignIcon} />
              </Button>

              <div className="min-w-12 text-center text-muted-foreground">
                <span className="font-semibold text-foreground">
                  {row.quantity}
                </span>{" "}
                шт.
              </div>

              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="size-7"
                aria-label={`Увеличить количество: ${row.name}`}
                onClick={(event) => {
                  event.stopPropagation()
                  increaseQuantity(row.name)
                }}
              >
                <HugeiconsIcon icon={PlusSignIcon} />
              </Button>
            </div>
          </div>
        ))}
      </div>

      <div className="grid gap-2 border-t bg-background p-2 sm:grid-cols-2">
        <Button
          size="sm"
          variant="outline"
          className="h-8 justify-center whitespace-nowrap"
          onClick={(event) => {
            event.stopPropagation()
            onMoveToRentalItem()
          }}
        >
          <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
          Переместить
        </Button>

        <Button
          size="sm"
          variant="outline"
          className="h-8 justify-center whitespace-nowrap"
          onClick={(event) => {
            event.stopPropagation()
            onMoveToStock()
          }}
        >
          <HugeiconsIcon icon={WarehouseIcon} data-icon="inline-start" />
          Переместить на склад
        </Button>

        {hasChanges && (
          <Button
            size="sm"
            className="h-8 justify-center whitespace-nowrap sm:col-span-2"
            onClick={(event) => {
              event.stopPropagation()
              applyChanges()
            }}
          >
            Изменить
          </Button>
        )}
      </div>
    </div>
  )
}

export function ContentsCell({
  item,
  open,
  onOpenChange,
  onAddContents,
  onMoveToRentalItem,
  onMoveToStock,
}: {
  item: RentalItemDto
  open: boolean
  onOpenChange: (open: boolean) => void
  onAddContents: () => void
  onMoveToRentalItem: () => void
  onMoveToStock: () => void
}) {
  const hasRows = item.contentsItems.length > 0

  if (!hasRows) {
    return (
      <Button
        size="sm"
        variant="secondary"
        className="h-8"
        onClick={(event) => {
          event.stopPropagation()
          onAddContents()
        }}
      >
        Добавить
      </Button>
    )
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="max-w-full truncate text-left text-primary hover:underline"
          onClick={(event) => {
            event.stopPropagation()
          }}
        >
          {formatRentalItemContents(item.contentsItems, item.contents)}
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className="w-[420px] p-0"
        onClick={(event) => {
          event.stopPropagation()
        }}
      >
        <ContentsPopoverBody
          key={`${item.id}-${open}-${item.contentsItems
            .map((contentItem) => `${contentItem.name}:${contentItem.quantity}`)
            .join("|")}`}
          item={item}
          onMoveToRentalItem={onMoveToRentalItem}
          onMoveToStock={onMoveToStock}
        />
      </PopoverContent>
    </Popover>
  )
}

export function RentalItemsTableView({
  schema,
  items,
  sorting,
  onSortingChange,
  onOpenPhotos,
  onOpenItem,
  loading,
  isLoading,
  columnsConfig,
}: RentalItemsTableViewProps) {
  const persistedTableState = useMemo(() => readPersistedTableState(), [])

  const schemaColumnById = useMemo(() => {
    return new Map(schema.columns.map((column) => [column.id, column]))
  }, [schema.columns])

  const activeColumnsConfig = useMemo(() => {
    return columnsConfig.map((column) => ({
      ...schemaColumnById.get(column.id),
      ...column,
    }))
  }, [columnsConfig, schemaColumnById])

  const columnVisibility = useMemo(() => {
    return columnsConfigToVisibility(activeColumnsConfig)
  }, [activeColumnsConfig])

  const columnOrder = useMemo(() => {
    return columnsConfigToOrder(activeColumnsConfig)
  }, [activeColumnsConfig])

  const defaultColumnSizing = useMemo<ColumnSizingState>(() => {
    return Object.fromEntries(
      activeColumnsConfig.map((column) => [column.id, getColumnSize(column)])
    )
  }, [activeColumnsConfig])

  const [columnSizing, setColumnSizing] = useState<ColumnSizingState>(
    persistedTableState.columnSizing ?? {}
  )

  const effectiveColumnSizing = useMemo<ColumnSizingState>(() => {
    return {
      ...defaultColumnSizing,
      ...columnSizing,
    }
  }, [columnSizing, defaultColumnSizing])

  const [openedContentsItemId, setOpenedContentsItemId] = useState<
    string | null
  >(null)

  const [addContentsItem, setAddContentsItem] = useState<RentalItemDto | null>(
    null
  )

  const [moveContentsToStockItem, setMoveContentsToStockItem] =
    useState<RentalItemDto | null>(null)

  const [moveContentsToRentalItem, setMoveContentsToRentalItem] =
    useState<RentalItemDto | null>(null)

  useEffect(() => {
    persistTableState({
      columnSizing,
    })
  }, [columnSizing])

  const columns = useMemo<ColumnDef<RentalItemDto>[]>(
    () =>
      activeColumnsConfig.map((columnConfig) => ({
        id: columnConfig.id,
        accessorFn: (row) => getRentalItemSortValue(row, columnConfig.id),
        size: getColumnSize(columnConfig),
        minSize: getColumnMinSize(columnConfig),
        maxSize: getColumnMaxSize(columnConfig),
        header: ({ column }) => (
          <SortableHeader column={column} label={columnConfig.label} />
        ),
        cell: ({ row }) => {
          const item = row.original

          if (columnConfig.id === "number") {
            return (
              <button
                type="button"
                className="font-medium text-primary hover:underline"
                onClick={(event) => {
                  event.stopPropagation()
                  onOpenItem(item)
                }}
              >
                {item.number}
              </button>
            )
          }

          if (columnConfig.id === "status") {
            return <RentalItemStatusBadge status={item.status} />
          }

          if (columnConfig.id === "hasPhotos") {
            if (!item.hasPhotos) {
              return <span className="text-muted-foreground">Нет</span>
            }

            return (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-primary hover:underline"
                onClick={(event) => {
                  event.stopPropagation()
                  onOpenPhotos(item)
                }}
              >
                <HugeiconsIcon icon={Image01Icon} data-icon="inline-start" />
                Да
                <span className="text-muted-foreground">
                  ({item.photoCount})
                </span>
              </button>
            )
          }

          if (columnConfig.id === "contents") {
            return (
              <ContentsCell
                item={item}
                open={openedContentsItemId === item.id}
                onOpenChange={(open) => {
                  setOpenedContentsItemId(open ? item.id : null)
                }}
                onAddContents={() => {
                  setOpenedContentsItemId(null)
                  setAddContentsItem(item)
                }}
                onMoveToRentalItem={() => {
                  setOpenedContentsItemId(null)
                  setMoveContentsToRentalItem(item)
                }}
                onMoveToStock={() => {
                  setOpenedContentsItemId(null)
                  setMoveContentsToStockItem(item)
                }}
              />
            )
          }

          if (columnConfig.dataType === "boolean") {
            const value = item[columnConfig.id]

            return (
              <BooleanText
                value={
                  typeof value === "boolean" || value === null
                    ? value
                    : undefined
                }
              />
            )
          }

          if (columnConfig.dataType === "currency") {
            const value = item[columnConfig.id]
            const price = typeof value === "number" ? formatPrice(value) : null

            return price ? (
              <span className="font-medium whitespace-nowrap">{price}</span>
            ) : (
              <EmptyValue />
            )
          }

          const value = formatRentalItemFieldValue(item, columnConfig.id)

          if (
            columnConfig.id === "type" ||
            columnConfig.id === "characteristics" ||
            columnConfig.id === "comment"
          ) {
            return (
              <ExpandableTextCell value={value} label={columnConfig.label} />
            )
          }

          return value === "—" ? (
            <EmptyValue />
          ) : (
            <div className="truncate">{value}</div>
          )
        },
      })),
    [activeColumnsConfig, onOpenItem, onOpenPhotos, openedContentsItemId]
  )

  const table = useReactTable({
    data: items,
    columns,
    defaultColumn: {
      minSize: 80,
      size: 160,
      maxSize: 900,
    },
    state: {
      sorting,
      columnVisibility,
      columnOrder,
      columnSizing: effectiveColumnSizing,
    },
    onSortingChange,
    onColumnSizingChange: setColumnSizing,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    columnResizeMode: "onChange",
    enableColumnResizing: true,
  })

  const visibleColumns = table.getVisibleLeafColumns()
  const tableTotalSize = table.getTotalSize()
  const showLoading = loading ?? isLoading ?? false

  return (
    <>
      <div className="min-h-0 w-full flex-1 overflow-hidden rounded-lg border bg-card">
        <div className="h-full w-full overflow-auto">
          <table
            className="table-fixed border-separate border-spacing-0 text-sm"
            style={{
              width: `max(100%, ${tableTotalSize}px)`,
              minWidth: "100%",
            }}
          >
            <colgroup>
              {visibleColumns.map((column) => (
                <col
                  key={column.id}
                  style={{
                    width: `${column.getSize()}px`,
                  }}
                />
              ))}
            </colgroup>

            <thead className={GRID_HEADER_CLASS}>
              {table.getHeaderGroups().map((headerGroup) => (
                <tr key={headerGroup.id}>
                  {headerGroup.headers.map((header) => (
                    <th
                      key={header.id}
                      className={`relative ${GRID_HEADER_CELL_CLASS}`}
                    >
                      <div className="min-w-0 pr-2">
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext()
                            )}
                      </div>

                      {header.column.getCanResize() ? (
                        <button
                          type="button"
                          aria-label="Изменить ширину колонки"
                          className={[
                            "absolute top-0 right-0 h-full w-1.5 cursor-col-resize touch-none select-none",
                            "bg-primary/40 opacity-0 transition-opacity hover:opacity-100",
                            header.column.getIsResizing() ? "opacity-100" : "",
                          ].join(" ")}
                          onClick={(event) => {
                            event.stopPropagation()
                          }}
                          onMouseDown={(event) => {
                            event.stopPropagation()
                            header.getResizeHandler()(event)
                          }}
                          onTouchStart={(event) => {
                            event.stopPropagation()
                            header.getResizeHandler()(event)
                          }}
                        />
                      ) : null}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>

            <tbody>
              {showLoading ? (
                <tr>
                  <td
                    colSpan={visibleColumns.length || 1}
                    className="px-4 py-6 text-sm text-muted-foreground"
                  >
                    Загрузка склада...
                  </td>
                </tr>
              ) : table.getRowModel().rows.length === 0 ? (
                <tr>
                  <td
                    colSpan={visibleColumns.length || 1}
                    className="px-4 py-6 text-sm text-muted-foreground"
                  >
                    Бытовки не найдены.
                  </td>
                </tr>
              ) : (
                table.getRowModel().rows.map((row) => (
                  <tr
                    key={row.id}
                    className={`${GRID_TABLE_ROW_CLASS} cursor-pointer border-b hover:bg-muted/40`}
                    onDoubleClick={() => {
                      onOpenItem(row.original)
                    }}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className={GRID_CELL_CLASS}>
                        <div className="min-w-0 truncate">
                          {flexRender(
                            cell.column.columnDef.cell,
                            cell.getContext()
                          )}
                        </div>
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <AddContentsDialog
        item={addContentsItem}
        open={addContentsItem !== null}
        onOpenChange={(open) => {
          if (!open) {
            setAddContentsItem(null)
          }
        }}
      />

      <MoveContentsToRentalItemDialog
        item={moveContentsToRentalItem}
        open={moveContentsToRentalItem !== null}
        onOpenChange={(open) => {
          if (!open) {
            setMoveContentsToRentalItem(null)
          }
        }}
      />

      <MoveContentsToStockDialog
        item={moveContentsToStockItem}
        open={moveContentsToStockItem !== null}
        onOpenChange={(open) => {
          if (!open) {
            setMoveContentsToStockItem(null)
          }
        }}
      />
    </>
  )
}
