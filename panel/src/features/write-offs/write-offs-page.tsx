import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { useNavigate, useSearchParams } from "react-router-dom"
import { ArrowLeft01Icon, FilterIcon } from "@hugeicons/core-free-icons"
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
import {
  formatAcceptanceDateTime,
  repairTaskOriginLabel,
} from "@/features/acceptance/acceptance-formatters"
import { AcceptanceStatusBadge } from "@/features/acceptance/acceptance-presentation"
import { RepairAcceptanceDossier } from "@/features/acceptance/repair-acceptance-dossier"
import { useAuth } from "@/features/auth/use-auth"
import { LogisticsDocumentFilters } from "@/features/logistics/logistics-document-filters"
import {
  getRepairTask,
  listRepairWriteOffs,
  repairTaskDetailQueryKey,
  repairWriteOffsListQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { listDossierActorDisplays } from "@/features/rental-items/dossier/actor/actor-display-api"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"
import { cn } from "@/lib/utils"

import {
  buildWriteOffFilterOptions,
  EMPTY_WRITE_OFF_LIST_FILTERS,
  filterWriteOffTasks,
  formatWriteOffAuthor,
  writeOffActorIds,
  type WriteOffListFilters,
} from "./write-off-list-filters"

const EMPTY_WRITE_OFF_TASKS: RepairTaskDto[] = []

function WriteOffMobileCard({
  task,
  authorName,
  onOpen,
}: {
  task: RepairTaskDto
  authorName: string
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
            aria-label={`Открыть списание бытовки ${task.cabinNumber}`}
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
        <span className="text-muted-foreground">Автор</span>
        <span>{authorName}</span>
        <span className="text-muted-foreground">Дата списания</span>
        <span>{formatAcceptanceDateTime(task.writtenOffAt ?? null)}</span>
        <span className="text-muted-foreground">Статус</span>
        <span>
          <AcceptanceStatusBadge status={task.acceptanceStatus} />
        </span>
      </CardContent>
    </Card>
  )
}

export function WriteOffsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState<WriteOffListFilters>(
    EMPTY_WRITE_OFF_LIST_FILTERS
  )
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const writeOffId = searchParams.get("writeOffId")
  const listSearchParams = new URLSearchParams(searchParams)
  listSearchParams.delete("writeOffId")
  const listSearch = listSearchParams.toString()
  const listHref = listSearch ? `/write-offs?${listSearch}` : "/write-offs"
  const goBack = useWorkspaceBack(listHref)

  const listQuery = useQuery({
    queryKey: repairWriteOffsListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listRepairWriteOffs(selectedWarehouseId!),
    enabled: selectedWarehouseId !== null,
  })
  const detailQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(
      selectedWarehouseId ?? "none",
      writeOffId
    ),
    queryFn: () => getRepairTask(writeOffId!, selectedWarehouseId!),
    enabled: Boolean(writeOffId && selectedWarehouseId),
  })

  const tasks = listQuery.data ?? EMPTY_WRITE_OFF_TASKS
  const selectedTask = detailQuery.data ?? null
  const authorIds = useMemo(
    () =>
      [
        ...new Set([
          ...writeOffActorIds(tasks),
          ...(selectedTask?.actorId ? [selectedTask.actorId] : []),
          ...(selectedTask?.decisionActorId
            ? [selectedTask.decisionActorId]
            : []),
        ]),
      ].sort(),
    [selectedTask?.actorId, selectedTask?.decisionActorId, tasks]
  )
  const actorDisplaysQuery = useQuery({
    queryKey: ["write-offs", "actor-displays", authorIds],
    queryFn: () => listDossierActorDisplays(accessToken!, authorIds),
    enabled: Boolean(accessToken && authorIds.length > 0),
  })
  const actorsById = useMemo(
    () =>
      new Map<string, DossierActorDisplay>(
        (actorDisplaysQuery.data ?? []).map((actor) => [actor.subjectId, actor])
      ),
    [actorDisplaysQuery.data]
  )
  const filterOptions = useMemo(
    () => buildWriteOffFilterOptions(tasks, actorsById),
    [actorsById, tasks]
  )
  const visibleTasks = useMemo(
    () => filterWriteOffTasks(tasks, search, filters, actorsById),
    [actorsById, filters, search, tasks]
  )
  const selectedTaskIsWrittenOff =
    selectedTask?.acceptanceStatus === "WRITTEN_OFF"

  function openTask(taskId: string) {
    const next = new URLSearchParams(searchParams)
    next.set("writeOffId", taskId)
    navigate(`/write-offs?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  if (writeOffId) {
    return (
      <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
        <header className="flex flex-wrap items-center gap-3">
          <Button type="button" variant="outline" onClick={goBack}>
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
        </header>

        {detailQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">
            Загрузка досье списания...
          </p>
        ) : detailQuery.isError || !selectedTaskIsWrittenOff ? (
          <Card>
            <CardHeader>
              <CardTitle>Списание недоступно</CardTitle>
              <CardDescription role="alert">
                {detailQuery.isError
                  ? "Не удалось загрузить досье списания."
                  : "Списанная бытовка не найдена на выбранном складе."}
              </CardDescription>
            </CardHeader>
          </Card>
        ) : selectedTask ? (
          <RepairAcceptanceDossier
            accessToken={accessToken}
            task={selectedTask}
            mode="WRITE_OFF"
            canEdit={false}
            canManage={false}
            actorName={formatWriteOffAuthor(selectedTask.actorId, actorsById)}
            decisionActorName={formatWriteOffAuthor(
              selectedTask.decisionActorId,
              actorsById
            )}
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
            aria-label="Поиск списаний"
            name="write-offs-search"
            autoComplete="off"
            placeholder="Номер бытовки, источник или автор"
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <Button
            type="button"
            size="icon"
            variant={filtersOpen ? "secondary" : "outline"}
            aria-label={
              filtersOpen
                ? "Скрыть фильтры списаний"
                : "Показать фильтры списаний"
            }
            aria-controls="write-off-filters"
            aria-expanded={filtersOpen}
            onClick={() => setFiltersOpen((current) => !current)}
          >
            <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      <div id="write-off-filters" hidden={!filtersOpen}>
        <LogisticsDocumentFilters
          filters={filters}
          stateOptions={[{ value: "WRITTEN_OFF", label: "Списана" }]}
          dateLabel="Дата списания"
          showSchedule={false}
          extraFilters={[
            {
              label: "Источник",
              options: filterOptions.sources,
              selected: filters.sources,
              onApply: (sources) =>
                setFilters((current) => ({ ...current, sources })),
            },
            {
              label: "Автор",
              options: filterOptions.authors,
              selected: filters.authors,
              onApply: (authors) =>
                setFilters((current) => ({ ...current, authors })),
            },
          ]}
          onChange={(nextFilters) =>
            setFilters((current) => ({ ...current, ...nextFilters }))
          }
          onReset={() => setFilters(EMPTY_WRITE_OFF_LIST_FILTERS)}
        />
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {listQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">Загрузка списаний...</p>
        ) : listQuery.isError ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось загрузить список списанных бытовок.
          </p>
        ) : (
          <>
            <div
              className={cn(
                "min-h-0 min-w-0 flex-1",
                visibleTasks.length > 0 && "hidden md:block"
              )}
            >
              <OperationsListGrid
                className="min-h-full"
                items={visibleTasks}
                columns={[
                  {
                    id: "cabinNumber",
                    label: "Номер бытовки",
                    className: "w-48",
                    getSortValue: (task) => task.cabinNumber,
                    render: (task) => (
                      <Button
                        type="button"
                        variant="link"
                        size="sm"
                        aria-label={`Открыть списание бытовки ${task.cabinNumber}`}
                        onClick={() => openTask(task.id)}
                      >
                        {task.cabinNumber}
                      </Button>
                    ),
                  },
                  {
                    id: "origin",
                    label: "Источник",
                    className: "w-44",
                    getSortValue: (task) =>
                      repairTaskOriginLabel(task.origin, task.kind),
                    render: (task) =>
                      repairTaskOriginLabel(task.origin, task.kind),
                  },
                  {
                    id: "author",
                    label: "Автор",
                    className: "w-44",
                    getSortValue: (task) =>
                      formatWriteOffAuthor(task.decisionActorId, actorsById),
                    render: (task) =>
                      formatWriteOffAuthor(task.decisionActorId, actorsById),
                  },
                  {
                    id: "decidedAt",
                    label: "Дата списания",
                    className: "w-48",
                    getSortValue: (task) =>
                      task.writtenOffAt
                        ? new Date(task.writtenOffAt).getTime()
                        : null,
                    render: (task) =>
                      formatAcceptanceDateTime(task.writtenOffAt ?? null),
                  },
                  {
                    id: "status",
                    label: "Статус",
                    className: "w-40",
                    getSortValue: (task) => task.acceptanceStatus,
                    render: (task) => (
                      <AcceptanceStatusBadge status={task.acceptanceStatus} />
                    ),
                  },
                ]}
              />
            </div>

            {visibleTasks.length > 0 ? (
              <div className="grid gap-3 md:hidden">
                {visibleTasks.map((task) => (
                  <WriteOffMobileCard
                    key={task.id}
                    task={task}
                    authorName={formatWriteOffAuthor(
                      task.decisionActorId,
                      actorsById
                    )}
                    onOpen={() => openTask(task.id)}
                  />
                ))}
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  )
}
