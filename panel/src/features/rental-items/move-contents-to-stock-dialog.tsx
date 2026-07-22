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
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS,
  createEquipmentMovementTask,
  createEquipmentMovementTaskIdempotencyKey,
  equipmentMovementDeadlineToIso,
  equipmentMovementWorkerOperationCount,
  type CreateEquipmentMovementTaskInput,
} from "@/features/logistics/api/equipment-movement-tasks-api"
import { RentalItemContentsQuantityRows } from "@/features/rental-items/rental-item-contents-quantity-rows"
import {
  canTransferRentalItemContents,
  formatRentalItemContentsSourceSummary,
  invalidateRentalItemContentsQueries,
  rentalItemContentsTransferRows,
  RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"

type Props = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}
type Draft = { quantity: number; selected: boolean }

function taskSuccessMessage(deadlineAt: string) {
  return `Задание создано. Мебель зарезервирована до ${new Intl.DateTimeFormat(
    "ru-RU",
    { dateStyle: "short", timeStyle: "short" }
  ).format(new Date(deadlineAt))}; остатки изменятся после выполнения.`
}

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
            Будет создано задание работнику. Выбранная мебель останется в
            бытовке до его выполнения.
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
  const [reservationDeadline, setReservationDeadline] = useState("")
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKeys = useRef(new Map<string, string>())

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
  const taskLines = selectedRows.map((row) => ({
    equipmentId: row.equipmentId,
    sourceRentalItemId: item.id,
    sourceLocationKind: row.sourceBalance.locationKind,
    expectedSourceBalanceVersion: row.sourceBalance.version,
    targetRentalItemId: null,
    targetLocationKind: "STOCK" as const,
    quantity: row.quantity,
  }))
  const operationLimitExceeded =
    equipmentMovementWorkerOperationCount(taskLines) >
    MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS

  function taskIdempotencyKey(input: CreateEquipmentMovementTaskInput) {
    const signature = JSON.stringify(input)
    let key = idempotencyKeys.current.get(signature)
    if (!key) {
      key = createEquipmentMovementTaskIdempotencyKey()
      idempotencyKeys.current.set(signature, key)
    }
    return key
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
      if (operationLimitExceeded) {
        throw new Error(
          `В одном задании допускается не более ${MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.`
        )
      }
      const input: CreateEquipmentMovementTaskInput = {
        warehouseId: item.warehouseId,
        unitNumber: item.number,
        plannedDurationMinutes: null,
        deadlineAt: equipmentMovementDeadlineToIso(reservationDeadline),
        lines: taskLines,
      }
      return createEquipmentMovementTask({
        accessToken,
        idempotencyKey: taskIdempotencyKey(input),
        input,
      })
    },
    onSuccess: (task) => {
      void invalidateRentalItemContentsQueries(queryClient)
      toast.success(taskSuccessMessage(task.deadlineAt))
      onClose()
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Не удалось создать задание."
      )
      if (error instanceof ApiError && error.status === 409) {
        void invalidateRentalItemContentsQueries(queryClient)
      }
    },
  })

  function toggleRow(equipmentId: string, selected: boolean) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
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
      <FieldGroup className="gap-4">
        <Field data-invalid={Boolean(errorText && !reservationDeadline)}>
          <FieldLabel htmlFor="move-to-stock-reservation-deadline">
            Резерв до
          </FieldLabel>
          <Input
            id="move-to-stock-reservation-deadline"
            type="datetime-local"
            value={reservationDeadline}
            onChange={(event) => {
              setReservationDeadline(event.target.value)
              setErrorText(null)
            }}
            aria-invalid={Boolean(errorText && !reservationDeadline)}
            required
          />
          <FieldDescription>
            После этого срока резерв освободится, если работник не завершит
            задание.
          </FieldDescription>
        </Field>
      </FieldGroup>
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
      {operationLimitExceeded ? (
        <FieldError>
          В одном задании допускается не более{" "}
          {MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.
        </FieldError>
      ) : null}
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          Отмена
        </Button>
        <Button
          disabled={
            selectedRows.length === 0 ||
            !reservationDeadline ||
            equipmentQuery.isFetching ||
            operationLimitExceeded ||
            mutation.isPending
          }
          onClick={() => mutation.mutate()}
        >
          <HugeiconsIcon icon={WarehouseIcon} data-icon="inline-start" />
          {mutation.isPending ? "Создание задания…" : "Создать задание"}
        </Button>
      </DialogFooter>
    </div>
  )
}
