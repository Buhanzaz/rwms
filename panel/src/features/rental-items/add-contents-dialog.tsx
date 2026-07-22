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
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Separator } from "@/components/ui/separator"
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
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import { RentalItemContentsQuantityRows } from "@/features/rental-items/rental-item-contents-quantity-rows"
import {
  cabinLocationKind,
  canTransferRentalItemContents,
  eligibleRentalItemContentsTargets,
  formatRentalItemContentsSourceSummary,
  invalidateRentalItemContentsQueries,
  rentalItemContentsTransferRows,
  RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
  RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY,
  warehouseStockTransferRows,
  type RentalItemContentsTransferRow,
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
            Выберите оборудование на складе или из другой бытовки. Оно
            переместится только после выполнения задания работником.
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
  const stockTaskLines = selectedStock.map((row) => ({
    equipmentId: row.equipmentId,
    sourceRentalItemId: null,
    sourceLocationKind: "STOCK" as const,
    expectedSourceBalanceVersion: row.sourceBalance.version,
    targetRentalItemId: item.id,
    targetLocationKind: cabinLocationKind(item),
    quantity: row.quantity,
  }))
  const cabinTaskLines = selectedCabin.map((row) => ({
    equipmentId: row.equipmentId,
    sourceRentalItemId: selectedSource?.id ?? null,
    sourceLocationKind:
      row.sourceBalance.locationKind === "CABIN_RENTED"
        ? ("CABIN_RENTED" as const)
        : ("CABIN_NON_RENTED" as const),
    expectedSourceBalanceVersion: row.sourceBalance.version,
    targetRentalItemId: item.id,
    targetLocationKind: cabinLocationKind(item),
    quantity: row.quantity,
  }))
  const stockOperationLimitExceeded =
    equipmentMovementWorkerOperationCount(stockTaskLines) >
    MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS
  const cabinOperationLimitExceeded =
    equipmentMovementWorkerOperationCount(cabinTaskLines) >
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

  function fail(error: unknown) {
    setErrorText(
      error instanceof Error
        ? error.message
        : "Не удалось создать задание на перемещение."
    )
    if (error instanceof ApiError && error.status === 409) {
      void invalidateRentalItemContentsQueries(queryClient)
    }
  }

  const stockMutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      if (!canManage || !targetEligible) {
        throw new Error("Бытовка недоступна для изменения наполнения.")
      }
      if (stockOperationLimitExceeded) {
        throw new Error(
          `В одном задании допускается не более ${MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.`
        )
      }
      const input: CreateEquipmentMovementTaskInput = {
        warehouseId: item.warehouseId,
        unitNumber: item.number,
        plannedDurationMinutes: null,
        deadlineAt: equipmentMovementDeadlineToIso(reservationDeadline),
        lines: stockTaskLines,
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
    onError: fail,
  })
  const cabinMutation = useMutation({
    mutationFn: () => {
      if (!selectedSource) throw new Error("Выберите бытовку-источник.")
      if (!accessToken) throw new Error("Сессия завершена.")
      if (!canManage || !targetEligible) {
        throw new Error("Бытовка недоступна для изменения наполнения.")
      }
      if (cabinOperationLimitExceeded) {
        throw new Error(
          `В одном задании допускается не более ${MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.`
        )
      }
      const input: CreateEquipmentMovementTaskInput = {
        warehouseId: item.warehouseId,
        unitNumber: item.number,
        plannedDurationMinutes: null,
        deadlineAt: equipmentMovementDeadlineToIso(reservationDeadline),
        lines: cabinTaskLines,
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
      <FieldGroup className="gap-4">
        <Field data-invalid={Boolean(errorText && !reservationDeadline)}>
          <FieldLabel htmlFor="add-contents-reservation-deadline">
            Резерв до
          </FieldLabel>
          <Input
            id="add-contents-reservation-deadline"
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
      <section className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 className="font-medium">Доступно на складе</h3>
          <Button
            disabled={
              selectedStock.length === 0 ||
              !reservationDeadline ||
              equipmentQuery.isFetching ||
              stockOperationLimitExceeded ||
              pending
            }
            onClick={() => stockMutation.mutate()}
          >
            {stockMutation.isPending
              ? "Создание задания…"
              : "Создать задание со склада"}
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
        {stockOperationLimitExceeded ? (
          <FieldError>
            В одном задании допускается не более{" "}
            {MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.
          </FieldError>
        ) : null}
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
              disabled={
                selectedCabin.length === 0 ||
                !reservationDeadline ||
                equipmentQuery.isFetching ||
                sourcesQuery.isFetching ||
                cabinOperationLimitExceeded ||
                pending
              }
              onClick={() => cabinMutation.mutate()}
            >
              <HugeiconsIcon icon={Exchange01Icon} data-icon="inline-start" />
              {cabinMutation.isPending
                ? "Создание задания…"
                : "Создать задание на перенос"}
            </Button>
            {cabinOperationLimitExceeded ? (
              <FieldError>
                В одном задании допускается не более{" "}
                {MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.
              </FieldError>
            ) : null}
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
