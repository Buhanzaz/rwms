import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { CheckSquare, Minus, Plus, Square } from "lucide-react"

import { moveRentalItemEquipmentToStock } from "@/api/equipment-api"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type {
  MoveRentalItemContentToStockPayload,
  PageResponse,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type MoveContentsToStockDialogProps = {
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

export function MoveContentsToStockDialog({
  item,
  open,
  onOpenChange,
}: MoveContentsToStockDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>
            Переместить наполнение на склад
            {item ? ` — ${item.number}` : ""}
          </DialogTitle>
        </DialogHeader>

        {item ? (
          <MoveContentsToStockDialogContent
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
  updatedItem: RentalItemDto
) {
  if (!oldData) {
    return oldData
  }

  return {
    ...oldData,
    content: oldData.content.map((item) => {
      return item.id === updatedItem.id ? updatedItem : item
    }),
  }
}

function MoveContentsToStockDialogContent({
  item,
  onClose,
}: {
  item: RentalItemDto
  onClose: () => void
}) {
  const queryClient = useQueryClient()

  const [draftRows, setDraftRows] = useState<DraftMoveRow[]>(() =>
    item.contentsItems.map((contentItem) => ({
      name: contentItem.name,
      maxQuantity: contentItem.quantity,
      quantity: contentItem.quantity,
      selected: false,
    }))
  )

  const selectedPayload: MoveRentalItemContentToStockPayload[] = draftRows
    .filter((row) => {
      return row.selected && row.quantity > 0
    })
    .map((row) => ({
      name: row.name,
      quantity: Math.min(row.quantity, row.maxQuantity),
    }))

  const allSelected =
    draftRows.length > 0 &&
    draftRows.every((row) => {
      return row.selected
    })

  const moveMutation = useMutation({
    mutationFn: () => {
      return moveRentalItemEquipmentToStock(item.id, selectedPayload)
    },
    onSuccess: (updatedItem) => {
      if (!updatedItem) {
        return
      }

      queryClient.setQueryData(["rental-item", item.id], updatedItem)

      queryClient.setQueriesData<PageResponse<RentalItemDto>>(
        {
          queryKey: ["rental-items"],
        },
        (oldData) => updateRentalItemsPageCache(oldData, updatedItem)
      )

      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item", item.id],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item"],
      })

      onClose()
    },
  })

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

      <div className="max-h-[360px] overflow-auto rounded-md border">
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
          disabled={selectedPayload.length === 0 || moveMutation.isPending}
          onClick={() => moveMutation.mutate()}
        >
          Переместить
        </Button>
      </div>
    </div>
  )
}
