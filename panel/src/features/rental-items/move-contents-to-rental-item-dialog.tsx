import { useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  ArrowRightLeft,
  Check,
  CheckSquare,
  ChevronDown,
  Minus,
  Plus,
  Search,
  Square,
} from "lucide-react"

import {
  getRentalItemsForContentsMove,
  moveRentalItemContentsToRentalItem,
  type MoveRentalItemContentsToRentalItemResult,
} from "@/features/rental-items/api/rental-items-api"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import {
  formatRentalItemContents,
  type MoveRentalItemContentToStockPayload,
  type PageResponse,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type MoveContentsToRentalItemDialogProps = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

type DraftMoveRow = {
  name: string
  maxQuantity: number
  quantity: number
  selected: boolean
}

export function MoveContentsToRentalItemDialog({
  item,
  open,
  onOpenChange,
}: MoveContentsToRentalItemDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            Переместить наполнение в другую бытовку
            {item ? ` — ${item.number}` : ""}
          </DialogTitle>
        </DialogHeader>

        {item ? (
          <MoveContentsToRentalItemDialogContent
            key={getDialogStateKey(item, open)}
            item={item}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function getDialogStateKey(item: RentalItemDto, open: boolean) {
  const contentsKey = item.contentsItems
    .map((contentItem) => `${contentItem.name}:${contentItem.quantity}`)
    .join("|")

  return `${item.id}:${open ? "open" : "closed"}:${contentsKey}`
}

function updateRentalItemsPageCache(
  oldData: PageResponse<RentalItemDto> | undefined,
  result: MoveRentalItemContentsToRentalItemResult
) {
  if (!oldData) {
    return oldData
  }

  return {
    ...oldData,
    content: oldData.content.map((item) => {
      if (item.id === result.sourceItem.id) {
        return result.sourceItem
      }

      if (item.id === result.targetItem.id) {
        return result.targetItem
      }

      return item
    }),
  }
}

function MoveContentsToRentalItemDialogContent({
  item,
  onClose,
}: {
  item: RentalItemDto
  onClose: () => void
}) {
  const queryClient = useQueryClient()

  const [targetSearch, setTargetSearch] = useState("")
  const [targetRentalItemId, setTargetRentalItemId] = useState<string | null>(
    null
  )
  const [targetDropdownOpen, setTargetDropdownOpen] = useState(false)

  const [draftRows, setDraftRows] = useState<DraftMoveRow[]>(() =>
    item.contentsItems.map((contentItem) => ({
      name: contentItem.name,
      maxQuantity: contentItem.quantity,
      quantity: contentItem.quantity,
      selected: false,
    }))
  )

  const targetItemsQuery = useQuery({
    queryKey: ["rental-items-for-contents-move", item.warehouseId, item.id],
    queryFn: () =>
      getRentalItemsForContentsMove({
        warehouseId: item.warehouseId,
        sourceRentalItemId: item.id,
      }),
  })

  const targetItems = useMemo(() => {
    return targetItemsQuery.data ?? []
  }, [targetItemsQuery.data])

  const filteredTargetItems = useMemo(() => {
    const normalizedSearch = targetSearch.trim().toLowerCase()

    const filteredItems = normalizedSearch
      ? targetItems.filter((targetItem) => {
          const contentsText = formatRentalItemContents(
            targetItem.contentsItems,
            targetItem.contents
          )

          return [
            targetItem.number,
            targetItem.type,
            targetItem.dimensions,
            targetItem.finishing,
            targetItem.category,
            contentsText,
          ]
            .filter(Boolean)
            .some((value) =>
              String(value).toLowerCase().includes(normalizedSearch)
            )
        })
      : targetItems

    return filteredItems.slice(0, 12)
  }, [targetItems, targetSearch])

  const selectedTargetItem =
    targetItems.find((targetItem) => targetItem.id === targetRentalItemId) ??
    null

  const selectedPayload: MoveRentalItemContentToStockPayload[] = draftRows
    .filter((row) => row.selected && row.quantity > 0)
    .map((row) => ({
      name: row.name,
      quantity: Math.min(row.quantity, row.maxQuantity),
    }))

  const allSelected =
    draftRows.length > 0 && draftRows.every((row) => row.selected)

  const moveMutation = useMutation({
    mutationFn: () => {
      if (!targetRentalItemId) {
        return Promise.resolve(null)
      }

      return moveRentalItemContentsToRentalItem({
        sourceRentalItemId: item.id,
        targetRentalItemId,
        payload: selectedPayload,
      })
    },
    onSuccess: (result) => {
      if (!result) {
        return
      }

      queryClient.setQueryData(
        ["rental-item", result.sourceItem.id],
        result.sourceItem
      )
      queryClient.setQueryData(
        ["rental-item", result.targetItem.id],
        result.targetItem
      )

      queryClient.setQueriesData<PageResponse<RentalItemDto>>(
        {
          queryKey: ["rental-items"],
        },
        (oldData) => updateRentalItemsPageCache(oldData, result)
      )

      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", result.sourceItem.id],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", result.targetItem.id],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items-for-contents-move"],
      })

      onClose()
    },
  })

  function selectTargetItem(targetItem: RentalItemDto) {
    setTargetRentalItemId(targetItem.id)
    setTargetSearch(targetItem.number)
    setTargetDropdownOpen(false)
  }

  function toggleRow(name: string, selected: boolean) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          selected,
          quantity:
            selected && row.quantity === 0 ? row.maxQuantity : row.quantity,
        }
      })
    )
  }

  function toggleAll() {
    setDraftRows((currentRows) =>
      currentRows.map((row) => ({
        ...row,
        selected: !allSelected,
        quantity:
          !allSelected && row.quantity === 0 ? row.maxQuantity : row.quantity,
      }))
    )
  }

  function decreaseQuantity(name: string) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        const nextQuantity = Math.max(0, row.quantity - 1)

        return {
          ...row,
          quantity: nextQuantity,
          selected: nextQuantity > 0 ? row.selected : false,
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

        const nextQuantity = Math.min(row.maxQuantity, row.quantity + 1)

        return {
          ...row,
          quantity: nextQuantity,
          selected: nextQuantity > 0,
        }
      })
    )
  }

  if (draftRows.length === 0) {
    return (
      <div className="grid gap-4">
        <div className="rounded-md border bg-muted/30 p-4 text-sm text-muted-foreground">
          В бытовке нет наполнения для перемещения.
        </div>

        <div className="flex justify-end">
          <Button variant="outline" onClick={onClose}>
            Закрыть
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="grid gap-4">
      <div className="grid gap-2">
        <div className="text-sm font-medium">Куда переместить</div>

        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 z-10 size-4 -translate-y-1/2 text-muted-foreground" />

          <Input
            value={targetSearch}
            onFocus={() => setTargetDropdownOpen(true)}
            onChange={(event) => {
              setTargetSearch(event.target.value)
              setTargetRentalItemId(null)
              setTargetDropdownOpen(true)
            }}
            placeholder="Введите номер бытовки..."
            className="pr-10 pl-9"
          />

          <button
            type="button"
            className="absolute top-1/2 right-2 z-10 inline-flex size-7 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={() => setTargetDropdownOpen((current) => !current)}
          >
            <ChevronDown
              className={[
                "size-4 transition-transform",
                targetDropdownOpen ? "rotate-180" : "",
              ].join(" ")}
            />
          </button>

          {targetDropdownOpen && (
            <div className="absolute top-[calc(100%+4px)] right-0 left-0 z-50 overflow-hidden rounded-md border bg-popover text-popover-foreground shadow-md">
              <div className="max-h-64 overflow-auto">
                {targetItemsQuery.isLoading ? (
                  <div className="p-3 text-sm text-muted-foreground">
                    Загрузка бытовок...
                  </div>
                ) : filteredTargetItems.length === 0 ? (
                  <div className="p-3 text-sm text-muted-foreground">
                    Бытовки не найдены.
                  </div>
                ) : (
                  filteredTargetItems.map((targetItem) => {
                    const selected = targetItem.id === targetRentalItemId

                    return (
                      <button
                        key={targetItem.id}
                        type="button"
                        className={[
                          "grid w-full gap-1 border-b px-3 py-2 text-left text-sm last:border-b-0 hover:bg-muted/70",
                          selected ? "bg-primary/10" : "",
                        ].join(" ")}
                        onMouseDown={(event) => {
                          event.preventDefault()
                          selectTargetItem(targetItem)
                        }}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex min-w-0 items-center gap-2">
                            <span className="font-medium">
                              {targetItem.number}
                            </span>

                            {selected ? (
                              <Check className="size-4 text-primary" />
                            ) : null}
                          </div>

                          <span className="text-xs text-muted-foreground">
                            {targetItem.type}
                          </span>
                        </div>

                        <div className="truncate text-xs text-muted-foreground">
                          {targetItem.contentsItems.length > 0
                            ? formatRentalItemContents(
                                targetItem.contentsItems,
                                targetItem.contents
                              )
                            : "Наполнение не указано"}
                        </div>
                      </button>
                    )
                  })
                )}
              </div>
            </div>
          )}
        </div>

        {selectedTargetItem ? (
          <div className="rounded-md border border-primary/40 bg-primary/5 p-3 text-sm">
            <div className="font-medium">
              Выбрано: {selectedTargetItem.number}
            </div>

            <div className="text-xs text-muted-foreground">
              {selectedTargetItem.type}
              {selectedTargetItem.contentsItems.length > 0
                ? ` · ${formatRentalItemContents(
                    selectedTargetItem.contentsItems,
                    selectedTargetItem.contents
                  )}`
                : " · Наполнение не указано"}
            </div>
          </div>
        ) : null}
      </div>

      <Button
        type="button"
        variant="outline"
        className="w-fit"
        onClick={toggleAll}
      >
        {allSelected ? (
          <CheckSquare className="mr-2 size-4" />
        ) : (
          <Square className="mr-2 size-4" />
        )}
        Выбрать всё
      </Button>

      <div className="max-h-[320px] overflow-auto rounded-md border">
        {draftRows.map((row) => (
          <div
            key={row.name}
            className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 border-b px-3 py-2 text-sm last:border-b-0"
          >
            <Checkbox
              checked={row.selected}
              onCheckedChange={(value) => toggleRow(row.name, value === true)}
            />

            <div className="min-w-0">
              <div className="truncate font-medium">{row.name}</div>
              <div className="text-xs text-muted-foreground">
                В бытовке: {row.maxQuantity} шт.
              </div>
            </div>

            <div className="flex items-center gap-2 whitespace-nowrap">
              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="size-7"
                disabled={row.quantity <= 0}
                onClick={() => decreaseQuantity(row.name)}
              >
                <Minus className="size-3.5" />
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
                disabled={row.quantity >= row.maxQuantity}
                onClick={() => increaseQuantity(row.name)}
              >
                <Plus className="size-3.5" />
              </Button>
            </div>
          </div>
        ))}
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose}>
          Отмена
        </Button>

        <Button
          disabled={
            !targetRentalItemId ||
            selectedPayload.length === 0 ||
            moveMutation.isPending
          }
          onClick={() => moveMutation.mutate()}
        >
          <ArrowRightLeft className="mr-2 size-4" />
          Переместить
        </Button>
      </div>
    </div>
  )
}
