import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  Loading03Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { getEquipmentItems } from "@/api/equipment-api"
import { Badge } from "@/components/ui/badge"
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS,
  createEquipmentMovementTask,
  createEquipmentMovementTaskIdempotencyKey,
  equipmentMovementDeadlineToIso,
  equipmentMovementWorkerOperationCount,
  type CreateEquipmentMovementTaskInput,
  type EquipmentMovementTaskLineInput,
} from "@/features/logistics/api/equipment-movement-tasks-api"
import type {
  OrderDetail,
  OrderEquipmentContent,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import {
  cabinLocationKind,
  invalidateRentalItemContentsQueries,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import { ApiError } from "@/lib/api-client"
import type { EquipmentBalanceDto, EquipmentItemDto } from "@/types/equipment"

const EQUIPMENT_QUERY_KEY = ["orders", "equipment-catalog"] as const

type DesiredEquipmentRow = {
  equipmentId: string
  code: string
  name: string
  currentQuantity: number
  desiredQuantity: number
  minimumDesiredQuantity: number
  maximumDesiredQuantity: number
  stockSourceBalance: EquipmentBalanceDto | null
  cabinSourceBalance: EquipmentBalanceDto | null
}

function locationLabel(value: string) {
  switch (value) {
    case "CABIN_NON_RENTED":
      return "В бытовке"
    case "CABIN_RENTED":
      return "В арендованной бытовке"
    case "STOCK":
      return "На складе"
    default:
      return value
  }
}

function taskSuccessMessage(deadlineAt: string) {
  return `Задание создано. Мебель зарезервирована до ${new Intl.DateTimeFormat(
    "ru-RU",
    { dateStyle: "short", timeStyle: "short" }
  ).format(
    new Date(deadlineAt)
  )}; наполнение изменится после выполнения работником.`
}

function operationLimitMessage(operationCount: number) {
  return `Выбрано ${operationCount} действий работника. В одном задании допускается не более ${MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS}.`
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}

function sourceBalance(
  item: EquipmentItemDto | undefined,
  rentalItemId: string | null,
  locationKind: "STOCK" | "CABIN_NON_RENTED" | "CABIN_RENTED"
) {
  return (
    item?.balances.find(
      (balance) =>
        balance.rentalItemId === rentalItemId &&
        balance.locationKind === locationKind
    ) ?? null
  )
}

export function OrderUnitContentsView({
  contents,
}: {
  contents: OrderEquipmentContent[]
}) {
  if (contents.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">Наполнение отсутствует</p>
    )
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Оборудование</TableHead>
          <TableHead>Код</TableHead>
          <TableHead>Количество</TableHead>
          <TableHead>Состояние</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {contents.map((content) => (
          <TableRow key={content.equipmentId}>
            <TableCell className="font-medium">
              {content.equipmentName}
            </TableCell>
            <TableCell>{content.equipmentCode}</TableCell>
            <TableCell>{content.quantity}</TableCell>
            <TableCell>
              <Badge variant="outline">
                {locationLabel(content.locationKind)}
              </Badge>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

export function OrderUnitEquipmentDialog({
  open,
  order,
  candidate,
  onOpenChange,
  onConflict,
}: {
  open: boolean
  order: OrderDetail
  candidate: OrderUnitCandidate | null
  onOpenChange: (open: boolean) => void
  onConflict: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            Наполнение{candidate ? ` — ${candidate.unit.number}` : ""}
          </DialogTitle>
          <DialogDescription>
            Укажите желаемое количество и срок резерва. Мебель переместится
            только после выполнения задания работником.
          </DialogDescription>
        </DialogHeader>
        {candidate && open ? (
          <OrderUnitEquipmentDialogContent
            key={`${candidate.unit.id}:${order.version}`}
            order={order}
            candidate={candidate}
            onClose={() => onOpenChange(false)}
            onScheduled={onConflict}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function OrderUnitEquipmentDialogContent({
  order,
  candidate,
  onClose,
  onScheduled,
}: {
  order: OrderDetail
  candidate: OrderUnitCandidate
  onClose: () => void
  onScheduled: () => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [desiredQuantityByEquipmentId, setDesiredQuantityByEquipmentId] =
    useState<Record<string, number>>({})
  const [reservationDeadline, setReservationDeadline] = useState("")
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKeysRef = useRef(new Map<string, string>())
  const targetLocationKind = cabinLocationKind(candidate.unit)

  const equipmentQuery = useQuery({
    queryKey: [
      ...EQUIPMENT_QUERY_KEY,
      currentUser?.id ?? "unknown-user",
      candidate.unit.warehouseId,
    ],
    queryFn: () =>
      getEquipmentItems(accessToken, {
        warehouseId: candidate.unit.warehouseId,
      }),
    enabled: Boolean(accessToken && order.permissions.canEdit),
    refetchInterval: 2_000,
  })

  const desiredRows = useMemo(() => {
    const equipmentById = new Map(
      (equipmentQuery.data ?? []).map((item) => [item.id, item])
    )
    const contentByEquipmentId = new Map(
      candidate.unit.contents
        .filter((content) => content.quantity > 0)
        .map((content) => [content.equipmentId, content])
    )
    const equipmentIds = new Set([
      ...equipmentById.keys(),
      ...contentByEquipmentId.keys(),
    ])

    return [...equipmentIds]
      .flatMap((equipmentId) => {
        const item = equipmentById.get(equipmentId)
        const content = contentByEquipmentId.get(equipmentId)
        const currentQuantity = content?.quantity ?? 0
        const stockSourceBalance = sourceBalance(item, null, "STOCK")
        const cabinSourceBalance = sourceBalance(
          item,
          candidate.unit.id,
          targetLocationKind
        )
        const availableFromStock = stockSourceBalance?.availableStock ?? 0
        const availableFromCabin = Math.min(
          cabinSourceBalance?.quantity ?? 0,
          cabinSourceBalance?.availableStock ?? 0
        )
        const minimumDesiredQuantity = content
          ? Math.max(0, currentQuantity - availableFromCabin)
          : 0
        const maximumDesiredQuantity = currentQuantity + availableFromStock

        if (
          currentQuantity === 0 &&
          (!item?.active || availableFromStock === 0)
        ) {
          return []
        }

        return [
          {
            equipmentId,
            code: item?.code ?? content?.equipmentCode ?? "—",
            name: item?.name ?? content?.equipmentName ?? "Оборудование",
            currentQuantity,
            desiredQuantity: clamp(
              desiredQuantityByEquipmentId[equipmentId] ?? currentQuantity,
              minimumDesiredQuantity,
              maximumDesiredQuantity
            ),
            minimumDesiredQuantity,
            maximumDesiredQuantity,
            stockSourceBalance,
            cabinSourceBalance,
          } satisfies DesiredEquipmentRow,
        ]
      })
      .sort((left, right) => left.name.localeCompare(right.name, "ru"))
  }, [
    candidate.unit.contents,
    candidate.unit.id,
    desiredQuantityByEquipmentId,
    equipmentQuery.data,
    targetLocationKind,
  ])

  const movementLines = useMemo(() => {
    const lines: EquipmentMovementTaskLineInput[] = []

    for (const row of desiredRows) {
      if (row.desiredQuantity > row.currentQuantity && row.stockSourceBalance) {
        lines.push({
          equipmentId: row.equipmentId,
          sourceRentalItemId: null,
          sourceLocationKind: "STOCK",
          expectedSourceBalanceVersion: row.stockSourceBalance.version,
          targetRentalItemId: candidate.unit.id,
          targetLocationKind,
          quantity: row.desiredQuantity - row.currentQuantity,
        })
      }

      if (row.desiredQuantity < row.currentQuantity && row.cabinSourceBalance) {
        lines.push({
          equipmentId: row.equipmentId,
          sourceRentalItemId: candidate.unit.id,
          sourceLocationKind: targetLocationKind,
          expectedSourceBalanceVersion: row.cabinSourceBalance.version,
          targetRentalItemId: null,
          targetLocationKind: "STOCK",
          quantity: row.currentQuantity - row.desiredQuantity,
        })
      }
    }

    return lines
  }, [candidate.unit.id, desiredRows, targetLocationKind])
  const workerOperationCount =
    equipmentMovementWorkerOperationCount(movementLines)
  const operationLimitExceeded =
    workerOperationCount > MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS

  function idempotencyKeyFor(input: CreateEquipmentMovementTaskInput) {
    const signature = JSON.stringify(input)
    let key = idempotencyKeysRef.current.get(signature)
    if (!key) {
      key = createEquipmentMovementTaskIdempotencyKey()
      idempotencyKeysRef.current.set(signature, key)
    }
    return key
  }

  function refreshReservations() {
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: ["orders"] }),
      invalidateRentalItemContentsQueries(queryClient),
    ])
  }

  function handleSchedulingError(error: unknown) {
    if (error instanceof ApiError && error.status === 409) {
      refreshReservations()
      onScheduled()
      setErrorText(
        "Остатки мебели изменились. Актуальные значения загружены с сервера."
      )
      return
    }

    setErrorText(
      error instanceof Error ? error.message : "Не удалось создать задание."
    )
  }

  const scheduleMovementMutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      if (!order.permissions.canEdit) {
        throw new Error("Изменение этого заказа запрещено.")
      }
      if (movementLines.length === 0) {
        throw new Error("Измените хотя бы одно количество.")
      }
      if (operationLimitExceeded) {
        throw new Error(operationLimitMessage(workerOperationCount))
      }

      const input: CreateEquipmentMovementTaskInput = {
        warehouseId: candidate.unit.warehouseId,
        unitNumber: candidate.unit.number,
        plannedDurationMinutes: null,
        deadlineAt: equipmentMovementDeadlineToIso(reservationDeadline),
        lines: movementLines,
      }

      return createEquipmentMovementTask({
        accessToken,
        idempotencyKey: idempotencyKeyFor(input),
        input,
      })
    },
    onSuccess: async (task) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["orders"] }),
        invalidateRentalItemContentsQueries(queryClient),
      ])
      onScheduled()
      toast.success(taskSuccessMessage(task.deadlineAt))
      onClose()
    },
    onError: handleSchedulingError,
  })

  function changeDesiredQuantity(equipmentId: string, delta: number) {
    const row = desiredRows.find(
      (candidateRow) => candidateRow.equipmentId === equipmentId
    )
    if (!row) return

    setErrorText(null)
    setDesiredQuantityByEquipmentId((current) => ({
      ...current,
      [equipmentId]: clamp(
        row.desiredQuantity + delta,
        row.minimumDesiredQuantity,
        row.maximumDesiredQuantity
      ),
    }))
  }

  const mutationPending = scheduleMovementMutation.isPending
  const canSchedule =
    order.permissions.canEdit &&
    !equipmentQuery.isLoading &&
    reservationDeadline.trim() !== "" &&
    movementLines.length > 0 &&
    !operationLimitExceeded &&
    !mutationPending

  return (
    <div className="flex max-h-[75vh] flex-col gap-5 overflow-auto pr-1">
      <FieldGroup className="gap-4">
        <Field data-invalid={Boolean(errorText && !reservationDeadline)}>
          <FieldLabel htmlFor="order-contents-reservation-deadline">
            Резерв до
          </FieldLabel>
          <Input
            id="order-contents-reservation-deadline"
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
            До этого времени мебель недоступна для других перемещений. Состав
            бытовки изменится только после выполнения задания.
          </FieldDescription>
        </Field>
      </FieldGroup>

      {errorText ? <FieldError>{errorText}</FieldError> : null}

      {!order.permissions.canEdit ? (
        <FieldError>Изменение этого заказа запрещено.</FieldError>
      ) : null}

      <section className="flex flex-col gap-3">
        <div>
          <h3 className="font-medium">Желаемое наполнение</h3>
          <p className="text-sm text-muted-foreground">
            Указаны текущие количества. Увеличение создаст задание со склада,
            уменьшение — задание на возврат на склад.
          </p>
        </div>
        {equipmentQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">
            Загружаем актуальные остатки…
          </p>
        ) : null}
        {equipmentQuery.isError ? (
          <FieldError>
            {equipmentQuery.error instanceof Error
              ? equipmentQuery.error.message
              : "Не удалось загрузить остатки мебели."}
          </FieldError>
        ) : null}
        {desiredRows.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Доступных позиций нет.
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Оборудование</TableHead>
                <TableHead>Сейчас</TableHead>
                <TableHead>Будет</TableHead>
                <TableHead>Доступно со склада</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {desiredRows.map((row) => {
                const decreaseUnavailable =
                  row.currentQuantity > 0 && row.cabinSourceBalance === null

                return (
                  <TableRow key={row.equipmentId}>
                    <TableCell className="min-w-0">
                      <span className="block truncate font-medium">
                        {row.name}
                      </span>
                      <span className="block text-xs text-muted-foreground">
                        {row.code}
                        {decreaseUnavailable
                          ? " · актуальный остаток в бытовке не найден"
                          : ""}
                      </span>
                    </TableCell>
                    <TableCell>{row.currentQuantity} шт.</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2 whitespace-nowrap">
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          disabled={
                            mutationPending ||
                            row.desiredQuantity <= row.minimumDesiredQuantity
                          }
                          aria-label={`Уменьшить ${row.name}`}
                          onClick={() =>
                            changeDesiredQuantity(row.equipmentId, -1)
                          }
                        >
                          <HugeiconsIcon
                            icon={MinusSignIcon}
                            data-icon="inline-start"
                          />
                        </Button>
                        <span className="min-w-8 text-center font-semibold">
                          {row.desiredQuantity}
                        </span>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          disabled={
                            mutationPending ||
                            row.desiredQuantity >= row.maximumDesiredQuantity
                          }
                          aria-label={`Увеличить ${row.name}`}
                          onClick={() =>
                            changeDesiredQuantity(row.equipmentId, 1)
                          }
                        >
                          <HugeiconsIcon
                            icon={Add01Icon}
                            data-icon="inline-start"
                          />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell>
                      {row.stockSourceBalance?.availableStock ?? 0} шт.
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </section>

      {operationLimitExceeded ? (
        <FieldError>{operationLimitMessage(workerOperationCount)}</FieldError>
      ) : null}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Закрыть
        </Button>
        <Button
          type="button"
          disabled={!canSchedule}
          onClick={() => scheduleMovementMutation.mutate()}
        >
          {mutationPending ? (
            <HugeiconsIcon
              icon={Loading03Icon}
              data-icon="inline-start"
              className="animate-spin"
            />
          ) : null}
          {mutationPending ? "Создание задания…" : "Создать задание"}
        </Button>
      </DialogFooter>
    </div>
  )
}
