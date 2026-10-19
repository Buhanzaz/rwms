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
import { FieldError } from "@/components/ui/field"
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
  createOrderIdempotencyKey,
  setOrderUnitDesiredEquipment,
} from "@/features/orders/api/orders-api"
import type {
  OrderDetail,
  OrderEquipmentContent,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { invalidateRentalItemContentsQueries } from "@/features/rental-items/rental-item-contents-transfer-support"
import { ApiError } from "@/lib/api-client"

const EQUIPMENT_QUERY_KEY = ["orders", "equipment-catalog"] as const

/** Quantity editor row fenced by shared availability and the per-cabin cap. */
type DesiredEquipmentRow = {
  equipmentId: string
  name: string
  savedDesiredQuantity: number
  desiredQuantity: number
  availableForOrder: number
  maximumPerCabin: number | null
  maximumQuantity: number
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

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
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
      <DialogContent className="max-h-[85vh] max-w-4xl overflow-hidden">
        <DialogHeader>
          <DialogTitle>
            Желаемое наполнение
            {candidate ? ` — ${candidate.unit.number}` : ""}
          </DialogTitle>
          <DialogDescription>
            Выберите комплектацию из каталога дополнительного оборудования.
            После сохранения мебель бронируется за бронированием; фактические
            перемещения создаются в отгрузке.
          </DialogDescription>
        </DialogHeader>
        {candidate && open ? (
          <OrderUnitEquipmentDialogContent
            key={`${candidate.unit.id}:${order.version}`}
            order={order}
            candidate={candidate}
            onClose={() => onOpenChange(false)}
            onSaved={onConflict}
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
  onSaved,
}: {
  order: OrderDetail
  candidate: OrderUnitCandidate
  onClose: () => void
  onSaved: () => void
}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [desiredQuantityByEquipmentId, setDesiredQuantityByEquipmentId] =
    useState<Record<string, number>>({})
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKeysRef = useRef(new Map<string, string>())

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
    const physicalQuantityByEquipmentId = new Map<string, number>()
    const orderDesiredQuantityByEquipmentId = new Map<string, number>()
    order.units.forEach((orderUnit) => {
      orderUnit.unit.contents.forEach((content) => {
        physicalQuantityByEquipmentId.set(
          content.equipmentId,
          (physicalQuantityByEquipmentId.get(content.equipmentId) ?? 0) +
            content.quantity
        )
      })
      orderUnit.desiredContents.forEach((content) => {
        orderDesiredQuantityByEquipmentId.set(
          content.equipmentId,
          (orderDesiredQuantityByEquipmentId.get(content.equipmentId) ?? 0) +
            content.quantity
        )
      })
    })
    const savedDesiredByEquipmentId = new Map(
      candidate.desiredContents.map((content) => [content.equipmentId, content])
    )
    const equipmentIds = new Set([
      ...equipmentById.keys(),
      ...savedDesiredByEquipmentId.keys(),
    ])

    return [...equipmentIds]
      .flatMap((equipmentId) => {
        const item = equipmentById.get(equipmentId)
        const savedDesired = savedDesiredByEquipmentId.get(equipmentId)
        if (!item && !savedDesired) return []
        if (!item?.active && !savedDesired) return []

        const savedDesiredQuantity = savedDesired?.quantity ?? 0
        const ownPhysicalSurplus = Math.max(
          (physicalQuantityByEquipmentId.get(equipmentId) ?? 0) -
            (orderDesiredQuantityByEquipmentId.get(equipmentId) ?? 0),
          0
        )
        const availableForOrder = Math.max(
          savedDesiredQuantity,
          (item?.availableQuantity ?? 0) +
            savedDesiredQuantity +
            ownPhysicalSurplus
        )
        const maximumPerCabin = item?.maximumPerCabin ?? null
        const maximumQuantity = Math.max(
          savedDesiredQuantity,
          Math.min(
            availableForOrder,
            maximumPerCabin ?? Number.POSITIVE_INFINITY
          )
        )
        return [
          {
            equipmentId,
            name: item?.name ?? savedDesired?.equipmentName ?? "Оборудование",
            savedDesiredQuantity,
            desiredQuantity: clamp(
              desiredQuantityByEquipmentId[equipmentId] ?? savedDesiredQuantity,
              0,
              maximumQuantity
            ),
            availableForOrder,
            maximumPerCabin,
            maximumQuantity,
          } satisfies DesiredEquipmentRow,
        ]
      })
      .sort((left, right) => left.name.localeCompare(right.name, "ru"))
  }, [
    candidate.desiredContents,
    desiredQuantityByEquipmentId,
    equipmentQuery.data,
    order.units,
  ])

  const requirements = useMemo(
    () =>
      desiredRows
        .filter((row) => row.desiredQuantity > 0)
        .map((row) => ({
          equipmentId: row.equipmentId,
          quantity: row.desiredQuantity,
        })),
    [desiredRows]
  )
  const hasChanges = desiredRows.some(
    (row) => row.desiredQuantity !== row.savedDesiredQuantity
  )

  function idempotencyKeyForCurrentRequirements() {
    const signature = JSON.stringify(requirements)
    let key = idempotencyKeysRef.current.get(signature)
    if (!key) {
      key = createOrderIdempotencyKey()
      idempotencyKeysRef.current.set(signature, key)
    }
    return key
  }

  function refresh() {
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: ["orders"] }),
      queryClient.invalidateQueries({
        queryKey: [
          ...EQUIPMENT_QUERY_KEY,
          currentUser?.id ?? "unknown-user",
          candidate.unit.warehouseId,
        ],
      }),
      invalidateRentalItemContentsQueries(queryClient),
    ])
  }

  const saveDesiredMutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      if (!order.permissions.canEdit) {
        throw new Error("Изменение этого бронирования запрещено.")
      }
      return setOrderUnitDesiredEquipment({
        accessToken,
        orderId: order.id,
        expectedVersion: order.version,
        unitId: candidate.unit.id,
        requirements,
        idempotencyKey: idempotencyKeyForCurrentRequirements(),
      })
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["orders"] }),
        queryClient.invalidateQueries({
          queryKey: [
            ...EQUIPMENT_QUERY_KEY,
            currentUser?.id ?? "unknown-user",
            candidate.unit.warehouseId,
          ],
        }),
        invalidateRentalItemContentsQueries(queryClient),
      ])
      onSaved()
      toast.success(
        "Желаемое наполнение сохранено. Мебель забронирована за бронированием."
      )
      onClose()
    },
    onError: (error: unknown) => {
      if (error instanceof ApiError && error.status === 409) {
        refresh()
        onSaved()
        setErrorText(
          "Состав бронирования или доступный остаток изменились. Актуальные данные загружены с сервера."
        )
        return
      }
      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить желаемое наполнение."
      )
    },
  })

  function setDesiredQuantity(equipmentId: string, nextQuantity: number) {
    const row = desiredRows.find(
      (candidateRow) => candidateRow.equipmentId === equipmentId
    )
    if (!row) return
    setErrorText(null)
    setDesiredQuantityByEquipmentId((current) => ({
      ...current,
      [equipmentId]: clamp(nextQuantity, 0, row.maximumQuantity),
    }))
  }

  function changeDesiredQuantity(equipmentId: string, delta: number) {
    const row = desiredRows.find(
      (candidateRow) => candidateRow.equipmentId === equipmentId
    )
    if (!row) return
    setDesiredQuantity(equipmentId, row.desiredQuantity + delta)
  }

  const mutationPending = saveDesiredMutation.isPending
  const canSave =
    order.permissions.canEdit &&
    !equipmentQuery.isLoading &&
    hasChanges &&
    !mutationPending

  return (
    <div className="flex max-h-[70vh] min-h-0 flex-col gap-4">
      {errorText ? <FieldError>{errorText}</FieldError> : null}
      {!order.permissions.canEdit ? (
        <FieldError>Изменение этого бронирования запрещено.</FieldError>
      ) : null}

      <section className="min-h-0 flex-1 overflow-y-auto pr-1">
        <div className="mb-3">
          <h3 className="font-medium">Желаемое наполнение</h3>
          <p className="text-sm text-muted-foreground">
            «Доступно» учитывает мебель на складе и в свободных бытовках за
            вычетом резервов других бронирований.
          </p>
        </div>
        {equipmentQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">
            Загружаем каталог дополнительного оборудования…
          </p>
        ) : null}
        {equipmentQuery.isError ? (
          <FieldError>
            {equipmentQuery.error instanceof Error
              ? equipmentQuery.error.message
              : "Не удалось загрузить каталог дополнительного оборудования."}
          </FieldError>
        ) : null}
        {desiredRows.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Позиции дополнительного оборудования не найдены.
          </p>
        ) : (
          <div className="space-y-2">
            {desiredRows.map((row) => (
              <div
                key={row.equipmentId}
                className="grid min-w-0 gap-3 rounded-lg border bg-card p-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{row.name}</div>
                  <div className="text-xs text-muted-foreground">
                    Доступно этому заказу: {row.availableForOrder} шт.
                  </div>
                  <div className="text-xs text-muted-foreground">
                    Максимум в одной бытовке:{" "}
                    {row.maximumPerCabin === null
                      ? "не ограничен"
                      : `${row.maximumPerCabin} шт.`}
                  </div>
                  {row.savedDesiredQuantity >
                  (row.maximumPerCabin ?? Number.POSITIVE_INFINITY) ? (
                    <div className="text-xs text-muted-foreground">
                      Ранее сохранено {row.savedDesiredQuantity} шт.; увеличить
                      это количество нельзя.
                    </div>
                  ) : null}
                </div>
                <div className="flex items-center gap-2 justify-self-start sm:justify-self-auto">
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    disabled={mutationPending || row.desiredQuantity === 0}
                    aria-label={`Уменьшить ${row.name}`}
                    onClick={() => changeDesiredQuantity(row.equipmentId, -1)}
                  >
                    <HugeiconsIcon icon={MinusSignIcon} />
                  </Button>
                  <Input
                    type="number"
                    min={0}
                    max={row.maximumQuantity}
                    step={1}
                    inputMode="numeric"
                    className="h-8 w-20 text-center font-semibold tabular-nums"
                    aria-label={`Количество ${row.name}`}
                    value={row.desiredQuantity}
                    disabled={mutationPending}
                    onChange={(event) => {
                      const next = Number(event.target.value)
                      if (Number.isSafeInteger(next)) {
                        setDesiredQuantity(row.equipmentId, next)
                      }
                    }}
                  />
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    disabled={
                      mutationPending ||
                      row.desiredQuantity >= row.maximumQuantity
                    }
                    aria-label={`Увеличить ${row.name}`}
                    onClick={() => changeDesiredQuantity(row.equipmentId, 1)}
                  >
                    <HugeiconsIcon icon={Add01Icon} />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Закрыть
        </Button>
        <Button
          type="button"
          disabled={!canSave}
          onClick={() => saveDesiredMutation.mutate()}
        >
          {mutationPending ? (
            <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
          ) : null}
          {mutationPending ? "Сохранение…" : "Сохранить наполнение"}
        </Button>
      </DialogFooter>
    </div>
  )
}
