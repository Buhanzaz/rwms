import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { useNavigate, useSearchParams } from "react-router-dom"
import { ArrowLeft01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
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
  listPendingRepairAcceptance,
  repairAcceptanceListQueryKey,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import {
  formatDossierActorDisplay,
  type DossierActorDisplay,
} from "@/features/rental-items/dossier/actor/actor-display"
import { useDossierActorDisplays } from "@/features/rental-items/dossier/actor/use-dossier-actor-displays"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"

import {
  formatAcceptanceDateTime,
  repairTaskOriginLabel,
} from "./acceptance-formatters"
import {
  buildAcceptanceFilterOptions,
  createEmptyAcceptanceFilters,
  filterAcceptanceTasks,
} from "./acceptance-filtering"
import {
  AcceptanceFilters,
  AcceptanceFiltersToggle,
} from "./acceptance-filters"
import { AcceptanceStatusBadge } from "./acceptance-presentation"
import { RepairAcceptanceDossier } from "./repair-acceptance-dossier"

const EMPTY_REPAIR_TASKS: RepairTaskDto[] = []

function acceptanceActorLabel(
  actorId: string | null | undefined,
  actorDisplays: ReadonlyMap<string, DossierActorDisplay>
) {
  if (!actorId) return "Автор не указан"
  const display = actorDisplays.get(actorId)
  return display ? formatDossierActorDisplay(display) : "Автор недоступен"
}

function AcceptanceMobileCard({
  task,
  actorName,
  onOpen,
}: {
  task: RepairTaskDto
  actorName: string
  onOpen: () => void
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <Button
            type="button"
            variant="link"
            size="sm"
            aria-label={`Открыть приёмку бытовки ${task.cabinNumber}`}
            onClick={onOpen}
          >
            {task.cabinNumber}
          </Button>
        </CardTitle>
        <CardDescription>
          {repairTaskOriginLabel(task.origin, task.kind)}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2">
        <span className="text-muted-foreground">От кого</span>
        <span>{task.sourceParty || "—"}</span>
        <span className="text-muted-foreground">Автор</span>
        <span>{actorName}</span>
        <span className="text-muted-foreground">Готово к приёмке</span>
        <span>{formatAcceptanceDateTime(task.readyAt ?? null)}</span>
        <span className="text-muted-foreground">Статус</span>
        <span>
          <AcceptanceStatusBadge status={task.acceptanceStatus} />
        </span>
      </CardContent>
    </Card>
  )
}

export function AcceptancePage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const canManage = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "MANAGE")
  )
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(createEmptyAcceptanceFilters)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const acceptanceId = searchParams.get("acceptanceId")
  const listSearchParams = new URLSearchParams(searchParams)
  listSearchParams.delete("acceptanceId")
  const listSearch = listSearchParams.toString()
  const listHref = listSearch ? `/acceptance?${listSearch}` : "/acceptance"
  const goBack = useWorkspaceBack(listHref)

  const listQuery = useQuery({
    queryKey: repairAcceptanceListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listPendingRepairAcceptance(selectedWarehouseId!),
    enabled: selectedWarehouseId !== null,
  })
  const detailQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(
      selectedWarehouseId ?? "none",
      acceptanceId
    ),
    queryFn: () => getRepairTask(acceptanceId!, selectedWarehouseId!),
    enabled: Boolean(acceptanceId && selectedWarehouseId),
  })

  const tasks = listQuery.data ?? EMPTY_REPAIR_TASKS
  const selectedTask = detailQuery.data ?? null
  const actorDisplays = useDossierActorDisplays([
    ...tasks.map((task) => task.actorId),
    ...(selectedTask?.actorId ? [selectedTask.actorId] : []),
    ...(selectedTask?.decisionActorId ? [selectedTask.decisionActorId] : []),
  ])
  const actorLabel = (actorId: string | null | undefined) =>
    acceptanceActorLabel(actorId, actorDisplays)
  const filterOptions = buildAcceptanceFilterOptions(tasks, actorLabel)
  const visibleTasks = filterAcceptanceTasks(tasks, search, filters, actorLabel)
  const selectedTaskIsPending =
    selectedTask?.status === "COMPLETED" &&
    selectedTask.acceptanceStatus === "PENDING"
  const selectedTaskIsActionable =
    selectedTaskIsPending && tasks.some((task) => task.id === selectedTask?.id)
  const selectedTaskLoading = detailQuery.isLoading || listQuery.isLoading
  const selectedTaskIsUnavailable =
    detailQuery.isError || listQuery.isError || !selectedTaskIsActionable

  function openTask(taskId: string) {
    const next = new URLSearchParams(searchParams)
    next.set("acceptanceId", taskId)
    navigate(`/acceptance?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  if (acceptanceId) {
    return (
      <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
        <header className="flex flex-wrap items-center gap-3">
          <Button type="button" variant="outline" onClick={goBack}>
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
        </header>

        {selectedTaskLoading ? (
          <p className="text-xs text-muted-foreground">
            Загрузка досье приёмки...
          </p>
        ) : selectedTaskIsUnavailable ? (
          <Card>
            <CardHeader>
              <CardTitle>Приёмка недоступна</CardTitle>
              <CardDescription role="alert">
                {detailQuery.isError
                  ? "Не удалось загрузить досье приёмки."
                  : listQuery.isError
                    ? "Не удалось проверить доступность приёмки."
                    : "Задание больше не доступно для приёмки."}
              </CardDescription>
            </CardHeader>
          </Card>
        ) : selectedTask ? (
          <RepairAcceptanceDossier
            accessToken={accessToken}
            task={selectedTask}
            mode="ACCEPTANCE"
            canEdit={canEdit}
            canManage={canManage}
            actorName={actorLabel(selectedTask.actorId)}
            decisionActorName={actorLabel(selectedTask.decisionActorId)}
            onDecision={() => navigate(listHref, { replace: true })}
          />
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            type="search"
            value={search}
            aria-label="Поиск приёмок"
            name="acceptance-search"
            autoComplete="off"
            placeholder="Бытовка, источник, от кого или автор"
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <AcceptanceFiltersToggle
            open={filtersOpen}
            controls="acceptance-filters"
            onOpenChange={setFiltersOpen}
          />
        </PageToolbarActions>
      </PageToolbar>
      <div id="acceptance-filters" hidden={!filtersOpen}>
        <AcceptanceFilters
          filters={filters}
          options={filterOptions}
          onChange={setFilters}
        />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {listQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">Загрузка приёмки...</p>
        ) : listQuery.isError ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось загрузить очередь приёмки.
          </p>
        ) : (
          <>
            {visibleTasks.length > 0 ? (
              <>
                <div className="hidden min-h-full min-w-0 flex-1 md:block">
                  <OperationsListGrid
                    className="min-h-full"
                    items={visibleTasks}
                    columns={[
                      {
                        id: "cabinNumber",
                        label: "Номер бытовки",
                        className: "w-44",
                        getSortValue: (task) => task.cabinNumber,
                        render: (task) => (
                          <Button
                            type="button"
                            variant="link"
                            size="sm"
                            aria-label={`Открыть приёмку бытовки ${task.cabinNumber}`}
                            onClick={() => openTask(task.id)}
                          >
                            {task.cabinNumber}
                          </Button>
                        ),
                      },
                      {
                        id: "origin",
                        label: "Источник",
                        className: "w-40",
                        getSortValue: (task) =>
                          repairTaskOriginLabel(task.origin, task.kind),
                        render: (task) =>
                          repairTaskOriginLabel(task.origin, task.kind),
                      },
                      {
                        id: "sourceParty",
                        label: "От кого",
                        className: "w-48",
                        getSortValue: (task) => task.sourceParty,
                        render: (task) => task.sourceParty || "—",
                      },
                      {
                        id: "actorId",
                        label: "Автор",
                        className: "w-44",
                        getSortValue: (task) => actorLabel(task.actorId),
                        render: (task) => actorLabel(task.actorId),
                      },
                      {
                        id: "readyAt",
                        label: "Готово к приёмке",
                        className: "w-44",
                        getSortValue: (task) =>
                          task.readyAt
                            ? new Date(task.readyAt).getTime()
                            : null,
                        render: (task) =>
                          formatAcceptanceDateTime(task.readyAt ?? null),
                      },
                      {
                        id: "status",
                        label: "Статус",
                        className: "w-44",
                        getSortValue: (task) => task.acceptanceStatus,
                        render: (task) => (
                          <AcceptanceStatusBadge
                            status={task.acceptanceStatus}
                          />
                        ),
                      },
                    ]}
                  />
                </div>

                <div className="grid gap-3 md:hidden">
                  {visibleTasks.map((task) => (
                    <AcceptanceMobileCard
                      key={task.id}
                      task={task}
                      actorName={actorLabel(task.actorId)}
                      onOpen={() => openTask(task.id)}
                    />
                  ))}
                </div>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                {tasks.length > 0
                  ? "По выбранным фильтрам заданий нет."
                  : "Заданий, ожидающих приёмки, нет."}
              </p>
            )}
          </>
        )}
      </div>
    </div>
  )
}
