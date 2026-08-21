import { useCallback, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Add01Icon, ArrowLeft01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Navigate,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom"

import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { canEditRepairTaskPlan } from "@/features/repair-tasks/domain/repair-task-domain"
import {
  listMaintenanceRepairs,
  type MaintenanceRepair,
  type MaintenanceRepairStage,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import type {
  RentalItemRepairSeed,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"
import { RepairTaskDetailWorkspace } from "@/features/repair-tasks/repair-task-detail-workspace"
import { RepairTaskEditorWorkspace } from "@/features/repair-tasks/repair-task-editor-workspace"
import { canInitiatePropertyDisposition } from "@/features/write-offs/property-disposition-presentation"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useWorkspaceBack } from "@/hooks/use-workspace-back"

const REPAIRS_PAGE_SIZE = 50
const CABIN_LOOKUP_CONCURRENCY = 8

/** One maintenance-owned repair paired with the asset-owned display number. */
type RepairsOverviewRow = {
  id: string
  repair: MaintenanceRepair
  cabinNumber: string
}

const repairStatusLabels: Record<MaintenanceRepair["executionState"], string> =
  {
    DRAFT: "Черновик",
    QUEUED: "В очереди",
    IN_PROGRESS: "В работе",
    COMPLETED: "Завершён",
    CANCELLED: "Отменён",
  }

const repairOriginLabels: Record<MaintenanceRepair["origin"], string> = {
  ESTIMATE: "Смета",
  DIRECT_REPAIR: "Прямой ремонт",
  INVENTORY: "Инвентаризация",
}

function complexityBadgeStyle(complexity: MaintenanceRepair["complexity"]) {
  const red = Number.parseInt(complexity.color.slice(1, 3), 16)
  const green = Number.parseInt(complexity.color.slice(3, 5), 16)
  const blue = Number.parseInt(complexity.color.slice(5, 7), 16)
  const foreground =
    (red * 299 + green * 587 + blue * 114) / 1000 >= 150 ? "#111827" : "#FFFFFF"
  return {
    backgroundColor: complexity.color,
    borderColor: complexity.color,
    color: foreground,
  }
}

function unfinishedStage(repair: MaintenanceRepair) {
  return repair.plan.stages
    .slice()
    .sort((left, right) => left.order - right.order)
    .find((stage) => stage.state !== "DONE" && stage.state !== "CANCELLED")
}

function repairStageLabel(repair: MaintenanceRepair) {
  if (
    repair.executionState === "QUEUED" &&
    repair.movementToRepair &&
    repair.plan.stages.every(
      (stage) => stage.taskSync.taskBoardRegistrationVersion === null
    )
  ) {
    return "Перемещение на ремонт"
  }
  return (
    unfinishedStage(repair)?.routing.queueName ??
    (repair.executionState === "COMPLETED" ? "Ремонт завершён" : "Без этапа")
  )
}

function repairWorkLabel(stage: MaintenanceRepairStage | undefined) {
  if (!stage) return "—"
  const work = stage.workLines
    .map((line) => line.description.trim())
    .filter(Boolean)
  return work.length > 0 ? work.join(", ") : stage.groupComment.trim() || "—"
}

/** Loads one bounded repairs page and resolves cabin numbers in small parallel batches. */
async function loadRepairsOverview(
  accessToken: string,
  warehouseId: string,
  page: number
) {
  const response = await listMaintenanceRepairs(accessToken, warehouseId, {
    page,
    size: REPAIRS_PAGE_SIZE,
  })
  const rentalItemIds = Array.from(
    new Set(response.items.map((repair) => repair.rentalItemId))
  )
  const cabinNumberById = new Map<string, string>()
  for (
    let offset = 0;
    offset < rentalItemIds.length;
    offset += CABIN_LOOKUP_CONCURRENCY
  ) {
    const batch = rentalItemIds.slice(offset, offset + CABIN_LOOKUP_CONCURRENCY)
    const cabins = await Promise.all(
      batch.map((rentalItemId) => getAssetRentalItem(accessToken, rentalItemId))
    )
    cabins.forEach((cabin) => {
      if (cabin.warehouseId !== warehouseId) {
        throw new Error("Сервис имущества вернул бытовку другого склада.")
      }
      cabinNumberById.set(cabin.id, cabin.number)
    })
  }
  return {
    ...response,
    items: response.items.map<RepairsOverviewRow>((repair) => {
      const cabinNumber = cabinNumberById.get(repair.rentalItemId)
      if (!cabinNumber) {
        throw new Error("Не удалось определить номер бытовки для ремонта.")
      }
      return { id: repair.id, repair, cabinNumber }
    }),
  }
}

function RepairsOverview() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState("")
  const [mobileCreateOpen, setMobileCreateOpen] = useState(false)
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )

  const listQuery = useQuery({
    queryKey: [
      "maintenance",
      "repairs",
      selectedWarehouseId ?? "none",
      "overview",
      page,
    ],
    queryFn: () =>
      loadRepairsOverview(accessToken!, selectedWarehouseId!, page),
    enabled: Boolean(accessToken && selectedWarehouseId),
  })
  const normalizedSearch = search.trim().toLocaleLowerCase("ru")
  const rows = useMemo(
    () =>
      (listQuery.data?.items ?? []).filter((row) => {
        if (!normalizedSearch) return true
        const stage = unfinishedStage(row.repair)
        return [
          row.cabinNumber,
          row.repair.complexity.name,
          repairStatusLabels[row.repair.executionState],
          repairOriginLabels[row.repair.origin],
          repairStageLabel(row.repair),
          repairWorkLabel(stage),
          row.repair.sourceParty ?? "",
        ]
          .join(" ")
          .toLocaleLowerCase("ru")
          .includes(normalizedSearch)
      }),
    [listQuery.data, normalizedSearch]
  )
  const totalElements = listQuery.data?.totalElements ?? 0
  const totalPages = Math.max(1, Math.ceil(totalElements / REPAIRS_PAGE_SIZE))

  function openRepair(repairId: string) {
    navigate(`/repairs?repairId=${encodeURIComponent(repairId)}`, {
      state: { workspaceEntry: true },
    })
  }

  function requestCreation() {
    if (isMobile) {
      setMobileCreateOpen(true)
      return
    }
    navigate("/repairs?create=1", { state: { workspaceEntry: true } })
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            value={search}
            name="repairs-search"
            autoComplete="off"
            aria-label="Поиск по ремонтам"
            placeholder="Бытовка, этап, работа или тип ремонта"
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions className="w-full sm:w-auto">
          <Badge variant="secondary" className="h-9 px-3">
            Ремонтов: {totalElements}
          </Badge>
          {canEdit ? (
            <Button
              type="button"
              className="w-full sm:w-auto"
              onClick={requestCreation}
            >
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать задание
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

      {!accessToken ? (
        <p role="alert" className="text-xs text-destructive">
          Не получен токен доступа к ремонтам.
        </p>
      ) : !selectedWarehouseId ? (
        <p role="status" className="text-xs text-muted-foreground">
          Выберите склад.
        </p>
      ) : listQuery.isLoading ? (
        <p role="status" className="text-xs text-muted-foreground">
          Загрузка ремонтов...
        </p>
      ) : listQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          {listQuery.error instanceof Error
            ? listQuery.error.message
            : "Не удалось загрузить ремонты."}
        </p>
      ) : rows.length === 0 ? (
        <div
          role="status"
          className="flex min-h-0 flex-1 items-center justify-center rounded-lg border p-6 text-sm text-muted-foreground"
        >
          Ремонты по заданным условиям не найдены.
        </div>
      ) : isMobile ? (
        <div className="grid min-h-0 flex-1 gap-3 overflow-y-auto">
          {rows.map((row) => {
            const stage = unfinishedStage(row.repair)
            return (
              <Card key={row.id}>
                <CardHeader>
                  <CardTitle>
                    <Button
                      type="button"
                      variant="link"
                      size="sm"
                      aria-label={`Открыть ремонт бытовки ${row.cabinNumber}`}
                      onClick={() => openRepair(row.id)}
                    >
                      {row.cabinNumber}
                    </Button>
                  </CardTitle>
                </CardHeader>
                <CardContent className="grid grid-cols-2 gap-2 text-sm">
                  <span className="text-muted-foreground">Тип</span>
                  <Badge
                    variant="outline"
                    style={complexityBadgeStyle(row.repair.complexity)}
                  >
                    {row.repair.complexity.name}
                  </Badge>
                  <span className="text-muted-foreground">Этап</span>
                  <span>{repairStageLabel(row.repair)}</span>
                  <span className="text-muted-foreground">Работа</span>
                  <span>{repairWorkLabel(stage)}</span>
                  <span className="text-muted-foreground">Приоритет</span>
                  <span>{row.repair.priority}</span>
                  <span className="text-muted-foreground">Статус</span>
                  <Badge variant="outline">
                    {repairStatusLabels[row.repair.executionState]}
                  </Badge>
                </CardContent>
              </Card>
            )
          })}
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            columns={[
              {
                id: "cabinNumber",
                label: "Номер бытовки",
                className: "w-52",
                getSortValue: (row) => row.cabinNumber,
                render: (row) => (
                  <Button
                    type="button"
                    variant="link"
                    size="sm"
                    aria-label={`Открыть ремонт бытовки ${row.cabinNumber}`}
                    onClick={() => openRepair(row.id)}
                  >
                    {row.cabinNumber}
                  </Button>
                ),
              },
              {
                id: "complexity",
                label: "Тип ремонта",
                className: "w-52",
                getSortValue: (row) => row.repair.complexity.name,
                render: (row) => (
                  <Badge
                    variant="outline"
                    style={complexityBadgeStyle(row.repair.complexity)}
                  >
                    {row.repair.complexity.name}
                  </Badge>
                ),
              },
              {
                id: "stage",
                label: "Текущий этап",
                className: "w-56",
                getSortValue: (row) => repairStageLabel(row.repair),
                render: (row) => repairStageLabel(row.repair),
              },
              {
                id: "work",
                label: "Работа",
                className: "min-w-72",
                getSortValue: (row) =>
                  repairWorkLabel(unfinishedStage(row.repair)),
                render: (row) => repairWorkLabel(unfinishedStage(row.repair)),
              },
              {
                id: "priority",
                label: "Приоритет",
                className: "w-32",
                getSortValue: (row) => row.repair.priority,
                render: (row) => (
                  <Badge
                    variant={row.repair.priority <= 2 ? "default" : "secondary"}
                  >
                    {row.repair.priority}
                  </Badge>
                ),
              },
              {
                id: "status",
                label: "Статус",
                className: "w-40",
                getSortValue: (row) =>
                  repairStatusLabels[row.repair.executionState],
                render: (row) => (
                  <Badge variant="outline">
                    {repairStatusLabels[row.repair.executionState]}
                  </Badge>
                ),
              },
              {
                id: "origin",
                label: "Источник",
                className: "w-44",
                getSortValue: (row) => repairOriginLabels[row.repair.origin],
                render: (row) => repairOriginLabels[row.repair.origin],
              },
            ]}
          />
        </div>
      )}

      {listQuery.isSuccess && totalPages > 1 ? (
        <div className="flex items-center justify-end gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={page === 0 || listQuery.isFetching}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            Назад
          </Button>
          <Badge variant="secondary">
            Страница {page + 1} из {totalPages}
          </Badge>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={page + 1 >= totalPages || listQuery.isFetching}
            onClick={() => setPage((current) => current + 1)}
          >
            Далее
          </Button>
        </div>
      ) : null}

      <MobileAppRequiredDialog
        open={mobileCreateOpen}
        onOpenChange={setMobileCreateOpen}
        operation="Создание ремонта"
      />
    </div>
  )
}

function resolveRentalItemRepairSeed(
  value: unknown,
  selectedWarehouseId: string | null
): RentalItemRepairSeed | undefined {
  if (
    !selectedWarehouseId ||
    !value ||
    typeof value !== "object" ||
    !("type" in value) ||
    value.type !== "rental-item-repair-seed-v1" ||
    !("warehouseId" in value) ||
    value.warehouseId !== selectedWarehouseId ||
    !("rentalItemId" in value) ||
    typeof value.rentalItemId !== "string" ||
    !value.rentalItemId ||
    !("number" in value) ||
    typeof value.number !== "string" ||
    !value.number
  ) {
    return undefined
  }

  return value as RentalItemRepairSeed
}

function RepairsWorkspace({
  repairId,
  createRequested,
  editRequested,
}: {
  repairId: string | null
  createRequested: boolean
  editRequested: boolean
}) {
  const isMobile = useIsMobile()
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const canManage = Boolean(
    selectedWarehouseId &&
    canInitiatePropertyDisposition(currentUser, selectedWarehouseId)
  )
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const workspaceBack = useWorkspaceBack("/repairs")
  const locationState = location.state as RepairsLocationState | null
  const goBack = useCallback(() => {
    if (locationState?.workspaceEntry === true) {
      navigate(-1)
      return
    }
    workspaceBack()
  }, [locationState?.workspaceEntry, navigate, workspaceBack])
  const reworkSeed = createRequested ? locationState?.reworkSeed : undefined
  const rentalItemSeed = createRequested
    ? resolveRentalItemRepairSeed(
        locationState?.rentalItemSeed,
        selectedWarehouseId
      )
    : undefined

  const detailQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(selectedWarehouseId ?? "none", repairId),
    queryFn: () => getRepairTask(repairId!, selectedWarehouseId!),
    enabled: Boolean(repairId && selectedWarehouseId),
  })
  const reworkSourceQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(
      selectedWarehouseId ?? "none",
      reworkSeed?.sourceRepairTaskId ?? null
    ),
    queryFn: () =>
      getRepairTask(reworkSeed!.sourceRepairTaskId, selectedWarehouseId!),
    enabled: Boolean(reworkSeed && selectedWarehouseId),
  })

  const selectedRepair = detailQuery.data ?? null
  const selectedRepairPlanEditable = Boolean(
    selectedRepair && canEditRepairTaskPlan(selectedRepair)
  )
  const showRepairEditor = Boolean(
    selectedRepair &&
    (selectedRepair.status === "DRAFT" ||
      (editRequested && canEdit && selectedRepairPlanEditable))
  )

  function editRepair() {
    if (!selectedRepair) return
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.set("repairId", selectedRepair.id)
    next.set("edit", "1")
    navigate(`/repairs?${next.toString()}`, {
      replace: true,
      state: location.state,
    })
  }

  const workspaceBackToolbar = (
    <PageToolbar>
      <Button type="button" variant="outline" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
    </PageToolbar>
  )

  if (isMobile && createRequested) {
    return (
      <MobileAppRequiredDialog
        open
        onOpenChange={(open) => {
          if (!open) goBack()
        }}
        operation="Создание ремонта"
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      {!selectedWarehouseId ? workspaceBackToolbar : null}

      {selectedWarehouseId ? (
        createRequested && !canEdit ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Создание ремонта недоступно</CardTitle>
                <CardDescription role="alert">
                  Для создания ремонта нужен доступ EDIT к выбранному складу.
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : repairId && !detailQuery.isLoading && !selectedRepair ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Ремонт недоступен</CardTitle>
                <CardDescription role="alert">
                  {detailQuery.isError
                    ? "Не удалось загрузить ремонт."
                    : "Ремонт не найден на выбранном складе."}
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : reworkSeed &&
          !reworkSourceQuery.isLoading &&
          (!reworkSourceQuery.data ||
            reworkSeed.warehouseId !== selectedWarehouseId ||
            reworkSourceQuery.data.version !==
              reworkSeed.sourceRepairTaskVersion ||
            reworkSourceQuery.data.rentalItemId !== reworkSeed.rentalItemId ||
            reworkSourceQuery.data.status !== "COMPLETED" ||
            reworkSourceQuery.data.acceptanceStatus !== "PENDING") ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Доработка недоступна</CardTitle>
                <CardDescription role="alert">
                  Исходное задание изменилось или больше не ожидает приёмки.
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : selectedRepair && !showRepairEditor ? (
          <>
            {workspaceBackToolbar}
            <RepairTaskDetailWorkspace
              accessToken={accessToken}
              task={selectedRepair}
              readOnly={!canEdit}
              onEdit={
                canEdit && selectedRepairPlanEditable ? editRepair : undefined
              }
            />
          </>
        ) : (
          <RepairTaskEditorWorkspace
            accessToken={accessToken}
            warehouseId={selectedWarehouseId}
            task={selectedRepair}
            readOnly={!canEdit}
            canManage={canManage}
            sourceTask={reworkSourceQuery.data}
            seed={reworkSourceQuery.data ? reworkSeed : undefined}
            initialRentalItemId={
              repairId || reworkSeed ? undefined : rentalItemSeed?.rentalItemId
            }
            loading={Boolean(
              (repairId && detailQuery.isLoading) ||
              (reworkSeed && reworkSourceQuery.isLoading)
            )}
            onBack={goBack}
            onClose={goBack}
            onSaved={goBack}
          />
        )
      ) : null}
    </div>
  )
}

export function RepairsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const [searchParams] = useSearchParams()
  const repairId = searchParams.get("repairId")?.trim() || null
  const createRequested = !repairId && searchParams.get("create") === "1"

  if (!repairId && !createRequested && searchParams.get("view") === "queue") {
    return <Navigate to="/repairs" replace />
  }

  if (!repairId && !createRequested) {
    return <RepairsOverview key={selectedWarehouseId ?? "none"} />
  }

  return (
    <RepairsWorkspace
      repairId={repairId}
      createRequested={createRequested}
      editRequested={Boolean(repairId && searchParams.get("edit") === "1")}
    />
  )
}
