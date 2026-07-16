import { useId, useMemo, useState, type ReactNode } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"
import { toast } from "sonner"

import {
  PhotoCarousel,
  type PhotoCarouselPhoto,
} from "@/components/media/photo-carousel"
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
import { Checkbox } from "@/components/ui/checkbox"
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
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"
import { Separator } from "@/components/ui/separator"
import { useAuth } from "@/features/auth/use-auth"
import { mediaKindFromMimeType } from "@/features/media/model/media"
import { useOriginalPhotoPreference } from "@/features/media/use-media-preferences"
import { useOriginalMediaUrls } from "@/features/media/use-original-media-urls"
import { RepairWorkDetailWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import type {
  RepairEstimateLineDto,
  RepairEstimateMediaRefDto,
} from "@/features/repair-estimates/model/repair-estimate"
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

function mediaToCarouselPhotos(
  media: RepairEstimateMediaRefDto[],
  originalUrls: Record<string, string>
): PhotoCarouselPhoto[] {
  return media.map((item) => ({
    id: item.id,
    url: item.variants.small.url,
    kind: item.kind ?? mediaKindFromMimeType(item.mimeType),
    mimeType: item.mimeType,
    rotationDegrees: item.rotationDegrees,
    variants: {
      small: { url: item.variants.small.url },
      largeWebp: { url: item.variants.largeWebp.url },
      ...(originalUrls[item.id]
        ? { original: { url: originalUrls[item.id] } }
        : {}),
    },
    createdAt: item.createdAt,
  }))
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
      <dd className="min-w-0 font-medium">{children}</dd>
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
      <InfoRow label="Бытовка">{task.cabinNumber}</InfoRow>
      <InfoRow label="Источник">
        {repairTaskOriginLabel(task.origin, task.kind)}
      </InfoRow>
      <InfoRow label="Причина">{task.reason || "—"}</InfoRow>
      <InfoRow label="Автор">{task.authorName || "—"}</InfoRow>
      <InfoRow label="Начато">
        {formatAcceptanceDateTime(task.startedAt)}
      </InfoRow>
      <InfoRow label="Завершено">
        {formatAcceptanceDateTime(task.completedAt)}
      </InfoRow>
      <InfoRow label="Статус">
        <AcceptanceStatusBadge status={task.acceptanceStatus} />
      </InfoRow>
      <InfoRow label="Комментарий">{task.comment || "—"}</InfoRow>

      {mode === "WRITE_OFF" ? (
        <>
          <InfoRow label="Причина списания">
            {task.acceptanceComment || "—"}
          </InfoRow>
          <InfoRow label="Решение принял">
            {task.acceptanceDecidedBy || "—"}
          </InfoRow>
          <InfoRow label="Дата списания">
            {formatAcceptanceDateTime(task.acceptanceDecidedAt)}
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
  const sourceEstimateId = task.sourceEstimateId
  if (task.origin === "ESTIMATE" && !sourceEstimateId) {
    return null
  }

  const href =
    task.origin === "ESTIMATE"
      ? `/estimates?estimateId=${encodeURIComponent(sourceEstimateId!)}`
      : `/repairs?repairId=${encodeURIComponent(task.id)}`

  return (
    <Button variant="outline" size="sm" asChild>
      <Link to={href} state={workspaceEntryNavigationOptions.state}>
        {task.origin === "ESTIMATE" ? "Перейти к смете" : "Перейти к заданию"}
      </Link>
    </Button>
  )
}

function LinesTable({ subtask }: { subtask: RepairTaskSubtaskDto }) {
  const lines: Array<RepairEstimateLineDto & { visibleType: string }> = [
    ...subtask.workLines.map((line) => ({ ...line, visibleType: "Работа" })),
    ...subtask.materialLines.map((line) => ({
      ...line,
      visibleType: "Материал",
    })),
  ]

  if (lines.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">Состав этапа не указан.</p>
    )
  }

  return (
    <div className="w-full max-w-full overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Тип</TableHead>
            <TableHead>Позиция</TableHead>
            <TableHead>Комментарий</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {lines.map((line) => (
            <TableRow key={line.id}>
              <TableCell>
                <Badge variant="secondary">{line.visibleType}</Badge>
              </TableCell>
              <TableCell>{line.description}</TableCell>
              <TableCell>{line.lineComment || "—"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function AssignmentTable({
  subtask,
  selectedIds,
  onToggle,
  readOnly,
}: {
  subtask: RepairTaskSubtaskDto
  selectedIds: Set<string>
  onToggle: (subtaskId: string, id: string, checked: boolean) => void
  readOnly: boolean
}) {
  if (subtask.assignments.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {subtask.assigneeName
          ? `Исполнитель: ${subtask.assigneeName}`
          : "Исполнители не зафиксированы."}
      </p>
    )
  }

  return (
    <FieldSet className="min-w-0">
      <FieldLegend variant="label">Исполнители</FieldLegend>
      <div className="w-full max-w-full overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              {!readOnly ? <TableHead className="w-12">Выбор</TableHead> : null}
              <TableHead>Работник</TableHead>
              <TableHead>Взял</TableHead>
              <TableHead>Начал</TableHead>
              <TableHead>Завершил</TableHead>
              <TableHead>Время</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {subtask.assignments.map((assignment) => (
              <TableRow key={assignment.id}>
                {!readOnly ? (
                  <TableCell>
                    <Checkbox
                      aria-label={`Выбрать ${assignment.worker?.name ?? "исполнителя"}`}
                      checked={selectedIds.has(assignment.id)}
                      onCheckedChange={(checked) =>
                        onToggle(subtask.id, assignment.id, checked === true)
                      }
                    />
                  </TableCell>
                ) : null}
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
                  {formatActiveTime(assignment.activeWorkSeconds)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </FieldSet>
  )
}

function SubtaskCard({
  subtask,
  index,
  selectedIds,
  onToggle,
  onToggleGroup,
  readOnly,
  originalUrls,
  showOriginalPhotos,
}: {
  subtask: RepairTaskSubtaskDto
  index: number
  selectedIds: Set<string>
  onToggle: (subtaskId: string, id: string, checked: boolean) => void
  onToggleGroup: (subtask: RepairTaskSubtaskDto, checked: boolean) => void
  readOnly: boolean
  originalUrls: Record<string, string>
  showOriginalPhotos: boolean
}) {
  const assignmentIds =
    subtask.assignments.length > 0
      ? subtask.assignments.map((assignment) => assignment.id)
      : []
  const groupMarker = `group:${subtask.id}`
  const selectedCount = assignmentIds.filter((id) => selectedIds.has(id)).length
  const groupChecked = selectedIds.has(groupMarker)
    ? true
    : selectedCount === 0
      ? false
      : "indeterminate"

  return (
    <Card size="sm" className="min-w-0">
      <CardHeader className="min-w-0">
        <CardTitle>{subtaskTitle(subtask, index)}</CardTitle>
        <CardDescription>
          {subtask.workerGroup?.name ?? "Рабочая группа не указана"}
        </CardDescription>
        <CardAction className="flex flex-wrap items-center justify-end gap-2">
          <RemainingTimeBadge
            plannedDurationMinutes={subtask.plannedDurationMinutes}
            activeWorkSeconds={subtask.activeWorkSeconds}
          />
          {!readOnly ? (
            <FieldSet>
              <FieldLegend className="sr-only" variant="label">
                Выбор группы
              </FieldLegend>
              <Field orientation="horizontal">
                <Checkbox
                  id={`acceptance-group-${subtask.id}`}
                  checked={groupChecked}
                  onCheckedChange={(checked) =>
                    onToggleGroup(subtask, checked === true)
                  }
                />
                <FieldLabel htmlFor={`acceptance-group-${subtask.id}`}>
                  Группа
                </FieldLabel>
              </Field>
            </FieldSet>
          ) : null}
        </CardAction>
      </CardHeader>

      <CardContent className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(18rem,0.8fr)]">
        <div className="flex min-w-0 flex-col gap-4">
          <dl className="grid grid-cols-2 gap-2 text-sm">
            <dt className="text-muted-foreground">Начато</dt>
            <dd>{formatAcceptanceDateTime(subtask.startedAt)}</dd>
            <dt className="text-muted-foreground">Завершено</dt>
            <dd>{formatAcceptanceDateTime(subtask.completedAt)}</dd>
            <dt className="text-muted-foreground">Норматив</dt>
            <dd>
              {subtask.plannedDurationMinutes === null
                ? "—"
                : `${subtask.plannedDurationMinutes} мин`}
            </dd>
            <dt className="text-muted-foreground">Фактически</dt>
            <dd>{formatActiveTime(subtask.activeWorkSeconds)}</dd>
          </dl>

          <AssignmentTable
            subtask={subtask}
            selectedIds={selectedIds}
            onToggle={onToggle}
            readOnly={readOnly}
          />
          <LinesTable subtask={subtask} />
        </div>

        <section className="flex min-h-56 min-w-0 flex-col gap-2">
          <h4 className="text-sm font-medium">Фото результата</h4>
          <PhotoCarousel
            photos={mediaToCarouselPhotos(subtask.resultMedia, originalUrls)}
            title={`${subtaskTitle(subtask, index)} — ${subtask.workerGroup?.name ?? taskGroupFallback(subtask)}`}
            className="min-h-48 flex-1 rounded-lg border"
            fit="contain"
            controlsVisibility="always"
            fullscreenQuality={showOriginalPhotos ? "original" : "preview"}
          />
        </section>
      </CardContent>
    </Card>
  )
}

function taskGroupFallback(subtask: RepairTaskSubtaskDto) {
  return subtask.assigneeName || "исполнители"
}

function DecisionDialogs({
  task,
  selectedIds,
  onDecision,
}: {
  task: RepairTaskDto
  selectedIds: Set<string>
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
    if (!writeOffReason.trim()) {
      return
    }
    writeOffMutation.mutate()
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
            disabled={selectedIds.size === 0}
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
        selectedIds={selectedIds}
        onOpenChange={setReworkOpen}
      />

      <Dialog open={acceptOpen} onOpenChange={setAcceptOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Принять бытовку</DialogTitle>
            <DialogDescription>
              Бытовка станет свободной и исчезнет из очереди приёмки.
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
            </Field>
          </FieldGroup>
          {acceptMutation.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {acceptMutation.error instanceof Error &&
              acceptMutation.error.message
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
              Решение завершит ремонтный цикл и перенесёт бытовку в раздел
              «Списание».
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
                Причина сохранится в досье списания.
              </FieldDescription>
              {writeOffSubmitted && !writeOffReason.trim() ? (
                <FieldError>Укажите причину списания.</FieldError>
              ) : null}
            </Field>
          </FieldGroup>
          {writeOffMutation.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {writeOffMutation.error instanceof Error &&
              writeOffMutation.error.message
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
  const originalToggleId = useId()
  const { currentUser } = useAuth()
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const orderedSubtasks = useMemo(
    () =>
      task.subtasks
        .slice()
        .sort((left, right) => left.sortOrder - right.sortOrder),
    [task.subtasks]
  )
  const allMedia = useMemo(
    () => [
      ...task.media,
      ...orderedSubtasks.flatMap((subtask) => subtask.resultMedia),
    ],
    [orderedSubtasks, task.media]
  )
  const originalPreference = useOriginalPhotoPreference(currentUser?.id, "WORK")
  const originalUrls = useOriginalMediaUrls(
    allMedia,
    originalPreference.showOriginalPhotos,
    "WORK"
  )

  function toggleSelection(subtaskId: string, id: string, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current)
      next.delete(`group:${subtaskId}`)
      if (checked) {
        next.add(id)
      } else {
        next.delete(id)
      }
      return next
    })
  }

  function toggleGroup(subtask: RepairTaskSubtaskDto, checked: boolean) {
    const ids = [
      `group:${subtask.id}`,
      ...subtask.assignments.map((assignment) => assignment.id),
    ]
    setSelectedIds((current) => {
      const next = new Set(current)
      for (const id of ids) {
        if (checked) {
          next.add(id)
        } else {
          next.delete(id)
        }
      }
      return next
    })
  }

  return (
    <RepairWorkDetailWorkspaceLayout
      ariaLabel={`${mode === "WRITE_OFF" ? "Списание" : "Приёмка"} бытовки ${task.cabinNumber}`}
      mobileContentFlow
      photos={
        <section className="flex h-full min-h-0 flex-col gap-3">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-sm font-medium">Фото до ремонта</h3>
            <div className="flex flex-wrap items-center gap-3">
              <Field orientation="horizontal" className="w-auto gap-2">
                <Checkbox
                  id={originalToggleId}
                  checked={originalPreference.showOriginalPhotos}
                  disabled={originalPreference.loading}
                  onCheckedChange={(checked) => {
                    void originalPreference
                      .setShowOriginalPhotos(checked === true)
                      .catch(() => {
                        toast.error("Не удалось сохранить качество фотографий")
                      })
                  }}
                />
                <FieldLabel htmlFor={originalToggleId} className="font-normal">
                  Оригиналы
                </FieldLabel>
              </Field>
              <Badge variant="secondary">{task.media.length} фото</Badge>
            </div>
          </div>
          <PhotoCarousel
            photos={mediaToCarouselPhotos(task.media, originalUrls)}
            title={`Фото до ремонта — ${task.cabinNumber}`}
            className="min-h-56 flex-1 rounded-lg border xl:min-h-0"
            fit="contain"
            controlsVisibility="always"
            fullscreenQuality={
              originalPreference.showOriginalPhotos ? "original" : "preview"
            }
          />
        </section>
      }
      information={<TaskInformation task={task} mode={mode} />}
      informationDescription={
        mode === "WRITE_OFF"
          ? "Сведения о списанной бытовке и решении."
          : "Сведения о завершённом ремонтном задании."
      }
      informationAction={<SourceLink task={task} />}
      lowerTitle="Этапы ремонта"
      lowerDescription="Выполненные этапы, исполнители, время и фотографии."
      lowerContent={
        <div className="flex min-h-full flex-col gap-3">
          {orderedSubtasks.length > 0 ? (
            <div className="flex min-w-0 flex-col gap-3">
              {orderedSubtasks.map((subtask, index) => (
                <SubtaskCard
                  key={subtask.id}
                  subtask={subtask}
                  index={index}
                  selectedIds={selectedIds}
                  onToggle={toggleSelection}
                  onToggleGroup={toggleGroup}
                  readOnly={mode === "WRITE_OFF"}
                  originalUrls={originalUrls}
                  showOriginalPhotos={originalPreference.showOriginalPhotos}
                />
              ))}
            </div>
          ) : (
            <p className="text-muted-foreground">Этапы ремонта не найдены.</p>
          )}
          {mode === "ACCEPTANCE" ? (
            <div className="sticky bottom-0 mt-auto bg-card py-2">
              <Separator className="mb-2" />
              <DecisionDialogs
                task={task}
                selectedIds={selectedIds}
                onDecision={onDecision}
              />
            </div>
          ) : null}
        </div>
      }
    />
  )
}
