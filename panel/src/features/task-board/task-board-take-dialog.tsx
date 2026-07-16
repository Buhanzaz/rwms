import { useState, type FormEvent } from "react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { useAuth } from "@/features/auth/use-auth"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"
import type {
  RepairTaskWorkerGroupSnapshotDto,
  RepairTaskWorkerSnapshotDto,
} from "@/features/repair-tasks/model/repair-task"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

type TaskBoardTakeDialogProps = {
  entry: TaskBoardEntryDto | null
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onTake: (params: {
    workerGroup: RepairTaskWorkerGroupSnapshotDto
    workers?: RepairTaskWorkerSnapshotDto[]
  }) => void
}

export function TaskBoardTakeDialog({
  entry,
  pending,
  error,
  onOpenChange,
  onTake,
}: TaskBoardTakeDialogProps) {
  return (
    <Dialog
      open={entry !== null}
      onOpenChange={(open) => {
        if (!pending) onOpenChange(open)
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Взять подзадание в работу</DialogTitle>
          <DialogDescription>
            Выберите доступную для очереди рабочую группу и, при необходимости,
            одного исполнителя.
          </DialogDescription>
        </DialogHeader>
        {entry ? (
          <TaskBoardTakeForm
            key={`${entry.task.id}:${entry.task.version}:${entry.subtask.id}`}
            entry={entry}
            pending={pending}
            error={error}
            onCancel={() => onOpenChange(false)}
            onTake={onTake}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function TaskBoardTakeForm({
  entry,
  pending,
  error,
  onCancel,
  onTake,
}: {
  entry: TaskBoardEntryDto
  pending: boolean
  error: string | null
  onCancel: () => void
  onTake: TaskBoardTakeDialogProps["onTake"]
}) {
  const { accessToken } = useAuth()
  const query = {
    warehouseId: entry.task.warehouseId,
    queueCode: entry.subtask.queueCode,
    routeQueueKind: entry.subtask.routeQueueKind,
  }
  const groupsQuery = useQuery({
    queryKey: repairWorkerGroupsQueryKey(query),
    queryFn: () => listRepairWorkerGroups(query, accessToken ?? undefined),
  })
  const [groupId, setGroupId] = useState("")
  const [workerId, setWorkerId] = useState("ALL")
  const groups = groupsQuery.data ?? []
  const effectiveGroupId = groups.some((group) => group.id === groupId)
    ? groupId
    : (groups[0]?.id ?? "")
  const selectedGroup =
    groups.find((candidate) => candidate.id === effectiveGroupId) ?? null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedGroup) return
    const selectedWorker = selectedGroup.members.find(
      (worker) => worker.id === workerId
    )
    onTake({
      workerGroup: { id: selectedGroup.id, name: selectedGroup.name },
      workers: selectedWorker ? [selectedWorker] : undefined,
    })
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <FieldGroup>
        <Field data-disabled={pending}>
          <FieldLabel htmlFor="task-board-worker-group">
            Рабочая группа
          </FieldLabel>
          <Select
            value={effectiveGroupId}
            disabled={pending || groups.length === 0}
            onValueChange={(value) => {
              setGroupId(value)
              setWorkerId("ALL")
            }}
          >
            <SelectTrigger
              id="task-board-worker-group"
              aria-label="Рабочая группа"
              className="w-full"
            >
              <SelectValue
                placeholder={
                  groupsQuery.isLoading
                    ? "Загрузка групп..."
                    : "Выберите группу"
                }
              />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {groups.map((group) => (
                  <SelectItem key={group.id} value={group.id}>
                    {group.name}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field data-disabled={pending || !selectedGroup}>
          <FieldLabel htmlFor="task-board-worker">Исполнитель</FieldLabel>
          <Select
            value={workerId}
            disabled={pending || !selectedGroup}
            onValueChange={setWorkerId}
          >
            <SelectTrigger
              id="task-board-worker"
              aria-label="Конкретный исполнитель"
              className="w-full"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">Вся группа</SelectItem>
                {selectedGroup?.members.map((worker) => (
                  <SelectItem key={worker.id} value={worker.id}>
                    {worker.name}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
      </FieldGroup>

      {groupsQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          Не удалось загрузить рабочие группы.
        </p>
      ) : groups.length === 0 && !groupsQuery.isLoading ? (
        <p role="alert" className="text-xs text-destructive">
          Для этой очереди нет доступных рабочих групп.
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">
          Если выбран вариант «Вся группа», задание получат все активные
          участники группы.
        </p>
      )}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}

      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          disabled={pending}
          onClick={onCancel}
        >
          Отмена
        </Button>
        <Button type="submit" disabled={pending || !selectedGroup}>
          {pending ? "Сохранение..." : "Взять в работу"}
        </Button>
      </DialogFooter>
    </form>
  )
}
