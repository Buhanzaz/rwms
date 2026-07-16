import { useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useNavigate, useSearchParams } from "react-router-dom"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Delete02Icon,
  PencilEdit01Icon,
} from "@hugeicons/core-free-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
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
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useAuth } from "@/features/auth/use-auth"
import type {
  ReturnReceiptItemDto,
  ReturnReceiptItemViewDto,
  ReturnReceiptViewDto,
  ReturnTaskDto,
} from "@/features/logistics/model/logistics"
import type {
  RentalItemContentsItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import { RENTAL_ITEM_STATUS_LABEL } from "@/features/rental-items/model/rental-item"
import { smartLocalSearch } from "@/features/rental-items/smart-local-search"
import type { PendingEstimateMediaUpload } from "@/features/repair-estimates/model/repair-estimate"
import {
  LOGISTICS_QUERY_KEY,
  LOGISTICS_STORAGE_KEY,
  LOGISTICS_UPDATED_EVENT,
  acceptReturnUndamaged,
  attachReturnMedia,
  createReturnReceipt,
  isReturnReceiptMembershipEditable,
  listCompanies,
  listReturnCandidates,
  listReturnEquipmentCatalog,
  listReturnEditCandidates,
  listReturnReceipts,
  markReturnEstimateCreated,
  resolveReturnFurnitureDisposition,
  retryReturnFurnitureTransfer,
  updateReturnReceipt,
} from "@/features/logistics/api/logistics-api"
import { LogisticsCabinContentsEditor } from "@/features/logistics/logistics-cabin-contents-editor"
import { ImportRentedCabinDialog } from "@/features/logistics/import-rented-cabin-dialog"
import { LogisticsPhotoDialog } from "@/features/logistics/logistics-photo-dialog"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import {
  getRentalItemsForContentsMove,
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

type ReturnCabinDraft = {
  slotId: string
  rentalItemId: string
  expectedContents: RentalItemContentsItemDto[]
  returnedContents: RentalItemContentsItemDto[]
}

function newReturnCabinDraft(
  rentalItemId = "",
  expectedContents: RentalItemContentsItemDto[] = [],
  returnedContents: RentalItemContentsItemDto[] = expectedContents
): ReturnCabinDraft {
  return {
    slotId: crypto.randomUUID(),
    rentalItemId,
    expectedContents: expectedContents.map((item) => ({ ...item })),
    returnedContents: returnedContents.map((item) => ({ ...item })),
  }
}

function stageLabel(status: RentalItemStatus) {
  if (status === "WAITING_REPAIR_CHECK") return "В ремонте"
  return RENTAL_ITEM_STATUS_LABEL[status]
}

function returnStageLabel(item: ReturnReceiptItemViewDto) {
  if (item.technicalState === "CONFLICT") return "Конфликт"
  if (item.technicalState === "CANCELLED") return "Отменён"
  return stageLabel(item.currentStatus)
}

function toTask(
  receipt: ReturnReceiptViewDto,
  item: ReturnReceiptItemDto
): ReturnTaskDto {
  return {
    ...item,
    receiptId: receipt.id,
    warehouseId: receipt.warehouseId,
    fromParty: receipt.fromParty,
    driverId: receipt.driverId,
    driverName: receipt.driverName,
    shipmentDate: receipt.shipmentDate,
    returnDate: receipt.returnDate,
    createdAt: receipt.createdAt,
    createdBy: receipt.createdBy,
    receiverName: receipt.receiverName,
    status: item.technicalState,
  }
}

export function LogisticsReturnsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedReceiptId = searchParams.get("receiptId")
  const selectedReturnItemId = searchParams.get("returnItemId")
  const { selectedWarehouseId, selectedWarehouse } = useWarehouse()
  const { currentUser, accessToken } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [importRentedCabinOpen, setImportRentedCabinOpen] = useState(false)
  const [editingReceipt, setEditingReceipt] =
    useState<ReturnReceiptViewDto | null>(null)
  const [acceptTask, setAcceptTask] = useState<ReturnTaskDto | null>(null)
  const [furnitureConflictTask, setFurnitureConflictTask] =
    useState<ReturnTaskDto | null>(null)
  const [photoAction, setPhotoAction] = useState<{
    kind: "ACCEPT" | "ESTIMATE"
    task: ReturnTaskDto
  } | null>(null)
  const [search, setSearch] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const [showAll, setShowAll] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const query = useQuery({
    queryKey: [...LOGISTICS_QUERY_KEY, "return-receipts", selectedWarehouseId],
    queryFn: () => listReturnReceipts(selectedWarehouseId!),
    enabled: Boolean(selectedWarehouseId),
  })
  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
    }
    const onStorage = (event: StorageEvent) => {
      if (
        event.key === LOGISTICS_STORAGE_KEY ||
        event.key === "rwms:logistics:v1" ||
        event.key === RENTAL_ITEMS_MOCK_STORAGE_KEY
      )
        invalidate()
    }
    window.addEventListener(LOGISTICS_UPDATED_EVENT, invalidate)
    window.addEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", onStorage)
    return () => {
      window.removeEventListener(LOGISTICS_UPDATED_EVENT, invalidate)
      window.removeEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", onStorage)
    }
  }, [queryClient])

  const rows = useMemo(() => {
    const filtered = (query.data ?? []).filter(
      (receipt) =>
        (!selectedReceiptId || receipt.id === selectedReceiptId) &&
        (!selectedReturnItemId ||
          receipt.items.some((item) => item.id === selectedReturnItemId)) &&
        (!dateFrom || receipt.returnDate >= dateFrom) &&
        (!dateTo || receipt.returnDate <= dateTo) &&
        (showAll ||
          receipt.items.some(
            (item) =>
              item.technicalState === "PENDING_INSPECTION" ||
              item.technicalState === "CONFLICT"
          ))
    )
    return smartLocalSearch(filtered, search, (receipt) => [
      receipt.fromParty,
      receipt.driverName,
      receipt.receiverName,
      ...receipt.items.flatMap((item) => [
        item.cabinNumber,
        returnStageLabel(item),
        item.conflicts.map((conflict) => conflict.message).join(" "),
      ]),
    ])
  }, [
    query.data,
    search,
    dateFrom,
    dateTo,
    selectedReceiptId,
    selectedReturnItemId,
    showAll,
  ])

  const photoMutation = useMutation({
    mutationFn: async ({
      action,
      uploads,
    }: {
      action: NonNullable<typeof photoAction>
      uploads: PendingEstimateMediaUpload[]
    }) => {
      if (action.kind === "ACCEPT") {
        return {
          kind: "ACCEPT" as const,
          task: await acceptReturnUndamaged({
            warehouseId: action.task.warehouseId,
            returnTaskId: action.task.id,
            expectedVersion: action.task.version,
            uploads,
          }),
        }
      }
      const saved = await attachReturnMedia({
        warehouseId: action.task.warehouseId,
        returnTaskId: action.task.id,
        expectedVersion: action.task.version,
        uploads,
      })
      return { kind: "ESTIMATE" as const, task: saved }
    },
    onSuccess: (result) => {
      setError(null)
      setPhotoAction(null)
      void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      if (result.kind === "ESTIMATE") {
        navigate(
          `/estimates?create=1&returnTaskId=${encodeURIComponent(result.task.id)}`,
          workspaceEntryNavigationOptions
        )
      }
    },
    onError: (cause) =>
      setError(
        cause instanceof Error
          ? cause.message
          : "Не удалось сохранить фотографии"
      ),
  })

  async function openEstimate(task: ReturnTaskDto) {
    const knownEstimateId = task.sourceEstimateId ?? task.pendingEstimateId
    if (knownEstimateId) {
      if (!task.sourceEstimateId) {
        try {
          await markReturnEstimateCreated({
            warehouseId: task.warehouseId,
            returnTaskId: task.id,
            expectedVersion: task.version,
            estimateId: knownEstimateId,
            rentalItemId: task.rentalItemId,
            claimId: null,
          })
          void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
        } catch {
          // The persisted estimate remains reachable while linkage recovery retries.
        }
      }
      navigate(
        `/estimates?estimateId=${encodeURIComponent(knownEstimateId)}`,
        workspaceEntryNavigationOptions
      )
    } else if (task.media.length > 0) {
      navigate(
        `/estimates?create=1&returnTaskId=${encodeURIComponent(task.id)}`,
        workspaceEntryNavigationOptions
      )
    } else setPhotoAction({ kind: "ESTIMATE", task })
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="grid gap-2 md:grid-cols-[minmax(12rem,1fr)_auto_auto]">
          <Input
            aria-label="Поиск возвратов"
            placeholder="Компания или номер бытовки"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Input
            aria-label="Возврат с даты"
            type="date"
            value={dateFrom}
            onChange={(event) => setDateFrom(event.target.value)}
          />
          <Input
            aria-label="Возврат по дату"
            type="date"
            value={dateTo}
            onChange={(event) => setDateTo(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedReceiptId ? (
            <Button
              variant="outline"
              onClick={() => {
                const next = new URLSearchParams(searchParams)
                next.delete("receiptId")
                next.delete("returnItemId")
                setSearchParams(next, { replace: true })
              }}
            >
              Показать все
            </Button>
          ) : null}
          <Button
            variant="outline"
            onClick={() => setImportRentedCabinOpen(true)}
            disabled={!selectedWarehouseId}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить бытовку из аренды
          </Button>
          <Button
            variant="outline"
            onClick={() => setShowAll((value) => !value)}
          >
            {showAll ? "Требуют действий" : "Показать все"}
          </Button>
          <Button
            onClick={() => {
              setCreateOpen(true)
            }}
            disabled={!selectedWarehouseId}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить возврат
          </Button>
        </PageToolbarActions>
      </PageToolbar>
      {error && !photoAction ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            columns={[
              {
                id: "party",
                label: "От кого",
                className: "w-56",
                getSortValue: (receipt) => receipt.fromParty,
                render: (receipt) => receipt.fromParty,
              },
              {
                id: "date",
                label: "Дата возврата",
                className: "w-44",
                getSortValue: (receipt) => receipt.returnDate,
                render: (receipt) => dateLabel(receipt.returnDate),
              },
              {
                id: "driver",
                label: "Водитель",
                className: "w-52",
                getSortValue: (receipt) => receipt.driverName ?? "",
                render: (receipt) => receipt.driverName ?? "Не указан",
              },
              {
                id: "receiver",
                label: "Принимающий",
                className: "w-52",
                getSortValue: (receipt) => receipt.receiverName ?? "",
                render: (receipt) => receipt.receiverName ?? "Не зафиксировано",
              },
              {
                id: "cabins",
                label: "Возвращённые бытовки",
                className: "min-w-72",
                getSortValue: (receipt) => receipt.items.length,
                render: (receipt) => (
                  <div className="flex flex-wrap gap-2">
                    {receipt.items.map((item) => (
                      <Badge
                        key={item.id}
                        variant="outline"
                        data-selected={item.id === selectedReturnItemId}
                        className="data-[selected=true]:ring-2 data-[selected=true]:ring-ring"
                      >
                        {item.cabinNumber}
                      </Badge>
                    ))}
                  </div>
                ),
              },
              {
                id: "stage",
                label: "Этап",
                className: "w-64",
                getSortValue: (receipt) =>
                  receipt.items.map((item) => returnStageLabel(item)).join(" "),
                render: (receipt) => (
                  <div className="flex flex-col gap-3">
                    {receipt.items.map((item) => (
                      <Badge
                        key={item.id}
                        variant={
                          item.technicalState === "CONFLICT"
                            ? "destructive"
                            : "secondary"
                        }
                        data-selected={item.id === selectedReturnItemId}
                        className="data-[selected=true]:ring-2 data-[selected=true]:ring-ring"
                      >
                        {returnStageLabel(item)}
                      </Badge>
                    ))}
                  </div>
                ),
              },
              {
                id: "damage",
                label: "Повреждения",
                className: "w-40",
                getSortValue: (receipt) =>
                  receipt.items.map((item) => factValue(item)).join(" "),
                render: (receipt) => (
                  <div className="flex flex-col gap-3">
                    {receipt.items.map((item) => (
                      <FactBadge key={item.id} value={factValue(item)} />
                    ))}
                  </div>
                ),
              },
              {
                id: "estimate",
                label: "Смета",
                className: "w-40",
                getSortValue: (receipt) =>
                  receipt.items.map((item) => factValue(item)).join(" "),
                render: (receipt) => (
                  <div className="flex flex-col gap-3">
                    {receipt.items.map((item) => (
                      <FactBadge key={item.id} value={factValue(item)} />
                    ))}
                  </div>
                ),
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-80",
                getSortValue: (receipt) => receipt.returnDate,
                render: (receipt) => (
                  <div className="flex flex-col gap-3">
                    {receipt.items.map((item, index) => (
                      <div key={item.id} className="flex flex-wrap gap-2">
                        {index === 0 ? (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setEditingReceipt(receipt)}
                          >
                            <HugeiconsIcon
                              icon={PencilEdit01Icon}
                              data-icon="inline-start"
                            />
                            Редактировать
                          </Button>
                        ) : null}
                        <ReturnItemActions
                          item={item}
                          onAccept={() => setAcceptTask(toTask(receipt, item))}
                          onEstimate={() => openEstimate(toTask(receipt, item))}
                          onFurnitureConflict={() =>
                            setFurnitureConflictTask(toTask(receipt, item))
                          }
                          onEquipmentConflict={() =>
                            navigate(
                              `/write-offs/equipment?returnItemId=${encodeURIComponent(item.id)}`
                            )
                          }
                        />
                      </div>
                    ))}
                  </div>
                ),
              },
            ]}
          />
        </div>
        <div className="grid gap-3 md:hidden">
          {rows.map((receipt) => (
            <ReturnReceiptCard
              key={receipt.id}
              receipt={receipt}
              onAccept={(item) => setAcceptTask(toTask(receipt, item))}
              onEstimate={(item) => openEstimate(toTask(receipt, item))}
              onFurnitureConflict={(item) =>
                setFurnitureConflictTask(toTask(receipt, item))
              }
              onEquipmentConflict={(item) =>
                navigate(
                  `/write-offs/equipment?returnItemId=${encodeURIComponent(item.id)}`
                )
              }
              onEdit={() => setEditingReceipt(receipt)}
              selectedReturnItemId={selectedReturnItemId}
            />
          ))}
          {rows.length === 0 ? (
            <p
              role="status"
              className="p-4 text-center text-sm text-muted-foreground"
            >
              Возвраты не найдены
            </p>
          ) : null}
        </div>
      </div>
      <ImportRentedCabinDialog
        open={importRentedCabinOpen}
        warehouseId={selectedWarehouseId}
        createdBy={currentUser?.displayName ?? "Текущий пользователь"}
        onOpenChange={setImportRentedCabinOpen}
        onCreated={() => {
          void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
          void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
        }}
      />
      {createOpen || editingReceipt !== null ? (
        <ReturnReceiptDialog
          key={
            editingReceipt
              ? `edit:${editingReceipt.id}:${editingReceipt.version}`
              : "create-open:empty"
          }
          open
          warehouseId={selectedWarehouseId}
          createdBy={currentUser?.displayName ?? "Текущий пользователь"}
          receipt={editingReceipt}
          onOpenChange={(open) => {
            if (!open) {
              setCreateOpen(false)
              setEditingReceipt(null)
            }
          }}
          onCreated={() => {
            void queryClient.invalidateQueries({
              queryKey: LOGISTICS_QUERY_KEY,
            })
            void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
          }}
        />
      ) : null}
      <AlertDialog
        open={acceptTask !== null}
        onOpenChange={(open) => !open && setAcceptTask(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Принять бытовку без повреждений?
            </AlertDialogTitle>
            <AlertDialogDescription>
              После подтверждения потребуется загрузить фотографии осмотра.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Нет</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (acceptTask?.media.length) {
                  photoMutation.mutate({
                    action: { kind: "ACCEPT", task: acceptTask },
                    uploads: [],
                  })
                } else if (acceptTask)
                  setPhotoAction({ kind: "ACCEPT", task: acceptTask })
                setAcceptTask(null)
              }}
            >
              Да
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {photoAction ? (
        <LogisticsPhotoDialog
          open
          title={
            photoAction.kind === "ACCEPT"
              ? "Фотографии приёмки"
              : "Фотографии перед созданием сметы"
          }
          pending={photoMutation.isPending}
          error={error}
          onOpenChange={(open) => !open && setPhotoAction(null)}
          onSubmit={(uploads) =>
            photoMutation.mutate({ action: photoAction, uploads })
          }
        />
      ) : null}
      {furnitureConflictTask ? (
        <FurnitureConflictDialog
          task={furnitureConflictTask}
          resolvedBy={currentUser?.displayName ?? "Текущий пользователь"}
          actorId={currentUser?.id ?? null}
          accessToken={accessToken}
          serviceWarehouseId={selectedWarehouse?.id ?? null}
          onOpenChange={(open) => {
            if (!open) setFurnitureConflictTask(null)
          }}
          onCreateEstimate={() => {
            const task = furnitureConflictTask
            setFurnitureConflictTask(null)
            void openEstimate(task)
          }}
        />
      ) : null}
    </div>
  )
}

