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
  formatAcceptanceDateTime,
  repairTaskOriginLabel,
} from "@/features/acceptance/acceptance-formatters"
import { AcceptanceStatusBadge } from "@/features/acceptance/acceptance-presentation"
import { RepairAcceptanceDossier } from "@/features/acceptance/repair-acceptance-dossier"
import {
  REPAIR_TASKS_QUERY_KEY,
  getRepairTask,
  listRepairWriteOffs,
  repairTaskDetailQueryKey,
  repairWriteOffsListQueryKey,
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

function WriteOffMobileCard({
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
        <span className="text-muted-foreground">Причина списания</span>
        <span>{task.acceptanceComment || "—"}</span>
        <span className="text-muted-foreground">Автор</span>
        <span>{task.acceptanceDecidedBy || "—"}</span>
        <span className="text-muted-foreground">Дата списания</span>
        <span>{formatAcceptanceDateTime(task.acceptanceDecidedAt)}</span>
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
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
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
          <RepairAcceptanceDossier task={selectedTask} mode="WRITE_OFF" />
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {listQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">Загрузка списаний...</p>
        ) : listQuery.isError ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось загрузить список списанных бытовок.
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
                    id: "reason",
                    label: "Причина списания",
                    className: "w-56",
                    getSortValue: (task) => task.acceptanceComment,
                    render: (task) => task.acceptanceComment || "—",
                  },
                  {
                    id: "author",
                    label: "Автор",
                    className: "w-44",
                    getSortValue: (task) => task.acceptanceDecidedBy,
                    render: (task) => task.acceptanceDecidedBy || "—",
                  },
                  {
                    id: "decidedAt",
                    label: "Дата списания",
                    className: "w-48",
                    getSortValue: (task) =>
                      task.acceptanceDecidedAt
                        ? new Date(task.acceptanceDecidedAt).getTime()
                        : null,
                    render: (task) =>
                      formatAcceptanceDateTime(task.acceptanceDecidedAt),
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

            {tasks.length > 0 ? (
              <div className="grid gap-3 md:hidden">
                {tasks.map((task) => (
                  <WriteOffMobileCard
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
