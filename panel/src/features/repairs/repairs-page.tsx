import { useMemo, useState } from "react"
import { useLocation, useNavigate, useSearchParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  FilterIcon,
  Queue01Icon,
  Table01Icon,
} from "@hugeicons/core-free-icons"

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
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { canInitiatePropertyDisposition } from "@/features/write-offs/property-disposition-presentation"
import {
  getRepairTask,
  listRepairTasks,
  repairTaskDetailQueryKey,
  repairTasksListQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { canEditRepairTaskPlan } from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RentalItemRepairSeed,
  RepairTaskDto,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"
import { RepairTaskDetailWorkspace } from "@/features/repair-tasks/repair-task-detail-workspace"
import { RepairTaskEditorWorkspace } from "@/features/repair-tasks/repair-task-editor-workspace"
import { RepairsQueueView } from "@/features/repairs/repairs-queue-view"
import { RepairsTableFilters } from "@/features/repairs/repairs-table-filters"
import {
  buildRepairsTableFilterDefinitions,
  createEmptyRepairsTableFilters,
  filterRepairsTable,
  formatRepairCalendarDate,
  getOperationalRepairSubtask,
  getRepairDate,
  getRepairOperationalStatusLabel,
  getRepairPriority,
  getRepairStageLabel,
  getRepairTaskLabel,
  getRepairTypeLabel,
} from "@/features/repairs/repairs-table-model"
import {
  listMaintenanceRepairs,
  type MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  getTaskBoardsForAvailableDates,
  moveTaskBoardEntry,
  pinTaskBoardEntry,
  TASK_BOARD_QUERY_KEY,
} from "@/features/task-board/api/task-board-api"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useIsMobile } from "@/hooks/use-mobile"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"
import { ApiError } from "@/lib/api-client"

const EMPTY_REPAIRS: RepairTaskDto[] = []

function complexityBadgeStyle(complexity: MaintenanceRepair["complexity"]) {
  const color = complexity.color
  const red = Number.parseInt(color.slice(1, 3), 16)
  const green = Number.parseInt(color.slice(3, 5), 16)
  const blue = Number.parseInt(color.slice(5, 7), 16)
  const foreground =
    (red * 299 + green * 587 + blue * 114) / 1000 >= 150 ? "#111827" : "#FFFFFF"
  return { backgroundColor: color, borderColor: color, color: foreground }
}