function ReturnItemSummary({
  item,
  selected,
  onAccept,
  onEstimate,
  onFurnitureConflict,
  onEquipmentConflict,
}: {
  item: ReturnReceiptItemViewDto
  selected?: boolean
  onAccept: () => void
  onEstimate: () => void
  onFurnitureConflict: () => void
  onEquipmentConflict: () => void
}) {
  return (
    <div
      data-selected={selected}
      className="flex flex-col gap-2 rounded-lg border p-3 data-[selected=true]:ring-2 data-[selected=true]:ring-ring"
    >
      <strong>{item.cabinNumber}</strong>
      <div className="grid grid-cols-2 gap-2">
        <span className="text-muted-foreground">Этап</span>
        <Badge
          variant={
            item.technicalState === "CONFLICT" ? "destructive" : "secondary"
          }
        >
          {returnStageLabel(item)}
        </Badge>
        <span className="text-muted-foreground">Повреждения</span>
        <FactBadge value={factValue(item)} />
        <span className="text-muted-foreground">Смета</span>
        <FactBadge value={factValue(item)} />
      </div>
      <ReturnItemActions
        item={item}
        onAccept={onAccept}
        onEstimate={onEstimate}
        onFurnitureConflict={onFurnitureConflict}
        onEquipmentConflict={onEquipmentConflict}
      />
    </div>
  )
}

