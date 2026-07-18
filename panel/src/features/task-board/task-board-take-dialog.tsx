import { useState, type FormEvent } from "react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
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
import { useAuth } from "@/features/auth/use-auth"
import { listEligibleTaskBoardGroups } from "@/features/task-board/api/task-board-api"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

type TaskBoardTakeDialogProps = {
  entry: TaskBoardEntryDto | null
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onTake: (params: { workerGroupId: string; workerId: string | null }) => void
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
          <DialogTitle>Взять этап в работу</DialogTitle>
          <DialogDescription>
            Выберите доступную для очереди рабочую группу и, при необходимости,
            одного исполнителя.
          </DialogDescription>
        </DialogHeader>
        {entry ? (
          <TaskBoardTakeForm
            key={`${entry.id}:${entry.version}`}
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
  const groupsQuery = useQuery({
    queryKey: [
      "task-board",
      entry.warehouseId,
      entry.queueId,
      "eligible-groups",
    ],
    queryFn: () => listEligibleTaskBoardGroups(accessToken!, entry),
    enabled: Boolean(accessToken && entry.queueId),
  })
  const [groupId, setGroupId] = useState("")
  const [workerId, setWorkerId] = useState("ALL")
  const groups = groupsQuery.data ?? []
  const effectiveGroupId = groups.some((group) => group.id === groupId)
    ? groupId
    : (groups[0]?.id ?? "")
  const selectedGroup =
    groups.find((candidate) => candidate.id === effectiveGroupId) ?? null
  const workers = selectedGroup?.members.filter((member) => member.active) ?? []

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedGroup) return
    onTake({
      workerGroupId: selectedGroup.id,
      workerId: workers.some((worker) => worker.workerId === workerId)
        ? workerId
        : null,
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
                {workers.map((worker) => (
                  <SelectItem key={worker.id} value={worker.workerId}>
                    {worker.workerName}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
      </FieldGroup>

      {groupsQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          Не удалось загрузить доступные рабочие группы.
        </p>
      ) : !entry.queueId ? (
        <p role="alert" className="text-xs text-destructive">
          Сначала назначьте этап в реальную очередь.
        </p>
      ) : groups.length === 0 && !groupsQuery.isLoading ? (
        <p role="alert" className="text-xs text-destructive">
          Для этой очереди нет доступных рабочих групп.
        </p>
      ) : null}
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
