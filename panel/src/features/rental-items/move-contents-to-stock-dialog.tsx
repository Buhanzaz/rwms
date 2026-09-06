import { DateTimePicker } from "@/components/ui/date-time-picker"
import { useMemo, useState } from "react"
import { WarehouseIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useQuery } from "@tanstack/react-query"

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
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS,
  equipmentMovementDeadlineToIso,
  equipmentMovementWorkerOperationCount,
  type CreateEquipmentMovementTaskInput,
} from "@/features/logistics/api/equipment-movement-tasks-api"
import { RentalItemContentsQuantityRows } from "@/features/rental-items/rental-item-contents-quantity-rows"
import {
  canTransferRentalItemContents,
  formatRentalItemContentsSourceSummary,
  rentalItemContentsTransferRows,
  RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  useContentsMovementMutation,
  useRentalItemContentsDraft,
} from "./use-rental-item-contents-movement"

type Props = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
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
  const { accessToken, currentUser } = useAuth()
  const canManage = hasWarehouseAccess(currentUser, item.warehouseId, "MANAGE")
  const eligible = canTransferRentalItemContents(item)
  const [reservationDeadline, setReservationDeadline] = useState("")

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
  const [errorText, setErrorText] = useState<string | null>(null)
  const {
    rows,
    selectedRows,
    allSelected,
    toggleRow,
    changeQuantity,
    toggleAll,
  } = useRentalItemContentsDraft(sourceRows, () => setErrorText(null))
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

  const { mutation } = useContentsMovementMutation({
    onErrorText: setErrorText,
    accessToken,
    onClose,
    createInput: () => {
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
      return input
    },
  })

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
          <DateTimePicker
            label="Резерв до"
            hideLabel
            allowClear
            id="move-to-stock-reservation-deadline"
            value={reservationDeadline}
            onValueChange={(nextValue) => {
              setReservationDeadline(nextValue)
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
