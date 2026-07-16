import { useEffect, useMemo, useRef, useState, type RefObject } from "react"
import { useSearchParams } from "react-router-dom"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  CheckmarkCircle02Icon,
  DeliverySent01Icon,
  Loading03Icon,
  Refresh01Icon,
} from "@hugeicons/core-free-icons"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
import { Textarea } from "@/components/ui/textarea"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useAuth } from "@/features/auth/use-auth"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { RentalItemPhotoUploader } from "@/features/rental-items/rental-item-photo-uploader"
import type { RentalItemCreationPhoto } from "@/features/rental-items/model/rental-item-create"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import {
  LOGISTICS_QUERY_KEY,
  getReturnTask,
  resumeImportedReturnConflict,
} from "@/features/logistics/api/logistics-api"
import {
  WAREHOUSE_TRANSFERS_QUERY_KEY,
  WAREHOUSE_TRANSFERS_STORAGE_KEY,
  WAREHOUSE_TRANSFERS_UPDATED_EVENT,
  acceptWarehouseTransferArrival,
  approveWarehouseAccountingCorrection,
  cancelWarehouseTransferLine,
  confirmWarehouseTransferDeparture,
  createWarehouseAccountingCorrection,
  createWarehouseTransfer,
  getWarehouseCorrectionCandidate,
  getWarehouseTransferCandidates,
  listWarehouseAccountingCorrections,
  listWarehouseTransfers,
  retryWarehouseTransferDestinationTask,
  retryWarehouseTransferSourceTask,
} from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import {
  warehouseTransferStatusLabels,
  type WarehouseTransferActorSnapshot,
  type WarehouseAccountingCorrection,
  type WarehouseTransferDocument,
  type WarehouseTransferLine,
  type WarehouseTransferLineStatus,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

type TransferDirection = "ALL" | "OUTBOUND" | "INBOUND"
type TransferStatusFilter = "ALL" | WarehouseTransferLineStatus

type TransferRow = {
  id: string
  document: WarehouseTransferDocument
  line: WarehouseTransferLine
  direction: Exclude<TransferDirection, "ALL">
}

const dateFormatter = new Intl.DateTimeFormat("ru-RU", {
  day: "numeric",
  month: "short",
  year: "numeric",
})

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date)
}

function actorSnapshot(
  currentUser: ReturnType<typeof useAuth>["currentUser"]
): WarehouseTransferActorSnapshot {
  return {
    id: currentUser?.id ?? null,
    displayName: currentUser?.displayName ?? "Текущий пользователь",
  }
}

function canManageWarehouse(
  currentUser: ReturnType<typeof useAuth>["currentUser"],
  serviceWarehouseId: string
) {
  if (!currentUser) return false
  if (
    currentUser.warehouseAccessAll ||
    isGlobalAdministrator(currentUser.globalRole)
  ) {
    return true
  }
  return currentUser.warehouseAccesses.some(
    (access) =>
      access.warehouseId === serviceWarehouseId && access.level === "MANAGE"
  )
}

function TransferStatusBadge({
  status,
}: {
  status: WarehouseTransferLineStatus
}) {
  return (
    <Badge
      variant={
        status === "CONFLICT"
          ? "destructive"
          : status === "RECEIVED"
            ? "default"
            : status === "CANCELLED"
              ? "outline"
              : "secondary"
      }
    >
      {warehouseTransferStatusLabels[status]}
    </Badge>
  )
}

export type WarehouseTransferCabinSlot = {
  slotId: string
  rentalItemId: string
}

