import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { getEquipmentItems } from "@/api/equipment-api"
import type { WarehouseInfo } from "@/api/warehouse-api"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
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
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { SingleDayPicker } from "@/components/ui/single-day-picker"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  createContractorDriver,
  listLogisticsDriverResources,
  type LogisticsDriverResource,
} from "@/features/logistics/warehouse-transfers/api/logistics-driver-resources-api"
import {
  TRANSFER_PLAN_QUERY_KEY,
  WAREHOUSE_TRANSFERS_QUERY_KEY,
  confirmWarehouseTransferPlan,
  createWarehouseTransfer,
  getWarehouseTransferPlan,
  updateWarehouseTransferPlan,
} from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import type {
  TransferDocument,
  TransferCabinGroupRequest,
  TransferLooseFurnitureRequest,
  TransferPlan,
  TransferPlanRequest,
  TransferResourceRepositionMode,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import {
  autoAllocateCabinGroups,
  cabinGroupMismatches,
  calculateCabinFurnitureDelta,
  calculateTransferFurnitureTotals,
  emptyTransferCabinGroup,
  type TransferCabinGroupDraft,
} from "@/features/logistics/warehouse-transfers/model/transfer-plan-form"
import { currentBusinessDate } from "@/features/logistics/use-logistics-day"
import {
  getRentalItemCreationOptions,
  listAssetRentalItems,
  rentalItemCreationOptionsQueryKey,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

/** Stable retry identity retained while a command payload is unchanged. */
type CommandAttempt = { signature: string; idempotencyKey: string }

/** Props for the end-to-end interwarehouse planning dialog. */
export type TransferPlanDialogProps = {
  accessToken: string
  currentUser: ReturnType<typeof useAuth>["currentUser"]
  warehouseId: string
  warehouses: WarehouseInfo[]
  initialDestinationWarehouseId: string | null
  /** The operation day selected in the surrounding transfers list. */
  initialScheduledDate?: string
  existingDocument?: TransferDocument | null
  existingPlan?: TransferPlan | null
  onCreated: (document: TransferDocument) => void
  onLegacyCreate: () => void
  onOpenChange: (open: boolean) => void
}

function identity() {
  return crypto.randomUUID()
}

function iso(value: string) {
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? date.toISOString() : null
}

function localDateTimeInput(value: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ""
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function resourceIntent(
  resourceId: string,
  mode: TransferResourceRepositionMode,
  until: string
) {
  if (mode === "NONE") {
    return { resourceId: null, mode, until: null }
  }
  return {
    resourceId: resourceId || null,
    mode,
    until: mode === "TEMPORARY" ? iso(until) : null,
  }
}

function catalogName(
  items: readonly { id: string; name: string }[],
  id: string | null
) {
  return items.find((item) => item.id === id)?.name ?? "Не выбрано"
}

function furnitureName(items: readonly EquipmentItemDto[], id: string) {
  return items.find((item) => item.id === id)?.name ?? "Позиция недоступна"
}

function selectedCabinIds(groups: readonly TransferCabinGroupDraft[]) {
  return new Set(
    groups.flatMap((group) =>
      group.allocatedCabins.map((allocation) => allocation.assetId)
    )
  )
}

/** Removes the client-only key before crossing the logistics API boundary. */
function groupRequest(
  group: TransferCabinGroupDraft
): TransferCabinGroupRequest {
  return {
    rentalTypeId: group.rentalTypeId,
    dimensionId: group.dimensionId,
    finishingId: group.finishingId,
    characteristicIds: group.characteristicIds,
    linoleum: group.linoleum,
    quantity: group.quantity,
    furniturePerCabin: group.furniturePerCabin,
    allocatedCabins: group.allocatedCabins,
  }
}

/** Full planning UI for a transfer draft and its explicit confirmation. */
export function TransferPlanDialog({
  accessToken,
  currentUser,
  warehouseId,
  warehouses,
  initialDestinationWarehouseId,
  initialScheduledDate,
  existingDocument = null,
  existingPlan = null,
  onCreated,
  onLegacyCreate,
  onOpenChange,
}: TransferPlanDialogProps) {
  const queryClient = useQueryClient()
  const createAttempt = useRef<CommandAttempt | null>(null)
  const updateAttempt = useRef<CommandAttempt | null>(null)
  const confirmAttempt = useRef<CommandAttempt | null>(null)
  const destinations = warehouses.filter(
    (warehouse) =>
      warehouse.active &&
      warehouse.id !== warehouseId &&
      hasWarehouseAccess(currentUser, warehouse.id, "EDIT")
  )
  const sourceWarehouse = warehouses.find(
    (warehouse) => warehouse.id === warehouseId
  )
  const sourceBusinessDate = sourceWarehouse
    ? currentBusinessDate(sourceWarehouse.timeZone)
    : null
  const destinationCandidate =
    existingDocument?.destinationWarehouseId ?? initialDestinationWarehouseId
  const validInitialDestination = destinations.some(
    (warehouse) => warehouse.id === destinationCandidate
  )
    ? destinationCandidate!
    : ""
  const [destinationWarehouseId, setDestinationWarehouseId] = useState(
    validInitialDestination
  )
  const canConfirmPlan =
    destinationWarehouseId !== "" &&
    hasWarehouseAccess(currentUser, warehouseId, "MANAGE") &&
    hasWarehouseAccess(currentUser, destinationWarehouseId, "MANAGE")
  const [scheduledDate, setScheduledDate] = useState(
    existingPlan?.scheduledDate ??
      existingDocument?.scheduledDate ??
      (initialScheduledDate || sourceBusinessDate || "")
  )
  const [plannedDepartureAt, setPlannedDepartureAt] = useState(
    localDateTimeInput(existingPlan?.plannedDepartureAt ?? null)
  )
  const [plannedArrivalAt, setPlannedArrivalAt] = useState(
    localDateTimeInput(existingPlan?.plannedArrivalAt ?? null)
  )
  const [logisticsComment, setLogisticsComment] = useState(
    existingPlan?.logisticsComment ?? ""
  )
  const [tripDriverId, setTripDriverId] = useState(
    existingPlan?.tripDriverId ?? ""
  )
  const [driverRepositionMode, setDriverRepositionMode] =
    useState<TransferResourceRepositionMode>(
      existingPlan?.driverReposition.mode ?? "NONE"
    )
  const [repositionDriverId, setRepositionDriverId] = useState(
    existingPlan?.driverReposition.resourceId ?? ""
  )
  const [driverRepositionUntil, setDriverRepositionUntil] = useState(
    localDateTimeInput(existingPlan?.driverReposition.until ?? null)
  )
  const [groups, setGroups] = useState<TransferCabinGroupDraft[]>(() =>
    (existingPlan?.cabinGroups ?? []).map((group) => ({
      key: group.groupId,
      rentalTypeId: group.rentalTypeId,
      dimensionId: group.dimensionId,
      finishingId: group.finishingId,
      characteristicIds: group.characteristicIds,
      linoleum: group.linoleum,
      quantity: group.quantity,
      furniturePerCabin: group.furniturePerCabin.map((item) => ({
        furnitureCatalogItemId: item.furnitureCatalogItemId,
        quantityPerCabin: item.quantityPerCabin,
      })),
      allocatedCabins: group.allocatedCabins,
    }))
  )
  const [looseFurniture, setLooseFurniture] = useState<
    TransferLooseFurnitureRequest[]
  >(existingPlan?.looseFurniture ?? [])
  const [contractorOpen, setContractorOpen] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [createdDocument, setCreatedDocument] =
    useState<TransferDocument | null>(existingDocument)
  const [createdPlan, setCreatedPlan] = useState<TransferPlan | null>(
    existingPlan
  )
  const [dirty, setDirty] = useState(false)

  const creationOptionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(warehouseId),
    queryFn: () => getRentalItemCreationOptions(accessToken, warehouseId),
  })
  const cabinsQuery = useQuery({
    queryKey: ["rental-items", "transfer-plan-candidates", warehouseId],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: 0,
        size: 200,
      }),
  })
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "transfer-plan", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
  })
  const departureIso = iso(plannedDepartureAt)
  const driversQuery = useQuery({
    queryKey: [
      "task-board",
      "logistics-driver-resources",
      warehouseId,
      departureIso,
    ],
    queryFn: () =>
      listLogisticsDriverResources({
        accessToken,
        warehouseId,
        at: departureIso!,
        includeIncoming: true,
      }),
    enabled: departureIso !== null,
  })
  const cabins = useMemo(
    () => cabinsQuery.data?.content ?? [],
    [cabinsQuery.data?.content]
  )
  const cabinById = useMemo(
    () => new Map(cabins.map((cabin) => [cabin.id, cabin])),
    [cabins]
  )
  const furniture = (equipmentQuery.data ?? []).filter(
    (item) => item.active && item.category === "FURNITURE"
  )
  const equipmentById = new Map(furniture.map((item) => [item.id, item]))
  const furnitureTotals = calculateTransferFurnitureTotals(
    groups,
    looseFurniture
  )
  const allocatedIds = selectedCabinIds(groups)

  function changed() {
    createAttempt.current = null
    setDirty(createdDocument !== null)
    setValidationError(null)
  }

  function planRequest(): TransferPlanRequest {
    return {
      plannedDepartureAt: iso(plannedDepartureAt),
      plannedArrivalAt: iso(plannedArrivalAt),
      logisticsComment: logisticsComment.trim() || null,
      tripDriverId: tripDriverId || null,
      tripVehicleId: null,
      driverReposition: resourceIntent(
        repositionDriverId,
        driverRepositionMode,
        driverRepositionUntil
      ),
      vehicleReposition: null,
      cabinGroups: groups.map(groupRequest),
      looseFurniture,
    }
  }

  function validateDraft() {
    if (!destinationWarehouseId || destinationWarehouseId === warehouseId) {
      return "Выберите другой активный склад назначения."
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(scheduledDate)) {
      return "Укажите плановую дату."
    }
    if (!sourceBusinessDate) {
      return "Не удалось определить часовой пояс склада-источника."
    }
    if (scheduledDate < sourceBusinessDate) {
      return "Плановая дата не может быть в прошлом."
    }
    const departure = iso(plannedDepartureAt)
    const arrival = iso(plannedArrivalAt)
    if (departure && arrival && Date.parse(arrival) <= Date.parse(departure)) {
      return "Прибытие должно быть позже отправления."
    }
    if (
      groups.some(
        (group) =>
          !group.rentalTypeId ||
          group.quantity < 1 ||
          group.furniturePerCabin.some(
            (item) => !item.furnitureCatalogItemId || item.quantityPerCabin < 1
          )
      )
    ) {
      return "Заполните тип, количество и мебель каждой группы бытовок."
    }
    if (
      groups.some(
        (group) =>
          new Set(
            group.furniturePerCabin.map((item) => item.furnitureCatalogItemId)
          ).size !== group.furniturePerCabin.length
      )
    ) {
      return "Одну позицию мебели можно добавить в группу только один раз."
    }
    if (
      looseFurniture.some(
        (item) => !item.furnitureCatalogItemId || item.quantity < 1
      )
    ) {
      return "Заполните все строки отдельной мебели."
    }
    if (
      new Set(looseFurniture.map((item) => item.furnitureCatalogItemId))
        .size !== looseFurniture.length
    ) {
      return "Одну позицию отдельной мебели можно добавить только один раз."
    }
    if (driverRepositionMode !== "NONE" && !repositionDriverId) {
      return "Выберите водителя, который меняет оперативный склад."
    }
    if (
      driverRepositionMode === "TEMPORARY" &&
      (!iso(driverRepositionUntil) ||
        (arrival &&
          Date.parse(iso(driverRepositionUntil)!) <= Date.parse(arrival)))
    ) {
      return "Окончание временного назначения должно быть позже прибытия."
    }
    return null
  }

  const missingAllocations = groups.reduce(
    (total, group) =>
      total + Math.max(0, group.quantity - group.allocatedCabins.length),
    0
  )
  const requiredFromStock = useMemo(() => {
    const totals = new Map<string, number>()
    for (const item of looseFurniture) {
      totals.set(
        item.furnitureCatalogItemId,
        (totals.get(item.furnitureCatalogItemId) ?? 0) + item.quantity
      )
    }
    for (const group of groups) {
      for (const allocation of group.allocatedCabins) {
        const cabin = cabinById.get(allocation.assetId)
        if (!cabin) continue
        for (const delta of calculateCabinFurnitureDelta(
          cabin,
          group.furniturePerCabin
        )) {
          if (delta.delta > 0) {
            totals.set(
              delta.furnitureCatalogItemId,
              (totals.get(delta.furnitureCatalogItemId) ?? 0) + delta.delta
            )
          }
        }
      }
      const unallocated = Math.max(
        0,
        group.quantity - group.allocatedCabins.length
      )
      for (const item of group.furniturePerCabin) {
        totals.set(
          item.furnitureCatalogItemId,
          (totals.get(item.furnitureCatalogItemId) ?? 0) +
            item.quantityPerCabin * unallocated
        )
      }
    }
    return totals
  }, [cabinById, groups, looseFurniture])
  const furnitureShortages = [...requiredFromStock.entries()].filter(
    ([id, quantity]) => quantity > (equipmentById.get(id)?.availableStock ?? 0)
  )
  const confirmProblems = [
    !canConfirmPlan
      ? "Для подтверждения требуется право управления обоими складами."
      : null,
    !iso(plannedDepartureAt) ? "Не указано время отправления." : null,
    !iso(plannedArrivalAt) ? "Не указано время прибытия." : null,
    !tripDriverId ? "Не назначен водитель рейса." : null,
    missingAllocations > 0
      ? `Не назначено бытовок: ${missingAllocations}.`
      : null,
    ...furnitureShortages.map(
      ([id, quantity]) =>
        `${furnitureName(furniture, id)}: требуется со склада ${quantity}, доступно ${equipmentById.get(id)?.availableStock ?? 0}.`
    ),
  ].filter((item): item is string => item !== null)

  const createMutation = useMutation({
    mutationFn: async ({
      plan,
      idempotencyKey,
    }: {
      plan: TransferPlanRequest
      idempotencyKey: string
    }) => {
      const document = await createWarehouseTransfer({
        accessToken,
        warehouseId,
        destinationWarehouseId,
        scheduledDate,
        lines: [],
        furnitureReplacements: [],
        plan,
        idempotencyKey,
      })
      const projection = await getWarehouseTransferPlan(
        accessToken,
        document.id
      )
      return { document, projection }
    },
    onSuccess: ({ document, projection }) => {
      setCreatedDocument(document)
      setCreatedPlan(projection)
      setDirty(false)
      onCreated(document)
      queryClient.setQueryData(
        [...TRANSFER_PLAN_QUERY_KEY, document.id],
        projection
      )
    },
  })
  const updateMutation = useMutation({
    mutationFn: ({
      plan,
      idempotencyKey,
    }: {
      plan: TransferPlanRequest
      idempotencyKey: string
    }) =>
      updateWarehouseTransferPlan({
        accessToken,
        documentId: createdDocument!.id,
        expectedVersion: createdPlan!.documentVersion,
        scheduledDate,
        plan,
        idempotencyKey,
      }),
    onSuccess: (plan) => {
      setCreatedPlan(plan)
      setDirty(false)
      queryClient.setQueryData(
        [...TRANSFER_PLAN_QUERY_KEY, plan.transferId],
        plan
      )
    },
  })
  const confirmMutation = useMutation({
    mutationFn: (idempotencyKey: string) =>
      confirmWarehouseTransferPlan({
        accessToken,
        documentId: createdDocument!.id,
        expectedVersion: createdPlan!.documentVersion,
        idempotencyKey,
      }),
    onSuccess: (plan) => {
      setCreatedPlan(plan)
      queryClient.setQueryData(
        [...TRANSFER_PLAN_QUERY_KEY, plan.transferId],
        plan
      )
      void queryClient.invalidateQueries({
        queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
      })
    },
  })

  function saveDraft() {
    const error = validateDraft()
    if (error) {
      setValidationError(error)
      return
    }
    const plan = planRequest()
    if (createdDocument) {
      const signature = JSON.stringify({
        documentId: createdDocument.id,
        expectedVersion: createdPlan?.documentVersion,
        scheduledDate,
        plan,
      })
      const idempotencyKey =
        updateAttempt.current?.signature === signature
          ? updateAttempt.current.idempotencyKey
          : identity()
      updateAttempt.current = { signature, idempotencyKey }
      updateMutation.mutate({ plan, idempotencyKey })
      return
    }
    const signature = JSON.stringify({
      warehouseId,
      destinationWarehouseId,
      scheduledDate,
      plan,
    })
    const idempotencyKey =
      createAttempt.current?.signature === signature
        ? createAttempt.current.idempotencyKey
        : identity()
    createAttempt.current = { signature, idempotencyKey }
    createMutation.mutate({ plan, idempotencyKey })
  }

  const pending =
    createMutation.isPending ||
    updateMutation.isPending ||
    confirmMutation.isPending
  const options = creationOptionsQuery.data

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>
            {existingDocument
              ? "Изменить межскладское перемещение"
              : "Создать межскладское перемещение"}
          </DialogTitle>
          <DialogDescription>
            Черновик не резервирует имущество. Выберите конкретные бытовки и
            требуемое наполнение: при подтверждении сервер атомарно проверит и
            зарезервирует их, а для разницы состава создаст задания подготовки.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-5 py-3">
          <FieldSet disabled={pending}>
            <FieldLegend>1. Маршрут</FieldLegend>
            <FieldGroup>
              <div className="grid gap-4 md:grid-cols-2">
                <Field>
                  <FieldLabel>Откуда</FieldLabel>
                  <Input
                    value={
                      warehouses.find(
                        (warehouse) => warehouse.id === warehouseId
                      )?.name ?? "Склад недоступен"
                    }
                    disabled
                  />
                </Field>
                <Field>
                  <FieldLabel>Куда</FieldLabel>
                  <Select
                    value={destinationWarehouseId}
                    disabled={existingDocument !== null}
                    onValueChange={(value) => {
                      setDestinationWarehouseId(value)
                      changed()
                    }}
                  >
                    <SelectTrigger aria-label="Склад назначения">
                      <SelectValue placeholder="Выберите склад" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {destinations.map((warehouse) => (
                          <SelectItem key={warehouse.id} value={warehouse.id}>
                            {warehouse.name} · {warehouse.city}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
              </div>
              <div className="grid gap-4 md:grid-cols-3">
                <SingleDayPicker
                  id="transfer-plan-date"
                  label="Дата"
                  value={scheduledDate}
                  disabled={pending}
                  onValueChange={(value) => {
                    setScheduledDate(value)
                    changed()
                  }}
                />
                <Field>
                  <FieldLabel htmlFor="transfer-plan-departure">
                    Отправление
                  </FieldLabel>
                  <Input
                    id="transfer-plan-departure"
                    type="datetime-local"
                    value={plannedDepartureAt}
                    onChange={(event) => {
                      setPlannedDepartureAt(event.target.value)
                      setTripDriverId("")
                      changed()
                    }}
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="transfer-plan-arrival">
                    Прибытие
                  </FieldLabel>
                  <Input
                    id="transfer-plan-arrival"
                    type="datetime-local"
                    value={plannedArrivalAt}
                    onChange={(event) => {
                      setPlannedArrivalAt(event.target.value)
                      changed()
                    }}
                  />
                </Field>
              </div>
            </FieldGroup>
          </FieldSet>

          <Separator />

          <FieldSet disabled={pending}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <FieldLegend>2. Исполнитель</FieldLegend>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setContractorOpen(true)}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Добавить наёмного водителя
              </Button>
            </div>
            <FieldGroup>
              <Field>
                <FieldLabel>Водитель рейса</FieldLabel>
                <Select
                  value={tripDriverId}
                  disabled={!departureIso || driversQuery.isFetching}
                  onValueChange={(value) => {
                    setTripDriverId(value)
                    changed()
                  }}
                >
                  <SelectTrigger aria-label="Водитель рейса">
                    <SelectValue
                      placeholder={
                        departureIso
                          ? "Выберите доступного водителя"
                          : "Сначала укажите время отправления"
                      }
                    />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {(driversQuery.data ?? []).map((driver) => (
                        <SelectItem
                          key={driver.workerId}
                          value={driver.workerId}
                        >
                          {driver.displayName}
                          {driver.employmentType === "CONTRACTOR"
                            ? " · наёмный"
                            : ""}
                          {driver.availabilityKind === "INCOMING"
                            ? " · прибывающий"
                            : ""}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
                {driversQuery.isError ? (
                  <FieldError>
                    Не удалось проверить доступных водителей.
                  </FieldError>
                ) : null}
              </Field>
              <Alert>
                <AlertTitle>
                  Автомобиль пока не назначается из панели
                </AlertTitle>
                <AlertDescription>
                  Публичного справочника автомобилей с доступностью и
                  вместимостью нет. Поле не подменяется ручным UUID; серверная
                  проверка автомобиля остаётся обязательной перед рейсом.
                </AlertDescription>
              </Alert>
            </FieldGroup>
          </FieldSet>

          <FieldSet disabled={pending}>
            <FieldLegend>3. Дальнейшее назначение водителя</FieldLegend>
            <div className="grid gap-4 md:grid-cols-2">
              <Field>
                <FieldLabel>После прибытия</FieldLabel>
                <Select
                  value={driverRepositionMode}
                  onValueChange={(value) => {
                    const mode = value as TransferResourceRepositionMode
                    setDriverRepositionMode(mode)
                    if (mode === "NONE") setRepositionDriverId("")
                    else if (!repositionDriverId) {
                      setRepositionDriverId(tripDriverId)
                    }
                    changed()
                  }}
                >
                  <SelectTrigger aria-label="Назначение водителя после прибытия">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="NONE">Только выполняет рейс</SelectItem>
                    <SelectItem value="TEMPORARY">
                      Временно работает на складе назначения
                    </SelectItem>
                    <SelectItem value="PERMANENT">
                      Постоянно работает на складе назначения
                    </SelectItem>
                  </SelectContent>
                </Select>
              </Field>
              {driverRepositionMode !== "NONE" ? (
                <Field>
                  <FieldLabel>Перемещаемый водитель</FieldLabel>
                  <Select
                    value={repositionDriverId}
                    onValueChange={(value) => {
                      setRepositionDriverId(value)
                      changed()
                    }}
                  >
                    <SelectTrigger aria-label="Перемещаемый водитель">
                      <SelectValue placeholder="Выберите водителя" />
                    </SelectTrigger>
                    <SelectContent>
                      {(driversQuery.data ?? []).map((driver) => (
                        <SelectItem
                          key={driver.workerId}
                          value={driver.workerId}
                        >
                          {driver.displayName}
                          {driver.workerId === tripDriverId
                            ? " · выполняет рейс"
                            : ""}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </Field>
              ) : null}
              {driverRepositionMode === "TEMPORARY" ? (
                <Field>
                  <FieldLabel htmlFor="driver-reposition-until">
                    Работает до
                  </FieldLabel>
                  <Input
                    id="driver-reposition-until"
                    type="datetime-local"
                    value={driverRepositionUntil}
                    onChange={(event) => {
                      setDriverRepositionUntil(event.target.value)
                      changed()
                    }}
                  />
                </Field>
              ) : null}
            </div>
            <FieldDescription>
              Домашний склад не меняется при сохранении черновика. Оперативное
              назначение активируется только после завершения перемещения.
            </FieldDescription>
          </FieldSet>

          <Separator />

          <FieldSet disabled={pending}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <FieldLegend>4. Бытовки</FieldLegend>
                <FieldDescription>
                  Требования задаются по справочнику; экземпляры можно подобрать
                  автоматически или заменить вручную.
                </FieldDescription>
              </div>
              <div className="flex flex-wrap gap-2">
                {groups.length > 0 ? (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setGroups(
                        autoAllocateCabinGroups(groups, cabins, warehouseId)
                      )
                      changed()
                    }}
                  >
                    Подобрать бытовки
                  </Button>
                ) : null}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setGroups((current) => [
                      ...current,
                      emptyTransferCabinGroup(identity()),
                    ])
                    changed()
                  }}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить бытовки
                </Button>
              </div>
            </div>
            {creationOptionsQuery.isError ? (
              <FieldError>Не удалось загрузить справочники бытовок.</FieldError>
            ) : null}
            {cabinsQuery.isError ? (
              <FieldError>Не удалось загрузить остатки бытовок.</FieldError>
            ) : null}
            {groups.length === 0 ? (
              <Card size="sm">
                <CardContent className="py-4 text-sm text-muted-foreground">
                  Бытовки не добавлены. Допустим черновик перемещения только
                  водителя или отдельной мебели.
                </CardContent>
              </Card>
            ) : null}
            {options
              ? groups.map((group, index) => (
                  <CabinGroupEditor
                    key={group.key}
                    index={index}
                    group={group}
                    cabins={cabins}
                    allocatedElsewhere={allocatedIds}
                    sourceWarehouseId={warehouseId}
                    options={options}
                    furniture={furniture}
                    onChange={(next) => {
                      setGroups((current) =>
                        current.map((item) =>
                          item.key === group.key ? next : item
                        )
                      )
                      changed()
                    }}
                    onDelete={() => {
                      setGroups((current) =>
                        current.filter((item) => item.key !== group.key)
                      )
                      changed()
                    }}
                  />
                ))
              : null}
          </FieldSet>

          <Separator />

          <FurnitureRows
            title="5. Отдельная мебель"
            items={looseFurniture.map((item) => ({
              furnitureCatalogItemId: item.furnitureCatalogItemId,
              quantity: item.quantity,
            }))}
            furniture={furniture}
            quantityLabel="Количество"
            onChange={(items) => {
              setLooseFurniture(
                items.map((item) => ({
                  furnitureCatalogItemId: item.furnitureCatalogItemId,
                  quantity: item.quantity,
                }))
              )
              changed()
            }}
          />

          <Separator />

          <FieldSet>
            <FieldLegend>6. Итог и готовность</FieldLegend>
            <div className="grid gap-3 md:grid-cols-3">
              <Card size="sm">
                <CardHeader>
                  <CardTitle>Бытовки</CardTitle>
                </CardHeader>
                <CardContent className="text-2xl font-semibold">
                  {groups.reduce((total, group) => total + group.quantity, 0)}
                </CardContent>
              </Card>
              <Card size="sm">
                <CardHeader>
                  <CardTitle>Назначено экземпляров</CardTitle>
                </CardHeader>
                <CardContent className="text-2xl font-semibold">
                  {allocatedIds.size}
                </CardContent>
              </Card>
              <Card size="sm">
                <CardHeader>
                  <CardTitle>Позиций мебели</CardTitle>
                </CardHeader>
                <CardContent className="text-2xl font-semibold">
                  {furnitureTotals.length}
                </CardContent>
              </Card>
            </div>
            {furnitureTotals.length > 0 ? (
              <ul className="grid gap-1 text-sm md:grid-cols-2">
                {furnitureTotals.map((item) => (
                  <li key={item.furnitureCatalogItemId}>
                    {furnitureName(furniture, item.furnitureCatalogItemId)} —{" "}
                    {item.totalQuantity} (в бытовки{" "}
                    {item.cabinRequirementQuantity}, отдельно{" "}
                    {item.looseQuantity})
                  </li>
                ))}
              </ul>
            ) : (
              <FieldDescription>Мебель не добавлена.</FieldDescription>
            )}
            {createdPlan?.state === "CONFIRMED" ? (
              <Alert>
                <AlertTitle>Перемещение подтверждено</AlertTitle>
                <AlertDescription>
                  Конкретные бытовки и мебель зарезервированы. Если фактическое
                  наполнение отличается от требуемого, созданы задания на эту
                  разницу. Выезд станет доступен после готовности наполнения.
                </AlertDescription>
              </Alert>
            ) : confirmProblems.length > 0 ? (
              <Alert variant="destructive">
                <AlertTitle>Подтверждение пока недоступно</AlertTitle>
                <AlertDescription>
                  <ul className="list-disc pl-4">
                    {confirmProblems.map((problem) => (
                      <li key={problem}>{problem}</li>
                    ))}
                  </ul>
                </AlertDescription>
              </Alert>
            ) : (
              <Alert>
                <AlertTitle>
                  Готово к резервированию и созданию заданий
                </AlertTitle>
                <AlertDescription>
                  Logistics-service атомарно повторно проверит и зарезервирует
                  выбранные бытовки и мебель. Для разницы между фактическим и
                  требуемым наполнением будут созданы задания подготовки; выезд
                  станет доступен после их завершения.
                </AlertDescription>
              </Alert>
            )}
            <Field>
              <FieldLabel htmlFor="transfer-logistics-comment">
                Комментарий логиста
              </FieldLabel>
              <Textarea
                id="transfer-logistics-comment"
                value={logisticsComment}
                maxLength={2000}
                onChange={(event) => {
                  setLogisticsComment(event.target.value)
                  changed()
                }}
              />
            </Field>
            {createdPlan ? (
              <div className="flex flex-wrap gap-2">
                <Badge variant="outline">
                  План:{" "}
                  {createdPlan.state === "CONFIRMED"
                    ? "подтверждён"
                    : "черновик"}
                </Badge>
                <Badge variant="outline">
                  Резерв:{" "}
                  {createdPlan.reservationReadiness === "RESERVED"
                    ? "создан"
                    : "не создан"}
                </Badge>
                {createdPlan.readinessDetail ? (
                  <span className="text-sm text-muted-foreground">
                    {createdPlan.readinessDetail}
                  </span>
                ) : null}
              </div>
            ) : null}
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {createMutation.error ||
            updateMutation.error ||
            confirmMutation.error ? (
              <FieldError>
                {errorMessage(
                  createMutation.error ??
                    updateMutation.error ??
                    confirmMutation.error,
                  "Не удалось сохранить план перемещения"
                )}
              </FieldError>
            ) : null}
          </FieldSet>
        </div>

        <DialogFooter className="flex-wrap">
          {!createdDocument ? (
            <Button type="button" variant="ghost" onClick={onLegacyCreate}>
              Выбрать конкретные бытовки без планирования
            </Button>
          ) : null}
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Закрыть
          </Button>
          {createdPlan?.state !== "CONFIRMED" ? (
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={saveDraft}
            >
              {pending
                ? "Сохраняется…"
                : createdDocument
                  ? "Сохранить изменения"
                  : "Сохранить черновик"}
            </Button>
          ) : null}
          {createdDocument && createdPlan?.state === "DRAFT" ? (
            <Button
              type="button"
              disabled={pending || dirty || confirmProblems.length > 0}
              onClick={() => {
                const signature = `${createdDocument.id}:${createdPlan.documentVersion}`
                const idempotencyKey =
                  confirmAttempt.current?.signature === signature
                    ? confirmAttempt.current.idempotencyKey
                    : identity()
                confirmAttempt.current = { signature, idempotencyKey }
                confirmMutation.mutate(idempotencyKey)
              }}
            >
              Подтвердить и создать задания
            </Button>
          ) : null}
        </DialogFooter>

        {contractorOpen ? (
          <ContractorDriverDialog
            accessToken={accessToken}
            warehouseId={warehouseId}
            onOpenChange={setContractorOpen}
            onCreated={(driver) => {
              queryClient.setQueryData<LogisticsDriverResource[]>(
                [
                  "task-board",
                  "logistics-driver-resources",
                  warehouseId,
                  departureIso,
                ],
                (current) => [
                  driver,
                  ...(current ?? []).filter(
                    (item) => item.workerId !== driver.workerId
                  ),
                ]
              )
              setTripDriverId(driver.workerId)
              setContractorOpen(false)
              changed()
            }}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

/** Shared editable row shape for per-cabin and independent furniture. */
type FurnitureDraft = { furnitureCatalogItemId: string; quantity: number }

function FurnitureRows({
  title,
  items,
  furniture,
  quantityLabel,
  onChange,
}: {
  title: string
  items: FurnitureDraft[]
  furniture: EquipmentItemDto[]
  quantityLabel: string
  onChange: (items: FurnitureDraft[]) => void
}) {
  return (
    <FieldSet>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <FieldLegend>{title}</FieldLegend>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={furniture.length === 0}
          onClick={() =>
            onChange([...items, { furnitureCatalogItemId: "", quantity: 1 }])
          }
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить мебель
        </Button>
      </div>
      {items.length === 0 ? (
        <FieldDescription>Мебель не добавлена.</FieldDescription>
      ) : null}
      <FieldGroup>
        {items.map((item, index) => (
          <div
            key={`${index}:${item.furnitureCatalogItemId}`}
            className="grid items-end gap-2 rounded-lg border p-3 md:grid-cols-[minmax(0,1fr)_10rem_auto]"
          >
            <Field>
              <FieldLabel>Позиция {index + 1}</FieldLabel>
              <Select
                value={item.furnitureCatalogItemId}
                onValueChange={(value) =>
                  onChange(
                    items.map((current, currentIndex) =>
                      currentIndex === index
                        ? { ...current, furnitureCatalogItemId: value }
                        : current
                    )
                  )
                }
              >
                <SelectTrigger aria-label={`Мебель ${index + 1}`}>
                  <SelectValue placeholder="Выберите позицию" />
                </SelectTrigger>
                <SelectContent>
                  {furniture.map((equipment) => (
                    <SelectItem key={equipment.id} value={equipment.id}>
                      {equipment.name} · доступно {equipment.availableStock} ·
                      резерв {equipment.reservedQuantity}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor={`furniture-quantity-${title}-${index}`}>
                {quantityLabel}
              </FieldLabel>
              <Input
                id={`furniture-quantity-${title}-${index}`}
                type="number"
                min={1}
                value={item.quantity}
                onChange={(event) =>
                  onChange(
                    items.map((current, currentIndex) =>
                      currentIndex === index
                        ? {
                            ...current,
                            quantity: Math.max(
                              1,
                              Number(event.target.value) || 1
                            ),
                          }
                        : current
                    )
                  )
                }
              />
            </Field>
            <Button
              type="button"
              size="icon-sm"
              variant="outline"
              aria-label={`Удалить мебель ${index + 1}`}
              onClick={() =>
                onChange(
                  items.filter(
                    (_current, currentIndex) => currentIndex !== index
                  )
                )
              }
            >
              <HugeiconsIcon icon={Delete02Icon} />
            </Button>
          </div>
        ))}
      </FieldGroup>
    </FieldSet>
  )
}

function CabinGroupEditor({
  index,
  group,
  cabins,
  allocatedElsewhere,
  sourceWarehouseId,
  options,
  furniture,
  onChange,
  onDelete,
}: {
  index: number
  group: TransferCabinGroupDraft
  cabins: RentalItemDto[]
  allocatedElsewhere: ReadonlySet<string>
  sourceWarehouseId: string
  options: Awaited<ReturnType<typeof getRentalItemCreationOptions>>
  furniture: EquipmentItemDto[]
  onChange: (group: TransferCabinGroupDraft) => void
  onDelete: () => void
}) {
  const dimensions = options.dimensions.filter((dimension) =>
    options.typeDimensions.some(
      (pair) =>
        pair.typeId === group.rentalTypeId && pair.dimensionId === dimension.id
    )
  )
  const allocated = new Set(
    group.allocatedCabins.map((allocation) => allocation.assetId)
  )
  const candidateCabins = cabins.filter((cabin) => {
    if (allocated.has(cabin.id)) {
      return true
    }
    return (
      !allocatedElsewhere.has(cabin.id) &&
      cabinGroupMismatches(cabin, group, sourceWarehouseId).length === 0
    )
  })
  return (
    <Card size="sm" aria-label={`Группа бытовок ${index + 1}`}>
      <CardHeader>
        <CardTitle>Группа {index + 1}</CardTitle>
        <CardAction>
          <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
            Удалить группу
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field>
            <FieldLabel>Тип бытовки</FieldLabel>
            <Select
              value={group.rentalTypeId}
              onValueChange={(rentalTypeId) =>
                onChange({
                  ...group,
                  rentalTypeId,
                  dimensionId: null,
                  allocatedCabins: [],
                })
              }
            >
              <SelectTrigger aria-label={`Тип бытовки группы ${index + 1}`}>
                <SelectValue placeholder="Выберите тип" />
              </SelectTrigger>
              <SelectContent>
                {options.rentalTypes.map((item) => (
                  <SelectItem key={item.id} value={item.id}>
                    {item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>Исполнение / габариты</FieldLabel>
            <Select
              value={group.dimensionId ?? ""}
              onValueChange={(dimensionId) =>
                onChange({ ...group, dimensionId, allocatedCabins: [] })
              }
            >
              <SelectTrigger aria-label={`Исполнение группы ${index + 1}`}>
                <SelectValue placeholder="Не указано" />
              </SelectTrigger>
              <SelectContent>
                {dimensions.map((item) => (
                  <SelectItem key={item.id} value={item.id}>
                    {item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>Отделка</FieldLabel>
            <Select
              value={group.finishingId ?? ""}
              onValueChange={(finishingId) =>
                onChange({ ...group, finishingId, allocatedCabins: [] })
              }
            >
              <SelectTrigger aria-label={`Отделка группы ${index + 1}`}>
                <SelectValue placeholder="Не указано" />
              </SelectTrigger>
              <SelectContent>
                {options.finishings.map((item) => (
                  <SelectItem key={item.id} value={item.id}>
                    {item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel htmlFor={`group-quantity-${group.key}`}>
              Количество
            </FieldLabel>
            <Input
              id={`group-quantity-${group.key}`}
              aria-label={`Количество бытовок группы ${index + 1}`}
              type="number"
              min={1}
              max={100}
              value={group.quantity}
              onChange={(event) => {
                const quantity = Math.min(
                  100,
                  Math.max(1, Number(event.target.value) || 1)
                )
                onChange({
                  ...group,
                  quantity,
                  allocatedCabins: group.allocatedCabins.slice(0, quantity),
                })
              }}
            />
          </Field>
        </div>
        <label className="flex items-center gap-2 text-sm font-medium">
          <Checkbox
            checked={group.linoleum === true}
            onCheckedChange={(checked) =>
              onChange({
                ...group,
                linoleum: checked === true,
                allocatedCabins: [],
              })
            }
          />
          Линолеум
        </label>
        {options.characteristics.length > 0 ? (
          <div
            className="flex flex-wrap gap-3"
            aria-label={`Характеристики группы ${index + 1}`}
          >
            {options.characteristics.map((characteristic) => (
              <label
                key={characteristic.id}
                className="flex items-center gap-2 text-sm"
              >
                <Checkbox
                  checked={group.characteristicIds.includes(characteristic.id)}
                  onCheckedChange={(checked) =>
                    onChange({
                      ...group,
                      characteristicIds:
                        checked === true
                          ? [...group.characteristicIds, characteristic.id]
                          : group.characteristicIds.filter(
                              (id) => id !== characteristic.id
                            ),
                      allocatedCabins: [],
                    })
                  }
                />
                {characteristic.name}
              </label>
            ))}
          </div>
        ) : null}

        <FurnitureRows
          title="Мебель на одну бытовку"
          items={group.furniturePerCabin.map((item) => ({
            furnitureCatalogItemId: item.furnitureCatalogItemId,
            quantity: item.quantityPerCabin,
          }))}
          furniture={furniture}
          quantityLabel="На одну бытовку"
          onChange={(items) =>
            onChange({
              ...group,
              furniturePerCabin: items.map((item) => ({
                furnitureCatalogItemId: item.furnitureCatalogItemId,
                quantityPerCabin: item.quantity,
              })),
            })
          }
        />

        <FieldSet>
          <FieldLegend>Конкретные бытовки</FieldLegend>
          <FieldDescription>
            Выбрано {group.allocatedCabins.length} из {group.quantity}. Черновик
            можно сохранить без выбора; подтверждение — нельзя.
          </FieldDescription>
          <div className="grid gap-2 md:grid-cols-2">
            {candidateCabins.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Нет свободных бытовок, подходящих под требования группы.
              </p>
            ) : null}
            {candidateCabins.map((cabin) => {
              const mismatches = cabinGroupMismatches(
                cabin,
                group,
                sourceWarehouseId
              )
              const selected = allocated.has(cabin.id)
              const usedByOther = allocatedElsewhere.has(cabin.id) && !selected
              const disabled =
                !selected &&
                (mismatches.length > 0 ||
                  usedByOther ||
                  group.allocatedCabins.length >= group.quantity)
              const delta = selected
                ? calculateCabinFurnitureDelta(cabin, group.furniturePerCabin)
                : []
              return (
                <label
                  key={cabin.id}
                  className="grid gap-1 rounded-lg border p-3 text-sm has-[[data-checked]]:border-primary"
                >
                  <span className="flex items-center gap-2 font-medium">
                    <Checkbox
                      checked={selected}
                      disabled={disabled}
                      aria-label={`Выбрать бытовку ${cabin.number}`}
                      onCheckedChange={(checked) =>
                        onChange({
                          ...group,
                          allocatedCabins:
                            checked === true
                              ? [
                                  ...group.allocatedCabins,
                                  {
                                    assetId: cabin.id,
                                    assetVersion: cabin.version,
                                  },
                                ]
                              : group.allocatedCabins.filter(
                                  (allocation) =>
                                    allocation.assetId !== cabin.id
                                ),
                        })
                      }
                    />
                    {cabin.number} · {cabin.type}
                  </span>
                  <span className="text-muted-foreground">
                    {RENTAL_ITEM_STATUS_LABEL[cabin.status]} ·{" "}
                    {catalogName(options.finishings, cabin.finishingId)}
                    {cabin.linoleum ? " · линолеум" : ""}
                  </span>
                  {usedByOther ? (
                    <span className="text-destructive">
                      Уже выбрана в другой группе.
                    </span>
                  ) : mismatches.length > 0 ? (
                    <span className="text-destructive">
                      {mismatches.map((item) => item.message).join(" ")}
                    </span>
                  ) : (
                    <span className="text-emerald-700 dark:text-emerald-400">
                      Соответствует требованиям.
                    </span>
                  )}
                  {selected ? (
                    delta.length === 0 ? (
                      <span>
                        Фактическое наполнение соответствует требуемому.
                      </span>
                    ) : (
                      <ul className="text-muted-foreground">
                        {delta.map((item) => (
                          <li key={item.furnitureCatalogItemId}>
                            {furnitureName(
                              furniture,
                              item.furnitureCatalogItemId
                            )}
                            : фактически {item.actual}, требуется{" "}
                            {item.required} —{" "}
                            {item.delta > 0
                              ? `добавить ${item.delta}`
                              : `убрать ${Math.abs(item.delta)}`}
                          </li>
                        ))}
                      </ul>
                    )
                  ) : null}
                </label>
              )
            })}
          </div>
        </FieldSet>
      </CardContent>
    </Card>
  )
}

function ContractorDriverDialog({
  accessToken,
  warehouseId,
  onOpenChange,
  onCreated,
}: {
  accessToken: string
  warehouseId: string
  onOpenChange: (open: boolean) => void
  onCreated: (driver: LogisticsDriverResource) => void
}) {
  const contractorId = useRef(identity()).current
  const [displayName, setDisplayName] = useState("")
  const [phone, setPhone] = useState("")
  const [comment, setComment] = useState("")
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () =>
      createContractorDriver({
        accessToken,
        warehouseId,
        contractor: {
          contractorId,
          displayName: displayName.trim(),
          phone: phone.trim(),
          comment: comment.trim() || null,
        },
      }),
    onSuccess: (contractor) =>
      onCreated({
        workerId: contractor.workerId,
        displayName: contractor.displayName,
        employmentType: "CONTRACTOR",
        phone: contractor.phone,
        operationalWarehouseId: contractor.homeWarehouseId,
        availabilityKind: "HOME",
      }),
  })

  function submit() {
    if (!displayName.trim() || !phone.trim()) {
      setError("Укажите имя и телефон.")
      return
    }
    setError(null)
    mutation.mutate()
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Добавить наёмного водителя</DialogTitle>
          <DialogDescription>
            Создаётся постоянный профиль без учётной записи. На рейс и выбранный
            день водитель назначается отдельно.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup className="py-3">
          <Field>
            <FieldLabel htmlFor="contractor-name">Имя</FieldLabel>
            <Input
              id="contractor-name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="contractor-phone">Телефон</FieldLabel>
            <Input
              id="contractor-phone"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="contractor-comment">Комментарий</FieldLabel>
            <Textarea
              id="contractor-comment"
              maxLength={1000}
              value={comment}
              onChange={(event) => setComment(event.target.value)}
            />
          </Field>
          {error ? <FieldError>{error}</FieldError> : null}
          {mutation.error ? (
            <FieldError>
              {errorMessage(
                mutation.error,
                "Не удалось создать наёмного водителя"
              )}
            </FieldError>
          ) : null}
        </FieldGroup>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button type="button" disabled={mutation.isPending} onClick={submit}>
            {mutation.isPending ? "Добавляется…" : "Добавить водителя"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
