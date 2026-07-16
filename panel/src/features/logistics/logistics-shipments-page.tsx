import { useEffect, useMemo, useRef, useState } from "react"
import { useSearchParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
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
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useAuth } from "@/features/auth/use-auth"
import { LogisticsCabinContentsEditor } from "@/features/logistics/logistics-cabin-contents-editor"
import {
  SHIPMENTS_QUERY_KEY,
  SHIPMENTS_STORAGE_KEY,
  SHIPMENTS_UPDATED_EVENT,
  cancelShipment,
  combineShipmentPreparationCatalog,
  confirmShipmentPreparation,
  computeShipmentContentsChanges,
  dispatchShipmentPreparationTask,
  finalizeShipment,
  createShipmentPreparationTask,
  listCompanies,
  listShipmentCandidates,
  listShipmentEquipment,
  listShipmentSourceCabins,
  listShipments,
  retryShipmentPreparationTasks,
  shipmentPreparationIdentity,
  stablePreparationExternalTaskId,
  upsertShipmentDraft,
} from "@/features/logistics/shipments/api"
import type {
  Shipment as ShipmentDto,
  ShipmentCandidate as ShipmentCandidateDto,
  ShipmentPreparationTask as ShipmentPreparationTaskDto,
  ShipmentSourceAllocation,
  ShipmentSourceCandidate,
} from "@/features/logistics/shipments/model"
import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"
import {
  RENTAL_ITEMS_MOCK_STORAGE_KEY,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
} from "@/features/rental-items/api/rental-items-api"

