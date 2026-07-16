import { useRef, useState, type RefObject } from "react"
import {
  Add01Icon,
  Exchange01Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { hasUnresolvedReturnEquipmentDispositionForRentalItem } from "@/api/equipment-api"
import {
  getRentalItemsForContentsMove,
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
  type MoveRentalItemContentsToRentalItemResult,
} from "@/features/rental-items/api/rental-items-api"
import { transferRentalItemContentsWithTask } from "@/features/rental-items/contents-transfer/api/contents-transfer-api"
import { RENTAL_ITEM_DOSSIER_QUERY_KEY } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import { useAuth } from "@/features/auth/use-auth"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  formatRentalItemContents,
  type MoveRentalItemContentToStockPayload,
  type PageResponse,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"

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
  const dialogContentRef = useRef<HTMLDivElement>(null)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={dialogContentRef} className="max-w-2xl">
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
            portalContainer={dialogContentRef}
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
  portalContainer,
  onClose,
}: {
  item: RentalItemDto
  portalContainer: RefObject<HTMLDivElement | null>
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { currentUser, accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const externalTaskIdRef = useRef<string | null>(null)

  const [targetRentalItemId, setTargetRentalItemId] = useState<string | null>(
    null
  )
  const [errorText, setErrorText] = useState<string | null>(null)
  const sourceFrozen = hasUnresolvedReturnEquipmentDispositionForRentalItem(
    item.id
  )
  const sourceEligible =
    !sourceFrozen &&
    item.contentsItems.length > 0 &&
    RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(item.status)

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
    enabled: sourceEligible,
  })

  const targetItems = targetItemsQuery.data ?? []

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
      if (!selectedTargetItem) {
        return Promise.resolve(null)
      }
      if (!accessToken || !selectedWarehouse?.id) {
        throw new Error("Сервис заданий сейчас недоступен")
      }
      if (
        sourceFrozen ||
        hasUnresolvedReturnEquipmentDispositionForRentalItem(
          selectedTargetItem.id
        )
      ) {
        throw new Error(
          "Оборудование выбранной бытовки ожидает решения в разделе списания"
        )
      }

      externalTaskIdRef.current ??= crypto.randomUUID()
      return transferRentalItemContentsWithTask({
        externalTaskId: externalTaskIdRef.current,
        warehouseId: item.warehouseId,
        serviceWarehouseId: selectedWarehouse.id,
        accessToken,
        actor: {
          id: currentUser?.id ?? null,
          displayName:
            currentUser?.displayName ||
            currentUser?.username ||
            "Текущий пользователь",
        },
        source: {
          rentalItemId: item.id,
          number: item.number,
          expectedVersion: item.version,
        },
        target: {
          rentalItemId: selectedTargetItem.id,
          number: selectedTargetItem.number,
          expectedVersion: selectedTargetItem.version,
        },
        items: selectedPayload,
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
      queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
      })

      onClose()
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Не удалось переместить"
      )
    },
  })

  function selectTargetItem(targetItem: RentalItemDto) {
    externalTaskIdRef.current = null
    setTargetRentalItemId(targetItem.id)
  }

  function toggleRow(name: string, selected: boolean) {
    externalTaskIdRef.current = null
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
    externalTaskIdRef.current = null
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
    externalTaskIdRef.current = null
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
    externalTaskIdRef.current = null
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

  if (!sourceEligible) {
    return (
      <div className="grid gap-4">
        <p className="text-sm text-muted-foreground">
          Перемещение недоступно для текущего статуса бытовки.
        </p>
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
      <Field>
        <FieldLabel htmlFor="move-contents-target">Куда переместить</FieldLabel>
        {targetItemsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загрузка бытовок…</p>
        ) : targetItemsQuery.isError ? (
          <p role="alert" className="text-sm text-destructive">
            Не удалось загрузить бытовки.
          </p>
        ) : (
          <Combobox
            items={targetItems.map((targetItem) => targetItem.number)}
            value={selectedTargetItem?.number ?? null}
            onValueChange={(value) => {
              const target =
                targetItems.find((candidate) => candidate.number === value) ??
                null
              if (target) selectTargetItem(target)
              else setTargetRentalItemId(null)
            }}
          >
            <ComboboxInput
              id="move-contents-target"
              placeholder="Введите номер бытовки"
            />
            <ComboboxContent portalContainer={portalContainer}>
              <ComboboxList>
                <ComboboxEmpty>Бытовки не найдены</ComboboxEmpty>
                <ComboboxGroup>
                  {targetItems.map((targetItem) => (
                    <ComboboxItem key={targetItem.id} value={targetItem.number}>
                      <span>{targetItem.number}</span>
                      <span className="text-xs text-muted-foreground">
                        {targetItem.type}
                      </span>
                    </ComboboxItem>
                  ))}
                </ComboboxGroup>
              </ComboboxList>
            </ComboboxContent>
          </Combobox>
        )}
      </Field>

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

      <Button
        type="button"
        variant="outline"
        className="w-fit"
        onClick={toggleAll}
      >
        Выбрать всё
      </Button>

      <div className="max-h-[320px] overflow-auto rounded-md border">
        {draftRows.map((row) => (
          <div
            key={row.name}
            className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 border-b px-3 py-2 text-sm last:border-b-0"
          >
            <Checkbox
              aria-label={`Выбрать ${row.name}`}
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
                aria-label={`Уменьшить ${row.name}`}
                onClick={() => decreaseQuantity(row.name)}
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
                disabled={row.quantity >= row.maxQuantity}
                aria-label={`Увеличить ${row.name}`}
                onClick={() => increaseQuantity(row.name)}
              >
                <HugeiconsIcon icon={Add01Icon} />
              </Button>
            </div>
          </div>
        ))}
      </div>

      <div className="flex justify-end gap-2">
        {sourceFrozen ? (
          <p role="alert" className="mr-auto text-sm text-destructive">
            Оборудование ожидает решения в разделе списания.
          </p>
        ) : errorText ? (
          <p role="alert" className="mr-auto text-sm text-destructive">
            {errorText}
          </p>
        ) : null}
        <Button variant="outline" onClick={onClose}>
          Отмена
        </Button>

        <Button
          disabled={
            !targetRentalItemId ||
            sourceFrozen ||
            selectedPayload.length === 0 ||
            moveMutation.isPending
          }
          onClick={() => moveMutation.mutate()}
        >
          <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
          Переместить
        </Button>
      </div>
    </div>
  )
}
