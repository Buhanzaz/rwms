import { useMemo, useState, type ReactNode } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { PhotoCarousel } from "@/components/media/photo-carousel"
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
  RepairTaskEvidenceDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import {
  inventoryFindingMediaOwner,
  maintenanceAcceptanceMediaOwner,
  maintenanceEstimateMediaOwner,
  maintenanceRepairMediaOwner,
  taskBoardEntryMediaOwner,
  type ReadyMediaReference,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import { useServiceOwnerMedia } from "@/features/media/use-service-owner-media"

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
type WorkReviewDecision = "ACCEPTED" | "REWORK"
const EMPTY_TASK_EVIDENCE: RepairTaskEvidenceDto[] = []

type RepairAcceptanceDossierProps = {
  accessToken?: string | null
  task: RepairTaskDto
  mode: DossierMode
  canEdit: boolean
  canManage: boolean
  actorName?: string
  decisionActorName?: string
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
  actorName,
  decisionActorName,
}: {
  task: RepairTaskDto
  mode: DossierMode
  actorName: string
  decisionActorName: string
}) {
  return (
    <dl className="flex flex-col gap-1 text-sm">
      <InfoRow label="Бытовка">{task.cabinNumber || "—"}</InfoRow>
      <InfoRow label="Тип источника">
        {repairTaskOriginLabel(task.origin, task.kind)}
      </InfoRow>
      <InfoRow label="От кого">{task.sourceParty || "—"}</InfoRow>
      <InfoRow label="Автор">{actorName}</InfoRow>
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
          <InfoRow label="Решение принял">{decisionActorName}</InfoRow>
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

function stageComments(subtask: RepairTaskSubtaskDto) {
  const comments: Array<{ id: string; source: string; text: string }> = []
  const groupComment = subtask.groupComment.trim()
  if (groupComment) {
    comments.push({
      id: `${subtask.id}:stage`,
      source: "Этап",
      text: groupComment,
    })
  }
  return comments
}

function StageComments({ subtask }: { subtask: RepairTaskSubtaskDto }) {
  const comments = stageComments(subtask)
  const titleId = `stage-comments-${subtask.id}`
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <h4 id={titleId} className="text-sm font-medium">
          Комментарии
        </h4>
        <Badge
          variant="secondary"
          aria-label={`Комментариев: ${comments.length}`}
        >
          {comments.length}
        </Badge>
      </div>
      {comments.length > 0 ? (
        <ul className="flex flex-col gap-2 text-sm">
          {comments.map((comment) => (
            <li
              key={comment.id}
              className="grid grid-cols-[minmax(7.5rem,0.8fr)_minmax(0,1.2fr)] gap-3"
            >
              <span className="text-muted-foreground">{comment.source}</span>
              <span className="min-w-0 break-words">{comment.text}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">
          Комментарии по этапу не добавлены.
        </p>
      )}
    </section>
  )
}

function WorkLineReview({
  accessToken,
  sourceMediaOwner,
  line,
  primary,
  decision,
  canReview,
  onDecision,
}: {
  accessToken: string | null
  sourceMediaOwner: ServiceMediaOwner
  line: RepairTaskSubtaskDto["workLines"][number]
  primary: boolean
  decision: WorkReviewDecision | undefined
  canReview: boolean
  onDecision: (decision: WorkReviewDecision) => void
}) {
  const sourceReferences = line.maintenanceMediaReferences ?? []
  return (
    <Card size="sm" className="ring-inset">
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          <span>{line.description}</span>
          {primary ? <Badge variant="secondary">Основная</Badge> : null}
        </CardTitle>
        <CardDescription>
          {line.quantity} {line.unit || "ед"}
        </CardDescription>
        {decision ? (
          <CardAction>
            <Badge
              variant={decision === "REWORK" ? "destructive" : "secondary"}
            >
              {decision === "REWORK" ? "Переделать" : "Принято"}
            </Badge>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {sourceReferences.length > 0 ? (
          <ServiceOwnerPhotos
            accessToken={accessToken}
            owner={sourceMediaOwner}
            readOnly
            title="Фото работы до выполнения"
            authoritativeReadyReferences={sourceReferences}
            visibleMediaIds={sourceReferences.map(
              (reference) => reference.mediaId
            )}
          />
        ) : (
          <p className="rounded-lg border bg-muted/40 px-3 py-4 text-sm text-muted-foreground">
            Фото работы до выполнения не добавлены.
          </p>
        )}
        {line.lineComment.trim() ? (
          <p className="text-sm">
            <span className="text-muted-foreground">Комментарий: </span>
            {line.lineComment}
          </p>
        ) : null}
        {canReview ? (
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              type="button"
              size="sm"
              variant={decision === "REWORK" ? "destructive" : "outline"}
              aria-pressed={decision === "REWORK"}
              onClick={() => onDecision("REWORK")}
            >
              Переделать
            </Button>
            <Button
              type="button"
              size="sm"
              variant={decision === "ACCEPTED" ? "default" : "outline"}
              aria-pressed={decision === "ACCEPTED"}
              onClick={() => onDecision("ACCEPTED")}
            >
              Принято
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function StageContent({
  accessToken,
  task,
  subtask,
  decisions,
  canReview,
  onWorkDecision,
}: {
  accessToken: string | null
  task: RepairTaskDto
  subtask: RepairTaskSubtaskDto
  decisions: Readonly<Record<string, WorkReviewDecision>>
  canReview: boolean
  onWorkDecision: (lineId: string, decision: WorkReviewDecision) => void
}) {
  if (subtask.workLines.length + subtask.materialLines.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        Для этого этапа состав работ и материалов не задан.
      </p>
    )
  }
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {subtask.workLines.length > 0 ? (
        <section className="flex flex-col gap-2" aria-label="Работы этапа">
          <h4 className="text-sm font-medium">Работы</h4>
          {subtask.workLines.map((line) => (
            <WorkLineReview
              key={line.id}
              accessToken={accessToken}
              sourceMediaOwner={workSourceMediaOwner(task, subtask, line)}
              line={line}
              primary={line.id === subtask.primaryLineId}
              decision={decisions[line.id]}
              canReview={canReview}
              onDecision={(decision) => onWorkDecision(line.id, decision)}
            />
          ))}
        </section>
      ) : null}
      {subtask.materialLines.length > 0 ? (
        <section className="flex flex-col gap-2" aria-label="Материалы этапа">
          <h4 className="text-sm font-medium">Материалы</h4>
          <div className="w-full max-w-full overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Наименование</TableHead>
                  <TableHead>Количество</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {subtask.materialLines.map((line) => (
                  <TableRow key={line.id}>
                    <TableCell>{line.description}</TableCell>
                    <TableCell>
                      {line.quantity} {line.unit}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </section>
      ) : null}
    </div>
  )
}

function workSourceMediaOwner(
  task: RepairTaskDto,
  subtask: RepairTaskSubtaskDto,
  line: RepairTaskSubtaskDto["workLines"][number]
): ServiceMediaOwner {
  if (subtask.taskBoardEntryId) {
    return taskBoardEntryMediaOwner(subtask.taskBoardEntryId, task.warehouseId)
  }
  if (task.kind !== "REWORK" && task.sourceEstimateId) {
    return maintenanceEstimateMediaOwner(task.sourceEstimateId, task.warehouseId)
  }
  if (task.origin === "INVENTORY" && task.sourceInventoryFindingId) {
    return inventoryFindingMediaOwner(
      task.sourceInventoryFindingId,
      task.warehouseId
    )
  }
  return maintenanceRepairMediaOwner(
    line.rework?.sourceRepairId ?? task.id,
    task.warehouseId
  )
}

function EvidencePhotoGallery({
  accessToken,
  warehouseId,
  evidence,
  label,
}: {
  accessToken: string | null
  warehouseId: string
  evidence: RepairTaskEvidenceDto[]
  label: string
}) {
  const [activeIndex, setActiveIndex] = useState(0)
  const owner = useMemo(
    () => taskBoardEntryMediaOwner(evidence[0].entryId, warehouseId),
    [evidence, warehouseId]
  )
  const media = useServiceOwnerMedia({ accessToken, owner })
  const expectedGenerationByMediaId = useMemo(
    () => new Map(evidence.map((item) => [item.mediaId, item.mediaGeneration])),
    [evidence]
  )
  const projectedReadyAssetIds = useMemo(
    () =>
      new Set(
        media.assets
          .filter(
            (asset) =>
              asset.kind === "IMAGE" &&
              asset.status === "READY" &&
              expectedGenerationByMediaId.get(asset.id) === asset.generation
          )
          .map((asset) => asset.id)
      ),
    [expectedGenerationByMediaId, media.assets]
  )
  const photos = useMemo(() => {
    const photoById = new Map(media.photos.map((photo) => [photo.id, photo]))
    const seen = new Set<string>()
    return evidence.flatMap((item) => {
      if (seen.has(item.mediaId) || !projectedReadyAssetIds.has(item.mediaId)) {
        return []
      }
      seen.add(item.mediaId)
      const photo = photoById.get(item.mediaId)
      return photo ? [photo] : []
    })
  }, [evidence, media.photos, projectedReadyAssetIds])
  const expectedPhotoCount = expectedGenerationByMediaId.size
  const unavailableCount = expectedPhotoCount - projectedReadyAssetIds.size
  const previewsLoading =
    projectedReadyAssetIds.size > photos.length && !media.previewUnavailable
  const mediaUnavailable =
    !accessToken ||
    media.previewUnavailable ||
    (media.query.isError && media.assets.length === 0)

  if (mediaUnavailable) {
    return (
      <div className="flex min-h-72 flex-1 flex-col gap-2">
        <span className="text-sm font-medium">{label}</span>
        <div
          role="status"
          className="flex min-h-64 flex-1 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground"
        >
          Сервис фото результата недоступен
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-72 min-w-0 flex-1 flex-col gap-2">
      <span className="text-sm font-medium">{label}</span>
      <PhotoCarousel
        photos={photos}
        title={label}
        loading={media.query.isLoading || previewsLoading}
        photoCount={photos.length}
        showPhotoCount
        activeIndex={activeIndex}
        onActiveIndexChange={setActiveIndex}
        onRequestFullscreen={media.requestFullscreen}
        className="min-h-64 flex-1 rounded-lg border"
        imageVariant="preview"
        fit="contain"
        controlsVisibility="mobile-visible"
        fullscreenQuality="preview"
        emptyLabel="Спроецированные фото результата не найдены."
      />
      {unavailableCount > 0 && media.query.isSuccess ? (
        <p role="status" className="text-sm text-muted-foreground">
          {unavailableCount}{" "}
          {unavailableCount === 1
            ? "фото ещё недоступно"
            : "фото ещё недоступны"}{" "}
          в media-service.
        </p>
      ) : null}
    </div>
  )
}

function StagePhotos({
  accessToken,
  warehouseId,
  subtask,
}: {
  accessToken: string | null
  warehouseId: string
  subtask: RepairTaskSubtaskDto
}) {
  const evidence = subtask.evidence ?? EMPTY_TASK_EVIDENCE
  const evidenceByEntry = useMemo(() => {
    const groups = new Map<string, RepairTaskEvidenceDto[]>()
    evidence.forEach((item) => {
      const current = groups.get(item.entryId)
      if (current) current.push(item)
      else groups.set(item.entryId, [item])
    })
    return [...groups.values()]
  }, [evidence])

  if (evidence.length === 0) {
    return (
      <PhotoCarousel
        photos={[]}
        title="Фото результата этапа"
        photoCount={0}
        showPhotoCount
        className="min-h-72 flex-1 rounded-lg border"
        imageVariant="preview"
        fit="contain"
        controlsVisibility="mobile-visible"
        emptyLabel="Фото результата по этапу не зафиксированы."
      />
    )
  }

  return (
    <div className="flex min-h-72 flex-1 flex-col gap-3">
      {evidenceByEntry.map((items, entryIndex) => (
        <EvidencePhotoGallery
          key={items[0].entryId}
          accessToken={accessToken}
          warehouseId={warehouseId}
          evidence={items}
          label={
            evidenceByEntry.length === 1
              ? "Фото результата"
              : `Фото результата ${entryIndex + 1}`
          }
        />
      ))}
    </div>
  )
}

function StageEvidenceInformation({
  subtask,
}: {
  subtask: RepairTaskSubtaskDto
}) {
  const evidence = subtask.evidence ?? EMPTY_TASK_EVIDENCE
  if (evidence.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        Подтверждение результата рабочим не зафиксировано.
      </p>
    )
  }
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <h4 className="text-sm font-medium">Подтверждение результата</h4>
      <div className="w-full max-w-full overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Исполнитель</TableHead>
              <TableHead>Группа</TableHead>
              <TableHead>Снято</TableHead>
              <TableHead>Записано</TableHead>
              <TableHead>Состояние</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {evidence.map((item) => (
              <TableRow key={item.evidenceId}>
                <TableCell>
                  {item.workerDisplayName ?? "Исполнитель недоступен"}
                </TableCell>
                <TableCell>
                  {item.workerGroupName ?? "Без рабочей группы"}
                </TableCell>
                <TableCell>
                  {formatAcceptanceDateTime(item.capturedAt)}
                </TableCell>
                <TableCell>
                  {formatAcceptanceDateTime(item.recordedAt)}
                </TableCell>
                <TableCell>
                  <Badge
                    variant={
                      item.state === "REVIEW_REQUIRED"
                        ? "destructive"
                        : "secondary"
                    }
                  >
                    {item.state === "REVIEW_REQUIRED"
                      ? "Требует проверки"
                      : "Готово"}
                  </Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function RepairStageSection({
  accessToken,
  task,
  subtask,
  index,
  taskBoardAvailable,
  decisions,
  canReview,
  onWorkDecision,
}: {
  accessToken: string | null
  task: RepairTaskDto
  subtask: RepairTaskSubtaskDto
  index: number
  taskBoardAvailable: boolean
  decisions: Readonly<Record<string, WorkReviewDecision>>
  canReview: boolean
  onWorkDecision: (lineId: string, decision: WorkReviewDecision) => void
}) {
  const title = subtaskTitle(subtask, index)
  const titleId = `repair-stage-${subtask.id}`
  return (
    <section
      aria-labelledby={titleId}
      className="grid min-w-0 grid-cols-1 items-stretch gap-3 xl:min-h-[32rem] xl:grid-cols-[minmax(0,12fr)_minmax(0,8fr)]"
    >
      <Card className="h-full min-h-96 min-w-0 ring-inset">
        <CardHeader>
          <CardTitle>Фото после · этап {index + 1}</CardTitle>
          <CardDescription>
            Фотографии результата, прикреплённые рабочими.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex min-h-72 flex-1 flex-col">
          <StagePhotos
            accessToken={accessToken}
            warehouseId={task.warehouseId}
            subtask={subtask}
          />
        </CardContent>
      </Card>

      <Card className="h-full min-h-96 min-w-0 ring-inset">
        <CardHeader className="min-w-0">
          <CardTitle id={titleId}>{title}</CardTitle>
          <CardDescription>Очередь: {subtask.queueName || "—"}</CardDescription>
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
          <dl className="flex flex-col gap-1 text-sm">
            <InfoRow label="Статус этапа">{subtask.status}</InfoRow>
            <InfoRow label="Начато">
              {formatAcceptanceDateTime(subtask.startedAt)}
            </InfoRow>
            <InfoRow label="Завершено">
              {formatAcceptanceDateTime(subtask.completedAt)}
            </InfoRow>
            <InfoRow label="Норматив">
              {subtask.plannedDurationMinutes === null
                ? "—"
                : `${subtask.plannedDurationMinutes} мин`}
            </InfoRow>
            <InfoRow label="Активное время">
              {taskBoardAvailable
                ? formatActiveTime(subtask.activeWorkSeconds)
                : "Недоступно"}
            </InfoRow>
          </dl>

          <Separator />

          <div className="flex min-w-0 flex-col gap-2">
            <h4 className="text-sm font-medium">Исполнители</h4>
            {taskBoardAvailable ? (
              <AssignmentTable subtask={subtask} />
            ) : (
              <p role="status" className="text-sm text-muted-foreground">
                Исполнители и время не показаны, потому что публичная проекция
                task-board недоступна.
              </p>
            )}
          </div>

          <StageEvidenceInformation subtask={subtask} />

          <Separator />

          <StageContent
            accessToken={accessToken}
            task={task}
            subtask={subtask}
            decisions={decisions}
            canReview={canReview}
            onWorkDecision={onWorkDecision}
          />

          <Separator />

          <StageComments subtask={subtask} />
        </CardContent>
      </Card>
    </section>
  )
}

function DecisionDialogs({
  task,
  acceptanceMediaReferences,
  workReviewDecisions,
  canEdit,
  canManage,
  onDecision,
}: {
  task: RepairTaskDto
  acceptanceMediaReferences: ReadyMediaReference[]
  workReviewDecisions: Readonly<Record<string, WorkReviewDecision>>
  canEdit: boolean
  canManage: boolean
  onDecision?: () => void
}) {
  const queryClient = useQueryClient()
  const [reworkOpen, setReworkOpen] = useState(false)
  const [acceptOpen, setAcceptOpen] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [acceptComment, setAcceptComment] = useState("")
  const [writeOffReason, setWriteOffReason] = useState("")
  const [writeOffSubmitted, setWriteOffSubmitted] = useState(false)
  const canAcceptWithMedia = acceptanceMediaReferences.length > 0
  const workLines = task.subtasks.flatMap((subtask) => subtask.workLines)
  const allWorksReviewed = workLines.every(
    (line) => workReviewDecisions[line.id] !== undefined
  )
  const reworkLineageRootIds = Array.from(
    new Set(
      workLines
        .filter((line) => workReviewDecisions[line.id] === "REWORK")
        .map((line) => line.rework?.lineageRootLineId ?? line.id)
    )
  )
  const hasRework = reworkLineageRootIds.length > 0

  function handleDecisionSuccess(updated: RepairTaskDto) {
    queryClient.setQueryData(
      repairTaskDetailQueryKey(task.warehouseId, task.id),
      updated
    )
    void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    onDecision?.()
  }

  const acceptMutation = useMutation({
    mutationFn: () => {
      if (!canEdit) {
        throw new Error(
          "Недостаточно прав для решения по приёмке на выбранном складе."
        )
      }
      if (!canAcceptWithMedia) {
        throw new Error("Для приёмки добавьте хотя бы одну готовую фотографию.")
      }
      return acceptRepairTask({
        task,
        comment: acceptComment,
        maintenanceMediaReferences: acceptanceMediaReferences,
      })
    },
    onSuccess: handleDecisionSuccess,
  })
  const writeOffMutation = useMutation({
    mutationFn: () => {
      if (!canManage) {
        throw new Error(
          "Недостаточно прав для списания бытовки на выбранном складе."
        )
      }
      return writeOffRepairTask({ task, reason: writeOffReason })
    },
    onSuccess: handleDecisionSuccess,
  })

  function confirmWriteOff() {
    setWriteOffSubmitted(true)
    if (canManage && writeOffReason.trim()) writeOffMutation.mutate()
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        {canManage ? (
          <Button
            type="button"
            variant="destructive"
            size="sm"
            onClick={() => setWriteOffOpen(true)}
          >
            Списать
          </Button>
        ) : null}
        {canEdit ? (
          <div className="ml-auto flex flex-col items-end gap-2">
            {!allWorksReviewed ? (
              <p role="status" className="text-xs text-muted-foreground">
                Отметьте каждую работу как принятую или требующую переделки.
              </p>
            ) : !hasRework && !canAcceptWithMedia ? (
              <p role="status" className="text-xs text-muted-foreground">
                Для приёмки добавьте хотя бы одну фотографию. На доработку можно
                отправить без нового фото.
              </p>
            ) : null}
            <div className="flex flex-wrap justify-end gap-2">
              {hasRework ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!allWorksReviewed}
                  onClick={() => setReworkOpen(true)}
                >
                  Создать доработку ({reworkLineageRootIds.length})
                </Button>
              ) : (
                <Button
                  type="button"
                  size="sm"
                  disabled={!allWorksReviewed || !canAcceptWithMedia}
                  onClick={() => setAcceptOpen(true)}
                >
                  Принять
                </Button>
              )}
            </div>
          </div>
        ) : null}
      </div>

      <RepairReworkWizardDialog
        open={reworkOpen && canEdit}
        task={task}
        canEdit={canEdit}
        selectedLineageRootIds={reworkLineageRootIds}
        onOpenChange={setReworkOpen}
      />

      <Dialog open={acceptOpen && canEdit} onOpenChange={setAcceptOpen}>
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
              disabled={acceptMutation.isPending || !canAcceptWithMedia}
              onClick={() => {
                if (canEdit) {
                  acceptMutation.mutate()
                }
              }}
            >
              Принять
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={writeOffOpen && canManage} onOpenChange={setWriteOffOpen}>
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
  accessToken = null,
  task,
  mode,
  canEdit,
  canManage,
  actorName = "Автор недоступен",
  decisionActorName = "Автор недоступен",
  onDecision,
}: RepairAcceptanceDossierProps) {
  const [acceptanceMediaReferences, setAcceptanceMediaReferences] = useState<
    ReadyMediaReference[]
  >([])
  const reviewKey = `${task.id}:${task.version}`
  const [workReviewState, setWorkReviewState] = useState<{
    key: string
    decisions: Record<string, WorkReviewDecision>
  }>({ key: reviewKey, decisions: {} })
  const workReviewDecisions =
    workReviewState.key === reviewKey ? workReviewState.decisions : {}
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
        <div className="flex h-full min-h-72 flex-col gap-2 overflow-y-auto">
          <h3 className="text-sm font-medium">Фото приёмки</h3>
          <ServiceOwnerPhotos
            accessToken={accessToken}
            owner={maintenanceAcceptanceMediaOwner(task.id, task.warehouseId)}
            readOnly={mode !== "ACCEPTANCE" || !canEdit}
            title="Фотографии приёмки"
            onReadyReferencesChange={
              mode === "ACCEPTANCE" ? setAcceptanceMediaReferences : undefined
            }
          />
        </div>
      }
      information={
        <TaskInformation
          task={task}
          mode={mode}
          actorName={actorName}
          decisionActorName={decisionActorName}
        />
      }
      informationDescription={
        mode === "WRITE_OFF"
          ? "Сведения из maintenance write-off projection."
          : "Сведения из maintenance acceptance projection."
      }
      informationAction={<SourceLink task={task} />}
      lowerTitle="Этапы ремонта"
      lowerDescription="Этапы maintenance-service и доступные данные публичной проекции task-board."
      bareLowerContent
      lowerContent={
        <div className="flex min-h-full flex-col gap-4">
          {orderedSubtasks.length > 0 ? (
            <div className="flex min-w-0 flex-col gap-4">
              {orderedSubtasks.map((subtask, index) => (
                <RepairStageSection
                  key={subtask.id}
                  accessToken={accessToken}
                  task={task}
                  subtask={subtask}
                  index={index}
                  taskBoardAvailable={taskBoardAvailable}
                  decisions={workReviewDecisions}
                  canReview={mode === "ACCEPTANCE" && canEdit}
                  onWorkDecision={(lineId, decision) =>
                    setWorkReviewState((current) => ({
                      key: reviewKey,
                      decisions: {
                        ...(current.key === reviewKey
                          ? current.decisions
                          : {}),
                        [lineId]: decision,
                      },
                    }))
                  }
                />
              ))}
            </div>
          ) : (
            <p className="text-muted-foreground">Этапы ремонта не найдены.</p>
          )}
          {mode === "ACCEPTANCE" && (canEdit || canManage) ? (
            <div className="sticky bottom-0 mt-auto bg-card py-2">
              <Separator className="mb-2" />
              <DecisionDialogs
                task={task}
                acceptanceMediaReferences={acceptanceMediaReferences}
                workReviewDecisions={workReviewDecisions}
                canEdit={canEdit}
                canManage={canManage}
                onDecision={onDecision}
              />
            </div>
          ) : null}
        </div>
      }
    />
  )
}