type FactValue = "YES" | "NO" | "UNKNOWN"

function factValue(item: ReturnReceiptItemDto): FactValue {
  if (item.sourceEstimateId || item.pendingEstimateId) return "YES"
  if (item.technicalState === "ACCEPTED") return "NO"
  return "UNKNOWN"
}

function furnitureDelta(item: ReturnReceiptItemDto) {
  const totals = new Map<
    string,
    { name: string; expected: number; actual: number }
  >()
  item.expectedContents.forEach((entry) => {
    const key = entry.name.trim().toLocaleLowerCase("ru")
    if (!key) return
    const current = totals.get(key)
    totals.set(key, {
      name: current?.name ?? entry.name,
      expected: (current?.expected ?? 0) + entry.quantity,
      actual: current?.actual ?? 0,
    })
  })
  item.returnedContents.forEach((entry) => {
    const key = entry.name.trim().toLocaleLowerCase("ru")
    if (!key) return
    const current = totals.get(key)
    totals.set(key, {
      name: current?.name ?? entry.name,
      expected: current?.expected ?? 0,
      actual: (current?.actual ?? 0) + entry.quantity,
    })
  })
  return [...totals.values()]
    .filter((entry) => entry.expected !== entry.actual)
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

function hasFurnitureConflict(item: ReturnReceiptItemDto) {
  return (
    item.contentsMode === "FACTUAL" &&
    item.furnitureDispositions.some((entry) => entry.status === "PENDING")
  )
}

function FactBadge({ value }: { value: FactValue }) {
  if (value === "YES") return <Badge variant="destructive">Да</Badge>
  if (value === "NO") {
    return (
      <Badge
        variant="secondary"
        className="bg-[var(--status-free-bg)] text-[var(--status-free-fg)]"
      >
        Нет
      </Badge>
    )
  }
  return <Badge variant="secondary">Не указано</Badge>
}

function FurnitureConflictDialog({
  task,
  resolvedBy,
  actorId,
  accessToken,
  serviceWarehouseId,
  onOpenChange,
  onCreateEstimate,
}: {
  task: ReturnTaskDto
  resolvedBy: string
  actorId: string | null
  accessToken: string | null
  serviceWarehouseId: string | null
  onOpenChange: (open: boolean) => void
  onCreateEstimate: () => void
}) {
  const queryClient = useQueryClient()
  const [reason, setReason] = useState("")
  const [targetRentalItemId, setTargetRentalItemId] = useState("")
  const [quantities, setQuantities] = useState<Record<string, number>>({})
  const [resolutionError, setResolutionError] = useState<string | null>(null)
  const candidates = useQuery({
    queryKey: [
      ...LOGISTICS_QUERY_KEY,
      "return-candidates",
      task.warehouseId,
      task.fromParty,
      "furniture-conflict",
    ],
    queryFn: () => listReturnCandidates(task.warehouseId, task.fromParty),
  })
  const otherCabins = (candidates.data ?? []).filter(
    (item) => item.id !== task.rentalItemId
  )
  const transferTargets = useQuery({
    queryKey: [
      "rental-items",
      "contents-move-targets",
      task.warehouseId,
      task.rentalItemId,
    ],
    queryFn: () =>
      getRentalItemsForContentsMove({
        warehouseId: task.warehouseId,
        sourceRentalItemId: task.rentalItemId,
      }),
  })
  const delta = furnitureDelta(task)
  const pendingDispositions = task.furnitureDispositions.filter(
    (entry) => entry.status === "PENDING" && entry.remainingQuantity > 0
  )
  const pendingTaskAllocations = task.furnitureDispositions.flatMap(
    (disposition) =>
      disposition.allocations
        .filter((allocation) => allocation.status === "PENDING_TASK")
        .map((allocation) => ({ disposition, allocation }))
  )
  const resolution = useMutation({
    mutationFn: ({
      dispositionId,
      action,
      quantity,
      idempotencyKey,
    }: {
      dispositionId: string
      action:
        "KEEP_IN_CABIN" | "RETURN_TO_STOCK" | "WRITE_OFF" | "TRANSFER_TO_CABIN"
      quantity: number
      idempotencyKey: string
    }) =>
      resolveReturnFurnitureDisposition({
        warehouseId: task.warehouseId,
        serviceWarehouseId,
        accessToken,
        returnItemId: task.id,
        dispositionId,
        expectedVersion: task.version,
        quantity,
        idempotencyKey,
        action,
        targetRentalItemId: targetRentalItemId || null,
        reason,
        actorId,
        resolvedBy,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
      onOpenChange(false)
    },
    onError: (cause) =>
      setResolutionError(
        cause instanceof Error ? cause.message : "Не удалось сохранить решение"
      ),
  })
  const retryTransfer = useMutation({
    mutationFn: ({
      dispositionId,
      allocationId,
    }: {
      dispositionId: string
      allocationId: string
    }) =>
      retryReturnFurnitureTransfer({
        warehouseId: task.warehouseId,
        serviceWarehouseId,
        accessToken,
        returnItemId: task.id,
        dispositionId,
        allocationId,
        actorId,
        resolvedBy,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: LOGISTICS_QUERY_KEY })
      onOpenChange(false)
    },
    onError: (cause) =>
      setResolutionError(
        cause instanceof Error
          ? cause.message
          : "Не удалось повторить задание перемещения"
      ),
  })

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Конфликт мебели — {task.cabinNumber}</DialogTitle>
          <DialogDescription>
            Сверьте фактическое наполнение с данными отгрузки. Ниже показаны все
            другие бытовки этого контрагента, чтобы проверить, куда могла быть
            перемещена мебель до возврата.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <FieldSet>
            <FieldLegend variant="label">
              Расхождения в возвращённой бытовке
            </FieldLegend>
            <div className="flex flex-col gap-2">
              {delta.map((entry) => (
                <div
                  key={entry.name}
                  className="grid grid-cols-[minmax(0,1fr)_auto_auto] gap-3 rounded-lg border px-3 py-2 text-sm"
                >
                  <span className="truncate">{entry.name}</span>
                  <span>По отгрузке: {entry.expected}</span>
                  <span>Фактически: {entry.actual}</span>
                </div>
              ))}
            </div>
          </FieldSet>
          {pendingTaskAllocations.length ? (
            <FieldSet>
              <FieldLegend variant="label">
                Задания, ожидающие повторной отправки
              </FieldLegend>
              <div className="flex flex-col gap-2">
                {pendingTaskAllocations.map(({ disposition, allocation }) => (
                  <div
                    key={allocation.id}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-destructive/30 p-3"
                  >
                    <span className="text-sm">
                      {disposition.name}: {allocation.quantity} шт. ·
                      перемещение в выбранную бытовку не завершено
                    </span>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={retryTransfer.isPending}
                      onClick={() =>
                        retryTransfer.mutate({
                          dispositionId: disposition.id,
                          allocationId: allocation.id,
                        })
                      }
                    >
                      Повторить задание
                    </Button>
                  </div>
                ))}
              </div>
            </FieldSet>
          ) : null}
          {pendingDispositions.length ? (
            <FieldSet>
              <FieldLegend variant="label">
                Распределение старой и лишней мебели
              </FieldLegend>
              <FieldDescription>
                Пока все количества не распределены, создать смету или принять
                бытовку нельзя.
              </FieldDescription>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="return-disposition-target">
                    Бытовка назначения
                  </FieldLabel>
                  <Select
                    value={targetRentalItemId}
                    onValueChange={setTargetRentalItemId}
                  >
                    <SelectTrigger id="return-disposition-target">
                      <SelectValue placeholder="Для переноса в бытовку" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {(transferTargets.data ?? []).map((cabin) => (
                          <SelectItem key={cabin.id} value={cabin.id}>
                            {cabin.number}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel htmlFor="return-disposition-reason">
                    Причина решения
                  </FieldLabel>
                  <Input
                    id="return-disposition-reason"
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder="Обязательно для списания"
                  />
                </Field>
              </FieldGroup>
              <div className="flex flex-col gap-2">
                {pendingDispositions.map((entry) => {
                  const quantity =
                    quantities[entry.id] ?? entry.remainingQuantity
                  const command = (
                    action:
                      | "KEEP_IN_CABIN"
                      | "RETURN_TO_STOCK"
                      | "WRITE_OFF"
                      | "TRANSFER_TO_CABIN"
                  ) => ({
                    dispositionId: entry.id,
                    action,
                    quantity,
                    idempotencyKey: `return-disposition:${entry.id}:${entry.allocations.length}:${action}:${quantity}`,
                  })
                  return (
                    <div
                      key={entry.id}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3"
                    >
                      <span className="text-sm font-medium">
                        {entry.name}: осталось {entry.remainingQuantity} из{" "}
                        {entry.quantity} шт. ·{" "}
                        {entry.origin === "PREVIOUS_SNAPSHOT"
                          ? "старый снимок"
                          : "лишнее фактическое"}
                      </span>
                      <div className="flex flex-wrap gap-2">
                        <Input
                          className="w-20"
                          type="number"
                          min={1}
                          max={entry.remainingQuantity}
                          aria-label={`Количество ${entry.name}`}
                          value={quantity}
                          onChange={(event) =>
                            setQuantities((current) => ({
                              ...current,
                              [entry.id]: Number(event.target.value),
                            }))
                          }
                        />
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            resolution.mutate(command("KEEP_IN_CABIN"))
                          }
                        >
                          Оставить
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            resolution.mutate(command("RETURN_TO_STOCK"))
                          }
                        >
                          На склад
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={!targetRentalItemId}
                          onClick={() =>
                            resolution.mutate(command("TRANSFER_TO_CABIN"))
                          }
                        >
                          В бытовку
                        </Button>
                        <Button
                          size="sm"
                          variant="destructive"
                          disabled={!reason.trim()}
                          onClick={() =>
                            resolution.mutate(command("WRITE_OFF"))
                          }
                        >
                          Списать
                        </Button>
                      </div>
                    </div>
                  )
                })}
              </div>
              {resolutionError ? (
                <FieldError>{resolutionError}</FieldError>
              ) : null}
            </FieldSet>
          ) : null}
          <FieldSet>
            <FieldLegend variant="label">
              Другие бытовки {task.fromParty}
            </FieldLegend>
            {otherCabins.length ? (
              <div className="flex flex-col gap-2">
                {otherCabins.map((cabin) => (
                  <Card key={cabin.id}>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-base">
                        {cabin.number}
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="text-sm text-muted-foreground">
                      {cabin.contentsItems.length
                        ? cabin.contentsItems
                            .map((entry) => `${entry.name}: ${entry.quantity}`)
                            .join(", ")
                        : "Наполнение не указано"}
                    </CardContent>
                  </Card>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                У контрагента нет других бытовок в аренде.
              </p>
            )}
          </FieldSet>
        </FieldGroup>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Закрыть
          </Button>
          <Button
            type="button"
            onClick={onCreateEstimate}
            disabled={pendingDispositions.length > 0}
          >
            Создать смету
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ReturnItemActions({
  item,
  onAccept,
  onEstimate,
  onFurnitureConflict,
  onEquipmentConflict,
}: {
  item: ReturnReceiptItemViewDto
  onAccept: () => void
  onEstimate: () => void
  onFurnitureConflict: () => void
  onEquipmentConflict: () => void
}) {
  const navigate = useNavigate()
  const { selectedWarehouseId } = useWarehouse()
  const estimateExists = Boolean(
    item.sourceEstimateId || item.pendingEstimateId
  )
  const pending = item.currentStatus === "AFTER_RENT"
  const furnitureConflict = hasFurnitureConflict(item)
  const missingFurniture =
    item.contentsMode === "FACTUAL" &&
    furnitureDelta(item).some((entry) => entry.actual < entry.expected)
  const equipmentConflict =
    item.contentsMode !== "FACTUAL" && item.hasUnresolvedEquipmentDisposition
  const actionClassName = "w-52 justify-center"

  if (item.technicalState === "CONFLICT") {
    const conflict = item.conflicts[0]
    const params = new URLSearchParams({
      create: "1",
      destinationWarehouseId: selectedWarehouseId ?? "",
      sourceWarehouseId: conflict?.conflictingWarehouseId ?? "",
      rentalItemId: item.rentalItemId,
      returnConflictId: item.id,
    })
    return (
      <div className="flex flex-col gap-2">
        <p className="text-sm text-destructive">
          {conflict?.message ?? "Требуется решение конфликта"}
        </p>
        <div className="flex flex-wrap gap-2">
          {conflict?.code === "OTHER_WAREHOUSE" ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() => navigate(`/logistics/transfers?${params}`)}
            >
              Перемещение
            </Button>
          ) : null}
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              params.delete("create")
              params.set("correction", "1")
              navigate(`/logistics/transfers?${params}`)
            }}
          >
            Коррекция учёта
          </Button>
        </div>
      </div>
    )
  }

  if (equipmentConflict) {
    return (
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          className={actionClassName}
          onClick={onEquipmentConflict}
        >
          Конфликт оборудования
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-wrap gap-2">
      {furnitureConflict ? (
        <Button
          size="sm"
          variant="outline"
          className={actionClassName}
          onClick={onFurnitureConflict}
        >
          Конфликт мебели
        </Button>
      ) : (
        <Button
          size="sm"
          variant="outline"
          className={actionClassName}
          disabled={!pending || missingFurniture}
          onClick={onAccept}
        >
          {missingFurniture ? "Требуется смета" : "Принять без повреждений"}
        </Button>
      )}
      <Button
        size="sm"
        className={actionClassName}
        disabled={
          item.technicalState === "ACCEPTED" ||
          item.technicalState === "ESTIMATE_IN_PROGRESS" ||
          furnitureConflict ||
          (!pending && !estimateExists)
        }
        onClick={onEstimate}
      >
        {estimateExists ? "Открыть смету" : "Создать смету"}
      </Button>
    </div>
  )
}

function ReturnReceiptCard({
  receipt,
  onAccept,
  onEstimate,
  onFurnitureConflict,
  onEquipmentConflict,
  onEdit,
  selectedReturnItemId,
}: {
  receipt: ReturnReceiptViewDto
  onAccept: (item: ReturnReceiptItemViewDto) => void
  onEstimate: (item: ReturnReceiptItemViewDto) => void
  onFurnitureConflict: (item: ReturnReceiptItemViewDto) => void
  onEquipmentConflict: (item: ReturnReceiptItemViewDto) => void
  onEdit: () => void
  selectedReturnItemId: string | null
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{receipt.fromParty}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        <div className="grid grid-cols-2 gap-2">
          <span className="text-muted-foreground">Дата возврата</span>
          <span>{dateLabel(receipt.returnDate)}</span>
          <span className="text-muted-foreground">Водитель</span>
          <span>{receipt.driverName ?? "Не указан"}</span>
          <span className="text-muted-foreground">Принимающий</span>
          <span>{receipt.receiverName ?? "Не зафиксировано"}</span>
        </div>
        {receipt.items.map((item) => (
          <ReturnItemSummary
            key={item.id}
            item={item}
            selected={item.id === selectedReturnItemId}
            onAccept={() => onAccept(item)}
            onEstimate={() => onEstimate(item)}
            onFurnitureConflict={() => onFurnitureConflict(item)}
            onEquipmentConflict={() => onEquipmentConflict(item)}
          />
        ))}
      </CardContent>
      <CardFooter className="justify-between gap-2 text-xs text-muted-foreground">
        <span>{receipt.items.length} бытовок</span>
        <Button size="sm" variant="outline" onClick={onEdit}>
          <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
          Редактировать
        </Button>
      </CardFooter>
    </Card>
  )
}

function ReturnReceiptDialog({
  open,
  warehouseId,
  createdBy,
  receipt,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  warehouseId: string | null
  createdBy: string
  receipt: ReturnReceiptViewDto | null
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  const { accessToken } = useAuth()
  const dialogContentRef = useRef<HTMLDivElement>(null)
  const [fromParty, setFromParty] = useState(receipt?.fromParty ?? "")
  const [fromPartyInput, setFromPartyInput] = useState(receipt?.fromParty ?? "")
  const [returnDate, setReturnDate] = useState(receipt?.returnDate ?? today())
  const [driverId, setDriverId] = useState(receipt?.driverId ?? "")
  const [cabinRows, setCabinRows] = useState<ReturnCabinDraft[]>(
    receipt?.items.length
      ? receipt.items.map((item) =>
          newReturnCabinDraft(
            item.rentalItemId,
            item.expectedContents,
            item.returnedContents
          )
        )
      : [newReturnCabinDraft()]
  )
  const [error, setError] = useState<string | null>(null)
  const editing = receipt !== null
  const membershipEditable = receipt
    ? isReturnReceiptMembershipEditable(receipt)
    : true

  const companies = useQuery({
    queryKey: [...LOGISTICS_QUERY_KEY, "companies", warehouseId, open],
    queryFn: () => listCompanies(warehouseId!),
    enabled: open && Boolean(warehouseId),
  })
  const allCandidates = useQuery({
    queryKey: [
      ...LOGISTICS_QUERY_KEY,
      "return-candidates",
      warehouseId,
      receipt?.id,
      receipt?.version,
      open,
    ],
    queryFn: () =>
      receipt
        ? listReturnEditCandidates(warehouseId!, receipt.id)
        : listReturnCandidates(warehouseId!),
    enabled: open && Boolean(warehouseId),
  })
  const equipmentCatalog = useQuery({
    queryKey: [...LOGISTICS_QUERY_KEY, "return-equipment", warehouseId, open],
    queryFn: () => listReturnEquipmentCatalog(warehouseId!),
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
  const clientCabins = useMemo(
    () =>
      (allCandidates.data ?? []).filter(
        (item) =>
          receipt?.items.some(
            (receiptItem) =>
              receiptItem.rentalItemId === item.id &&
              receiptItem.selectionSource === "CLIENT_LIST"
          ) ||
          item.tenant?.trim().toLocaleLowerCase("ru") ===
            fromParty.trim().toLocaleLowerCase("ru")
      ),
    [allCandidates.data, fromParty, receipt]
  )
  const clientCabinIds = useMemo(
    () => new Set(clientCabins.map((item) => item.id)),
    [clientCabins]
  )
  const selectedRentalItemIds = cabinRows
    .map((row) => row.rentalItemId)
    .filter(Boolean)
  const clientRentalItemIds = selectedRentalItemIds.filter((rentalItemId) =>
    clientCabinIds.has(rentalItemId)
  )
  const manualRentalItemIds = selectedRentalItemIds.filter(
    (rentalItemId) => !clientCabinIds.has(rentalItemId)
  )
  const selectedDriver = drivers.find((driver) => driver.id === driverId)
  const contentsByRentalItemId = Object.fromEntries(
    cabinRows
      .filter((row) => row.rentalItemId)
      .map((row) => [
        row.rentalItemId,
        row.returnedContents.map((item) => ({ ...item })),
      ])
  )
  const mutation = useMutation({
    mutationFn: () =>
      receipt
        ? updateReturnReceipt({
            warehouseId: warehouseId!,
            accessToken,
            receiptId: receipt.id,
            expectedVersion: receipt.version,
            clientRentalItemIds,
            manualRentalItemIds,
            fromParty,
            driverId: selectedDriver?.id ?? null,
            driverName: selectedDriver?.name ?? null,
            returnDate,
            contentsByRentalItemId,
            updatedBy: createdBy,
          })
        : createReturnReceipt({
            warehouseId: warehouseId!,
            accessToken,
            clientRentalItemIds,
            manualRentalItemIds,
            fromParty,
            driverId: selectedDriver?.id ?? null,
            driverName: selectedDriver?.name ?? null,
            returnDate,
            contentsByRentalItemId,
            createdBy,
          }),
    onSuccess: () => {
      onCreated()
      onOpenChange(false)
      setFromParty("")
      setFromPartyInput("")
      setDriverId("")
      setCabinRows([newReturnCabinDraft()])
      setError(null)
    },
    onError: (cause) =>
      setError(
        cause instanceof Error
          ? cause.message
          : editing
            ? "Не удалось сохранить возврат"
            : "Не удалось создать возврат"
      ),
  })
  function applyCompany(value: string) {
    const nextValue = value.trim()
    setFromParty(nextValue)
    setFromPartyInput(nextValue)
    if (!editing) {
      setCabinRows([newReturnCabinDraft()])
    }
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        ref={dialogContentRef}
        className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl"
      >
        <DialogHeader>
          <DialogTitle>
            {editing ? "Редактировать возврат" : "Добавить возврат из аренды"}
          </DialogTitle>
          <DialogDescription>
            {editing
              ? "Исправьте отправителя, дату или состав ещё не обработанного возврата."
              : "Выберите клиента, дату, водителя, бытовки и фактическое наполнение."}
          </DialogDescription>
        </DialogHeader>
        <FieldGroup className="grid gap-4 sm:grid-cols-2">
          <Field>
            <FieldLabel htmlFor="return-party">От кого</FieldLabel>
            <Combobox
              items={companies.data ?? []}
              value={fromParty || null}
              inputValue={fromPartyInput}
              onInputValueChange={(value, eventDetails) => {
                if (eventDetails.reason === "input-change") {
                  setFromPartyInput(value)
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
                id="return-party"
                placeholder="Начните вводить компанию"
                showClear
                onKeyDownCapture={(event) => {
                  if (event.key !== "Enter") return
                  if (event.currentTarget.getAttribute("aria-activedescendant"))
                    return
                  applyCompany(fromPartyInput)
                }}
              />
              <ComboboxContent portalContainer={dialogContentRef}>
                <ComboboxList>
                  <ComboboxEmpty>Компании не найдены</ComboboxEmpty>
                  <ComboboxGroup>
                    {(companies.data ?? []).map((company) => (
                      <ComboboxItem key={company} value={company}>
                        {company}
                      </ComboboxItem>
                    ))}
                  </ComboboxGroup>
                </ComboboxList>
              </ComboboxContent>
            </Combobox>
          </Field>
          <Field>
            <FieldLabel htmlFor="return-date">Дата возврата</FieldLabel>
            <Input
              id="return-date"
              type="date"
              value={returnDate}
              onChange={(event) => setReturnDate(event.target.value)}
            />
          </Field>
          <Field className="sm:col-span-2">
            <FieldLabel htmlFor="return-driver">Водитель</FieldLabel>
            <Select value={driverId} onValueChange={setDriverId}>
              <SelectTrigger id="return-driver">
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
          <FieldSet className="sm:col-span-2" disabled={!membershipEditable}>
            <FieldLegend variant="label">Бытовки и наполнение</FieldLegend>
            <FieldDescription>
              {membershipEditable
                ? "Выберите бытовки, которые фактически вернулись из аренды."
                : "Состав нельзя менять после начала осмотра или создания сметы."}
            </FieldDescription>
            <FieldGroup>
              {cabinRows.map((row, index) => {
                const selectedCandidate = (allCandidates.data ?? []).find(
                  (item) => item.id === row.rentalItemId
                )
                const availableCandidates = (allCandidates.data ?? []).filter(
                  (item) =>
                    !selectedRentalItemIds.includes(item.id) ||
                    item.id === row.rentalItemId
                )
                const clientCandidates = availableCandidates.filter((item) =>
                  clientCabinIds.has(item.id)
                )
                const otherCandidates = availableCandidates.filter(
                  (item) => !clientCabinIds.has(item.id)
                )

                return (
                  <Card key={row.slotId}>
                    <CardHeader>
                      <CardTitle>Бытовка {index + 1}</CardTitle>
                    </CardHeader>
                    <CardContent className="flex flex-col gap-4">
                      <Field>
                        <FieldLabel htmlFor={`return-cabin-${row.slotId}`}>
                          Номер бытовки
                        </FieldLabel>
                        <div className="flex gap-2">
                          <Combobox
                            items={(allCandidates.data ?? []).map(
                              (item) => item.number
                            )}
                            disabled={!membershipEditable || !fromParty}
                            value={selectedCandidate?.number ?? null}
                            onValueChange={(value) =>
                              setCabinRows((current) =>
                                current.map((entry) =>
                                  entry.slotId === row.slotId
                                    ? {
                                        ...entry,
                                        rentalItemId:
                                          (allCandidates.data ?? []).find(
                                            (item) => item.number === value
                                          )?.id ?? "",
                                        expectedContents: (
                                          (allCandidates.data ?? []).find(
                                            (item) => item.number === value
                                          )?.contentsItems ?? []
                                        ).map((item) => ({ ...item })),
                                        returnedContents: (
                                          (allCandidates.data ?? []).find(
                                            (item) => item.number === value
                                          )?.contentsItems ?? []
                                        ).map((item) => ({ ...item })),
                                      }
                                    : entry
                                )
                              )
                            }
                          >
                            <ComboboxInput
                              id={`return-cabin-${row.slotId}`}
                              className="flex-1"
                              placeholder={
                                fromParty
                                  ? "Введите номер бытовки"
                                  : "Сначала выберите клиента"
                              }
                            />
                            <ComboboxContent portalContainer={dialogContentRef}>
                              <ComboboxList>
                                <ComboboxEmpty>
                                  Бытовки не найдены
                                </ComboboxEmpty>
                                {clientCandidates.length ? (
                                  <ComboboxGroup>
                                    <ComboboxLabel>
                                      Бытовки клиента
                                    </ComboboxLabel>
                                    {clientCandidates.map((item) => (
                                      <ComboboxItem
                                        key={item.id}
                                        value={item.number}
                                      >
                                        <span>{item.number}</span>
                                        <Badge variant="secondary">
                                          Клиент
                                        </Badge>
                                      </ComboboxItem>
                                    ))}
                                  </ComboboxGroup>
                                ) : null}
                                {otherCandidates.length ? (
                                  <ComboboxGroup>
                                    <ComboboxLabel>
                                      Другие арендованные
                                    </ComboboxLabel>
                                    {otherCandidates.map((item) => (
                                      <ComboboxItem
                                        key={item.id}
                                        value={item.number}
                                      >
                                        {item.number}
                                      </ComboboxItem>
                                    ))}
                                  </ComboboxGroup>
                                ) : null}
                              </ComboboxList>
                            </ComboboxContent>
                          </Combobox>
                          {cabinRows.length > 1 ? (
                            <Button
                              type="button"
                              size="icon"
                              variant="outline"
                              disabled={!membershipEditable}
                              aria-label={`Удалить бытовку ${index + 1}`}
                              onClick={() =>
                                setCabinRows((current) =>
                                  current.filter(
                                    (entry) => entry.slotId !== row.slotId
                                  )
                                )
                              }
                            >
                              <HugeiconsIcon icon={Delete02Icon} />
                            </Button>
                          ) : null}
                        </div>
                      </Field>
                      {row.rentalItemId ? (
                        <LogisticsCabinContentsEditor
                          contents={row.returnedContents}
                          catalog={equipmentCatalog.data ?? []}
                          frozen={!membershipEditable}
                          mode="return"
                          onUpdate={(updater) =>
                            setCabinRows((current) =>
                              current.map((entry) =>
                                entry.slotId === row.slotId
                                  ? {
                                      ...entry,
                                      returnedContents: updater(
                                        entry.returnedContents
                                      ),
                                    }
                                  : entry
                              )
                            )
                          }
                        />
                      ) : null}
                    </CardContent>
                  </Card>
                )
              })}
              <Button
                type="button"
                variant="outline"
                disabled={
                  !membershipEditable ||
                  !fromParty ||
                  (allCandidates.data ?? []).length === 0 ||
                  cabinRows.some((row) => !row.rentalItemId)
                }
                onClick={() =>
                  setCabinRows((current) => [...current, newReturnCabinDraft()])
                }
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Добавить ещё бытовку
              </Button>
            </FieldGroup>
          </FieldSet>
          {error ? <FieldError>{error}</FieldError> : null}
        </FieldGroup>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button
            disabled={
              !fromParty ||
              !returnDate ||
              !driverId ||
              selectedRentalItemIds.length === 0 ||
              cabinRows.some((row) => !row.rentalItemId) ||
              mutation.isPending
            }
            onClick={() => mutation.mutate()}
          >
            {editing ? "Сохранить" : "Создать возврат"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
