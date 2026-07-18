import { useMemo, useState, type ReactNode } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Separator } from "@/components/ui/separator"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { RepairWorkDetailWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import {
  REPAIR_TASKS_QUERY_KEY,
  acceptRepairTask,
  repairTaskDetailQueryKey,
  writeOffRepairTask,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

import {
  formatAcceptanceDateTime,
  formatActiveTime,
  repairTaskOriginLabel,
} from "./acceptance-formatters"
import {
  AcceptanceStatusBadge,
  RemainingTimeBadge,
} from "./acceptance-presentation"
import { RepairReworkWizardDialog } from "./repair-rework-wizard-dialog"

type DossierMode = "ACCEPTANCE" | "WRITE_OFF"

type RepairAcceptanceDossierProps = {
  task: RepairTaskDto
  mode: DossierMode
  onDecision?: () => void
}

function subtaskTitle(subtask: RepairTaskSubtaskDto, index: number) {
  if (subtask.kind === "MOVE_TO_REPAIR") {
    return `Этап ${index + 1}: перемещение в ремонт`
  }
  if (subtask.kind === "MOVE_FROM_REPAIR") {
    return `Этап ${index + 1}: перемещение из ремонта`
  }
  return `Этап ${index + 1}: ремонтные работы`
}

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(7.5rem,0.8fr)_minmax(0,1.2fr)] gap-3 py-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 font-medium break-words">{children}</dd>
    </div>
  )
}

function TaskInformation({
  task,
  mode,
}: {
  task: RepairTaskDto
  mode: DossierMode
}) {
  return (
    <dl className="flex flex-col gap-1 text-sm">
      <InfoRow label="Бытовка">{task.cabinNumber || "—"}</InfoRow>
      <InfoRow label="Тип источника">
        {repairTaskOriginLabel(task.origin, task.kind)}
      </InfoRow>
      <InfoRow label="От кого">{task.sourceParty || "—"}</InfoRow>
      <InfoRow label="Идентификатор автора">{task.actorId || "—"}</InfoRow>
      <InfoRow label="Дата прибытия">
        {formatAcceptanceDateTime(task.dispatchDate)}
      </InfoRow>
      <InfoRow label="Готово к приёмке">
        {formatAcceptanceDateTime(task.readyAt ?? null)}
      </InfoRow>
      <InfoRow label="Статус">
        <AcceptanceStatusBadge status={task.acceptanceStatus} />
      </InfoRow>

      {mode === "WRITE_OFF" ? (
        <>
          <InfoRow label="Решение принял">
            {task.decisionActorId || "—"}
          </InfoRow>
          <InfoRow label="Дата списания">
            {formatAcceptanceDateTime(task.writtenOffAt ?? null)}
          </InfoRow>
        </>
      ) : null}
    </dl>
  )
}

function SourceLink({ task }: { task: RepairTaskDto }) {
  if (task.kind === "REWORK" && task.sourceRepairTaskId) {
    return (
      <Button variant="outline" size="sm" asChild>
        <Link
          to={`/repairs?repairId=${encodeURIComponent(task.sourceRepairTaskId)}`}
          state={workspaceEntryNavigationOptions.state}
        >
          Перейти к исходному заданию
        </Link>
      </Button>
    )
  }
  if (task.origin === "ESTIMATE" && task.sourceEstimateId) {
    return (
      <Button variant="outline" size="sm" asChild>
        <Link
          to={`/estimates?estimateId=${encodeURIComponent(task.sourceEstimateId)}`}
          state={workspaceEntryNavigationOptions.state}
        >
          Перейти к смете
        </Link>
      </Button>
    )
  }
  return (
    <Button variant="outline" size="sm" asChild>
      <Link
        to={`/repairs?repairId=${encodeURIComponent(task.id)}`}
        state={workspaceEntryNavigationOptions.state}
      >
        Перейти к заданию
      </Link>
    </Button>
  )
}