function RepairComplexityBadge({
  complexity,
}: {
  complexity: MaintenanceRepair["complexity"]
}) {
  return (
    <Badge variant="outline" style={complexityBadgeStyle(complexity)}>
      {complexity.name}
    </Badge>
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

function RepairMobileCard({
  repair,
  complexity,
  onOpen,
}: {
  repair: RepairTaskDto
  complexity: MaintenanceRepair["complexity"]
  onOpen: () => void
}) {
  const subtask = getOperationalRepairSubtask(repair)
  const inProgress = subtask?.status === "IN_PROGRESS"
  return (
    <Card className={inProgress ? "border-primary bg-primary/5" : undefined}>
      <CardHeader>
        <CardTitle>
          <Button
            type="button"
            variant="link"
            size="sm"
            aria-label={`Открыть ремонт бытовки ${repair.cabinNumber}`}
            onClick={onOpen}
          >
            {repair.cabinNumber}
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2">
        <span className="text-muted-foreground">Ремонт</span>
        <span>{getRepairTypeLabel(repair)}</span>
        <span className="text-muted-foreground">Задание</span>
        <span>{getRepairTaskLabel(repair)}</span>
        <span className="text-muted-foreground">Дата</span>
        <span>
          {subtask?.scheduledDate
            ? formatRepairCalendarDate(subtask.scheduledDate)
            : repair.dispatchDate
              ? formatRepairCalendarDate(repair.dispatchDate)
              : "—"}
        </span>
        <span className="text-muted-foreground">Приоритет</span>
        <span>{subtask?.priority ?? repair.priority ?? 3}</span>
        <span className="text-muted-foreground">Статус</span>
        <Badge variant={inProgress ? "default" : "outline"}>
          {getRepairOperationalStatusLabel(repair)}
        </Badge>
        <span className="text-muted-foreground">Этап и тип</span>
        <span className="flex flex-wrap gap-1">
          <Badge variant="outline">{getRepairStageLabel(repair)}</Badge>
          <RepairComplexityBadge complexity={complexity} />
        </span>
      </CardContent>
    </Card>
  )
}

export function RepairsPage() {
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const [tableSearch, setTableSearch] = useState("")
  const [tableFilters, setTableFilters] = useState(
    createEmptyRepairsTableFilters
  )
  const { filtersOpen: tableFiltersOpen, setFiltersOpen: setTableFiltersOpen } =
    useResponsiveFiltersOpen()
  const [mobileCreateDialogOpen, setMobileCreateDialogOpen] = useState(false)
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
  const repairId = searchParams.get("repairId")
  const editRequested = searchParams.get("edit") === "1"
  const createRequested = searchParams.get("create") === "1"
  const mobileCreateBlocked =
    isMobile && (createRequested || mobileCreateDialogOpen)
  const view = searchParams.get("view") === "queue" ? "queue" : "table"
  const workspaceOpen = createRequested || Boolean(repairId)
  const locationState = location.state as RepairsLocationState | null
  const reworkSeed = createRequested ? locationState?.reworkSeed : undefined
  const rentalItemSeed = createRequested
    ? resolveRentalItemRepairSeed(
        locationState?.rentalItemSeed,
        selectedWarehouseId
      )
    : undefined

  const listSearchParams = new URLSearchParams(searchParams)
  listSearchParams.delete("repairId")
  listSearchParams.delete("edit")
  listSearchParams.delete("create")
  const listSearch = listSearchParams.toString()
  const repairsListHref = listSearch ? `/repairs?${listSearch}` : "/repairs"
  const goBack = useWorkspaceBack(repairsListHref)
  const listQuery = useQuery({
    queryKey: repairTasksListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listRepairTasks(selectedWarehouseId!),
    enabled: selectedWarehouseId !== null,
  })
  const boardsQuery = useQuery({
    queryKey: [
      ...TASK_BOARD_QUERY_KEY,
      "all-dates",
      selectedWarehouseId ?? "none",
    ],
    queryFn: () =>
      getTaskBoardsForAvailableDates(accessToken!, selectedWarehouseId!),
    enabled: Boolean(
      accessToken && selectedWarehouseId && !workspaceOpen && view === "queue"
    ),
  })
  const detailQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(selectedWarehouseId ?? "none", repairId),
    queryFn: () => getRepairTask(repairId!, selectedWarehouseId!),
    enabled: Boolean(repairId && selectedWarehouseId),
  })
  const complexityQuery = useQuery({
    queryKey: [
      "maintenance",
      "repairs",
      selectedWarehouseId ?? "none",
      "complexity",
    ],
    queryFn: () => listMaintenanceRepairs(accessToken!, selectedWarehouseId!),
    enabled: Boolean(
      accessToken && selectedWarehouseId && !workspaceOpen && view === "table"
    ),
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

  const repairs = listQuery.data ?? EMPTY_REPAIRS
  const complexityByRepairId = useMemo(
    () =>
      new Map(
        (complexityQuery.data?.items ?? []).map((repair) => [
          repair.id,
          repair.complexity,
        ])
      ),
    [complexityQuery.data]
  )
  const boards = boardsQuery.data ?? []
  const selectedRepair = detailQuery.data ?? null
  const selectedRepairPlanEditable = Boolean(
    selectedRepair && canEditRepairTaskPlan(selectedRepair)
  )
  const showRepairEditor = Boolean(
    selectedRepair &&
    (selectedRepair.status === "DRAFT" ||
      (editRequested && canEdit && selectedRepairPlanEditable))
  )
  const tableFilterDefinitions = useMemo(
    () => buildRepairsTableFilterDefinitions(repairs),
    [repairs]
  )
  const filteredTableRepairs = useMemo(
    () => filterRepairsTable(repairs, tableSearch, tableFilters),
    [repairs, tableFilters, tableSearch]
  )

  async function refreshRepairBoard() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY }),
      selectedWarehouseId
        ? queryClient.invalidateQueries({
            queryKey: repairTasksListQueryKey(selectedWarehouseId),
          })
        : Promise.resolve(),
    ])
  }

  const boardMutation = useMutation({
    mutationFn: (
      command:
        | {
            kind: "move"
            entry: TaskBoardEntryDto
            queue: TaskBoardQueueDto
            targetIndex: number
            targetDate: string
          }
        | { kind: "pin"; entry: TaskBoardEntryDto; pinned: boolean }
    ) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return command.kind === "move"
        ? moveTaskBoardEntry({ accessToken, ...command })
        : pinTaskBoardEntry({ accessToken, ...command })
    },
    onSuccess: refreshRepairBoard,
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        await refreshRepairBoard()
      }
    },
  })

  function closeWorkspace() {
    navigate(repairsListHref, { replace: true })
  }

  function handleMobileCreateDialogOpenChange(open: boolean) {
    setMobileCreateDialogOpen(open)
    if (!open && createRequested) {
      closeWorkspace()
    }
  }

  function requestRepairCreation() {
    if (isMobile) {
      setMobileCreateDialogOpen(true)
      return
    }

    const next = new URLSearchParams(searchParams)
    next.delete("repairId")
    next.set("create", "1")
    navigate(`/repairs?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  function handleSaved() {
    closeWorkspace()
  }

  function openRepair(repairId: string) {
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.delete("edit")
    next.set("repairId", repairId)
    navigate(`/repairs?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  function editRepair(repairId: string) {
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.set("repairId", repairId)
    next.set("edit", "1")
    navigate(`/repairs?${next.toString()}`, { replace: true })
  }

  const workspaceBackToolbar = (
    <PageToolbar>
      <Button type="button" variant="outline" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
    </PageToolbar>
  )

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      {mobileCreateBlocked ? (
        <MobileAppRequiredDialog
          open
          onOpenChange={handleMobileCreateDialogOpenChange}
          operation="Создание ремонта"
        />
      ) : (
        <>
          {workspaceOpen && !selectedWarehouseId ? workspaceBackToolbar : null}

          {workspaceOpen && selectedWarehouseId ? (
            createRequested && !repairId && !canEdit ? (
              <>
                {workspaceBackToolbar}
                <Card>
                  <CardHeader>
                    <CardTitle>Создание ремонта недоступно</CardTitle>
                    <CardDescription role="alert">
                      Для создания ремонта нужен доступ EDIT к выбранному
                      складу.
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
                reworkSourceQuery.data.rentalItemId !==
                  reworkSeed.rentalItemId ||
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
                    canEdit && selectedRepairPlanEditable
                      ? () => editRepair(selectedRepair.id)
                      : undefined
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
                  repairId || reworkSeed
                    ? undefined
                    : rentalItemSeed?.rentalItemId
                }
                loading={Boolean(
                  (repairId && detailQuery.isLoading) ||
                  (reworkSeed && reworkSourceQuery.isLoading)
                )}
                onBack={goBack}
                onClose={closeWorkspace}
                onSaved={handleSaved}
              />
            )
          ) : (
            <>
              <PageToolbar>
                {view === "table" ? (
                  <PageToolbarContent className="max-w-xl">
                    <Input
                      value={tableSearch}
                      name="repairs-search"
                      autoComplete="off"
                      aria-label="Поиск по ремонтам"
                      placeholder="Поиск по номеру бытовки…"
                      onChange={(event) => setTableSearch(event.target.value)}
                    />
                  </PageToolbarContent>
                ) : null}
                <PageToolbarActions className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto">
                  <div className="order-2 col-span-2 sm:order-1 sm:col-auto">
                    <ToggleGroup
                      type="single"
                      variant="outline"
                      value={view}
                      aria-label="Вид ремонтов"
                      onValueChange={(nextView) => {
                        if (nextView !== "table" && nextView !== "queue") return
                        const next = new URLSearchParams(searchParams)
                        if (nextView === "queue") next.set("view", "queue")
                        else next.delete("view")
                        navigate(
                          next.size > 0
                            ? `/repairs?${next.toString()}`
                            : "/repairs",
                          { replace: true }
                        )
                      }}
                    >
                      <ToggleGroupItem value="table" className="h-9">
                        <HugeiconsIcon
                          icon={Table01Icon}
                          data-icon="inline-start"
                        />
                        Таблица
                      </ToggleGroupItem>
                      <ToggleGroupItem value="queue" className="h-9">
                        <HugeiconsIcon
                          icon={Queue01Icon}
                          data-icon="inline-start"
                        />
                        Очереди
                      </ToggleGroupItem>
                    </ToggleGroup>
                  </div>
                  <div className="order-1 col-span-2 flex w-full items-center gap-2 sm:order-2 sm:col-auto sm:w-auto">
                    {view === "table" && repairs.length > 0 ? (
                      <Button
                        type="button"
                        size="icon"
                        variant={tableFiltersOpen ? "secondary" : "outline"}
                        aria-label={
                          tableFiltersOpen
                            ? "Скрыть фильтры ремонтов"
                            : "Показать фильтры ремонтов"
                        }
                        aria-controls="repairs-table-filters"
                        aria-expanded={tableFiltersOpen}
                        onClick={() =>
                          setTableFiltersOpen((current) => !current)
                        }
                      >
                        <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
                      </Button>
                    ) : null}
                    {canEdit ? (
                      <Button
                        type="button"
                        className="ml-auto sm:ml-0"
                        disabled={!selectedWarehouseId}
                        onClick={requestRepairCreation}
                      >
                        <HugeiconsIcon
                          icon={Add01Icon}
                          data-icon="inline-start"
                        />
                        Создать задание
                      </Button>
                    ) : null}
                  </div>
                </PageToolbarActions>
              </PageToolbar>

              {view === "table" && repairs.length > 0 ? (
                <div id="repairs-table-filters" hidden={!tableFiltersOpen}>
                  <RepairsTableFilters
                    definitions={tableFilterDefinitions}
                    filters={tableFilters}
                    onChange={setTableFilters}
                  />
                </div>
              ) : null}

              {view === "queue" &&
              boardMutation.isError &&
              !(
                boardMutation.error instanceof ApiError &&
                boardMutation.error.status === 409
              ) ? (
                <p role="alert" className="text-xs text-destructive">
                  {boardMutation.error instanceof Error
                    ? boardMutation.error.message
                    : "Не удалось обновить очередь ремонтов."}
                </p>
              ) : null}

              <div className="min-h-0 flex-1 overflow-hidden">
                {listQuery.isLoading ||
                (view === "table" && complexityQuery.isLoading) ? (
                  <p className="text-xs text-muted-foreground">
                    Загрузка ремонтов...
                  </p>
                ) : listQuery.isError ||
                  (view === "table" && complexityQuery.isError) ? (
                  <p role="alert" className="text-xs text-destructive">
                    Не удалось загрузить ремонты.
                  </p>
                ) : view === "queue" && boardsQuery.isLoading ? (
                  <p className="text-xs text-muted-foreground">
                    Загрузка очередей ремонтов...
                  </p>
                ) : view === "queue" && boardsQuery.isError ? (
                  <p role="alert" className="text-xs text-destructive">
                    Не удалось загрузить очереди ремонтов.
                  </p>
                ) : view === "queue" ? (
                  <RepairsQueueView
                    boards={boards}
                    repairs={repairs}
                    disabled={!canEdit || boardMutation.isPending}
                    onMove={(params) =>
                      boardMutation.mutate({ kind: "move", ...params })
                    }
                    onPin={(entry, pinned) =>
                      boardMutation.mutate({ kind: "pin", entry, pinned })
                    }
                    onOpen={openRepair}
                  />
                ) : (
                  <div
                    className="h-full w-full overflow-y-auto"
                    data-testid="repairs-table-workspace"
                  >
                    {filteredTableRepairs.length > 0 ? (
                      <>
                        <div className="hidden min-h-full w-full md:block">
                          <OperationsListGrid
                            className="min-h-full w-full"
                            items={filteredTableRepairs}
                            getRowClassName={(repair) => {
                              const status =
                                getOperationalRepairSubtask(repair)?.status
                              if (status === "IN_PROGRESS") {
                                return "bg-primary/5 hover:bg-primary/10"
                              }
                              if (status === "PAUSED") return "bg-muted/60"
                              if (status === "DONE" || status === "CANCELLED") {
                                return "opacity-65"
                              }
                              return undefined
                            }}
                            columns={[
                              {
                                id: "cabinNumber",
                                label: "Номер бытовки",
                                className: "w-52",
                                getSortValue: (repair) => repair.cabinNumber,
                                render: (repair) => (
                                  <Button
                                    type="button"
                                    variant="link"
                                    size="sm"
                                    aria-label={`Открыть ремонт бытовки ${repair.cabinNumber}`}
                                    onClick={() => openRepair(repair.id)}
                                  >
                                    {repair.cabinNumber}
                                  </Button>
                                ),
                              },
                              {
                                id: "kind",
                                label: "Ремонт",
                                className: "w-52",
                                getSortValue: getRepairTypeLabel,
                                render: getRepairTypeLabel,
                              },
                              {
                                id: "task",
                                label: "Задание",
                                className: "min-w-72",
                                getSortValue: getRepairTaskLabel,
                                render: (repair) => (
                                  <span
                                    className={
                                      getOperationalRepairSubtask(repair)
                                        ?.status === "IN_PROGRESS"
                                        ? "rounded-md bg-primary/10 px-2 py-1 font-medium"
                                        : undefined
                                    }
                                  >
                                    {getRepairTaskLabel(repair)}
                                  </span>
                                ),
                              },
                              {
                                id: "date",
                                label: "Дата",
                                className: "w-40",
                                getSortValue: getRepairDate,
                                render: (repair) => {
                                  const date = getRepairDate(repair)
                                  return date
                                    ? formatRepairCalendarDate(date)
                                    : "—"
                                },
                              },
                              {
                                id: "priority",
                                label: "Приоритет",
                                className: "w-32",
                                getSortValue: getRepairPriority,
                                render: (repair) => {
                                  const priority = getRepairPriority(repair)
                                  return (
                                    <Badge
                                      variant={
                                        priority <= 2 ? "default" : "secondary"
                                      }
                                    >
                                      {priority}
                                    </Badge>
                                  )
                                },
                              },
                              {
                                id: "stage-and-complexity",
                                label: "Этап и тип",
                                className: "w-64",
                                getSortValue: (repair) =>
                                  complexityByRepairId.get(repair.id)?.name ??
                                  "",
                                render: (repair) => {
                                  const complexity = complexityByRepairId.get(
                                    repair.id
                                  )
                                  return complexity ? (
                                    <span className="flex flex-wrap gap-1">
                                      <Badge variant="outline">
                                        {getRepairStageLabel(repair)}
                                      </Badge>
                                      <RepairComplexityBadge
                                        complexity={complexity}
                                      />
                                    </span>
                                  ) : null
                                },
                              },
                              {
                                id: "status",
                                label: "Статус",
                                className: "w-48",
                                getSortValue: getRepairOperationalStatusLabel,
                                render: (repair) => {
                                  const inProgress =
                                    getOperationalRepairSubtask(repair)
                                      ?.status === "IN_PROGRESS"
                                  return (
                                    <Badge
                                      variant={
                                        inProgress ? "default" : "outline"
                                      }
                                    >
                                      {getRepairOperationalStatusLabel(repair)}
                                    </Badge>
                                  )
                                },
                              },
                            ]}
                          />
                        </div>

                        <div className="grid w-full gap-3 md:hidden">
                          {filteredTableRepairs.map((repair) =>
                            complexityByRepairId.has(repair.id) ? (
                              <RepairMobileCard
                                key={repair.id}
                                repair={repair}
                                complexity={complexityByRepairId.get(
                                  repair.id
                                )!}
                                onOpen={() => openRepair(repair.id)}
                              />
                            ) : null
                          )}
                        </div>
                      </>
                    ) : (
                      <div
                        role="status"
                        className="flex min-h-full w-full items-center justify-center p-6 text-center text-sm text-muted-foreground"
                      >
                        Ремонты по заданным условиям не найдены.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </>
          )}
        </>
      )}
    </div>
  )
}
