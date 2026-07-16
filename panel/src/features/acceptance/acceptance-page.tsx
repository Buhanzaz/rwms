import { useEffect } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useNavigate, useSearchParams } from "react-router-dom"
import { ArrowLeft01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { OperationsListGrid } from "@/components/operations-list-grid"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  REPAIR_TASKS_QUERY_KEY,
  getRepairTask,
  listPendingRepairAcceptance,
  repairAcceptanceListQueryKey,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import {
  REPAIR_TASKS_MOCK_STORAGE_KEY,
  REPAIR_TASKS_UPDATED_EVENT,
} from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"

import {
  formatAcceptanceDateTime,
  repairTaskOriginLabel,
} from "./acceptance-formatters"
import { AcceptanceStatusBadge } from "./acceptance-presentation"
import { RepairAcceptanceDossier } from "./repair-acceptance-dossier"

function AcceptanceMobileCard({
  task,
  onOpen,
}: {
  task: RepairTaskDto
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
        <span className="text-muted-foreground">Причина</span>
        <span>{task.reason || "—"}</span>
        <span className="text-muted-foreground">Автор</span>
        <span>{task.authorName || "—"}</span>
        <span className="text-muted-foreground">Начато</span>
        <span>{formatAcceptanceDateTime(task.startedAt)}</span>
        <span className="text-muted-foreground">Завершено</span>
        <span>{formatAcceptanceDateTime(task.completedAt)}</span>
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
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
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

  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    }
    const handleStorage = (event: StorageEvent) => {
      if (event.key === REPAIR_TASKS_MOCK_STORAGE_KEY) {
        invalidate()
      }
    }
    window.addEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", handleStorage)
    return () => {
      window.removeEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", handleStorage)
    }
  }, [queryClient])

  const tasks = listQuery.data ?? []
  const selectedTask = detailQuery.data ?? null
  const selectedTaskIsPending =
    selectedTask?.status === "COMPLETED" &&
    selectedTask.acceptanceStatus === "PENDING"

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

        {detailQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">
            Загрузка досье приёмки...
          </p>
        ) : detailQuery.isError || !selectedTaskIsPending ? (
          <Card>
            <CardHeader>
              <CardTitle>Приёмка недоступна</CardTitle>
              <CardDescription role="alert">
                {detailQuery.isError
                  ? "Не удалось загрузить досье приёмки."
                  : "Завершённое задание не найдено на выбранном складе."}
              </CardDescription>
            </CardHeader>
          </Card>
        ) : selectedTask ? (
          <RepairAcceptanceDossier
            task={selectedTask}
            mode="ACCEPTANCE"
            onDecision={() => navigate(listHref, { replace: true })}
          />
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {listQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">Загрузка приёмки...</p>
        ) : listQuery.isError ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось загрузить очередь приёмки.
          </p>
        ) : (
          <>
            <div className="hidden min-h-full min-w-0 flex-1 md:block">
              <OperationsListGrid
                className="min-h-full"
                items={tasks}
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
                    id: "reason",
                    label: "Причина",
                    className: "w-48",
                    getSortValue: (task) => task.reason,
                    render: (task) => task.reason || "—",
                  },
                  {
                    id: "authorName",
                    label: "Автор",
                    className: "w-44",
                    getSortValue: (task) => task.authorName,
                    render: (task) => task.authorName || "—",
                  },
                  {
                    id: "startedAt",
                    label: "Начато",
                    className: "w-44",
                    getSortValue: (task) =>
                      task.startedAt
                        ? new Date(task.startedAt).getTime()
                        : null,
                    render: (task) => formatAcceptanceDateTime(task.startedAt),
                  },
                  {
                    id: "completedAt",
                    label: "Завершено",
                    className: "w-44",
                    getSortValue: (task) =>
                      task.completedAt
                        ? new Date(task.completedAt).getTime()
                        : null,
                    render: (task) =>
                      formatAcceptanceDateTime(task.completedAt),
                  },
                  {
                    id: "status",
                    label: "Статус",
                    className: "w-44",
                    getSortValue: (task) => task.acceptanceStatus,
                    render: (task) => (
                      <AcceptanceStatusBadge status={task.acceptanceStatus} />
                    ),
                  },
                ]}
              />
            </div>

            {tasks.length > 0 ? (
              <div className="grid gap-3 md:hidden">
                {tasks.map((task) => (
                  <AcceptanceMobileCard
                    key={task.id}
                    task={task}
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