export function WarehouseTransferCabinSelection({
  candidates,
  isLoading,
  slots,
  portalContainer,
  onChange,
}: {
  candidates: RentalItemDto[]
  isLoading: boolean
  slots: WarehouseTransferCabinSlot[]
  portalContainer?: RefObject<HTMLDivElement | null>
  onChange: (slots: WarehouseTransferCabinSlot[]) => void
}) {
  return (
    <FieldSet>
      <FieldLegend>Бытовки и наполнение</FieldLegend>
      <FieldDescription>
        Выберите бытовки, которые нужно переместить на другой склад.
      </FieldDescription>
      <div data-testid="transfer-cabin-list" className="flex flex-col gap-3">
        {slots.map((slot, index) => {
          const selected =
            candidates.find(
              (candidate) => candidate.id === slot.rentalItemId
            ) ?? null
          const selectedByOtherSlots = new Set(
            slots
              .filter((candidateSlot) => candidateSlot.slotId !== slot.slotId)
              .map((candidateSlot) => candidateSlot.rentalItemId)
              .filter(Boolean)
          )
          const availableCandidates = candidates.filter(
            (candidate) =>
              candidate.id === slot.rentalItemId ||
              !selectedByOtherSlots.has(candidate.id)
          )
          return (
            <Card key={slot.slotId} size="sm">
              <CardHeader>
                <div className="flex items-center justify-between gap-3">
                  <CardTitle>Бытовка {index + 1}</CardTitle>
                  {slots.length > 1 ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        onChange(
                          slots.filter(
                            (candidateSlot) =>
                              candidateSlot.slotId !== slot.slotId
                          )
                        )
                      }
                    >
                      Удалить
                    </Button>
                  ) : null}
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-4">
                <Field>
                  <FieldLabel htmlFor={`transfer-cabin-${slot.slotId}`}>
                    Номер бытовки
                  </FieldLabel>
                  {isLoading ? (
                    <p className="text-sm text-muted-foreground">
                      Загрузка бытовок…
                    </p>
                  ) : (
                    <Combobox
                      items={availableCandidates.map(
                        (candidate) => candidate.number
                      )}
                      value={selected?.number ?? null}
                      onValueChange={(value) => {
                        const candidate =
                          availableCandidates.find(
                            (item) => item.number === value
                          ) ?? null
                        onChange(
                          slots.map((candidateSlot) =>
                            candidateSlot.slotId === slot.slotId
                              ? {
                                  ...candidateSlot,
                                  rentalItemId: candidate?.id ?? "",
                                }
                              : candidateSlot
                          )
                        )
                      }}
                    >
                      <ComboboxInput
                        id={`transfer-cabin-${slot.slotId}`}
                        className="w-full"
                        placeholder="Введите номер бытовки"
                        showClear
                      />
                      <ComboboxContent portalContainer={portalContainer}>
                        <ComboboxList>
                          <ComboboxEmpty>Бытовки не найдены</ComboboxEmpty>
                          <ComboboxGroup>
                            {availableCandidates.map((candidate) => (
                              <ComboboxItem
                                key={candidate.id}
                                value={candidate.number}
                              >
                                {candidate.number}
                              </ComboboxItem>
                            ))}
                          </ComboboxGroup>
                        </ComboboxList>
                      </ComboboxContent>
                    </Combobox>
                  )}
                </Field>

                {selected ? (
                  <div className="flex flex-col gap-2">
                    <div className="text-sm font-medium">Наполнение</div>
                    {selected.contentsItems.length ? (
                      <div className="flex flex-col gap-1 text-sm">
                        {selected.contentsItems.map((item) => (
                          <div
                            key={item.name}
                            className="flex items-center justify-between gap-4"
                          >
                            <span>{item.name}</span>
                            <span className="text-muted-foreground">
                              {item.quantity} шт.
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <span className="text-sm text-muted-foreground">
                        В бытовке нет наполнения
                      </span>
                    )}
                  </div>
                ) : null}
              </CardContent>
            </Card>
          )
        })}

        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={() =>
            onChange([
              ...slots,
              { slotId: crypto.randomUUID(), rentalItemId: "" },
            ])
          }
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить ещё бытовку
        </Button>
      </div>
    </FieldSet>
  )
}

export function WarehouseTransfersPage() {
  const { warehouses, selectedWarehouse, selectedWarehouseId } = useWarehouse()
  const [searchParams] = useSearchParams()
  const { currentUser, accessToken } = useAuth()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const [direction, setDirection] = useState<TransferDirection>("ALL")
  const [status, setStatus] = useState<TransferStatusFilter>("ALL")
  const [createOpen, setCreateOpen] = useState(
    () => searchParams.get("create") === "1"
  )
  const [createPrefill, setCreatePrefill] = useState<{
    rentalItemId: string | null
    destinationWarehouseId: string | null
    sourceWarehouseId: string | null
    returnConflictId: string | null
  } | null>(() =>
    searchParams.get("create") === "1"
      ? {
          rentalItemId: searchParams.get("rentalItemId"),
          destinationWarehouseId: searchParams.get("destinationWarehouseId"),
          sourceWarehouseId: searchParams.get("sourceWarehouseId"),
          returnConflictId: searchParams.get("returnConflictId"),
        }
      : null
  )
  const [arrival, setArrival] = useState<TransferRow | null>(null)
  const [cancellation, setCancellation] = useState<TransferRow | null>(null)
  const [correctionsOpen, setCorrectionsOpen] = useState(
    () => searchParams.get("correction") === "1"
  )
  const [correctionPrefill] = useState(() =>
    searchParams.get("correction") === "1"
      ? {
          rentalItemId: searchParams.get("rentalItemId"),
          sourceWarehouseId: searchParams.get("sourceWarehouseId"),
          destinationWarehouseId: searchParams.get("destinationWarehouseId"),
          returnConflictId: searchParams.get("returnConflictId"),
        }
      : null
  )

  const query = useQuery({
    queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
    queryFn: listWarehouseTransfers,
  })
  const correctionsQuery = useQuery({
    queryKey: [...WAREHOUSE_TRANSFERS_QUERY_KEY, "corrections"],
    queryFn: listWarehouseAccountingCorrections,
  })

  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({
        queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
      })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === WAREHOUSE_TRANSFERS_STORAGE_KEY) invalidate()
    }
    window.addEventListener(WAREHOUSE_TRANSFERS_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", onStorage)
    return () => {
      window.removeEventListener(WAREHOUSE_TRANSFERS_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", onStorage)
    }
  }, [queryClient])

  const rows = useMemo<TransferRow[]>(() => {
    if (!selectedWarehouseId) return []
    const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU")
    return (query.data ?? [])
      .flatMap((document) =>
        document.lines.map((line) => ({
          id: `${document.id}:${line.id}`,
          document,
          line,
          direction:
            document.sourceWarehouse.id === selectedWarehouseId
              ? ("OUTBOUND" as const)
              : ("INBOUND" as const),
        }))
      )
      .filter(
        (row) =>
          (row.document.sourceWarehouse.id === selectedWarehouseId ||
            row.document.destinationWarehouse.id === selectedWarehouseId) &&
          (direction === "ALL" || row.direction === direction) &&
          (status === "ALL" || row.line.status === status) &&
          (!dateFrom || row.document.plannedDate >= dateFrom) &&
          (!dateTo || row.document.plannedDate <= dateTo) &&
          (!normalizedSearch ||
            `${row.line.cabinNumber} ${row.document.driverName} ${row.document.sourceWarehouse.name} ${row.document.destinationWarehouse.name}`
              .toLocaleLowerCase("ru-RU")
              .includes(normalizedSearch))
      )
  }, [
    dateFrom,
    dateTo,
    direction,
    query.data,
    search,
    selectedWarehouseId,
    status,
  ])

  async function runAction(action: () => Promise<unknown>, success: string) {
    try {
      await action()
      toast.success(success)
      await queryClient.invalidateQueries({
        queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
      })
      await queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      await queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Операция не выполнена"
      )
    }
  }

  function actions(row: TransferRow) {
    const sourceManage = canManageWarehouse(
      currentUser,
      row.document.sourceWarehouse.id
    )
    const destinationManage = canManageWarehouse(
      currentUser,
      row.document.destinationWarehouse.id
    )
    if (row.line.status === "PREPARING") {
      return (
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            variant="outline"
            disabled={!sourceManage || !accessToken}
            onClick={() =>
              void runAction(
                () =>
                  retryWarehouseTransferSourceTask({
                    documentId: row.document.id,
                    lineId: row.line.id,
                    expectedDocumentVersion: row.document.version,
                    accessToken: accessToken ?? "",
                    actor: actorSnapshot(currentUser),
                  }),
                "Задание отправления зарегистрировано"
              )
            }
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Повторить
          </Button>
          {!row.line.applicationAttempt ? (
            <Button
              size="sm"
              variant="ghost"
              disabled={!sourceManage}
              onClick={() => setCancellation(row)}
            >
              Отменить
            </Button>
          ) : null}
        </div>
      )
    }
    if (row.line.status === "READY_TO_DEPART") {
      return (
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            disabled={!sourceManage || !accessToken}
            onClick={() =>
              void runAction(
                () =>
                  confirmWarehouseTransferDeparture({
                    documentId: row.document.id,
                    lineId: row.line.id,
                    expectedDocumentVersion: row.document.version,
                    expectedLineVersion: row.line.version,
                    accessToken: accessToken ?? "",
                    actor: actorSnapshot(currentUser),
                  }),
                "Убытие бытовки подтверждено"
              )
            }
          >
            <HugeiconsIcon icon={DeliverySent01Icon} data-icon="inline-start" />
            Подтвердить убытие
          </Button>
          {!row.line.applicationAttempt ? (
            <Button
              size="sm"
              variant="ghost"
              disabled={!sourceManage}
              onClick={() => setCancellation(row)}
            >
              Отменить
            </Button>
          ) : null}
        </div>
      )
    }
    if (row.line.status === "IN_TRANSIT" || row.line.status === "CONFLICT") {
      return (
        <div className="flex flex-wrap gap-2">
          {!row.line.destinationTask ? (
            <Button
              size="sm"
              variant="outline"
              disabled={!destinationManage || !accessToken}
              onClick={() =>
                void runAction(
                  () =>
                    retryWarehouseTransferDestinationTask({
                      documentId: row.document.id,
                      lineId: row.line.id,
                      expectedDocumentVersion: row.document.version,
                      accessToken: accessToken ?? "",
                      actor: actorSnapshot(currentUser),
                    }),
                  "Задание приёмки зарегистрировано"
                )
              }
            >
              <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
              Повторить задание
            </Button>
          ) : null}
          {row.line.destinationTask ? (
            <Button
              size="sm"
              disabled={!destinationManage}
              onClick={() => setArrival(row)}
            >
              <HugeiconsIcon
                icon={CheckmarkCircle02Icon}
                data-icon="inline-start"
              />
              Принять
            </Button>
          ) : null}
        </div>
      )
    }
    if (row.line.status === "RECEIVED" && row.line.returnConflict) {
      return (
        <Button
          size="sm"
          variant="outline"
          disabled={!destinationManage}
          onClick={() =>
            void runAction(
              () =>
                resumeImportedReturnConflict({
                  warehouseId: row.line.returnConflict!.warehouseId,
                  returnItemId: row.line.returnConflict!.returnItemId,
                  expectedVersion: row.line.returnConflict!.expectedVersion,
                  resolvedBy: actorSnapshot(currentUser).displayName,
                  resolution: {
                    kind: "WAREHOUSE_TRANSFER",
                    id: row.document.id,
                  },
                }),
              "Конфликт возврата закрыт"
            )
          }
        >
          Продолжить возврат
        </Button>
      )
    }
    return null
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="grid gap-2 md:grid-cols-[minmax(12rem,1fr)_auto_auto_auto_auto]">
          <Input
            aria-label="Поиск перемещений"
            placeholder="Бытовка, водитель или склад"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Input
            aria-label="Перемещения с даты"
            type="date"
            value={dateFrom}
            onChange={(event) => setDateFrom(event.target.value)}
          />
          <Input
            aria-label="Перемещения по дату"
            type="date"
            value={dateTo}
            onChange={(event) => setDateTo(event.target.value)}
          />
          <Select
            value={direction}
            onValueChange={(value) => setDirection(value as TransferDirection)}
          >
            <SelectTrigger aria-label="Направление перемещения">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">Все направления</SelectItem>
                <SelectItem value="OUTBOUND">Исходящие</SelectItem>
                <SelectItem value="INBOUND">Входящие</SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>
          <Select
            value={status}
            onValueChange={(value) => setStatus(value as TransferStatusFilter)}
          >
            <SelectTrigger aria-label="Статус перемещения">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">Все статусы</SelectItem>
                {Object.entries(warehouseTransferStatusLabels).map(
                  ([value, label]) => (
                    <SelectItem key={value} value={value}>
                      {label}
                    </SelectItem>
                  )
                )}
              </SelectGroup>
            </SelectContent>
          </Select>
        </PageToolbarContent>
        <PageToolbarActions>
          <Button variant="outline" onClick={() => setCorrectionsOpen(true)}>
            Коррекции
            {(correctionsQuery.data ?? []).filter(
              (item) =>
                item.status === "PENDING_APPROVALS" ||
                item.status === "APPLYING"
            ).length ? (
              <Badge variant="secondary">
                {
                  (correctionsQuery.data ?? []).filter(
                    (item) =>
                      item.status === "PENDING_APPROVALS" ||
                      item.status === "APPLYING"
                  ).length
                }
              </Badge>
            ) : null}
          </Button>
          <Button
            disabled={
              !selectedWarehouse ||
              !canManageWarehouse(currentUser, selectedWarehouse.id) ||
              !accessToken
            }
            onClick={() => {
              setCreatePrefill(null)
              setCreateOpen(true)
            }}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Создать перемещение
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
                className: "w-40",
                getSortValue: (row) => row.document.plannedDate,
                render: (row) => formatDate(row.document.plannedDate),
              },
              {
                id: "direction",
                label: "Направление",
                className: "min-w-64",
                getSortValue: (row) =>
                  `${row.document.sourceWarehouse.name} ${row.document.destinationWarehouse.name}`,
                render: (row) =>
                  `${row.document.sourceWarehouse.code} → ${row.document.destinationWarehouse.code}`,
              },
              {
                id: "cabin",
                label: "Бытовка",
                className: "w-40",
                getSortValue: (row) => row.line.cabinNumber,
                render: (row) => <strong>{row.line.cabinNumber}</strong>,
              },
              {
                id: "driver",
                label: "Водитель",
                className: "min-w-52",
                getSortValue: (row) => row.document.driverName,
                render: (row) => row.document.driverName,
              },
              {
                id: "contents",
                label: "Наполнение",
                className: "min-w-64",
                getSortValue: (row) => row.line.contentsSnapshot.length,
                render: (row) =>
                  row.line.contentsSnapshot.length ? (
                    row.line.contentsSnapshot
                      .map((item) => `${item.name} — ${item.quantity}`)
                      .join(", ")
                  ) : (
                    <span className="text-muted-foreground">
                      Без наполнения
                    </span>
                  ),
              },
              {
                id: "status",
                label: "Статус",
                className: "w-44",
                getSortValue: (row) => row.line.status,
                render: (row) => (
                  <div className="flex flex-col gap-1">
                    <TransferStatusBadge status={row.line.status} />
                    {row.line.lastError ? (
                      <span className="max-w-64 text-xs text-destructive">
                        {row.line.lastError}
                      </span>
                    ) : null}
                    {row.line.conflictReason ? (
                      <span className="max-w-64 text-xs text-destructive">
                        {row.line.conflictReason}
                      </span>
                    ) : null}
                  </div>
                ),
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-72",
                getSortValue: (row) => row.line.status,
                render: actions,
              },
            ]}
          />
        </div>
        <div className="grid gap-3 md:hidden">
          {rows.map((row) => (
            <Card key={row.id} size="sm">
              <CardHeader>
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <CardTitle>{row.line.cabinNumber}</CardTitle>
                    <CardDescription>
                      {row.document.sourceWarehouse.code} →{" "}
                      {row.document.destinationWarehouse.code}
                    </CardDescription>
                  </div>
                  <TransferStatusBadge status={row.line.status} />
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-2 text-sm">
                <span>
                  {formatDate(row.document.plannedDate)} ·{" "}
                  {row.document.driverName}
                </span>
                {row.line.contentsSnapshot.length ? (
                  <span className="text-muted-foreground">
                    {row.line.contentsSnapshot
                      .map((item) => `${item.name} — ${item.quantity}`)
                      .join(", ")}
                  </span>
                ) : null}
                {row.line.lastError || row.line.conflictReason ? (
                  <span className="text-destructive">
                    {row.line.lastError ?? row.line.conflictReason}
                  </span>
                ) : null}
              </CardContent>
              {actions(row) ? <CardFooter>{actions(row)}</CardFooter> : null}
            </Card>
          ))}
          {rows.length === 0 ? (
            <p
              role="status"
              className="p-4 text-center text-sm text-muted-foreground"
            >
              Перемещения не найдены
            </p>
          ) : null}
        </div>
      </div>

      {createOpen &&
      (warehouses.find(
        (warehouse) => warehouse.id === createPrefill?.sourceWarehouseId
      ) ??
        selectedWarehouse) ? (
        <CreateTransferDialog
          open
          sourceWarehouseId={
            warehouses.find(
              (warehouse) => warehouse.id === createPrefill?.sourceWarehouseId
            )?.id ?? selectedWarehouse!.id
          }
          warehouses={warehouses}
          accessToken={accessToken ?? ""}
          actor={actorSnapshot(currentUser)}
          initialRentalItemId={createPrefill?.rentalItemId ?? null}
          initialDestinationWarehouseId={
            createPrefill?.destinationWarehouseId ?? null
          }
          initialReturnConflictId={createPrefill?.returnConflictId ?? null}
          onOpenChange={setCreateOpen}
          onCreated={() => {
            void queryClient.invalidateQueries({
              queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
            })
            void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
            void queryClient.invalidateQueries({
              queryKey: LOGISTICS_QUERY_KEY,
            })
          }}
        />
      ) : null}
      {arrival ? (
        <ArrivalDialog
          row={arrival}
          actor={actorSnapshot(currentUser)}
          onOpenChange={(open) => !open && setArrival(null)}
          onAccepted={() => {
            setArrival(null)
            void queryClient.invalidateQueries({
              queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
            })
            void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
            void queryClient.invalidateQueries({
              queryKey: LOGISTICS_QUERY_KEY,
            })
          }}
        />
      ) : null}
      {cancellation ? (
        <CancelTransferDialog
          row={cancellation}
          accessToken={accessToken ?? ""}
          actor={actorSnapshot(currentUser)}
          onOpenChange={(open) => !open && setCancellation(null)}
          onCancelled={() => {
            setCancellation(null)
            void queryClient.invalidateQueries({
              queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
            })
          }}
        />
      ) : null}
      {correctionsOpen ? (
        <WarehouseCorrectionsDialog
          open
          warehouses={warehouses}
          selectedWarehouseId={selectedWarehouseId}
          currentUser={currentUser}
          actor={actorSnapshot(currentUser)}
          corrections={correctionsQuery.data ?? []}
          prefill={correctionPrefill}
          onOpenChange={setCorrectionsOpen}
          onChanged={() => {
            void queryClient.invalidateQueries({
              queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
            })
            void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
            void queryClient.invalidateQueries({
              queryKey: LOGISTICS_QUERY_KEY,
            })
          }}
        />
      ) : null}
    </div>
  )
}

