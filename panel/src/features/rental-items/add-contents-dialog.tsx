import {
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type RefObject,
  type SetStateAction,
} from "react"
import { Exchange01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { getEquipmentItems } from "@/api/equipment-api"
import { Button } from "@/components/ui/button"
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
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field"
import { Separator } from "@/components/ui/separator"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import { RentalItemContentsQuantityRows } from "@/features/rental-items/rental-item-contents-quantity-rows"
import {
  cabinLocationKind,
  canTransferRentalItemContents,
  createRentalItemContentsTransferInput,
  eligibleRentalItemContentsTargets,
  executeRentalItemContentsTransferBatch,
  formatRentalItemContentsSourceSummary,
  formatRentalItemContentsTransferError,
  invalidateRentalItemContentsQueries,
  rentalItemContentsTransferLineKey,
  rentalItemContentsTransferRows,
  RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
  RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY,
  warehouseStockTransferRows,
  type RentalItemContentsTransferRow,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

type Props = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}
type Draft = { quantity: number; selected: boolean }

export function AddContentsDialog({ item, open, onOpenChange }: Props) {
  const contentRef = useRef<HTMLDivElement>(null)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={contentRef} className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            Добавить наполнение{item ? ` — ${item.number}` : ""}
          </DialogTitle>
          <DialogDescription>
            Выберите оборудование на складе или перенесите его из другой
            бытовки. Остатки обновятся сразу после перемещения.
          </DialogDescription>
        </DialogHeader>
        {item && open ? (
          <Content
            key={`${item.id}:open`}
            item={item}
            portalContainer={contentRef}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function Content({
  item,
  portalContainer,
  onClose,
}: {
  item: RentalItemDto
  portalContainer: RefObject<HTMLDivElement | null>
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const canManage = hasWarehouseAccess(currentUser, item.warehouseId, "MANAGE")
  const targetEligible = canTransferRentalItemContents(item)
  const [stockDraft, setStockDraft] = useState<Record<string, Draft>>({})
  const [cabinDraft, setCabinDraft] = useState<Record<string, Draft>>({})
  const [sourceId, setSourceId] = useState<string | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKeys = useRef(new Map<string, string>())
  const completedLines = useRef(new Set<string>())

  const equipmentQuery = useQuery({
    queryKey: [...RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY, item.warehouseId],
    queryFn: () =>
      getEquipmentItems(accessToken, { warehouseId: item.warehouseId }),
    enabled: canManage && Boolean(accessToken),
    refetchInterval: 2_000,
  })
  const sourcesQuery = useQuery({
    queryKey: [...RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY, item.warehouseId],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId: item.warehouseId,
        page: 0,
        size: 200,
      }),
    enabled: canManage && targetEligible && Boolean(accessToken),
  })
  const equipment = useMemo(
    () => equipmentQuery.data ?? [],
    [equipmentQuery.data]
  )
  const sourceItems = useMemo(
    () =>
      eligibleRentalItemContentsTargets({
        items: sourcesQuery.data?.content ?? [],
        sourceRentalItemId: item.id,
        requireContents: true,
        equipmentItems: equipment,
      }),
    [equipment, item.id, sourcesQuery.data]
  )
  const selectedSource =
    sourceItems.find((source) => source.id === sourceId) ?? null
  const stockSourceRows = useMemo(
    () => warehouseStockTransferRows(equipment),
    [equipment]
  )
  const cabinSourceRows = useMemo(
    () =>
      selectedSource
        ? rentalItemContentsTransferRows(equipment, selectedSource.id)
        : [],
    [equipment, selectedSource]
  )
  const stockRows = useMemo(
    () => rowsWithDraft(stockSourceRows, stockDraft, 0),
    [stockDraft, stockSourceRows]
  )
  const cabinRows = useMemo(
    () => rowsWithDraft(cabinSourceRows, cabinDraft, 0),
    [cabinDraft, cabinSourceRows]
  )
  const selectedStock = stockRows.filter(
    (row) => row.selected && row.quantity > 0
  )
  const selectedCabin = cabinRows.filter(
    (row) => row.selected && row.quantity > 0
  )

  function line(row: RentalItemContentsTransferRow & { quantity: number }) {
    const input = createRentalItemContentsTransferInput({
      row,
      targetWarehouseId: item.warehouseId,
      targetRentalItemId: item.id,
      targetLocationKind: cabinLocationKind(item),
      quantity: row.quantity,
    })
    return { lineKey: rentalItemContentsTransferLineKey(input), input }
  }

  function clearCompleted(row: RentalItemContentsTransferRow) {
    completedLines.current.delete(line({ ...row, quantity: 1 }).lineKey)
  }

  async function refresh() {
    await invalidateRentalItemContentsQueries(queryClient)
  }

  function fail(error: unknown) {
    setErrorText(formatRentalItemContentsTransferError(error))
    void refresh()
  }

  function transfer(
    rows: Array<RentalItemContentsTransferRow & { quantity: number }>,
    setDraft: Dispatch<SetStateAction<Record<string, Draft>>>
  ) {
    if (!accessToken) throw new Error("Сессия завершена.")
    if (!canManage) {
      throw new Error(
        "Для управления наполнением нужен доступ MANAGE к складу."
      )
    }
    if (!targetEligible) {
      throw new Error("Бытовка недоступна для изменения наполнения.")
    }
    return executeRentalItemContentsTransferBatch({
      accessToken,
      lines: rows.map(line),
      idempotencyKeys: idempotencyKeys.current,
      completedLineKeys: completedLines.current,
      onLineCompleted: (completed) =>
        setDraft((current) => ({
          ...current,
          [completed.input.equipmentId]: { quantity: 0, selected: false },
        })),
    })
  }

  const stockMutation = useMutation({
    mutationFn: () => transfer(selectedStock, setStockDraft),
    onSuccess: async () => {
      await refresh()
      toast.success("Оборудование добавлено в бытовку.")
      onClose()
    },
    onError: fail,
  })
  const cabinMutation = useMutation({
    mutationFn: () => {
      if (!selectedSource) throw new Error("Выберите бытовку-источник.")
      return transfer(selectedCabin, setCabinDraft)
    },
    onSuccess: async () => {
      await refresh()
      toast.success("Оборудование перемещено в бытовку.")
      onClose()
    },
    onError: fail,
  })

  function update(
    rows: Array<RentalItemContentsTransferRow & { quantity: number }>,
    setDraft: Dispatch<SetStateAction<Record<string, Draft>>>,
    equipmentId: string,
    selectedOrDelta: boolean | number
  ) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
    clearCompleted(row)
    setErrorText(null)
    setDraft((current) => {
      if (typeof selectedOrDelta === "boolean") {
        return {
          ...current,
          [equipmentId]: {
            selected: selectedOrDelta,
            quantity: selectedOrDelta && row.quantity === 0 ? 1 : row.quantity,
          },
        }
      }
      const quantity = Math.min(
        row.availableQuantity,
        Math.max(0, row.quantity + selectedOrDelta)
      )
      return {
        ...current,
        [equipmentId]: { quantity, selected: quantity > 0 },
      }
    })
  }

  if (!canManage || !targetEligible) {
    return (
      <DialogFooter>
        <FieldError className="mr-auto">
          {!canManage
            ? "Для управления наполнением нужен доступ MANAGE к складу."
            : "Бытовка недоступна для изменения наполнения в текущем статусе."}
        </FieldError>
        <Button variant="outline" onClick={onClose}>
          Закрыть
        </Button>
      </DialogFooter>
    )
  }

  const pending = stockMutation.isPending || cabinMutation.isPending
  return (
    <div className="flex max-h-[75vh] flex-col gap-5 overflow-auto pr-1">
      {errorText ? <FieldError>{errorText}</FieldError> : null}
      <section className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 className="font-medium">Доступно на складе</h3>
          <Button
            disabled={selectedStock.length === 0 || pending}
            onClick={() => stockMutation.mutate()}
          >
            {stockMutation.isPending ? "Перемещение…" : "Добавить со склада"}
          </Button>
        </div>
        {equipmentQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загрузка склада…</p>
        ) : equipmentQuery.isError ? (
          <FieldError>
            {equipmentQuery.error instanceof Error
              ? equipmentQuery.error.message
              : "Не удалось загрузить остатки склада."}
          </FieldError>
        ) : (
          <RentalItemContentsQuantityRows
            idPrefix="add-from-stock"
            legend="Оборудование на складе"
            rows={stockRows}
            onToggle={(id, value) =>
              update(stockRows, setStockDraft, id, value)
            }
            onChangeQuantity={(id, delta) =>
              update(stockRows, setStockDraft, id, delta)
            }
          />
        )}
      </section>

      <Separator />

      <section className="flex flex-col gap-3">
        <h3 className="font-medium">Переместить из другой бытовки</h3>
        <Field>
          <FieldLabel htmlFor="contents-transfer-source">
            Бытовка-источник
          </FieldLabel>
          {sourcesQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Загрузка бытовок…</p>
          ) : sourcesQuery.isError ? (
            <FieldError>
              {sourcesQuery.error instanceof Error
                ? sourcesQuery.error.message
                : "Не удалось загрузить бытовки."}
            </FieldError>
          ) : (
            <Combobox
              items={sourceItems.map((source) => source.number)}
              value={selectedSource?.number ?? null}
              onValueChange={(value) => {
                setSourceId(
                  sourceItems.find((source) => source.number === value)?.id ??
                    null
                )
                setCabinDraft({})
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
                    {sourceItems.map((source) => {
                      const summary = formatRentalItemContentsSourceSummary(
                        source.number,
                        rentalItemContentsTransferRows(equipment, source.id)
                      )
                      return (
                        <ComboboxItem
                          key={source.id}
                          value={source.number}
                          className="min-w-0"
                        >
                          <span className="min-w-0 truncate" title={summary}>
                            {summary}
                          </span>
                        </ComboboxItem>
                      )
                    })}
                  </ComboboxGroup>
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
          )}
          <FieldDescription>
            Показан фактический состав бытовок этого склада.
          </FieldDescription>
        </Field>
        {selectedSource ? (
          <>
            <p className="text-sm font-medium">
              {selectedSource.number} → {item.number}
            </p>
            <RentalItemContentsQuantityRows
              idPrefix="add-from-rental-item"
              legend="Оборудование из другой бытовки"
              rows={cabinRows}
              onToggle={(id, value) =>
                update(cabinRows, setCabinDraft, id, value)
              }
              onChangeQuantity={(id, delta) =>
                update(cabinRows, setCabinDraft, id, delta)
              }
            />
            <Button
              className="w-fit"
              disabled={selectedCabin.length === 0 || pending}
              onClick={() => cabinMutation.mutate()}
            >
              <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
              {cabinMutation.isPending
                ? "Перемещение…"
                : "Переместить в бытовку"}
            </Button>
          </>
        ) : null}
      </section>
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          Закрыть
        </Button>
      </DialogFooter>
    </div>
  )
}

function rowsWithDraft(
  rows: RentalItemContentsTransferRow[],
  draft: Record<string, Draft>,
  defaultQuantity: number
) {
  return rows.map((row) => ({
    ...row,
    quantity: Math.min(
      row.availableQuantity,
      draft[row.equipmentId]?.quantity ?? defaultQuantity
    ),
    selected: draft[row.equipmentId]?.selected ?? false,
  }))
}
