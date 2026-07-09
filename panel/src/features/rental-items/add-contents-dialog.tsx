import { useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Minus, Plus, Search } from "lucide-react"

import {
  addInventoryFromWarehouseStock,
  findNearestInventorySources,
  getRentalItemInventoryAddOptions,
  transferInventoryFromRentalItem,
} from "@/api/rental-item-inventory-api"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type {
  NearbyInventorySourceDto,
  WarehouseInventoryStockItemDto,
} from "@/types/warehouse-location"
import type { PageResponse, RentalItemDto } from "@/features/rental-items/model/rental-item"

type AddContentsDialogProps = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

type DraftRow = {
  name: string
  availableQuantity: number
  quantity: number
  selected: boolean
}

type RowDraftState = {
  quantity: number
  selected: boolean
}

export function AddContentsDialog({
  item,
  open,
  onOpenChange,
}: AddContentsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            Добавить наполнение
            {item ? ` — ${item.number}` : ""}
          </DialogTitle>
        </DialogHeader>

        {item ? (
          <AddContentsDialogContent
            key={`${item.id}:${open ? "open" : "closed"}`}
            item={item}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function updateRentalItemsPageCache(
  oldData: PageResponse<RentalItemDto> | undefined,
  updatedItems: RentalItemDto[]
) {
  if (!oldData) {
    return oldData
  }

  return {
    ...oldData,
    content: oldData.content.map((item) => {
      return (
        updatedItems.find((updatedItem) => updatedItem.id === item.id) ?? item
      )
    }),
  }
}

function buildDraftRows(
  items: WarehouseInventoryStockItemDto[],
  stateByName: Record<string, RowDraftState>
): DraftRow[] {
  return items.map((item) => {
    const state = stateByName[item.name]

    return {
      name: item.name,
      availableQuantity: item.availableQuantity,
      quantity: state?.quantity ?? 0,
      selected: state?.selected ?? false,
    }
  })
}

function buildSourceDraftRows(source: NearbyInventorySourceDto): DraftRow[] {
  return source.inventory.map((item) => ({
    name: item.name,
    availableQuantity: item.availableQuantity,
    quantity: 0,
    selected: false,
  }))
}

function AddContentsDialogContent({
  item,
  onClose,
}: {
  item: RentalItemDto
  onClose: () => void
}) {
  const queryClient = useQueryClient()

  const [stockStateByName, setStockStateByName] = useState<
    Record<string, RowDraftState>
  >({})

  const [nearestSources, setNearestSources] = useState<
    NearbyInventorySourceDto[]
  >([])

  const [sourceRowsById, setSourceRowsById] = useState<
    Record<string, DraftRow[]>
  >({})

  const [errorText, setErrorText] = useState<string | null>(null)

  const addOptionsQuery = useQuery({
    queryKey: ["rental-item-inventory-add-options", item.id],
    queryFn: () => getRentalItemInventoryAddOptions(item.id),
  })

  const stockRows = useMemo(() => {
    return buildDraftRows(
      addOptionsQuery.data?.warehouseStock ?? [],
      stockStateByName
    )
  }, [addOptionsQuery.data, stockStateByName])

  const selectedStockItems = stockRows
    .filter((row) => row.selected && row.quantity > 0)
    .map((row) => ({
      name: row.name,
      quantity: row.quantity,
    }))

  const addFromStockMutation = useMutation({
    mutationFn: () =>
      addInventoryFromWarehouseStock({
        targetRentalItemId: item.id,
        payload: {
          items: selectedStockItems,
        },
      }),
    onSuccess: (updatedItem) => {
      queryClient.setQueryData(["rental-item", updatedItem.id], updatedItem)

      queryClient.setQueriesData<PageResponse<RentalItemDto>>(
        {
          queryKey: ["rental-items"],
        },
        (oldData) => updateRentalItemsPageCache(oldData, [updatedItem])
      )

      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", updatedItem.id],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item-inventory-add-options", item.id],
      })

      onClose()
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Ошибка добавления."
      )
    },
  })

  const nearestSourcesMutation = useMutation({
    mutationFn: () =>
      findNearestInventorySources({
        targetRentalItemId: item.id,
        limit: 10,
      }),
    onSuccess: (sources) => {
      setNearestSources(sources)
      setSourceRowsById(
        Object.fromEntries(
          sources.map((source) => [
            source.rentalItemId,
            buildSourceDraftRows(source),
          ])
        )
      )
    },
    onError: (error) => {
      setErrorText(error instanceof Error ? error.message : "Ошибка поиска.")
    },
  })

  const transferMutation = useMutation({
    mutationFn: (sourceId: string) => {
      const rows = sourceRowsById[sourceId] ?? []

      const selectedItems = rows
        .filter((row) => row.selected && row.quantity > 0)
        .map((row) => ({
          name: row.name,
          quantity: row.quantity,
        }))

      return transferInventoryFromRentalItem({
        targetRentalItemId: item.id,
        payload: {
          sourceRentalItemId: sourceId,
          items: selectedItems,
        },
      })
    },
    onSuccess: (result) => {
      queryClient.setQueryData(
        ["rental-item", result.targetItem.id],
        result.targetItem
      )

      queryClient.setQueryData(
        ["rental-item", result.sourceItem.id],
        result.sourceItem
      )

      queryClient.setQueriesData<PageResponse<RentalItemDto>>(
        {
          queryKey: ["rental-items"],
        },
        (oldData) =>
          updateRentalItemsPageCache(oldData, [
            result.targetItem,
            result.sourceItem,
          ])
      )

      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", result.targetItem.id],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", result.sourceItem.id],
      })

      onClose()
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Ошибка перемещения."
      )
    },
  })

  function toggleStockRow(name: string, selected: boolean) {
    setStockStateByName((current) => {
      const currentState = current[name]

      return {
        ...current,
        [name]: {
          selected,
          quantity:
            selected && (currentState?.quantity ?? 0) === 0
              ? 1
              : (currentState?.quantity ?? 0),
        },
      }
    })
  }

  function changeStockQuantity(name: string, delta: number) {
    const stockRow = stockRows.find((row) => row.name === name)

    if (!stockRow) {
      return
    }

    setStockStateByName((current) => {
      const currentState = current[name]
      const currentQuantity = currentState?.quantity ?? 0

      const quantity = Math.min(
        stockRow.availableQuantity,
        Math.max(0, currentQuantity + delta)
      )

      return {
        ...current,
        [name]: {
          quantity,
          selected: quantity > 0,
        },
      }
    })
  }

  function toggleSourceRow(sourceId: string, name: string, selected: boolean) {
    setSourceRowsById((current) => ({
      ...current,
      [sourceId]: (current[sourceId] ?? []).map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          selected,
          quantity: selected && row.quantity === 0 ? 1 : row.quantity,
        }
      }),
    }))
  }

  function changeSourceQuantity(sourceId: string, name: string, delta: number) {
    setSourceRowsById((current) => ({
      ...current,
      [sourceId]: (current[sourceId] ?? []).map((row) => {
        if (row.name !== name) {
          return row
        }

        const quantity = Math.min(
          row.availableQuantity,
          Math.max(0, row.quantity + delta)
        )

        return {
          ...row,
          quantity,
          selected: quantity > 0,
        }
      }),
    }))
  }

  function renderRows(params: {
    rows: DraftRow[]
    onToggle: (name: string, selected: boolean) => void
    onChangeQuantity: (name: string, delta: number) => void
  }) {
    if (params.rows.length === 0) {
      return (
        <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
          Доступных позиций нет.
        </div>
      )
    }

    return (
      <div className="overflow-hidden rounded-md border">
        {params.rows.map((row) => (
          <div
            key={row.name}
            className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 border-b px-3 py-2 text-sm last:border-b-0"
          >
            <Checkbox
              checked={row.selected}
              onCheckedChange={(value) =>
                params.onToggle(row.name, value === true)
              }
            />

            <div className="min-w-0">
              <div className="truncate font-medium">{row.name}</div>

              <div className="text-xs text-muted-foreground">
                Доступно: {row.availableQuantity} шт.
              </div>
            </div>

            <div className="flex items-center gap-2 whitespace-nowrap">
              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="size-7"
                disabled={row.quantity <= 0}
                onClick={() => params.onChangeQuantity(row.name, -1)}
              >
                <Minus className="size-3.5" />
              </Button>

              <div className="min-w-12 text-center">
                <span className="font-semibold">{row.quantity}</span> шт.
              </div>

              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="size-7"
                disabled={row.quantity >= row.availableQuantity}
                onClick={() => params.onChangeQuantity(row.name, 1)}
              >
                <Plus className="size-3.5" />
              </Button>
            </div>
          </div>
        ))}
      </div>
    )
  }

  return (
    <div className="grid max-h-[75vh] gap-4 overflow-auto pr-1">
      {errorText ? (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
          {errorText}
        </div>
      ) : null}

      <section className="grid gap-3">
        <div className="flex items-center justify-between gap-3">
          <h3 className="font-medium">Доступно на складе</h3>

          <Button
            disabled={
              selectedStockItems.length === 0 || addFromStockMutation.isPending
            }
            onClick={() => addFromStockMutation.mutate()}
          >
            Добавить со склада
          </Button>
        </div>

        {addOptionsQuery.isLoading ? (
          <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
            Загрузка склада...
          </div>
        ) : (
          renderRows({
            rows: stockRows,
            onToggle: toggleStockRow,
            onChangeQuantity: changeStockQuantity,
          })
        )}
      </section>

      <section className="grid gap-3 border-t pt-4">
        <div className="flex items-center justify-between gap-3">
          <h3 className="font-medium">Ближайшие бытовки</h3>

          <Button
            onClick={() => nearestSourcesMutation.mutate()}
            disabled={nearestSourcesMutation.isPending}
          >
            <Search className="mr-2 size-4" />
            Найти ближайшие
          </Button>
        </div>

        {nearestSourcesMutation.isPending ? (
          <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
            Поиск ближайших бытовок...
          </div>
        ) : nearestSources.length === 0 ? (
          <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
            Поблизости нет бытовок с доступным наполнением.
          </div>
        ) : (
          <div className="grid gap-3">
            {nearestSources.map((source) => {
              const rows = sourceRowsById[source.rentalItemId] ?? []

              const selectedRows = rows.filter((row) => {
                return row.selected && row.quantity > 0
              })

              return (
                <div
                  key={source.rentalItemId}
                  className="rounded-md border p-3"
                >
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div>
                      <div className="font-medium">{source.number}</div>

                      <div className="text-xs text-muted-foreground">
                        {source.locationCode} — {source.locationDisplayName}
                      </div>

                      <div className="text-xs text-muted-foreground">
                        Расстояние: {source.distanceLabel}
                      </div>
                    </div>

                    <Button
                      size="sm"
                      disabled={
                        selectedRows.length === 0 || transferMutation.isPending
                      }
                      onClick={() =>
                        transferMutation.mutate(source.rentalItemId)
                      }
                    >
                      Переместить из бытовки
                    </Button>
                  </div>

                  {renderRows({
                    rows,
                    onToggle: (name, selected) =>
                      toggleSourceRow(source.rentalItemId, name, selected),
                    onChangeQuantity: (name, delta) =>
                      changeSourceQuantity(source.rentalItemId, name, delta),
                  })}
                </div>
              )
            })}
          </div>
        )}
      </section>

      <div className="flex justify-end border-t pt-3">
        <Button variant="outline" onClick={onClose}>
          Закрыть
        </Button>
      </div>
    </div>
  )
}
