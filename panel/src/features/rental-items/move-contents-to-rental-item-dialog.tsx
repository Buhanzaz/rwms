import { useMemo, useRef, useState, type RefObject } from "react"
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
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
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
  type RentalItemContentsTransferRow,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import {
  formatRentalItemContents,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type Props = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}
type Draft = { quantity: number; selected: boolean }

export function MoveContentsToRentalItemDialog({
  item,
  open,
  onOpenChange,
}: Props) {
  const contentRef = useRef<HTMLDivElement>(null)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent ref={contentRef} className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            Переместить наполнение в другую бытовку
            {item ? ` — ${item.number}` : ""}
          </DialogTitle>
          <DialogDescription>
            Выберите бытовку-получателя и фактическое оборудование. Остатки
            обновятся сразу после перемещения.
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
  const eligible = canTransferRentalItemContents(item)
  const [targetId, setTargetId] = useState<string | null>(null)
  const [draft, setDraft] = useState<Record<string, Draft>>({})
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
  const targetsQuery = useQuery({
    queryKey: [...RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY, item.warehouseId],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId: item.warehouseId,
        page: 0,
        size: 200,
      }),
    enabled: canManage && eligible && Boolean(accessToken),
  })
  const equipment = useMemo(
    () => equipmentQuery.data ?? [],
    [equipmentQuery.data]
  )
  const sourceRows = useMemo(
    () => rentalItemContentsTransferRows(equipment, item.id),
    [equipment, item.id]
  )
  const targets = useMemo(
    () =>
      eligibleRentalItemContentsTargets({
        items: targetsQuery.data?.content ?? [],
        sourceRentalItemId: item.id,
        equipmentItems: equipment,
      }),
    [equipment, item.id, targetsQuery.data]
  )
  const target = targets.find((candidate) => candidate.id === targetId) ?? null
  const rows = useMemo(
    () =>
      sourceRows.map((row) => ({
        ...row,
        quantity: Math.min(
          row.availableQuantity,
          draft[row.equipmentId]?.quantity ?? row.availableQuantity
        ),
        selected: draft[row.equipmentId]?.selected ?? false,
      })),
    [draft, sourceRows]
  )
  const selectedRows = rows.filter((row) => row.selected && row.quantity > 0)
  const allSelected = rows.length > 0 && rows.every((row) => row.selected)

  function line(row: RentalItemContentsTransferRow & { quantity: number }) {
    if (!target) throw new Error("Выберите бытовку-получателя.")
    const input = createRentalItemContentsTransferInput({
      row,
      targetWarehouseId: target.warehouseId,
      targetRentalItemId: target.id,
      targetLocationKind: cabinLocationKind(target),
      quantity: row.quantity,
    })
    return { lineKey: rentalItemContentsTransferLineKey(input), input }
  }

  function clearCompleted(row: RentalItemContentsTransferRow) {
    if (!target) return
    completedLines.current.delete(line({ ...row, quantity: 1 }).lineKey)
  }

  async function refresh() {
    await invalidateRentalItemContentsQueries(queryClient)
  }

  const mutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      if (!canManage) {
        throw new Error(
          "Для управления наполнением нужен доступ MANAGE к складу."
        )
      }
      if (!eligible) {
        throw new Error("Перемещение недоступно для текущего статуса бытовки.")
      }
      if (!target) throw new Error("Выберите бытовку-получателя.")
      return executeRentalItemContentsTransferBatch({
        accessToken,
        lines: selectedRows.map(line),
        idempotencyKeys: idempotencyKeys.current,
        completedLineKeys: completedLines.current,
        onLineCompleted: (completed) =>
          setDraft((current) => ({
            ...current,
            [completed.input.equipmentId]: { quantity: 0, selected: false },
          })),
      })
    },
    onSuccess: async () => {
      await refresh()
      toast.success("Наполнение перемещено в другую бытовку.")
      onClose()
    },
    onError: (error) => {
      setErrorText(formatRentalItemContentsTransferError(error))
      void refresh()
    },
  })

  function toggleRow(equipmentId: string, selected: boolean) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
    clearCompleted(row)
    setErrorText(null)
    setDraft((current) => ({
      ...current,
      [equipmentId]: {
        selected,
        quantity:
          selected && row.quantity === 0 ? row.availableQuantity : row.quantity,
      },
    }))
  }

  function changeQuantity(equipmentId: string, delta: number) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
    clearCompleted(row)
    const quantity = Math.min(
      row.availableQuantity,
      Math.max(0, row.quantity + delta)
    )
    setErrorText(null)
    setDraft((current) => ({
      ...current,
      [equipmentId]: { quantity, selected: quantity > 0 },
    }))
  }

  function toggleAll() {
    const selected = !allSelected
    rows.forEach(clearCompleted)
    setErrorText(null)
    setDraft((current) => ({
      ...current,
      ...Object.fromEntries(
        rows.map((row) => [
          row.equipmentId,
          {
            selected,
            quantity:
              selected && row.quantity === 0
                ? row.availableQuantity
                : row.quantity,
          },
        ])
      ),
    }))
  }

  if (!canManage || !eligible) {
    return (
      <DialogFooter>
        <FieldError className="mr-auto">
          {!canManage
            ? "Для управления наполнением нужен доступ MANAGE к складу."
            : "Перемещение недоступно для текущего статуса бытовки."}
        </FieldError>
        <Button variant="outline" onClick={onClose}>
          Закрыть
        </Button>
      </DialogFooter>
    )
  }

  return (
    <div className="flex max-h-[75vh] flex-col gap-4 overflow-auto pr-1">
      {!equipmentQuery.isLoading && !equipmentQuery.isError ? (
        <p
          className="text-sm"
          title={formatRentalItemContentsSourceSummary(item.number, sourceRows)}
        >
          {formatRentalItemContentsSourceSummary(item.number, sourceRows)}
        </p>
      ) : null}
      <Field>
        <FieldLabel htmlFor="move-contents-target">Куда переместить</FieldLabel>
        {targetsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загрузка бытовок…</p>
        ) : targetsQuery.isError ? (
          <FieldError>
            {targetsQuery.error instanceof Error
              ? targetsQuery.error.message
              : "Не удалось загрузить бытовки."}
          </FieldError>
        ) : (
          <Combobox
            items={targets.map((candidate) => candidate.number)}
            value={target?.number ?? null}
            onValueChange={(value) => {
              setTargetId(
                targets.find((candidate) => candidate.number === value)?.id ??
                  null
              )
              setErrorText(null)
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
                  {targets.map((candidate) => (
                    <ComboboxItem key={candidate.id} value={candidate.number}>
                      <span>{candidate.number}</span>
                      <span className="text-xs text-muted-foreground">
                        {candidate.type}
                      </span>
                    </ComboboxItem>
                  ))}
                </ComboboxGroup>
              </ComboboxList>
            </ComboboxContent>
          </Combobox>
        )}
      </Field>
      {target ? (
        <div className="rounded-md border p-3 text-sm">
          <div className="font-medium">Выбрано: {target.number}</div>
          <div className="text-xs text-muted-foreground">
            {target.type}
            {target.contentsItems.length > 0
              ? ` · ${formatRentalItemContents(
                  target.contentsItems,
                  target.contents
                )}`
              : " · Наполнение не указано"}
          </div>
        </div>
      ) : null}
      {equipmentQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Загрузка наполнения…</p>
      ) : equipmentQuery.isError ? (
        <FieldError>
          {equipmentQuery.error instanceof Error
            ? equipmentQuery.error.message
            : "Не удалось загрузить остатки оборудования."}
        </FieldError>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          В бытовке нет наполнения для перемещения.
        </p>
      ) : (
        <>
          <Button
            type="button"
            variant="outline"
            className="w-fit"
            onClick={toggleAll}
          >
            {allSelected ? "Снять выбор" : "Выбрать всё"}
          </Button>
          <RentalItemContentsQuantityRows
            idPrefix="move-to-rental-item"
            legend="Оборудование для перемещения в другую бытовку"
            rows={rows}
            onToggle={toggleRow}
            onChangeQuantity={changeQuantity}
          />
        </>
      )}
      {errorText ? <FieldError>{errorText}</FieldError> : null}
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          Отмена
        </Button>
        <Button
          disabled={!target || selectedRows.length === 0 || mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
          {mutation.isPending ? "Перемещение…" : "Переместить"}
        </Button>
      </DialogFooter>
    </div>
  )
}