function AssignmentTable({ subtask }: { subtask: RepairTaskSubtaskDto }) {
  if (subtask.assignments.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        Назначения исполнителей не зафиксированы в task-board.
      </p>
    )
  }
  return (
    <div className="w-full max-w-full overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Исполнитель</TableHead>
            <TableHead>Назначен</TableHead>
            <TableHead>Начал</TableHead>
            <TableHead>Завершил</TableHead>
            <TableHead>Статус</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {subtask.assignments.map((assignment) => (
            <TableRow key={assignment.id}>
              <TableCell>{assignment.worker?.name ?? "—"}</TableCell>
              <TableCell>
                {formatAcceptanceDateTime(assignment.assignedAt)}
              </TableCell>
              <TableCell>
                {formatAcceptanceDateTime(assignment.startedAt)}
              </TableCell>
              <TableCell>
                {formatAcceptanceDateTime(assignment.finishedAt)}
              </TableCell>
              <TableCell>
                <Badge variant="secondary">{assignment.status}</Badge>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function SubtaskCard({
  subtask,
  index,
  taskBoardAvailable,
}: {
  subtask: RepairTaskSubtaskDto
  index: number
  taskBoardAvailable: boolean
}) {
  return (
    <Card size="sm" className="min-w-0">
      <CardHeader className="min-w-0">
        <CardTitle>{subtaskTitle(subtask, index)}</CardTitle>
        <CardDescription>
          Очередь: {subtask.queueCode || subtask.queueId || "—"}
        </CardDescription>
        <CardAction>
          {taskBoardAvailable ? (
            <RemainingTimeBadge
              plannedDurationMinutes={subtask.plannedDurationMinutes}
              activeWorkSeconds={subtask.activeWorkSeconds}
            />
          ) : (
            <Badge variant="secondary">Task-board недоступен</Badge>
          )}
        </CardAction>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-4">
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-muted-foreground">Статус этапа</dt>
          <dd>{subtask.status}</dd>
          <dt className="text-muted-foreground">Начато</dt>
          <dd>{formatAcceptanceDateTime(subtask.startedAt)}</dd>
          <dt className="text-muted-foreground">Завершено</dt>
          <dd>{formatAcceptanceDateTime(subtask.completedAt)}</dd>
          {taskBoardAvailable ? (
            <>
              <dt className="text-muted-foreground">Норматив</dt>
              <dd>
                {subtask.plannedDurationMinutes === null
                  ? "—"
                  : `${subtask.plannedDurationMinutes} мин`}
              </dd>
              <dt className="text-muted-foreground">Активное время</dt>
              <dd>{formatActiveTime(subtask.activeWorkSeconds)}</dd>
            </>
          ) : null}
        </dl>

        {taskBoardAvailable ? (
          <AssignmentTable subtask={subtask} />
        ) : (
          <p role="status" className="text-sm text-muted-foreground">
            Исполнители и время не показаны, потому что публичная проекция
            task-board недоступна.
          </p>
        )}

        <p className="text-sm text-muted-foreground">
          Состав строк этапа не входит в публичную проекцию ремонта
          maintenance-service.
        </p>
      </CardContent>
    </Card>
  )
}

function DecisionDialogs({
  task,
  onDecision,
}: {
  task: RepairTaskDto
  onDecision?: () => void
}) {
  const queryClient = useQueryClient()
  const [reworkOpen, setReworkOpen] = useState(false)
  const [acceptOpen, setAcceptOpen] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [acceptComment, setAcceptComment] = useState("")
  const [writeOffReason, setWriteOffReason] = useState("")
  const [writeOffSubmitted, setWriteOffSubmitted] = useState(false)

  function handleDecisionSuccess(updated: RepairTaskDto) {
    queryClient.setQueryData(
      repairTaskDetailQueryKey(task.warehouseId, task.id),
      updated
    )
    void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    onDecision?.()
  }

  const acceptMutation = useMutation({
    mutationFn: () => acceptRepairTask({ task, comment: acceptComment }),
    onSuccess: handleDecisionSuccess,
  })
  const writeOffMutation = useMutation({
    mutationFn: () => writeOffRepairTask({ task, reason: writeOffReason }),
    onSuccess: handleDecisionSuccess,
  })

  function confirmWriteOff() {
    setWriteOffSubmitted(true)
    if (writeOffReason.trim()) writeOffMutation.mutate()
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="destructive"
          size="sm"
          onClick={() => setWriteOffOpen(true)}
        >
          Списать
        </Button>
        <div className="ml-auto flex flex-wrap justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setReworkOpen(true)}
          >
            Переделать
          </Button>
          <Button type="button" size="sm" onClick={() => setAcceptOpen(true)}>
            Принять
          </Button>
        </div>
      </div>

      <RepairReworkWizardDialog
        open={reworkOpen}
        task={task}
        onOpenChange={setReworkOpen}
      />

      <Dialog open={acceptOpen} onOpenChange={setAcceptOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Принять бытовку</DialogTitle>
            <DialogDescription>
              Команда завершит приёмку текущей версии ремонта.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="acceptance-comment">Комментарий</FieldLabel>
              <Textarea
                id="acceptance-comment"
                value={acceptComment}
                placeholder="Необязательно"
                onChange={(event) => setAcceptComment(event.target.value)}
              />
              <FieldDescription>
                Комментарий принимает maintenance-service; текущая публичная
                проекция приёмки его не возвращает.
              </FieldDescription>
            </Field>
          </FieldGroup>
          {acceptMutation.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {acceptMutation.error instanceof Error
                ? acceptMutation.error.message
                : "Не удалось принять бытовку."}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={acceptMutation.isPending}
              onClick={() => setAcceptOpen(false)}
            >
              Отмена
            </Button>
            <Button
              type="button"
              disabled={acceptMutation.isPending}
              onClick={() => acceptMutation.mutate()}
            >
              Принять
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={writeOffOpen} onOpenChange={setWriteOffOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Списать бытовку</DialogTitle>
            <DialogDescription>
              Команда завершит ремонтный цикл текущей версии ремонта.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field data-invalid={writeOffSubmitted && !writeOffReason.trim()}>
              <FieldLabel htmlFor="write-off-reason">
                Причина списания
              </FieldLabel>
              <Textarea
                id="write-off-reason"
                value={writeOffReason}
                aria-invalid={writeOffSubmitted && !writeOffReason.trim()}
                onChange={(event) => setWriteOffReason(event.target.value)}
              />
              <FieldDescription>
                Причина обязательна для команды, но публичная проекция списаний
                сейчас возвращает только дату и автора решения.
              </FieldDescription>
              {writeOffSubmitted && !writeOffReason.trim() ? (
                <FieldError>Укажите причину списания.</FieldError>
              ) : null}
            </Field>
          </FieldGroup>
          {writeOffMutation.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {writeOffMutation.error instanceof Error
                ? writeOffMutation.error.message
                : "Не удалось списать бытовку."}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={writeOffMutation.isPending}
              onClick={() => setWriteOffOpen(false)}
            >
              Отмена
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={writeOffMutation.isPending}
              onClick={confirmWriteOff}
            >
              Списать
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

export function RepairAcceptanceDossier({
  task,
  mode,
  onDecision,
}: RepairAcceptanceDossierProps) {
  const orderedSubtasks = useMemo(
    () =>
      task.subtasks
        .slice()
        .sort((left, right) => left.sortOrder - right.sortOrder),
    [task.subtasks]
  )
  const taskBoardAvailable = task.taskBoardAvailable !== false

  return (
    <RepairWorkDetailWorkspaceLayout
      ariaLabel={`${mode === "WRITE_OFF" ? "Списание" : "Приёмка"} бытовки ${task.cabinNumber}`}
      mobileContentFlow
      photos={
        <p className="text-sm text-muted-foreground">
          Фото для ремонта и приёмки временно недоступны: media-service ещё не
          подтверждает владельцев MAINTENANCE_REPAIR и MAINTENANCE_ACCEPTANCE.
        </p>
      }
      information={<TaskInformation task={task} mode={mode} />}
      informationDescription={
        mode === "WRITE_OFF"
          ? "Сведения из maintenance write-off projection."
          : "Сведения из maintenance acceptance projection."
      }
      informationAction={<SourceLink task={task} />}
      lowerTitle="Этапы ремонта"
      lowerDescription="Этапы maintenance-service и доступные данные публичной проекции task-board."
      lowerContent={
        <div className="flex min-h-full flex-col gap-3">
          {orderedSubtasks.length > 0 ? (
            <div className="flex min-w-0 flex-col gap-3">
              {orderedSubtasks.map((subtask, index) => (
                <SubtaskCard
                  key={subtask.id}
                  subtask={subtask}
                  index={index}
                  taskBoardAvailable={taskBoardAvailable}
                />
              ))}
            </div>
          ) : (
            <p className="text-muted-foreground">Этапы ремонта не найдены.</p>
          )}
          {mode === "ACCEPTANCE" ? (
            <div className="sticky bottom-0 mt-auto bg-card py-2">
              <Separator className="mb-2" />
              <DecisionDialogs task={task} onDecision={onDecision} />
            </div>
          ) : null}
        </div>
      }
    />
  )
}
