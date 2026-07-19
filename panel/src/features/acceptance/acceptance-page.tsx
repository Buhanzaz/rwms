import { useQuery } from "@tanstack/react-query"
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
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRepairTask,
  listPendingRepairAcceptance,
  repairAcceptanceListQueryKey,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
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
        <span className="text-muted-foreground">От кого</span>
        <span>{task.sourceParty || "—"}</span>
        <span className="text-muted-foreground">Идентификатор автора</span>
        <span>{task.actorId || "—"}</span>
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
  const { currentUser } = useAuth()
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
            canEdit={canEdit}
            canManage={canManage}
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
                    id: "sourceParty",
                    label: "От кого",
                    className: "w-48",
                    getSortValue: (task) => task.sourceParty,
                    render: (task) => task.sourceParty || "—",
                  },
                  {
                    id: "actorId",
                    label: "Идентификатор автора",
                    className: "w-44",
                    getSortValue: (task) => task.actorId,
                    render: (task) => task.actorId || "—",
                  },
                  {
                    id: "readyAt",
                    label: "Готово к приёмке",
                    className: "w-44",
                    getSortValue: (task) =>
                      task.readyAt ? new Date(task.readyAt).getTime() : null,
                    render: (task) =>
                      formatAcceptanceDateTime(task.readyAt ?? null),
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