function CreateTransferDialog({
  open,
  sourceWarehouseId,
  warehouses,
  accessToken,
  actor,
  initialRentalItemId,
  initialDestinationWarehouseId,
  initialReturnConflictId,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  sourceWarehouseId: string
  warehouses: ReturnType<typeof useWarehouse>["warehouses"]
  accessToken: string
  actor: WarehouseTransferActorSnapshot
  initialRentalItemId: string | null
  initialDestinationWarehouseId: string | null
  initialReturnConflictId: string | null
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  const dialogContentRef = useRef<HTMLDivElement>(null)
  const sourceWarehouse = warehouses.find(
    (item) => item.id === sourceWarehouseId
  )!
  const destinations = warehouses.filter(
    (item) => item.id !== sourceWarehouseId
  )
  const [destinationId, setDestinationId] = useState(
    destinations.some((item) => item.id === initialDestinationWarehouseId)
      ? (initialDestinationWarehouseId ?? "")
      : (destinations[0]?.id ?? "")
  )
  const [plannedDate, setPlannedDate] = useState(
    new Date().toISOString().slice(0, 10)
  )
  const [driverName, setDriverName] = useState("")
  const [comment, setComment] = useState("")
  const [cabinSlots, setCabinSlots] = useState<WarehouseTransferCabinSlot[]>(
    () => [
      {
        slotId: crypto.randomUUID(),
        rentalItemId: initialRentalItemId ?? "",
      },
    ]
  )
  const selectedIds = cabinSlots
    .map((slot) => slot.rentalItemId)
    .filter(Boolean)
  const allCabinsSelected =
    cabinSlots.length > 0 &&
    cabinSlots.every((slot) => Boolean(slot.rentalItemId))
  const [saving, setSaving] = useState(false)
  const candidatesQuery = useQuery({
    queryKey: ["warehouse-transfer-candidates", sourceWarehouseId],
    queryFn: () => getWarehouseTransferCandidates(sourceWarehouseId),
  })
  const selectedCandidatesAreAvailable = selectedIds.every((rentalItemId) =>
    (candidatesQuery.data ?? []).some(
      (candidate) => candidate.id === rentalItemId
    )
  )

  async function submit() {
    const destination = warehouses.find((item) => item.id === destinationId)
    if (!destination) return toast.error("Выберите склад назначения")
    const candidates = candidatesQuery.data ?? []
    if (
      !allCabinsSelected ||
      !selectedCandidatesAreAvailable ||
      candidatesQuery.isLoading
    ) {
      return toast.error("Выберите бытовки из доступного списка")
    }
    setSaving(true)
    try {
      const linkedReturn = initialReturnConflictId
        ? await getReturnTask(destination.id, initialReturnConflictId)
        : null
      if (
        initialReturnConflictId &&
        (!linkedReturn ||
          linkedReturn.technicalState !== "CONFLICT" ||
          !selectedIds.includes(linkedReturn.rentalItemId))
      ) {
        throw new Error(
          "Конфликт возврата изменился. Откройте перемещение из возврата повторно"
        )
      }
      const created = await createWarehouseTransfer({
        requestId: crypto.randomUUID(),
        accessToken,
        sourceWarehouse,
        destinationWarehouse: destination,
        plannedDate,
        driverName,
        comment,
        actor,
        cabins: selectedIds.map((rentalItemId) => {
          const cabin = candidates.find((item) => item.id === rentalItemId)!
          return {
            rentalItemId,
            expectedVersion: cabin.version,
            returnConflict:
              linkedReturn?.rentalItemId === rentalItemId
                ? {
                    warehouseId: linkedReturn.warehouseId,
                    returnItemId: linkedReturn.id,
                    expectedVersion: linkedReturn.version,
                  }
                : null,
          }
        }),
      })
      const failed = created.lines.filter(
        (line) => line.status === "PREPARING"
      ).length
      if (failed)
        toast.warning(`Перемещение создано. Не отправлено заданий: ${failed}`)
      else toast.success("Перемещение и задания созданы")
      onCreated()
      onOpenChange(false)
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось создать перемещение"
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        ref={dialogContentRef}
        className="max-h-[90svh] overflow-y-auto sm:max-w-2xl"
      >
        <DialogHeader>
          <DialogTitle>Новое межскладское перемещение</DialogTitle>
          <DialogDescription>
            Для каждой бытовки будет создано отдельное задание отправления.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <Field>
            <FieldLabel>Склад отправления</FieldLabel>
            <Input value={sourceWarehouse.name} disabled />
          </Field>
          <Field>
            <FieldLabel htmlFor="transfer-destination">
              Склад назначения
            </FieldLabel>
            <Select value={destinationId} onValueChange={setDestinationId}>
              <SelectTrigger id="transfer-destination">
                <SelectValue placeholder="Выберите склад" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {destinations.map((warehouse) => (
                    <SelectItem key={warehouse.id} value={warehouse.id}>
                      {warehouse.name}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="transfer-date">Плановая дата</FieldLabel>
              <Input
                id="transfer-date"
                type="date"
                value={plannedDate}
                onChange={(event) => setPlannedDate(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="transfer-driver">Водитель</FieldLabel>
              <Input
                id="transfer-driver"
                value={driverName}
                onChange={(event) => setDriverName(event.target.value)}
              />
            </Field>
          </div>
          <Field>
            <FieldLabel htmlFor="transfer-comment">Комментарий</FieldLabel>
            <Textarea
              id="transfer-comment"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
            />
          </Field>
          <WarehouseTransferCabinSelection
            candidates={candidatesQuery.data ?? []}
            isLoading={candidatesQuery.isLoading}
            slots={cabinSlots}
            portalContainer={dialogContentRef}
            onChange={setCabinSlots}
          />
        </FieldGroup>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button
            disabled={
              saving ||
              !plannedDate ||
              !driverName.trim() ||
              !allCabinsSelected ||
              !selectedCandidatesAreAvailable ||
              candidatesQuery.isLoading
            }
            onClick={() => void submit()}
          >
            {saving ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                className="animate-spin"
                data-icon="inline-start"
              />
            ) : (
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            )}
            Создать перемещение
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ArrivalDialog({
  row,
  actor,
  onOpenChange,
  onAccepted,
}: {
  row: TransferRow
  actor: WarehouseTransferActorSnapshot
  onOpenChange: (open: boolean) => void
  onAccepted: () => void
}) {
  const [contents, setContents] = useState<RentalItemContentsItemDto[]>(() =>
    structuredClone(row.line.contentsSnapshot)
  )
  const [photos, setPhotos] = useState<RentalItemCreationPhoto[]>([])
  const [saving, setSaving] = useState(false)
  const recoveryPhotos =
    row.line.applicationAttempt?.kind === "ARRIVAL"
      ? row.line.applicationAttempt.photos
      : []

  async function submit() {
    setSaving(true)
    try {
      const photoUploads = photos.map((photo) => ({
        id: photo.id,
        fileName: photo.name,
        dataUrl: photo.sourceDataUrl,
        rotationDegrees: photo.rotation,
      }))
      const saved = await acceptWarehouseTransferArrival({
        documentId: row.document.id,
        lineId: row.line.id,
        expectedDocumentVersion: row.document.version,
        expectedLineVersion: row.line.version,
        actualContents: contents,
        photoUploads,
        existingPhotos: [...row.line.photos, ...recoveryPhotos],
        actor,
      })
      const line = saved.lines.find((item) => item.id === row.line.id)
      if (line?.status === "CONFLICT")
        toast.error(line.conflictReason ?? "Обнаружен конфликт")
      else {
        toast.success("Бытовка принята на склад")
        if (line?.status === "RECEIVED" && line.returnConflict) {
          try {
            await resumeImportedReturnConflict({
              warehouseId: line.returnConflict.warehouseId,
              returnItemId: line.returnConflict.returnItemId,
              expectedVersion: line.returnConflict.expectedVersion,
              resolvedBy: actor.displayName,
              resolution: {
                kind: "WAREHOUSE_TRANSFER",
                id: saved.id,
              },
            })
            toast.success("Конфликт возврата закрыт, бытовка ожидает осмотра")
          } catch (error) {
            toast.error(
              error instanceof Error
                ? `Бытовка принята, но возврат не продолжен: ${error.message}`
                : "Бытовка принята, но возврат не продолжен"
            )
          }
        }
      }
      onAccepted()
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Не удалось принять бытовку"
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Приёмка {row.line.cabinNumber}</DialogTitle>
          <DialogDescription>
            Сверьте фактическое наполнение со снимком отправления и добавьте
            фотографии.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <FieldSet>
            <FieldLegend>Фактическое наполнение</FieldLegend>
            {contents.length ? (
              contents.map((item, index) => (
                <Field key={item.name} orientation="horizontal">
                  <FieldLabel htmlFor={`arrival-${index}`}>
                    {item.name}
                  </FieldLabel>
                  <Input
                    id={`arrival-${index}`}
                    className="w-24"
                    type="number"
                    min={0}
                    value={item.quantity}
                    onChange={(event) =>
                      setContents((current) =>
                        current.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                quantity: Math.max(
                                  0,
                                  Number(event.target.value) || 0
                                ),
                              }
                            : entry
                        )
                      )
                    }
                  />
                </Field>
              ))
            ) : (
              <FieldDescription>
                Наполнение при отправлении отсутствовало.
              </FieldDescription>
            )}
          </FieldSet>
          <RentalItemPhotoUploader
            photos={photos}
            disabled={saving}
            onChange={setPhotos}
          />
          {row.line.photos.length + recoveryPhotos.length ? (
            <FieldDescription>
              Ранее сохранено фотографий:{" "}
              {row.line.photos.length + recoveryPhotos.length}. Они будут
              использованы повторно.
            </FieldDescription>
          ) : null}
        </FieldGroup>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button
            disabled={
              saving ||
              (!photos.length &&
                !row.line.photos.length &&
                !recoveryPhotos.length)
            }
            onClick={() => void submit()}
          >
            {saving ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                className="animate-spin"
                data-icon="inline-start"
              />
            ) : (
              <HugeiconsIcon
                icon={CheckmarkCircle02Icon}
                data-icon="inline-start"
              />
            )}
            Подтвердить приёмку
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CancelTransferDialog({
  row,
  accessToken,
  actor,
  onOpenChange,
  onCancelled,
}: {
  row: TransferRow
  accessToken: string
  actor: WarehouseTransferActorSnapshot
  onOpenChange: (open: boolean) => void
  onCancelled: () => void
}) {
  const [reason, setReason] = useState("")
  const [saving, setSaving] = useState(false)
  async function submit() {
    setSaving(true)
    try {
      await cancelWarehouseTransferLine({
        documentId: row.document.id,
        lineId: row.line.id,
        expectedDocumentVersion: row.document.version,
        expectedLineVersion: row.line.version,
        accessToken,
        actor,
        reason,
      })
      toast.success("Перемещение отменено")
      onCancelled()
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось отменить перемещение"
      )
    } finally {
      setSaving(false)
    }
  }
  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Отменить перемещение {row.line.cabinNumber}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            Задание отправления будет отменено. После подтверждения убытия
            отмена недоступна.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <Field>
          <FieldLabel htmlFor="transfer-cancel-reason">Причина</FieldLabel>
          <Textarea
            id="transfer-cancel-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </Field>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={saving}>Назад</AlertDialogCancel>
          <AlertDialogAction
            disabled={saving || !reason.trim()}
            onClick={(event) => {
              event.preventDefault()
              void submit()
            }}
          >
            Отменить перемещение
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function WarehouseCorrectionsDialog({
  open,
  warehouses,
  selectedWarehouseId,
  currentUser,
  actor,
  corrections,
  prefill,
  onOpenChange,
  onChanged,
}: {
  open: boolean
  warehouses: ReturnType<typeof useWarehouse>["warehouses"]
  selectedWarehouseId: string | null
  currentUser: ReturnType<typeof useAuth>["currentUser"]
  actor: WarehouseTransferActorSnapshot
  corrections: WarehouseAccountingCorrection[]
  prefill: {
    rentalItemId: string | null
    sourceWarehouseId: string | null
    destinationWarehouseId: string | null
    returnConflictId: string | null
  } | null
  onOpenChange: (open: boolean) => void
  onChanged: () => void
}) {
  const [reason, setReason] = useState("")
  const [saving, setSaving] = useState(false)
  const [approval, setApproval] =
    useState<WarehouseAccountingCorrection | null>(null)
  const candidateQuery = useQuery({
    queryKey: ["warehouse-correction-candidate", prefill?.rentalItemId],
    queryFn: () => getWarehouseCorrectionCandidate(prefill!.rentalItemId!),
    enabled: Boolean(prefill?.rentalItemId),
  })
  const sourceWarehouse = warehouses.find(
    (item) => item.id === prefill?.sourceWarehouseId
  )
  const destinationWarehouse = warehouses.find(
    (item) => item.id === prefill?.destinationWarehouseId
  )
  const candidate = candidateQuery.data ?? null
  const pendingForCandidate = corrections.some(
    (item) =>
      item.rentalItemId === prefill?.rentalItemId &&
      item.status === "PENDING_APPROVALS"
  )

  async function createCorrection() {
    if (!candidate || !sourceWarehouse || !destinationWarehouse) return
    setSaving(true)
    try {
      const linkedReturn = prefill?.returnConflictId
        ? await getReturnTask(destinationWarehouse.id, prefill.returnConflictId)
        : null
      if (
        prefill?.returnConflictId &&
        (!linkedReturn ||
          linkedReturn.technicalState !== "CONFLICT" ||
          linkedReturn.rentalItemId !== candidate.id)
      ) {
        throw new Error(
          "Конфликт возврата изменился. Откройте коррекцию из возврата повторно"
        )
      }
      await createWarehouseAccountingCorrection({
        rentalItemId: candidate.id,
        expectedRentalItemVersion: candidate.version,
        sourceWarehouse,
        destinationWarehouse,
        reason,
        actor,
        returnConflict: linkedReturn
          ? {
              warehouseId: linkedReturn.warehouseId,
              returnItemId: linkedReturn.id,
              expectedVersion: linkedReturn.version,
            }
          : null,
      })
      toast.success("Коррекция создана и ожидает подтверждения двух складов")
      setReason("")
      onChanged()
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Не удалось создать коррекцию"
      )
    } finally {
      setSaving(false)
    }
  }

  async function approveCorrection(correction: WarehouseAccountingCorrection) {
    if (!selectedWarehouseId) return
    setSaving(true)
    try {
      const saved = await approveWarehouseAccountingCorrection({
        correctionId: correction.id,
        expectedVersion: correction.version,
        approvingWarehouseId: selectedWarehouseId,
        actor,
      })
      toast.success(
        saved.status === "APPLIED"
          ? "Коррекция подтверждена обоими складами и применена"
          : "Подтверждение склада сохранено"
      )
      if (saved.status === "APPLIED" && saved.returnConflict) {
        try {
          await resumeImportedReturnConflict({
            warehouseId: saved.returnConflict.warehouseId,
            returnItemId: saved.returnConflict.returnItemId,
            expectedVersion: saved.returnConflict.expectedVersion,
            resolvedBy: actor.displayName,
            resolution: {
              kind: "ACCOUNTING_CORRECTION",
              id: saved.id,
            },
          })
          toast.success("Конфликт возврата закрыт, бытовка ожидает осмотра")
        } catch (error) {
          toast.error(
            error instanceof Error
              ? `Коррекция применена, но возврат не продолжен: ${error.message}`
              : "Коррекция применена, но возврат не продолжен"
          )
        }
      }
      setApproval(null)
      onChanged()
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось подтвердить коррекцию"
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>Коррекции склада</DialogTitle>
            <DialogDescription>
              Коррекция не создаёт транспортное задание и применяется только
              после подтверждения обоих складов.
            </DialogDescription>
          </DialogHeader>

          {prefill && !pendingForCandidate ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>
                  Новая коррекция {candidate?.number ?? "бытовки"}
                </CardTitle>
                <CardDescription>
                  {sourceWarehouse?.name ?? "Неизвестный склад"} →{" "}
                  {destinationWarehouse?.name ?? "Неизвестный склад"}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Field data-invalid={!sourceWarehouse || !destinationWarehouse}>
                  <FieldLabel htmlFor="warehouse-correction-reason">
                    Причина
                  </FieldLabel>
                  <Textarea
                    id="warehouse-correction-reason"
                    value={reason}
                    aria-invalid={!sourceWarehouse || !destinationWarehouse}
                    onChange={(event) => setReason(event.target.value)}
                  />
                  {!sourceWarehouse || !destinationWarehouse ? (
                    <FieldDescription>
                      Ссылка содержит неизвестный склад. Откройте конфликт
                      повторно.
                    </FieldDescription>
                  ) : null}
                </Field>
              </CardContent>
              <CardFooter>
                <Button
                  disabled={
                    saving ||
                    !candidate ||
                    !sourceWarehouse ||
                    !destinationWarehouse ||
                    !reason.trim()
                  }
                  onClick={() => void createCorrection()}
                >
                  Создать коррекцию
                </Button>
              </CardFooter>
            </Card>
          ) : null}

          <div className="grid gap-3">
            {corrections.map((correction) => {
              const currentWarehouseParticipates =
                selectedWarehouseId === correction.sourceWarehouse.id ||
                selectedWarehouseId === correction.destinationWarehouse.id
              const currentWarehouse = warehouses.find(
                (item) => item.id === selectedWarehouseId
              )
              const mayApprove =
                (correction.status === "PENDING_APPROVALS" ||
                  correction.status === "APPLYING") &&
                currentWarehouseParticipates &&
                Boolean(currentWarehouse) &&
                canManageWarehouse(currentUser, currentWarehouse!.id) &&
                (correction.status === "APPLYING" ||
                  !correction.approvals.some(
                    (item) => item.warehouseId === selectedWarehouseId
                  ))
              const mayRetryReturn =
                correction.status === "APPLIED" &&
                Boolean(correction.returnConflict) &&
                currentWarehouseParticipates
              return (
                <Card key={correction.id} size="sm">
                  <CardHeader>
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <CardTitle>{correction.cabinNumber}</CardTitle>
                        <CardDescription>
                          {correction.sourceWarehouse.code} →{" "}
                          {correction.destinationWarehouse.code} ·{" "}
                          {correction.reason}
                        </CardDescription>
                      </div>
                      <Badge
                        variant={
                          correction.status === "REJECTED"
                            ? "destructive"
                            : correction.status === "APPLIED"
                              ? "default"
                              : "secondary"
                        }
                      >
                        {correction.status === "APPLIED"
                          ? "Применена"
                          : correction.status === "REJECTED"
                            ? "Отклонена"
                            : correction.status === "APPLYING"
                              ? "Применение не завершено"
                              : `Подтверждено ${correction.approvals.length}/2`}
                      </Badge>
                    </div>
                  </CardHeader>
                  <CardContent className="text-sm text-muted-foreground">
                    {correction.approvals.length
                      ? correction.approvals
                          .map(
                            (item) =>
                              `${item.actor.displayName} · ${warehouses.find((warehouse) => warehouse.id === item.warehouseId)?.code ?? item.warehouseId}`
                          )
                          .join(", ")
                      : "Подтверждений пока нет"}
                  </CardContent>
                  {mayApprove || mayRetryReturn ? (
                    <CardFooter>
                      <Button
                        onClick={() =>
                          mayRetryReturn
                            ? void approveCorrection(correction)
                            : setApproval(correction)
                        }
                      >
                        {mayRetryReturn
                          ? "Продолжить возврат"
                          : correction.status === "APPLYING"
                            ? "Повторить применение"
                            : "Подтвердить от текущего склада"}
                      </Button>
                    </CardFooter>
                  ) : null}
                </Card>
              )
            })}
            {!corrections.length ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                Коррекций пока нет
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Закрыть
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {approval ? (
        <AlertDialog
          open
          onOpenChange={(nextOpen) => !nextOpen && setApproval(null)}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>
                Подтвердить коррекцию {approval.cabinNumber}?
              </AlertDialogTitle>
              <AlertDialogDescription>
                После второго подтверждения склад бытовки изменится без
                транспортного задания. Проверьте, что это именно исправление
                учётной ошибки.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel disabled={saving}>Отмена</AlertDialogCancel>
              <AlertDialogAction
                disabled={saving}
                onClick={(event) => {
                  event.preventDefault()
                  void approveCorrection(approval)
                }}
              >
                Подтвердить
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </>
  )
}
