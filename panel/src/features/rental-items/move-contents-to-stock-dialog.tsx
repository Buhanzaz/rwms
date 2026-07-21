import { useMemo, useRef, useState } from "react"
import { WarehouseIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { getEquipmentItems } from "@/api/equipment-api"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { FieldError } from "@/components/ui/field"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { RentalItemContentsQuantityRows } from "@/features/rental-items/rental-item-contents-quantity-rows"
import {
  canTransferRentalItemContents,
  createRentalItemContentsTransferInput,
  executeRentalItemContentsTransferBatch,
  formatRentalItemContentsSourceSummary,
  formatRentalItemContentsTransferError,
  invalidateRentalItemContentsQueries,
  rentalItemContentsTransferLineKey,
  rentalItemContentsTransferRows,
  RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
  type RentalItemContentsTransferRow,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

type Props = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}
type Draft = { quantity: number; selected: boolean }

export function MoveContentsToStockDialog({ item, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>
            Переместить наполнение на склад
            {item ? ` — ${item.number}` : ""}
          </DialogTitle>
          <DialogDescription>
            Выберите фактическое оборудование бытовки. Остатки на складе
            обновятся сразу после перемещения.
          </DialogDescription>
        </DialogHeader>
        {item && open ? (
          <Content
            key={`${item.id}:open`}
            item={item}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function Content({
  item,
  onClose,
}: {
  item: RentalItemDto
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const canManage = hasWarehouseAccess(currentUser, item.warehouseId, "MANAGE")
  const eligible = canTransferRentalItemContents(item)
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
  const sourceRows = useMemo(
    () => rentalItemContentsTransferRows(equipmentQuery.data ?? [], item.id),
    [equipmentQuery.data, item.id]
  )
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
    const input = createRentalItemContentsTransferInput({
      row,
      targetWarehouseId: item.warehouseId,
      targetRentalItemId: null,
      targetLocationKind: "STOCK",
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
      toast.success("Оборудование возвращено на склад.")
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
          В бытовке нет доступного наполнения для перемещения.
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
            idPrefix="move-to-stock"
            legend="Оборудование для возврата на склад"
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
          disabled={selectedRows.length === 0 || mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          <HugeiconsIcon icon={WarehouseIcon} data-icon="inline-start" />
          {mutation.isPending ? "Перемещение…" : "Переместить на склад"}
        </Button>
      </DialogFooter>
    </div>
  )
}