const today = () => {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${date.getFullYear()}-${month}-${day}`
}
const dateLabel = (value: string) =>
  new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(value)
  )

type CabinDraft = {
  slotId: string
  rentalItemId: string
  cabinNumber: string
  expectedTargetVersion: number | null
  contentsBefore: RentalItemContentsItemDto[]
  contentsPlanned: RentalItemContentsItemDto[]
  sourceAllocations: ShipmentSourceAllocation[]
  preparationTask: ShipmentPreparationTaskDto | null
}

function newSlot(): CabinDraft {
  return {
    slotId: crypto.randomUUID(),
    rentalItemId: "",
    cabinNumber: "",
    expectedTargetVersion: 0,
    contentsBefore: [],
    contentsPlanned: [],
    sourceAllocations: [],
    preparationTask: null,
  }
}

function warehouseAllocation(
  before: RentalItemContentsItemDto[],
  planned: RentalItemContentsItemDto[]
): ShipmentSourceAllocation[] {
  const items = computeShipmentContentsChanges(before, planned)
    .filter((change) => change.direction === "BRING")
    .map((change) => ({ name: change.name, quantity: change.quantity }))
  return items.length
    ? [
        {
          sourceType: "WAREHOUSE",
          sourceRentalItemId: null,
          sourceCabinNumber: null,
          expectedSourceVersion: null,
          items,
        },
      ]
    : []
}

export function LogisticsShipmentsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedShipmentId = searchParams.get("shipmentId")
  const { selectedWarehouseId, selectedWarehouse } = useWarehouse()
  const { currentUser, accessToken } = useAuth()
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [resumeShipment, setResumeShipment] = useState<ShipmentDto | null>(null)
  const [search, setSearch] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const query = useQuery({
    queryKey: [...SHIPMENTS_QUERY_KEY, "shipments", selectedWarehouseId],
    queryFn: () => listShipments(selectedWarehouseId!),
    enabled: Boolean(selectedWarehouseId),
  })
  useEffect(() => {
    const invalidate = () => {
      void client.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void client.invalidateQueries({ queryKey: ["rental-items"] })
    }
    const onStorage = (event: StorageEvent) => {
      if (
        event.key === SHIPMENTS_STORAGE_KEY ||
        event.key === "rwms:logistics:v1" ||
        event.key === RENTAL_ITEMS_MOCK_STORAGE_KEY
      )
        invalidate()
    }
    window.addEventListener(SHIPMENTS_UPDATED_EVENT, invalidate)
    window.addEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", onStorage)
    return () => {
      window.removeEventListener(SHIPMENTS_UPDATED_EVENT, invalidate)
      window.removeEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", onStorage)
    }
  }, [client])
  const rows = useMemo(
    () =>
      (query.data ?? []).filter(
        (item) =>
          (!selectedShipmentId || item.id === selectedShipmentId) &&
          `${item.company} ${item.driverName} ${item.items.map((cabin) => cabin.cabinNumber).join(" ")}`
            .toLowerCase()
            .includes(search.trim().toLowerCase()) &&
          (!dateFrom || item.shipmentDate >= dateFrom) &&
          (!dateTo || item.shipmentDate <= dateTo)
      ),
    [query.data, search, dateFrom, dateTo, selectedShipmentId]
  )
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="grid gap-2 md:grid-cols-[minmax(12rem,1fr)_auto_auto]">
          <Input
            aria-label="Поиск отгрузок"
            placeholder="Компания, водитель или бытовка"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Input
            aria-label="Отгрузки с даты"
            type="date"
            value={dateFrom}
            onChange={(event) => setDateFrom(event.target.value)}
          />
          <Input
            aria-label="Отгрузки по дату"
            type="date"
            value={dateTo}
            onChange={(event) => setDateTo(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedShipmentId ? (
            <Button
              variant="outline"
              onClick={() => {
                const next = new URLSearchParams(searchParams)
                next.delete("shipmentId")
                setSearchParams(next, { replace: true })
              }}
            >
              Показать все
            </Button>
          ) : null}
          <Button
            disabled={!selectedWarehouseId}
            onClick={() => {
              setResumeShipment(null)
              setOpen(true)
            }}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Создать задание
          </Button>
        </PageToolbarActions>
      </PageToolbar>
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            columns={[
              {
                id: "date",
                label: "Дата",
                className: "w-44",
                getSortValue: (item) => item.shipmentDate,
                render: (item) => dateLabel(item.shipmentDate),
              },
              {
                id: "company",
                label: "Компания",
                className: "w-56",
                getSortValue: (item) => item.company,
                render: (item) => item.company,
              },
              {
                id: "driver",
                label: "Водитель",
                className: "w-56",
                getSortValue: (item) => item.driverName,
                render: (item) => item.driverName,
              },
              {
                id: "cabins",
                label: "Бытовки",
                className: "min-w-56",
                getSortValue: (item) => item.items.length,
                render: (item) =>
                  item.items.map((cabin) => cabin.cabinNumber).join(", "),
              },
              {
                id: "preparation",
                label: "Подготовка к отгрузке",
                className: "min-w-96",
                getSortValue: (item) =>
                  item.items.flatMap((entry) => entry.changes).length,
                render: (item) => (
                  <ShipmentPreparationSummary shipment={item} />
                ),
              },
              {
                id: "status",
                label: "Статус",
                className: "w-36",
                getSortValue: (item) => item.status,
                render: (item) => <ShipmentStatusBadge status={item.status} />,
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-72",
                getSortValue: (item) => item.status,
                render: (item) =>
                  item.status === "SHIPPED" ||
                  item.status === "CANCELLED" ? null : (
                    <ShipmentRecoveryActions
                      shipment={item}
                      warehouseId={selectedWarehouseId!}
                      serviceWarehouseId={selectedWarehouse?.id ?? ""}
                      accessToken={accessToken ?? ""}
                      actor={currentUser?.displayName ?? "Текущий пользователь"}
                      onChanged={() =>
                        void client.invalidateQueries({
                          queryKey: SHIPMENTS_QUERY_KEY,
                        })
                      }
                      onContinue={() => {
                        setResumeShipment(
                          item.status === "LEGACY_QUARANTINE" ? null : item
                        )
                        setOpen(true)
                      }}
                    />
                  ),
              },
            ]}
          />
        </div>
        <div className="grid gap-3 md:hidden">
          {rows.map((item) => (
            <ShipmentCard
              key={item.id}
              item={item}
              recovery={
                item.status === "SHIPPED" ||
                item.status === "CANCELLED" ? null : (
                  <ShipmentRecoveryActions
                    shipment={item}
                    warehouseId={selectedWarehouseId!}
                    serviceWarehouseId={selectedWarehouse?.id ?? ""}
                    accessToken={accessToken ?? ""}
                    actor={currentUser?.displayName ?? "Текущий пользователь"}
                    onChanged={() =>
                      void client.invalidateQueries({
                        queryKey: SHIPMENTS_QUERY_KEY,
                      })
                    }
                    onContinue={() => {
                      setResumeShipment(
                        item.status === "LEGACY_QUARANTINE" ? null : item
                      )
                      setOpen(true)
                    }}
                  />
                )
              }
            />
          ))}
          {rows.length === 0 ? (
            <p
              role="status"
              className="p-4 text-center text-sm text-muted-foreground"
            >
              Отгрузки не найдены
            </p>
          ) : null}
        </div>
      </div>
      {open ? (
        <CreateShipmentDialog
          key={resumeShipment?.id ?? "new-shipment"}
          open
          resumeShipment={resumeShipment}
          warehouseId={selectedWarehouseId}
          serviceWarehouseId={selectedWarehouse?.id ?? null}
          accessToken={accessToken}
          createdBy={currentUser?.displayName ?? "Текущий пользователь"}
          onOpenChange={setOpen}
          onCreated={() => {
            void client.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
            void client.invalidateQueries({ queryKey: ["rental-items"] })
          }}
        />
      ) : null}
    </div>
  )
}

function ShipmentPreparationSummary({ shipment }: { shipment: ShipmentDto }) {
  const changed = shipment.items.filter((item) => item.changes.length > 0)
  if (changed.length === 0)
    return <span className="text-muted-foreground">Без изменений</span>
  return (
    <div className="flex flex-col gap-2">
      {changed.map((item) => (
        <div key={item.rentalItemId} className="flex flex-col gap-1">
          <strong>{item.cabinNumber}</strong>
          {item.changes.map((change) => (
            <span
              key={`${change.direction}-${change.name}`}
              className="text-xs text-muted-foreground"
            >
              {change.label}
            </span>
          ))}
          {item.preparationTask ? (
            <Badge
              variant={
                item.preparationTask.dispatchStatus === "FAILED"
                  ? "destructive"
                  : "secondary"
              }
            >
              {item.preparationTask.dispatchStatus === "DISPATCHED"
                ? "Зарегистрирована"
                : item.preparationTask.dispatchStatus === "PENDING"
                  ? "Отправляется"
                  : item.preparationTask.dispatchStatus === "FAILED"
                    ? "Ошибка"
                    : "Черновик"}
            </Badge>
          ) : null}
          {item.preparationTask?.dispatchStatus === "DISPATCHED" ? (
            <span className="text-xs text-muted-foreground">
              Task-board: {item.preparationTask.boardTaskId} · очередь{" "}
              {item.preparationTask.queueCode}
            </span>
          ) : null}
          {item.preparationTask?.dispatchError ? (
            <span className="text-xs text-destructive">
              {item.preparationTask.dispatchError}
            </span>
          ) : null}
        </div>
      ))}
    </div>
  )
}

function ShipmentStatusBadge({ status }: { status: ShipmentDto["status"] }) {
  return (
    <Badge
      variant={
        status === "FAILED" || status === "CONFLICT"
          ? "destructive"
          : "secondary"
      }
    >
      {status === "SHIPPED"
        ? "Отгружено"
        : status === "CANCELLED"
          ? "Отменено"
          : status === "LEGACY_QUARANTINE"
            ? "Требуется новый план"
            : status === "READY_TO_SHIP"
              ? "Готово к отгрузке"
              : status === "AWAITING_CONFIRMATION"
                ? "Ждёт подтверждения"
                : status === "APPLYING"
                  ? "Применение подготовки"
                  : status === "FINALIZING"
                    ? "Завершение отгрузки"
                    : status === "CONFLICT"
                      ? "Конфликт подготовки"
                      : status === "FAILED"
                        ? "Ошибка подготовки"
                        : "Подготовка"}
    </Badge>
  )
}

function ShipmentRecoveryActions({
  shipment,
  warehouseId,
  serviceWarehouseId,
  accessToken,
  actor,
  onChanged,
  onContinue,
}: {
  shipment: ShipmentDto
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: string
  onChanged: () => void
  onContinue: () => void
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState("")
  const allDispatched = shipment.items.every(
    (item) =>
      item.changes.length === 0 ||
      item.preparationTask?.dispatchStatus === "DISPATCHED"
  )
  async function execute(command: () => Promise<unknown>) {
    setPending(true)
    setError(null)
    try {
      await command()
      onChanged()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Операция не выполнена")
    } finally {
      setPending(false)
    }
  }
  if (shipment.status === "LEGACY_QUARANTINE") {
    return (
      <div className="flex flex-col gap-2">
        <span className="text-xs text-muted-foreground">
          Старый MOCK не подтверждает версии и источники мебели.
        </span>
        <Button size="sm" variant="outline" onClick={onContinue}>
          Создать новый план
        </Button>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={pending}
          onClick={onContinue}
        >
          Продолжить
        </Button>
        {!allDispatched ? (
          <Button
            size="sm"
            variant="outline"
            disabled={pending || !accessToken || !serviceWarehouseId}
            onClick={() =>
              void execute(() =>
                retryShipmentPreparationTasks({
                  warehouseId,
                  serviceWarehouseId,
                  accessToken,
                  actor,
                  shipmentId: shipment.id,
                })
              )
            }
          >
            Повторить задачи
          </Button>
        ) : shipment.status === "READY_TO_SHIP" ||
          shipment.status === "FINALIZING" ? (
          <Button
            size="sm"
            disabled={pending}
            onClick={() =>
              void execute(() =>
                finalizeShipment({
                  warehouseId,
                  shipmentId: shipment.id,
                  actor,
                })
              )
            }
          >
            Завершить отгрузку
          </Button>
        ) : (
          <Button
            size="sm"
            disabled={pending}
            onClick={() =>
              void execute(() =>
                confirmShipmentPreparation({
                  warehouseId,
                  shipmentId: shipment.id,
                  actor,
                })
              )
            }
          >
            Подтвердить подготовку
          </Button>
        )}
        {shipment.status !== "READY_TO_SHIP" &&
        shipment.status !== "APPLYING" &&
        shipment.status !== "FINALIZING" ? (
          <Button
            size="sm"
            variant="outline"
            disabled={pending}
            onClick={() => setCancelOpen(true)}
          >
            Отменить
          </Button>
        ) : null}
      </div>
      <Dialog open={cancelOpen} onOpenChange={setCancelOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Отменить отгрузку</DialogTitle>
            <DialogDescription>
              Резервы будут освобождены, задания task-board — отменены.
            </DialogDescription>
          </DialogHeader>
          <Field data-invalid={cancelOpen && !cancelReason.trim()}>
            <FieldLabel htmlFor={`cancel-shipment-${shipment.id}`}>
              Причина
            </FieldLabel>
            <Input
              id={`cancel-shipment-${shipment.id}`}
              value={cancelReason}
              aria-invalid={cancelOpen && !cancelReason.trim()}
              onChange={(event) => setCancelReason(event.target.value)}
            />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelOpen(false)}>
              Назад
            </Button>
            <Button
              variant="destructive"
              disabled={!cancelReason.trim() || pending}
              onClick={() =>
                void execute(() =>
                  cancelShipment({
                    warehouseId,
                    serviceWarehouseId,
                    accessToken,
                    actor,
                    shipmentId: shipment.id,
                    reason: cancelReason,
                  })
                ).then(() => setCancelOpen(false))
              }
            >
              Отменить отгрузку
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {error || shipment.error ? (
        <span className="text-xs text-destructive">
          {error ?? shipment.error}
        </span>
      ) : null}
      {(error ?? shipment.error)?.includes("настройки доски") ? (
        <Button asChild size="sm" variant="link">
          <a href="/settings/task-board">Настроить доску задач</a>
        </Button>
      ) : null}
    </div>
  )
}

function ShipmentCard({
  item,
  recovery,
}: {
  item: ShipmentDto
  recovery: React.ReactNode
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{item.company}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        <div className="grid grid-cols-2 gap-2">
          <span className="text-muted-foreground">Дата</span>
          <span>{dateLabel(item.shipmentDate)}</span>
          <span className="text-muted-foreground">Водитель</span>
          <span>{item.driverName}</span>
          <span className="text-muted-foreground">Бытовки</span>
          <span>{item.items.map((cabin) => cabin.cabinNumber).join(", ")}</span>
        </div>
        <Separator />
        <strong>Подготовка к отгрузке</strong>
        <ShipmentPreparationSummary shipment={item} />
        <ShipmentStatusBadge status={item.status} />
        {recovery}
      </CardContent>
    </Card>
  )
}

function CreateShipmentDialog({
  open,
  resumeShipment,
  warehouseId,
  serviceWarehouseId,
  accessToken,
  createdBy,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  resumeShipment: ShipmentDto | null
  warehouseId: string | null
  serviceWarehouseId: string | null
  accessToken: string | null
  createdBy: string
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  const dialogContentRef = useRef<HTMLDivElement>(null)
  const [company, setCompany] = useState(resumeShipment?.company ?? "")
  const [companyInput, setCompanyInput] = useState(
    resumeShipment?.company ?? ""
  )
  const [shipmentDate, setShipmentDate] = useState(
    resumeShipment?.shipmentDate ?? today()
  )
  const [driverId, setDriverId] = useState(resumeShipment?.driverId ?? "")
  const [cabins, setCabins] = useState<CabinDraft[]>(() =>
    resumeShipment
      ? resumeShipment.items.map((item) => ({
          slotId: crypto.randomUUID(),
          rentalItemId: item.rentalItemId,
          cabinNumber: item.cabinNumber,
          expectedTargetVersion: item.expectedTargetVersion,
          contentsBefore: item.contentsBefore.map((entry) => ({ ...entry })),
          contentsPlanned: item.contentsPlanned.map((entry) => ({ ...entry })),
          sourceAllocations: structuredClone(item.sourceAllocations),
          preparationTask: item.preparationTask
            ? structuredClone(item.preparationTask)
            : null,
        }))
      : [newSlot()]
  )
  const [shipmentDraftId, setShipmentDraftId] = useState<string | null>(
    resumeShipment?.id ?? null
  )
  const [shipmentDraftVersion, setShipmentDraftVersion] = useState(
    resumeShipment?.version
  )
  const [error, setError] = useState<string | null>(
    resumeShipment?.error ?? null
  )
  const companies = useQuery({
    queryKey: [...SHIPMENTS_QUERY_KEY, "companies", warehouseId, open],
    queryFn: () => listCompanies(warehouseId!),
    enabled: open && Boolean(warehouseId),
  })
  const candidates = useQuery({
    queryKey: [
      ...SHIPMENTS_QUERY_KEY,
      "shipment-candidates",
      warehouseId,
      company,
      open,
    ],
    queryFn: () => listShipmentCandidates(warehouseId!, company),
    enabled: open && Boolean(warehouseId && company),
  })
  const equipment = useQuery({
    queryKey: [
      ...SHIPMENTS_QUERY_KEY,
      "shipment-equipment",
      warehouseId,
      shipmentDraftId,
      open,
    ],
    queryFn: () => listShipmentEquipment(warehouseId!, shipmentDraftId),
    enabled: open && Boolean(warehouseId),
  })
  const selectedIds = cabins.map((item) => item.rentalItemId).filter(Boolean)
  const sourceCabins = useQuery({
    queryKey: [
      ...SHIPMENTS_QUERY_KEY,
      "source-cabins",
      warehouseId,
      selectedIds.join("|"),
      open,
    ],
    queryFn: () =>
      listShipmentSourceCabins(warehouseId!, selectedIds, shipmentDraftId),
    enabled: open && Boolean(warehouseId),
  })
  const groups = useQuery({
    queryKey: repairWorkerGroupsQueryKey({
      warehouseId: warehouseId ?? "none",
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      purpose: "DRIVER_DIRECTORY",
    }),
    queryFn: () =>
      listRepairWorkerGroups(
        {
          warehouseId: warehouseId!,
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          purpose: "DRIVER_DIRECTORY",
        },
        accessToken ?? undefined
      ),
    enabled: open && Boolean(warehouseId),
  })
  const drivers = useMemo(
    () =>
      Array.from(
        new Map(
          (groups.data ?? [])
            .filter((group) => group.active)
            .flatMap((group) => group.members)
            .map((member) => [member.id, member])
        ).values()
      ),
    [groups.data]
  )
  function buildDraft(nextCabins: CabinDraft[]) {
    const driver = drivers.find((item) => item.id === driverId)
    if (!driver) throw new Error("Выберите водителя")
    return {
      shipmentId: shipmentDraftId,
      expectedVersion: shipmentDraftVersion,
      warehouseId: warehouseId!,
      accessToken,
      company,
      driverId: driver.id,
      driverName: driver.name,
      shipmentDate,
      items: nextCabins
        .filter((item) => item.rentalItemId)
        .map((item) => ({
          rentalItemId: item.rentalItemId,
          expectedTargetVersion: item.expectedTargetVersion,
          contentsBefore: item.contentsBefore,
          contentsPlanned: item.contentsPlanned,
          sourceAllocations: item.sourceAllocations,
          preparationTask: item.preparationTask,
        })),
      createdBy,
    }
  }

  function syncShipment(saved: ShipmentDto) {
    setShipmentDraftId(saved.id)
    setShipmentDraftVersion(saved.version)
    setCabins((current) =>
      saved.items.map((item) => ({
        slotId:
          current.find((entry) => entry.rentalItemId === item.rentalItemId)
            ?.slotId ?? crypto.randomUUID(),
        rentalItemId: item.rentalItemId,
        cabinNumber: item.cabinNumber,
        expectedTargetVersion: item.expectedTargetVersion,
        contentsBefore: item.contentsBefore.map((entry) => ({ ...entry })),
        contentsPlanned: item.contentsPlanned.map((entry) => ({ ...entry })),
        sourceAllocations: structuredClone(item.sourceAllocations),
        preparationTask: item.preparationTask
          ? structuredClone(item.preparationTask)
          : null,
      }))
    )
  }

  const mutation = useMutation({
    mutationFn: async () => {
      return upsertShipmentDraft(buildDraft(cabins))
    },
    onSuccess: (saved) => {
      syncShipment(saved)
      onCreated()
      onOpenChange(false)
      setCompany("")
      setCompanyInput("")
      setDriverId("")
      setCabins([newSlot()])
      setError(null)
    },
    onError: (cause) =>
      setError(
        cause instanceof Error ? cause.message : "Не удалось создать отгрузку"
      ),
  })
  const dispatchMutation = useMutation({
    mutationFn: async ({
      slotId,
      task,
    }: {
      slotId: string
      task: ShipmentPreparationTaskDto
    }) => {
      const nextCabins = cabins.map((cabin) =>
        cabin.slotId === slotId ? { ...cabin, preparationTask: task } : cabin
      )
      const draft = await upsertShipmentDraft(buildDraft(nextCabins))
      setShipmentDraftId(draft.id)
      const cabin = nextCabins.find((item) => item.slotId === slotId)!
      return dispatchShipmentPreparationTask({
        warehouseId: warehouseId!,
        serviceWarehouseId: serviceWarehouseId!,
        accessToken: accessToken!,
        actor: createdBy,
        shipmentId: draft.id,
        rentalItemId: cabin.rentalItemId,
      })
    },
    onSuccess: (saved) => {
      syncShipment(saved)
      setError(null)
      onCreated()
    },
    onError: (cause) => {
      setError(
        cause instanceof Error ? cause.message : "Не удалось отправить задачу"
      )
      onCreated()
    },
  })
  const hasDispatched = cabins.some(
    (item) =>
      item.preparationTask?.dispatchStatus === "DISPATCHED" ||
      item.preparationTask?.dispatchStatus === "PENDING"
  )
  const unacknowledged = cabins.some((cabin) => {
    const changes = computeShipmentContentsChanges(
      cabin.contentsBefore,
      cabin.contentsPlanned
    )
    return (
      changes.length > 0 &&
      cabin.preparationTask?.dispatchStatus !== "DISPATCHED"
    )
  })

  function applyCompany(value: string) {
    const nextValue = value.trim()
    setCompany(nextValue)
    setCompanyInput(nextValue)
    setCabins([newSlot()])
  }

  function selectCabin(slotId: string, rentalItemId: string | null) {
    const candidate = candidates.data?.find(
      (entry) => entry.item.id === rentalItemId
    )
    setCabins((current) =>
      current.map((slot) =>
        slot.slotId === slotId
          ? candidate
            ? {
                ...slot,
                rentalItemId: candidate.item.id,
                cabinNumber: candidate.item.number,
                expectedTargetVersion: candidate.item.version,
                contentsBefore: candidate.item.contentsItems.map((item) => ({
                  ...item,
                })),
                contentsPlanned: candidate.item.contentsItems.map((item) => ({
                  ...item,
                })),
                sourceAllocations: [],
                preparationTask: null,
              }
            : newSlot()
          : slot
      )
    )
  }

  function updateContents(
    slotId: string,
    updater: (items: RentalItemContentsItemDto[]) => RentalItemContentsItemDto[]
  ) {
    setCabins((current) =>
      current.map((slot) => {
        if (slot.slotId !== slotId) return slot
        const contentsPlanned = updater(slot.contentsPlanned)
        return {
          ...slot,
          contentsPlanned,
          sourceAllocations: warehouseAllocation(
            slot.contentsBefore,
            contentsPlanned
          ),
          preparationTask: null,
        }
      })
    )
  }

  function updateAllocations(
    slotId: string,
    sourceAllocations: ShipmentSourceAllocation[]
  ) {
    setCabins((current) =>
      current.map((slot) =>
        slot.slotId === slotId
          ? { ...slot, sourceAllocations, preparationTask: null }
          : slot
      )
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        ref={dialogContentRef}
        className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl"
      >
        <DialogHeader>
          <DialogTitle>Создать отгрузку в аренду</DialogTitle>
          <DialogDescription>
            Выберите компанию, дату, водителя, бытовки и подготовьте их
            наполнение.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup className="grid gap-4 sm:grid-cols-2">
          <Field>
            <FieldLabel htmlFor="shipment-company">Компания</FieldLabel>
            <Combobox
              items={companies.data ?? []}
              value={company || null}
              inputValue={companyInput}
              onInputValueChange={(value, eventDetails) => {
                if (eventDetails.reason === "input-change") {
                  setCompanyInput(value)
                  return
                }
                if (value === "" && eventDetails.reason === "clear-press") {
                  applyCompany("")
                }
              }}
              onValueChange={(value) => {
                if (value !== null) applyCompany(value)
              }}
            >
              <ComboboxInput
                id="shipment-company"
                aria-label="Компания"
                placeholder="Начните вводить компанию"
                showClear
                disabled={hasDispatched}
                onKeyDownCapture={(event) => {
                  if (event.key !== "Enter") return
                  if (event.currentTarget.getAttribute("aria-activedescendant"))
                    return
                  applyCompany(companyInput)
                }}
              />
              <ComboboxContent portalContainer={dialogContentRef}>
                <ComboboxList>
                  <ComboboxEmpty>Компании не найдены</ComboboxEmpty>
                  <ComboboxGroup>
                    {(companies.data ?? []).map((item) => (
                      <ComboboxItem key={item} value={item}>
                        {item}
                      </ComboboxItem>
                    ))}
                  </ComboboxGroup>
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
          </Field>
          <Field>
            <FieldLabel htmlFor="shipment-date">Дата отгрузки</FieldLabel>
            <Input
              id="shipment-date"
              type="date"
              disabled={hasDispatched}
              value={shipmentDate}
              onChange={(event) => {
                setShipmentDate(event.target.value)
                setCabins((current) =>
                  current.map((cabin) => ({
                    ...cabin,
                    preparationTask: null,
                  }))
                )
              }}
            />
          </Field>
          <Field className="sm:col-span-2">
            <FieldLabel htmlFor="shipment-driver">Водитель</FieldLabel>
            <Select
              value={driverId}
              onValueChange={setDriverId}
              disabled={hasDispatched}
            >
              <SelectTrigger id="shipment-driver">
                <SelectValue placeholder="Выберите водителя" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {drivers.map((driver) => (
                    <SelectItem key={driver.id} value={driver.id}>
                      {driver.name}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>
        </FieldGroup>
        <FieldSet>
          <FieldLegend variant="label">Бытовки и наполнение</FieldLegend>
          <FieldDescription>
            Зарезервированные за выбранной компанией бытовки показаны первыми.
          </FieldDescription>
          <FieldGroup>
            {cabins.map((cabin, index) => (
              <Card key={cabin.slotId}>
                <CardHeader>
                  <CardTitle>Бытовка {index + 1}</CardTitle>
                </CardHeader>
                <CardContent className="flex flex-col gap-4">
                  <Field>
                    <FieldLabel htmlFor={`shipment-cabin-${cabin.slotId}`}>
                      Номер бытовки
                    </FieldLabel>
                    <div className="flex gap-2">
                      <Combobox
                        items={(candidates.data ?? []).map(
                          (entry) => entry.item.number
                        )}
                        value={cabin.cabinNumber || null}
                        onValueChange={(value) =>
                          selectCabin(
                            cabin.slotId,
                            (candidates.data ?? []).find(
                              (entry) => entry.item.number === value
                            )?.item.id ?? null
                          )
                        }
                      >
                        <ComboboxInput
                          id={`shipment-cabin-${cabin.slotId}`}
                          className="flex-1"
                          placeholder={
                            company
                              ? "Введите номер бытовки"
                              : "Сначала выберите компанию"
                          }
                          disabled={!company || hasDispatched}
                        />
                        <ComboboxContent portalContainer={dialogContentRef}>
                          <ComboboxList>
                            <ComboboxEmpty>
                              Доступные бытовки не найдены
                            </ComboboxEmpty>
                            <CandidateGroups
                              candidates={candidates.data ?? []}
                              selectedIds={selectedIds}
                              currentId={cabin.rentalItemId}
                            />
                          </ComboboxList>
                        </ComboboxContent>
                      </Combobox>
                      {cabins.length > 1 ? (
                        <Button
                          type="button"
                          size="icon"
                          variant="outline"
                          disabled={hasDispatched}
                          aria-label={`Удалить бытовку ${index + 1}`}
                          onClick={() =>
                            setCabins((current) =>
                              current.filter(
                                (slot) => slot.slotId !== cabin.slotId
                              )
                            )
                          }
                        >
                          <HugeiconsIcon icon={Delete02Icon} />
                        </Button>
                      ) : null}
                    </div>
                  </Field>
                  {cabin.rentalItemId ? (
                    <CabinContentsEditor
                      cabin={cabin}
                      frozen={
                        cabin.preparationTask?.dispatchStatus ===
                          "DISPATCHED" ||
                        cabin.preparationTask?.dispatchStatus === "PENDING"
                      }
                      onUpdate={(updater) =>
                        updateContents(cabin.slotId, updater)
                      }
                      catalog={combineShipmentPreparationCatalog(
                        equipment.data ?? [],
                        sourceCabins.data ?? []
                      )}
                      sourceCabins={sourceCabins.data ?? []}
                      onAllocationsUpdate={(allocations) =>
                        updateAllocations(cabin.slotId, allocations)
                      }
                      onCreateTask={() => {
                        const changes = computeShipmentContentsChanges(
                          cabin.contentsBefore,
                          cabin.contentsPlanned
                        )
                        const task = createShipmentPreparationTask(
                          changes,
                          cabin.preparationTask?.id,
                          stablePreparationExternalTaskId(
                            shipmentPreparationIdentity({
                              warehouseId: warehouseId!,
                              company,
                              shipmentDate,
                              rentalItemId: cabin.rentalItemId,
                              changes,
                              sourceAllocations: cabin.sourceAllocations,
                            })
                          )
                        )
                        dispatchMutation.mutate({ slotId: cabin.slotId, task })
                      }}
                    />
                  ) : null}
                </CardContent>
              </Card>
            ))}
            <Button
              type="button"
              variant="outline"
              disabled={
                hasDispatched ||
                !company ||
                cabins.some((item) => !item.rentalItemId)
              }
              onClick={() => setCabins((current) => [...current, newSlot()])}
            >
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Добавить ещё бытовку
            </Button>
          </FieldGroup>
        </FieldSet>
        {error ? <FieldError>{error}</FieldError> : null}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button
            disabled={
              !company ||
              !serviceWarehouseId ||
              !accessToken ||
              !shipmentDate ||
              !driverId ||
              selectedIds.length === 0 ||
              cabins.some((item) => !item.rentalItemId) ||
              unacknowledged ||
              mutation.isPending
            }
            onClick={() => mutation.mutate()}
          >
            Сохранить план
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CandidateGroups({
  candidates,
  selectedIds,
  currentId,
}: {
  candidates: ShipmentCandidateDto[]
  selectedIds: string[]
  currentId: string
}) {
  const available = candidates.filter(
    (entry) =>
      !selectedIds.includes(entry.item.id) || entry.item.id === currentId
  )
  const reserved = available.filter((entry) => entry.reservedForCompany)
  const free = available.filter((entry) => !entry.reservedForCompany)
  return (
    <>
      {reserved.length ? (
        <ComboboxGroup>
          <ComboboxLabel>Зарезервированы компанией</ComboboxLabel>
          {reserved.map((entry) => (
            <ComboboxItem key={entry.item.id} value={entry.item.number}>
              <span>{entry.item.number}</span>
              <Badge variant="secondary">Зарезервирована</Badge>
            </ComboboxItem>
          ))}
        </ComboboxGroup>
      ) : null}
      {free.length ? (
        <ComboboxGroup>
          <ComboboxLabel>Другие доступные</ComboboxLabel>
          {free.map((entry) => (
            <ComboboxItem key={entry.item.id} value={entry.item.number}>
              {entry.item.number}
            </ComboboxItem>
          ))}
        </ComboboxGroup>
      ) : null}
    </>
  )
}

function CabinContentsEditor({
  cabin,
  frozen,
  onUpdate,
  catalog,
  sourceCabins,
  onAllocationsUpdate,
  onCreateTask,
}: {
  cabin: CabinDraft
  frozen: boolean
  onUpdate: (
    updater: (items: RentalItemContentsItemDto[]) => RentalItemContentsItemDto[]
  ) => void
  catalog: Array<{ name: string; availableQuantity: number }>
  sourceCabins: ShipmentSourceCandidate[]
  onAllocationsUpdate: (allocations: ShipmentSourceAllocation[]) => void
  onCreateTask: () => void
}) {
  const changes = computeShipmentContentsChanges(
    cabin.contentsBefore,
    cabin.contentsPlanned
  )
  return (
    <div className="flex flex-col gap-3">
      <LogisticsCabinContentsEditor
        contents={cabin.contentsPlanned}
        catalog={catalog}
        frozen={frozen}
        mode="shipment"
        onUpdate={onUpdate}
      />
      <ShipmentSourcesEditor
        cabin={cabin}
        sourceCabins={sourceCabins}
        frozen={frozen}
        onChange={onAllocationsUpdate}
      />
      {changes.length ? (
        <Card>
          <CardHeader>
            <CardTitle>Подготовка к отгрузке</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            {changes.map((change) => (
              <span key={`${change.direction}-${change.name}`}>
                {change.label}
              </span>
            ))}
            <Button
              type="button"
              size="sm"
              disabled={frozen}
              onClick={onCreateTask}
            >
              {cabin.preparationTask ? "Обновить задачу" : "Создать задачу"}
            </Button>
            {cabin.preparationTask ? (
              <Badge variant="secondary">Задача подготовлена</Badge>
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}

function ShipmentSourcesEditor({
  cabin,
  sourceCabins,
  frozen,
  onChange,
}: {
  cabin: CabinDraft
  sourceCabins: ShipmentSourceCandidate[]
  frozen: boolean
  onChange: (allocations: ShipmentSourceAllocation[]) => void
}) {
  const [sourceId, setSourceId] = useState("")
  const bring = computeShipmentContentsChanges(
    cabin.contentsBefore,
    cabin.contentsPlanned
  ).filter((change) => change.direction === "BRING")
  if (!bring.length) return null
  const cabinAllocations = cabin.sourceAllocations.filter(
    (allocation) => allocation.sourceType === "CABIN"
  )

  function commit(nextCabinAllocations: ShipmentSourceAllocation[]) {
    const warehouseItems = bring
      .map((change) => {
        const fromCabins = nextCabinAllocations.reduce(
          (sum, allocation) =>
            sum +
            (allocation.items.find(
              (item) =>
                item.name.toLocaleLowerCase("ru-RU") ===
                change.name.toLocaleLowerCase("ru-RU")
            )?.quantity ?? 0),
          0
        )
        return { name: change.name, quantity: change.quantity - fromCabins }
      })
      .filter((item) => item.quantity > 0)
    onChange([
      ...(warehouseItems.length
        ? [
            {
              sourceType: "WAREHOUSE" as const,
              sourceRentalItemId: null,
              sourceCabinNumber: null,
              expectedSourceVersion: null,
              items: warehouseItems,
            },
          ]
        : []),
      ...nextCabinAllocations.filter((allocation) => allocation.items.length),
    ])
  }

  function changeQuantity(
    allocation: ShipmentSourceAllocation,
    name: string,
    quantity: number
  ) {
    const required = bring.find(
      (change) =>
        change.name.toLocaleLowerCase("ru-RU") ===
        name.toLocaleLowerCase("ru-RU")
    )?.quantity
    const source = sourceCabins.find(
      (candidate) => candidate.id === allocation.sourceRentalItemId
    )
    const available =
      source?.contentsItems.find(
        (item) =>
          item.name.toLocaleLowerCase("ru-RU") ===
          name.toLocaleLowerCase("ru-RU")
      )?.quantity ?? 0
    const allocatedElsewhere = cabinAllocations
      .filter(
        (candidate) =>
          candidate.sourceRentalItemId !== allocation.sourceRentalItemId
      )
      .reduce(
        (sum, candidate) =>
          sum +
          (candidate.items.find(
            (item) =>
              item.name.toLocaleLowerCase("ru-RU") ===
              name.toLocaleLowerCase("ru-RU")
          )?.quantity ?? 0),
        0
      )
    const nextQuantity = Math.max(
      0,
      Math.min(
        quantity || 0,
        Math.max(0, (required ?? 0) - allocatedElsewhere),
        available
      )
    )
    commit(
      cabinAllocations.map((candidate) =>
        candidate.sourceRentalItemId === allocation.sourceRentalItemId
          ? {
              ...candidate,
              items: [
                ...candidate.items.filter(
                  (item) =>
                    item.name.toLocaleLowerCase("ru-RU") !==
                    name.toLocaleLowerCase("ru-RU")
                ),
                ...(nextQuantity ? [{ name, quantity: nextQuantity }] : []),
              ],
            }
          : candidate
      )
    )
  }

  return (
    <FieldSet>
      <FieldLegend variant="label">Источники мебели</FieldLegend>
      <FieldDescription>
        Остаток, не распределённый по бытовкам, резервируется на складе.
      </FieldDescription>
      <FieldGroup>
        {cabinAllocations.map((allocation) => {
          const source = sourceCabins.find(
            (candidate) => candidate.id === allocation.sourceRentalItemId
          )
          return (
            <Card key={allocation.sourceRentalItemId} size="sm">
              <CardHeader className="flex-row items-center justify-between">
                <CardTitle>{allocation.sourceCabinNumber}</CardTitle>
                <Button
                  type="button"
                  size="icon-xs"
                  variant="ghost"
                  disabled={frozen}
                  aria-label={`Удалить источник ${allocation.sourceCabinNumber}`}
                  onClick={() =>
                    commit(
                      cabinAllocations.filter(
                        (candidate) => candidate !== allocation
                      )
                    )
                  }
                >
                  <HugeiconsIcon icon={Delete02Icon} />
                </Button>
              </CardHeader>
              <CardContent className="grid gap-3 sm:grid-cols-2">
                {bring.map((change) => {
                  const controlId = `shipment-source-${cabin.slotId}-${allocation.sourceRentalItemId}-${change.name.replace(/[^a-zа-яё0-9]+/gi, "-")}`
                  const available =
                    source?.contentsItems.find(
                      (item) =>
                        item.name.toLocaleLowerCase("ru-RU") ===
                        change.name.toLocaleLowerCase("ru-RU")
                    )?.quantity ?? 0
                  return (
                    <Field key={change.name}>
                      <FieldLabel htmlFor={controlId}>
                        {change.name} · доступно {available}
                      </FieldLabel>
                      <Input
                        id={controlId}
                        aria-label={`${change.name} из бытовки ${allocation.sourceCabinNumber}`}
                        type="number"
                        min={0}
                        max={Math.min(change.quantity, available)}
                        disabled={frozen || available === 0}
                        value={
                          allocation.items.find(
                            (item) =>
                              item.name.toLocaleLowerCase("ru-RU") ===
                              change.name.toLocaleLowerCase("ru-RU")
                          )?.quantity ?? 0
                        }
                        onChange={(event) =>
                          changeQuantity(
                            allocation,
                            change.name,
                            Number(event.target.value)
                          )
                        }
                      />
                    </Field>
                  )
                })}
              </CardContent>
            </Card>
          )
        })}
        <Field>
          <FieldLabel htmlFor={`shipment-source-select-${cabin.slotId}`}>
            Переместить из другой бытовки
          </FieldLabel>
          <div className="flex gap-2">
            <Select
              value={sourceId}
              onValueChange={setSourceId}
              disabled={frozen}
            >
              <SelectTrigger
                id={`shipment-source-select-${cabin.slotId}`}
                aria-label="Выберите бытовку-источник"
                className="flex-1"
              >
                <SelectValue placeholder="Переместить из другой бытовки" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {sourceCabins
                    .filter(
                      (source) =>
                        !cabinAllocations.some(
                          (allocation) =>
                            allocation.sourceRentalItemId === source.id
                        )
                    )
                    .map((source) => (
                      <SelectItem key={source.id} value={source.id}>
                        {source.number}
                      </SelectItem>
                    ))}
                </SelectGroup>
              </SelectContent>
            </Select>
            <Button
              type="button"
              variant="outline"
              disabled={!sourceId || frozen}
              onClick={() => {
                const source = sourceCabins.find(
                  (candidate) => candidate.id === sourceId
                )
                if (!source) return
                commit([
                  ...cabinAllocations,
                  {
                    sourceType: "CABIN",
                    sourceRentalItemId: source.id,
                    sourceCabinNumber: source.number,
                    expectedSourceVersion: source.version,
                    items: [],
                  },
                ])
                setSourceId("")
              }}
            >
              Добавить источник
            </Button>
          </div>
        </Field>
      </FieldGroup>
    </FieldSet>
  )
}
