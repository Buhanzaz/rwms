import {
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type RefObject,
  type SetStateAction,
} from "react"
import {
  Add01Icon,
  Exchange01Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { hasUnresolvedReturnEquipmentDispositionForRentalItem } from "@/api/equipment-api"
import {
  addInventoryFromWarehouseStock,
  getRentalItemInventoryAddOptions,
} from "@/api/rental-item-inventory-api"
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
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { useAuth } from "@/features/auth/use-auth"
import {
  getRentalItemsForContentsMove,
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
} from "@/features/rental-items/api/rental-items-api"
import { transferRentalItemContentsWithTask } from "@/features/rental-items/contents-transfer/api/contents-transfer-api"
import { RENTAL_ITEM_DOSSIER_QUERY_KEY } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type {
  MoveRentalItemContentToStockPayload,
  PageResponse,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"
import type { WarehouseInventoryStockItemDto } from "@/types/warehouse-location"

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
  const dialogContentRef = useRef<HTMLDivElement>(null)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={dialogContentRef} className="max-w-3xl">
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
            portalContainer={dialogContentRef}
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
  if (!oldData) return oldData
  return {
    ...oldData,
    content: oldData.content.map(
      (item) =>
        updatedItems.find((updatedItem) => updatedItem.id === item.id) ?? item
    ),
  }
}

function buildDraftRows(
  items: WarehouseInventoryStockItemDto[],
  stateByName: Record<string, RowDraftState>
): DraftRow[] {
  return items.map((item) => ({
    name: item.name,
    availableQuantity: item.availableQuantity,
    quantity: stateByName[item.name]?.quantity ?? 0,
    selected: stateByName[item.name]?.selected ?? false,
  }))
}

function sourceDraftRows(source: RentalItemDto | null): DraftRow[] {
  return (source?.contentsItems ?? [])
    .filter((entry) => entry.quantity > 0)
    .map((entry) => ({
      name: entry.name,
      availableQuantity: entry.quantity,
      quantity: 0,
      selected: false,
    }))
}

function QuantityRows({
  rows,
  onToggle,
  onChangeQuantity,
}: {
  rows: DraftRow[]
  onToggle: (name: string, selected: boolean) => void
  onChangeQuantity: (name: string, delta: number) => void
}) {
  if (rows.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">Доступных позиций нет.</p>
    )
  }
  return (
    <div className="overflow-hidden rounded-md border">
      {rows.map((row) => (
        <div
          key={row.name}
          className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 border-b px-3 py-2 text-sm last:border-b-0"
        >
          <Checkbox
            aria-label={`Выбрать ${row.name}`}
            checked={row.selected}
            onCheckedChange={(value) => onToggle(row.name, value === true)}
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
              size="icon-sm"
              variant="ghost"
              disabled={row.quantity <= 0}
              aria-label={`Уменьшить ${row.name}`}
              onClick={() => onChangeQuantity(row.name, -1)}
            >
              <HugeiconsIcon icon={MinusSignIcon} />
            </Button>
            <div className="min-w-12 text-center">
              <span className="font-semibold">{row.quantity}</span> шт.
            </div>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              disabled={row.quantity >= row.availableQuantity}
              aria-label={`Увеличить ${row.name}`}
              onClick={() => onChangeQuantity(row.name, 1)}
            >
              <HugeiconsIcon icon={Add01Icon} />
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

function AddContentsDialogContent({
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
  const [stockStateByName, setStockStateByName] = useState<
    Record<string, RowDraftState>
  >({})
  const [sourceId, setSourceId] = useState<string | null>(null)
  const [sourceRows, setSourceRows] = useState<DraftRow[]>([])
  const [errorText, setErrorText] = useState<string | null>(null)
  const externalTaskIdRef = useRef<string | null>(null)
  const targetFrozen = hasUnresolvedReturnEquipmentDispositionForRentalItem(
    item.id
  )
  const targetEligible =
    !targetFrozen &&
    RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(item.status)

  const addOptionsQuery = useQuery({
    queryKey: ["rental-item-inventory-add-options", item.id],
    queryFn: () => getRentalItemInventoryAddOptions(item.id),
  })
  const sourcesQuery = useQuery({
    queryKey: [
      "rental-item-contents-transfer-sources",
      item.warehouseId,
      item.id,
    ],
    queryFn: () =>
      getRentalItemsForContentsMove({
        warehouseId: item.warehouseId,
        sourceRentalItemId: item.id,
        requireContents: true,
      }),
    enabled: targetEligible,
  })
  const sources = useMemo(() => sourcesQuery.data ?? [], [sourcesQuery.data])
  const selectedSource =
    sources.find((source) => source.id === sourceId) ?? null
  const stockRows = useMemo(
    () =>
      buildDraftRows(
        addOptionsQuery.data?.warehouseStock ?? [],
        stockStateByName
      ),
    [addOptionsQuery.data, stockStateByName]
  )
  const selectedStockItems = selectedItems(stockRows)
  const selectedSourceItems = selectedItems(sourceRows)

  function invalidateRentalItems(updatedItems: RentalItemDto[]) {
    updatedItems.forEach((updatedItem) =>
      queryClient.setQueryData(["rental-item", updatedItem.id], updatedItem)
    )
    updatedItems.forEach((updatedItem) => {
      void queryClient.invalidateQueries({
        queryKey: ["rental-item", updatedItem.id],
      })
    })
    queryClient.setQueriesData<PageResponse<RentalItemDto>>(
      { queryKey: ["rental-items"] },
      (oldData) => updateRentalItemsPageCache(oldData, updatedItems)
    )
    void queryClient.invalidateQueries({ queryKey: ["equipment-items"] })
    void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
    void queryClient.invalidateQueries({
      queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
    })
    void queryClient.invalidateQueries({
      queryKey: ["rental-item-contents-transfer-sources"],
    })
    void queryClient.invalidateQueries({
      queryKey: ["rental-item-inventory-add-options"],
    })
  }

  const addFromStockMutation = useMutation({
    mutationFn: () => {
      if (targetFrozen) {
        throw new Error(
          "Оборудование бытовки ожидает решения в разделе списания"
        )
      }
      return addInventoryFromWarehouseStock({
        targetRentalItemId: item.id,
        payload: { items: selectedStockItems },
      })
    },
    onSuccess: (updatedItem) => {
      invalidateRentalItems([updatedItem])
      toast.success("Наполнение добавлено")
      onClose()
    },
    onError: showError,
  })

  const transferMutation = useMutation({
    mutationFn: () => {
      if (!selectedSource) throw new Error("Выберите бытовку-источник")
      if (!accessToken || !selectedWarehouse?.id) {
        throw new Error("Сервис заданий сейчас недоступен")
      }
      if (
        targetFrozen ||
        hasUnresolvedReturnEquipmentDispositionForRentalItem(selectedSource.id)
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
          rentalItemId: selectedSource.id,
          number: selectedSource.number,
          expectedVersion: selectedSource.version,
        },
        target: {
          rentalItemId: item.id,
          number: item.number,
          expectedVersion: item.version,
        },
        items: selectedSourceItems,
      })
    },
    onSuccess: (result) => {
      invalidateRentalItems([result.sourceItem, result.targetItem])
      toast.success("Задание создано, наполнение перемещено")
      onClose()
    },
    onError: showError,
  })

  function showError(error: unknown) {
    setErrorText(
      error instanceof Error ? error.message : "Не удалось выполнить операцию"
    )
  }

  function updateRows(
    setter: Dispatch<SetStateAction<DraftRow[]>>,
    name: string,
    change: (row: DraftRow) => Pick<DraftRow, "quantity" | "selected">
  ) {
    externalTaskIdRef.current = null
    setter((current) =>
      current.map((row) =>
        row.name === name ? { ...row, ...change(row) } : row
      )
    )
  }

  function stockToggle(name: string, selected: boolean) {
    setStockStateByName((current) => {
      const row = current[name]
      return {
        ...current,
        [name]: {
          selected,
          quantity:
            selected && (row?.quantity ?? 0) === 0 ? 1 : (row?.quantity ?? 0),
        },
      }
    })
  }

  function stockQuantity(name: string, delta: number) {
    const row = stockRows.find((candidate) => candidate.name === name)
    if (!row) return
    setStockStateByName((current) => {
      const quantity = Math.min(
        row.availableQuantity,
        Math.max(0, (current[name]?.quantity ?? 0) + delta)
      )
      return { ...current, [name]: { quantity, selected: quantity > 0 } }
    })
  }

  return (
    <div className="flex max-h-[75vh] flex-col gap-5 overflow-auto pr-1">
      {targetFrozen ? (
        <p role="alert" className="text-sm text-destructive">
          Оборудование бытовки ожидает решения в разделе списания.
        </p>
      ) : errorText ? (
        <p role="alert" className="text-sm text-destructive">
          {errorText}
        </p>
      ) : null}

      <section className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 className="font-medium">Доступно на складе</h3>
          <Button
            disabled={
              targetFrozen ||
              selectedStockItems.length === 0 ||
              addFromStockMutation.isPending
            }
            onClick={() => addFromStockMutation.mutate()}
          >
            Добавить со склада
          </Button>
        </div>
        {addOptionsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загрузка склада…</p>
        ) : addOptionsQuery.isError ? (
          <p role="alert" className="text-sm text-destructive">
            Не удалось загрузить остатки склада.
          </p>
        ) : (
          <QuantityRows
            rows={stockRows}
            onToggle={stockToggle}
            onChangeQuantity={stockQuantity}
          />
        )}
      </section>

      {targetEligible ? (
        <section className="flex flex-col gap-3 border-t pt-4">
          <h3 className="font-medium">Переместить из другой бытовки</h3>
          <Field>
            <FieldLabel htmlFor="contents-transfer-source">
              Бытовка-источник
            </FieldLabel>
            {sourcesQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">Загрузка бытовок…</p>
            ) : sourcesQuery.isError ? (
              <p role="alert" className="text-sm text-destructive">
                Не удалось загрузить бытовки.
              </p>
            ) : (
              <Combobox
                items={sources.map((source) => source.number)}
                value={selectedSource?.number ?? null}
                onValueChange={(value) => {
                  const source =
                    sources.find((candidate) => candidate.number === value) ??
                    null
                  setSourceId(source?.id ?? null)
                  setSourceRows(sourceDraftRows(source))
                  externalTaskIdRef.current = null
                  setErrorText(null)
                }}
              >
                <ComboboxInput
                  id="contents-transfer-source"
                  placeholder="Введите номер бытовки"
                />
                <ComboboxContent portalContainer={portalContainer}>
                  <ComboboxList>
                    <ComboboxEmpty>
                      Бытовки с наполнением не найдены
                    </ComboboxEmpty>
                    <ComboboxGroup>
                      {sources.map((source) => (
                        <ComboboxItem key={source.id} value={source.number}>
                          <span>{source.number}</span>
                          <span className="text-xs text-muted-foreground">
                            {source.contentsItems.length} поз.
                          </span>
                        </ComboboxItem>
                      ))}
                    </ComboboxGroup>
                  </ComboboxList>
                </ComboboxContent>
              </Combobox>
            )}
            <FieldDescription>
              Только активные бытовки этого склада, в которых есть наполнение.
            </FieldDescription>
          </Field>
          {selectedSource ? (
            <>
              <p className="text-sm font-medium">
                {selectedSource.number} → {item.number}
              </p>
              <QuantityRows
                rows={sourceRows}
                onToggle={(name, selected) =>
                  updateRows(setSourceRows, name, (row) => ({
                    selected,
                    quantity: selected && row.quantity === 0 ? 1 : row.quantity,
                  }))
                }
                onChangeQuantity={(name, delta) =>
                  updateRows(setSourceRows, name, (row) => {
                    const quantity = Math.min(
                      row.availableQuantity,
                      Math.max(0, row.quantity + delta)
                    )
                    return { quantity, selected: quantity > 0 }
                  })
                }
              />
              <Button
                className="w-fit"
                disabled={
                  selectedSourceItems.length === 0 || transferMutation.isPending
                }
                onClick={() => transferMutation.mutate()}
              >
                <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
                Переместить из бытовки
              </Button>
            </>
          ) : null}
        </section>
      ) : !targetFrozen ? (
        <p className="border-t pt-4 text-sm text-muted-foreground">
          Перемещение из другой бытовки недоступно для текущего статуса.
        </p>
      ) : null}

      <div className="flex justify-end border-t pt-3">
        <Button variant="outline" onClick={onClose}>
          Закрыть
        </Button>
      </div>
    </div>
  )
}

function selectedItems(
  rows: DraftRow[]
): MoveRentalItemContentToStockPayload[] {
  return rows
    .filter((row) => row.selected && row.quantity > 0)
    .map((row) => ({ name: row.name, quantity: row.quantity }))
}
