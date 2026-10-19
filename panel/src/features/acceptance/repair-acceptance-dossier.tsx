import { useMemo, useState, type ReactNode } from "react"
import { createPortal } from "react-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Link, useNavigate } from "react-router-dom"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Carousel,
  CarouselContent,
  CarouselItem,
  CarouselNext,
  CarouselPrevious,
} from "@/components/ui/carousel"
import {
  Card,
  CardAction,
  CardContent,
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
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Separator } from "@/components/ui/separator"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
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
import {
  RepairTaskWriteOffDialog,
  type RepairTaskWriteOffDecision,
} from "@/features/repair-tasks/repair-task-write-off-dialog"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import {
  maintenanceAcceptanceMediaOwner,
  taskBoardEntryMediaOwner,
  type ReadyMediaReference,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import { useServiceOwnerMedia } from "@/features/media/use-service-owner-media"
import {
  repairTaskGeneralMediaOwner,
  repairTaskSourceMediaOwner,
} from "@/features/repair-tasks/repair-task-media-owner"

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

function queueIdentity(subtask: RepairTaskSubtaskDto) {
  const queueId = subtask.queueId?.trim()
  if (queueId) return `queue:${queueId}`
  const queueName =
    subtask.queueName?.trim().toLocaleLowerCase("ru-RU") || "unknown"
  return `legacy:${subtask.routeQueueKind ?? "unknown"}:${queueName}`
}

function earliestDate(values: Array<string | null | undefined>) {
  return values.filter((value): value is string => Boolean(value)).sort()[0] ?? null
}

function latestDate(values: Array<string | null | undefined>) {
  return values.filter((value): value is string => Boolean(value)).sort().at(-1) ?? null
}

function distinctById<T extends { id: string }>(values: T[]) {
  return [...new Map(values.map((value) => [value.id, value])).values()]
}

function distinctEvidence(values: RepairTaskEvidenceDto[]) {
  return [
    ...new Map(values.map((value) => [value.evidenceId, value])).values(),
  ]
}

/**
 * Presents historical same-queue stages as the single executable queue
 * subtask used by current maintenance plans. Persisted stage history remains
 * untouched; only the acceptance read model is coalesced.
 */
function queueSubtasks(subtasks: RepairTaskSubtaskDto[]) {
  const ordered = subtasks
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
  const grouped = new Map<string, RepairTaskSubtaskDto[]>()

  for (const subtask of ordered) {
    const key = queueIdentity(subtask)
    const current = grouped.get(key)
    if (current) current.push(subtask)
    else grouped.set(key, [subtask])
  }

  return [...grouped.values()].map((members) => {
    const first = members[0]
    if (members.length === 1) return first

    const comments = Array.from(
      new Set(
        members
          .map((member) => member.groupComment.trim())
          .filter(Boolean)
      )
    )
    const workerGroups = distinctById(
      members.flatMap((member) =>
        member.workerGroup ? [member.workerGroup] : []
      )
    )
    const plannedDurations = members
      .map((member) => member.plannedDurationMinutes)
      .filter((value): value is number => value !== null)
    const completedAt = members.every((member) => member.completedAt)
      ? latestDate(members.map((member) => member.completedAt))
      : null

    return {
      ...first,
      workLines: distinctById(members.flatMap((member) => member.workLines)),
      materialLines: distinctById(
        members.flatMap((member) => member.materialLines)
      ),
      primaryLineId:
        members.find((member) => member.primaryLineId)?.primaryLineId ?? null,
      groupComment: comments.join("\n\n"),
      evidence: distinctEvidence(
        members.flatMap((member) => member.evidence ?? EMPTY_TASK_EVIDENCE)
      ),
      sortOrder: Math.min(...members.map((member) => member.sortOrder)),
      queuePosition: Math.min(...members.map((member) => member.queuePosition)),
      plannedDurationMinutes:
        plannedDurations.length > 0
          ? plannedDurations.reduce((sum, value) => sum + value, 0)
          : null,
      startedAt: earliestDate(members.map((member) => member.startedAt)),
      completedAt,
      activeStartedAt: earliestDate(
        members.map((member) => member.activeStartedAt)
      ),
      activeWorkSeconds: members.reduce(
        (sum, member) => sum + member.activeWorkSeconds,
        0
      ),
      workerGroup:
        workerGroups.length === 0
          ? null
          : workerGroups.length === 1
            ? workerGroups[0]
            : {
                id: workerGroups.map((group) => group.id).join(":"),
                name: workerGroups.map((group) => group.name).join(", "),
              },
      assignments: distinctById(
        members.flatMap((member) => member.assignments)
      ),
    }
  })
}

type RepairAcceptanceDossierProps = {
  accessToken?: string | null
  task: RepairTaskDto
  mode: DossierMode
  canEdit: boolean
  canManage: boolean
  actorName?: string
  decisionActorName?: string
  /** Optional page-header target for acceptance decision controls. */
  headerActionsContainer?: HTMLElement | null
  onDecision?: () => void
}

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(6.5rem,0.8fr)_minmax(0,1.2fr)] gap-2 py-0.5">
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
    <dl className="flex h-full flex-col gap-0 text-sm">
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

function assignmentStatusLabel(
  status: RepairTaskSubtaskDto["assignments"][number]["status"]
) {
  if (status === "ACTIVE") return "Работает"
  if (status === "PAUSED") return "Пауза"
  if (status === "DONE") return "Завершил"
  return "Отменено"
}

function AssignmentSummary({ subtask }: { subtask: RepairTaskSubtaskDto }) {
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted-foreground">Бригада</span>
        <span className="font-medium">
          {subtask.workerGroup?.name ?? "Не назначена"}
        </span>
      </div>
      {subtask.assignments.length > 0 ? (
        <ul className="mt-2 divide-y text-sm">
          {subtask.assignments.map((assignment) => (
            <li
              key={assignment.id}
              className="flex flex-col gap-1 py-2 first:pt-0 last:pb-0"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-medium">
                  {assignment.worker?.name ?? "Исполнитель недоступен"}
                </span>
                <Badge variant="secondary">
                  {assignmentStatusLabel(assignment.status)}
                </Badge>
              </div>
              <span className="text-xs text-muted-foreground">
                Назначен {formatAcceptanceDateTime(assignment.assignedAt)} ·
                начал {formatAcceptanceDateTime(assignment.startedAt)} · завершил{" "}
                {formatAcceptanceDateTime(assignment.finishedAt)}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-2 text-xs text-muted-foreground">
          Состав исполнителей не зафиксирован в task-board.
        </p>
      )}
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
  task,
  taskBoardEntryId,
  line,
  decision,
  canReview,
  onDecision,
}: {
  accessToken: string | null
  line: RepairTaskSubtaskDto["workLines"][number]
  task: RepairTaskDto
  taskBoardEntryId: string | null
  decision: WorkReviewDecision | undefined
  canReview: boolean
  onDecision: (decision: WorkReviewDecision) => void
}) {
  const sourceReferences = line.maintenanceMediaReferences ?? []
  return (
    <li className="flex flex-col gap-2 px-3 py-2">
      <div className="flex min-w-0 flex-col gap-2 text-sm sm:flex-row sm:flex-nowrap sm:items-center">
        <span className="min-w-0 flex-1 font-medium break-words">
          {line.description} — {line.quantity} {line.unit || "ед."}
        </span>
        <div className="ml-auto flex shrink-0 flex-wrap items-center gap-2 sm:flex-nowrap">
          {line.lineComment.trim() ? (
            <Dialog>
              <DialogTrigger asChild>
                <Button type="button" size="xs" variant="ghost">
                  Комментарий
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Комментарий к работе</DialogTitle>
                  <DialogDescription>{line.description}</DialogDescription>
                </DialogHeader>
                <p className="text-sm whitespace-pre-wrap">
                  {line.lineComment}
                </p>
              </DialogContent>
            </Dialog>
          ) : null}
          {canReview ? (
            <>
              <Button
                type="button"
                size="xs"
                variant={decision === "REWORK" ? "destructive" : "outline"}
                aria-pressed={decision === "REWORK"}
                onClick={() => onDecision("REWORK")}
              >
                Переделать
              </Button>
              <Button
                type="button"
                size="xs"
                variant={decision === "ACCEPTED" ? "default" : "outline"}
                aria-pressed={decision === "ACCEPTED"}
                onClick={() => onDecision("ACCEPTED")}
              >
                Принято
              </Button>
            </>
          ) : decision ? (
            <Badge
              variant={decision === "REWORK" ? "destructive" : "secondary"}
            >
              {decision === "REWORK" ? "Переделать" : "Принято"}
            </Badge>
          ) : null}
        </div>
      </div>

      {sourceReferences.length > 0 ? (
        <div className="mt-1 w-full max-w-[22rem]">
          <ServiceOwnerPhotos
            accessToken={accessToken}
            owner={repairTaskSourceMediaOwner(task, line, taskBoardEntryId)}
            readOnly
            title="Фото работы до выполнения"
            presentation="work-carousel"
            authoritativeReadyReferences={sourceReferences}
            visibleMediaIds={sourceReferences.map(
              (reference) => reference.mediaId
            )}
          />
        </div>
      ) : null}
    </li>
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
          <ul className="divide-y rounded-lg border text-sm">
            {subtask.workLines.map((line) => (
              <WorkLineReview
                key={line.id}
                accessToken={accessToken}
                task={task}
                taskBoardEntryId={subtask.taskBoardEntryId ?? null}
                line={line}
                decision={decisions[line.id]}
                canReview={canReview}
                onDecision={(decision) => onWorkDecision(line.id, decision)}
              />
            ))}
          </ul>
        </section>
      ) : null}
      {subtask.materialLines.length > 0 ? (
        <section className="flex flex-col gap-2" aria-label="Материалы этапа">
          <h4 className="text-sm font-medium">Материалы</h4>
          <ul className="divide-y rounded-lg border text-sm">
            {subtask.materialLines.map((line) => (
              <li key={line.id} className="px-3 py-2">
                {line.description} — {line.quantity} {line.unit || "ед."}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}

type PrimaryPhotoSet = "ACCEPTANCE" | "TASK"

function taskPhotoSetTitle(photoSet: PrimaryPhotoSet) {
  return photoSet === "ACCEPTANCE"
    ? "Фото приёмки"
    : "Общие фотографии задания"
}

function TaskPhotoSet({
  accessToken,
  task,
  photoSet,
  acceptanceReadOnly,
  onAcceptanceReferencesChange,
}: {
  accessToken: string | null
  task: RepairTaskDto
  photoSet: PrimaryPhotoSet
  acceptanceReadOnly: boolean
  onAcceptanceReferencesChange?: (references: ReadyMediaReference[]) => void
}) {
  if (photoSet === "TASK") {
    const generalReferences = task.maintenanceMediaReferences ?? []
    return (
      <ServiceOwnerPhotos
        accessToken={accessToken}
        owner={repairTaskGeneralMediaOwner(task)}
        readOnly
        maxItems={100}
        title={taskPhotoSetTitle(photoSet)}
        shrinkToContainer
        visibleMediaIds={generalReferences.map(
          (reference) => reference.mediaId
        )}
        authoritativeReadyReferences={generalReferences}
        coverMediaId={task.coverMediaId ?? null}
      />
    )
  }

  return (
    <ServiceOwnerPhotos
      accessToken={accessToken}
      owner={maintenanceAcceptanceMediaOwner(task.id, task.warehouseId)}
      readOnly={acceptanceReadOnly}
      title={taskPhotoSetTitle(photoSet)}
      shrinkToContainer
      onReadyReferencesChange={
        acceptanceReadOnly ? undefined : onAcceptanceReferencesChange
      }
    />
  )
}

function TaskPhotoComparison({
  accessToken,
  task,
  mode,
  canEdit,
  primaryPhotoSet,
  comparisonOpen,
  onPrimaryPhotoSetChange,
  onComparisonOpenChange,
  onAcceptanceReferencesChange,
}: {
  accessToken: string | null
  task: RepairTaskDto
  mode: DossierMode
  canEdit: boolean
  primaryPhotoSet: PrimaryPhotoSet
  comparisonOpen: boolean
  onPrimaryPhotoSetChange: (photoSet: PrimaryPhotoSet) => void
  onComparisonOpenChange: (open: boolean) => void
  onAcceptanceReferencesChange: (references: ReadyMediaReference[]) => void
}) {
  const primaryTitle = taskPhotoSetTitle(primaryPhotoSet)

  return (
    <div className="flex h-full min-h-72 flex-col gap-2 overflow-y-auto xl:min-h-0">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium">{primaryTitle}</h3>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="xs"
            variant="outline"
            onClick={() =>
              onPrimaryPhotoSetChange(
                primaryPhotoSet === "ACCEPTANCE" ? "TASK" : "ACCEPTANCE"
              )
            }
          >
            {primaryPhotoSet === "ACCEPTANCE"
              ? "Показать фото задания"
              : "Показать фото приёмки"}
          </Button>
          <Button
            type="button"
            size="xs"
            variant="outline"
            aria-expanded={comparisonOpen}
            onClick={() => onComparisonOpenChange(!comparisonOpen)}
          >
            {comparisonOpen ? "Скрыть сравнение" : "Сравнить фотографии"}
          </Button>
        </div>
      </div>

      <div className="flex min-h-56 flex-1 flex-col xl:min-h-0">
        <TaskPhotoSet
          accessToken={accessToken}
          task={task}
          photoSet={primaryPhotoSet}
          acceptanceReadOnly={mode !== "ACCEPTANCE" || !canEdit}
          onAcceptanceReferencesChange={onAcceptanceReferencesChange}
        />
      </div>
    </div>
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
      <div className="flex min-h-72 flex-1 flex-col gap-2 xl:h-full xl:min-h-0">
        <span className="text-sm font-medium">{label}</span>
        <div
          role="status"
          className="flex min-h-64 flex-1 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground xl:min-h-0"
        >
          Сервис фото результата недоступен
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-72 min-w-0 flex-1 flex-col gap-2 xl:h-full xl:min-h-0">
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
        className="min-h-64 flex-1 rounded-lg border xl:min-h-0"
        imageVariant="preview"
        fit="contain"
        controlsVisibility="always"
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
        className="min-h-72 flex-1 rounded-lg border xl:min-h-0"
        imageVariant="preview"
        fit="contain"
        controlsVisibility="mobile-visible"
        emptyLabel="Фото результата по этапу не зафиксированы."
      />
    )
  }

  if (evidenceByEntry.length === 1) {
    return (
      <EvidencePhotoGallery
        accessToken={accessToken}
        warehouseId={warehouseId}
        evidence={evidenceByEntry[0]}
        label="Фото результата"
      />
    )
  }

  return (
    <Carousel
      opts={{ loop: true }}
      aria-label="Фотографии результата этапа"
      className="relative min-h-72 w-full xl:h-full xl:min-h-0 xl:flex-1"
    >
      <CarouselContent className="-ml-0 min-h-72 xl:h-full xl:min-h-0">
        {evidenceByEntry.map((items, entryIndex) => (
          <CarouselItem
            key={items[0].entryId}
            className="min-h-72 pl-0 xl:flex xl:h-full xl:min-h-0"
          >
            <EvidencePhotoGallery
              accessToken={accessToken}
              warehouseId={warehouseId}
              evidence={items}
              label={`Фото результата ${entryIndex + 1}`}
            />
          </CarouselItem>
        ))}
      </CarouselContent>
      <CarouselPrevious className="left-2 z-10 bg-background/90 shadow-sm" />
      <CarouselNext className="right-2 z-10 bg-background/90 shadow-sm" />
    </Carousel>
  )
}

function RepairStageSection({
  accessToken,
  task,
  subtask,
  availableSubtasks,
  onSubtaskChange,
  taskBoardAvailable,
  comparisonPhotoSet,
  decisions,
  canReview,
  onWorkDecision,
}: {
  accessToken: string | null
  task: RepairTaskDto
  subtask: RepairTaskSubtaskDto
  availableSubtasks: RepairTaskSubtaskDto[]
  onSubtaskChange: (subtaskId: string) => void
  taskBoardAvailable: boolean
  comparisonPhotoSet: PrimaryPhotoSet | null
  decisions: Readonly<Record<string, WorkReviewDecision>>
  canReview: boolean
  onWorkDecision: (lineId: string, decision: WorkReviewDecision) => void
}) {
  const queueName = subtask.queueName?.trim() || "Без названия"
  const titleId = `repair-stage-${subtask.id}`
  return (
    <section
      aria-labelledby={titleId}
      className="grid min-w-0 grid-cols-1 items-stretch gap-3 xl:h-full xl:min-h-0 xl:flex-1 xl:grid-cols-[minmax(0,12fr)_minmax(0,8fr)]"
    >
      <Card
        size="sm"
        className="h-full min-h-80 min-w-0 ring-inset xl:min-h-0"
      >
        <CardHeader>
          <CardTitle>
            {comparisonPhotoSet
              ? `Для сравнения: ${taskPhotoSetTitle(comparisonPhotoSet)}`
              : `Фото после: ${queueName}`}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex min-h-72 flex-1 flex-col xl:min-h-0">
          {comparisonPhotoSet ? (
            <TaskPhotoSet
              accessToken={accessToken}
              task={task}
              photoSet={comparisonPhotoSet}
              acceptanceReadOnly
            />
          ) : (
            <StagePhotos
              accessToken={accessToken}
              warehouseId={task.warehouseId}
              subtask={subtask}
            />
          )}
        </CardContent>
      </Card>

      <Card
        size="sm"
        className="h-full min-h-80 min-w-0 ring-inset xl:min-h-0"
      >
        <CardHeader className="min-w-0">
          <CardTitle id={titleId} className="min-w-0">
            <Select value={subtask.id} onValueChange={onSubtaskChange}>
              <SelectTrigger
                size="sm"
                className="min-w-52 max-w-full"
                aria-label="Выбрать очередь ремонта"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {availableSubtasks.map((availableSubtask) => (
                    <SelectItem
                      key={availableSubtask.id}
                      value={availableSubtask.id}
                    >
                      {availableSubtask.queueName?.trim() || "Без названия"}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </CardTitle>
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
        <CardContent className="flex min-w-0 flex-col gap-3 xl:min-h-0 xl:flex-1 xl:overflow-y-auto">
          <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
            <div className="rounded-md bg-muted/40 p-2">
              <dt className="text-xs text-muted-foreground">Начато</dt>
              <dd className="mt-0.5 font-medium">
                {formatAcceptanceDateTime(subtask.startedAt)}
              </dd>
            </div>
            <div className="rounded-md bg-muted/40 p-2">
              <dt className="text-xs text-muted-foreground">Завершено</dt>
              <dd className="mt-0.5 font-medium">
                {formatAcceptanceDateTime(subtask.completedAt)}
              </dd>
            </div>
            <div className="rounded-md bg-muted/40 p-2">
              <dt className="text-xs text-muted-foreground">Норматив</dt>
              <dd className="mt-0.5 font-medium">
                {subtask.plannedDurationMinutes === null
                  ? "—"
                  : `${subtask.plannedDurationMinutes} мин`}
              </dd>
            </div>
            <div className="rounded-md bg-muted/40 p-2">
              <dt className="text-xs text-muted-foreground">
                Активное время
              </dt>
              <dd className="mt-0.5 font-medium">
                {taskBoardAvailable
                  ? formatActiveTime(subtask.activeWorkSeconds)
                  : "Недоступно"}
              </dd>
            </div>
          </dl>

          <div className="flex min-w-0 flex-col gap-2">
            <h4 className="text-sm font-medium">Исполнители</h4>
            {taskBoardAvailable ? (
              <AssignmentSummary subtask={subtask} />
            ) : (
              <p role="status" className="text-sm text-muted-foreground">
                Исполнители и время не показаны, потому что публичная проекция
                task-board недоступна.
              </p>
            )}
          </div>

          <StageContent
            accessToken={accessToken}
            task={task}
            subtask={subtask}
            decisions={decisions}
            canReview={canReview}
            onWorkDecision={onWorkDecision}
          />

          <StageComments subtask={subtask} />
        </CardContent>
      </Card>
    </section>
  )
}

function DecisionDialogs({
  accessToken,
  task,
  acceptanceMediaReferences,
  workReviewDecisions,
  canEdit,
  canManage,
  compact = false,
  onDecision,
}: {
  accessToken: string | null
  task: RepairTaskDto
  acceptanceMediaReferences: ReadyMediaReference[]
  workReviewDecisions: Readonly<Record<string, WorkReviewDecision>>
  canEdit: boolean
  canManage: boolean
  /** Keeps actions on a single header line when a page supplies an action slot. */
  compact?: boolean
  onDecision?: () => void
}) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [reworkOpen, setReworkOpen] = useState(false)
  const [acceptOpen, setAcceptOpen] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [acceptComment, setAcceptComment] = useState("")
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
  const decisionHint = !allWorksReviewed
    ? "Отметьте каждую работу как принятую или требующую переделки."
    : !hasRework && !canAcceptWithMedia
      ? "Для приёмки добавьте хотя бы одну фотографию. На доработку можно отправить без нового фото."
      : undefined

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
    mutationFn: (decision: RepairTaskWriteOffDecision) => {
      if (!canManage) {
        throw new Error(
          "Недостаточно прав для списания бытовки на выбранном складе."
        )
      }
      return writeOffRepairTask({ task, ...decision })
    },
    onSuccess: (decision) => {
      setWriteOffOpen(false)
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: ["property-dispositions"],
      })
      onDecision?.()
      navigate(`/write-offs?decisionId=${encodeURIComponent(decision.id)}`, {
        ...workspaceEntryNavigationOptions,
        replace: true,
      })
    },
  })

  return (
    <>
      <div
        className={
          compact
            ? "flex flex-wrap items-center justify-end gap-2"
            : "flex flex-wrap items-center gap-2"
        }
      >
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
          <div
            className={
              compact
                ? "flex flex-wrap items-center justify-end gap-2"
                : "ml-auto flex flex-col items-end gap-2"
            }
          >
            {!compact && decisionHint ? (
              <p role="status" className="text-xs text-muted-foreground">
                {decisionHint}
              </p>
            ) : null}
            <div className="flex flex-wrap justify-end gap-2">
              {hasRework ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!allWorksReviewed}
                  title={compact ? decisionHint : undefined}
                  onClick={() => setReworkOpen(true)}
                >
                  Создать доработку ({reworkLineageRootIds.length})
                </Button>
              ) : (
                <Button
                  type="button"
                  size="sm"
                  disabled={!allWorksReviewed || !canAcceptWithMedia}
                  title={compact ? decisionHint : undefined}
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

      <RepairTaskWriteOffDialog
        accessToken={accessToken}
        warehouseId={task.warehouseId}
        rentalItemId={task.rentalItemId}
        open={writeOffOpen && canManage}
        pending={writeOffMutation.isPending}
        error={
          writeOffMutation.isError
            ? writeOffMutation.error instanceof Error
              ? writeOffMutation.error.message
              : "Не удалось создать решение о списании."
            : null
        }
        onOpenChange={setWriteOffOpen}
        onConfirm={(decision) => {
          if (canManage) writeOffMutation.mutate(decision)
        }}
      />
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
  headerActionsContainer = null,
  onDecision,
}: RepairAcceptanceDossierProps) {
  const reviewKey = `${task.id}:${task.version}`
  const [acceptanceMediaState, setAcceptanceMediaState] = useState<{
    key: string
    references: ReadyMediaReference[]
  }>({ key: reviewKey, references: [] })
  const acceptanceMediaReferences =
    acceptanceMediaState.key === reviewKey
      ? acceptanceMediaState.references
      : []
  const [workReviewState, setWorkReviewState] = useState<{
    key: string
    decisions: Record<string, WorkReviewDecision>
  }>({ key: reviewKey, decisions: {} })
  const workReviewDecisions =
    workReviewState.key === reviewKey ? workReviewState.decisions : {}
  const [photoComparisonState, setPhotoComparisonState] = useState<{
    key: string
    primaryPhotoSet: PrimaryPhotoSet
    comparisonOpen: boolean
  }>({
    key: reviewKey,
    primaryPhotoSet: "ACCEPTANCE",
    comparisonOpen: false,
  })
  const photoComparison =
    photoComparisonState.key === reviewKey
      ? photoComparisonState
      : {
          primaryPhotoSet: "ACCEPTANCE" as const,
          comparisonOpen: false,
        }
  const orderedSubtasks = useMemo(
    () => queueSubtasks(task.subtasks),
    [task.subtasks]
  )
  const [selectedSubtaskId, setSelectedSubtaskId] = useState<string | null>(
    null
  )
  const selectedSubtask =
    orderedSubtasks.find((subtask) => subtask.id === selectedSubtaskId) ??
    orderedSubtasks[0] ??
    null
  const taskBoardAvailable = task.taskBoardAvailable !== false
  const decisionControls =
    mode === "ACCEPTANCE" && (canEdit || canManage) ? (
      <DecisionDialogs
        accessToken={accessToken}
        task={task}
        acceptanceMediaReferences={acceptanceMediaReferences}
        workReviewDecisions={workReviewDecisions}
        canEdit={canEdit}
        canManage={canManage}
        compact={headerActionsContainer !== null}
        onDecision={onDecision}
      />
    ) : null

  return (
    <>
      <RepairWorkDetailWorkspaceLayout
        ariaLabel={`${mode === "WRITE_OFF" ? "Списание" : "Приёмка"} бытовки ${task.cabinNumber}`}
        mobileContentFlow
        desktopGridRowsClassName="xl:grid-rows-[minmax(0,0.8fr)_minmax(0,1.2fr)]"
        photos={
          <TaskPhotoComparison
            accessToken={accessToken}
            task={task}
            mode={mode}
            canEdit={canEdit}
            primaryPhotoSet={photoComparison.primaryPhotoSet}
            comparisonOpen={photoComparison.comparisonOpen}
            onPrimaryPhotoSetChange={(primaryPhotoSet) =>
              setPhotoComparisonState((current) => ({
                key: reviewKey,
                primaryPhotoSet,
                comparisonOpen:
                  current.key === reviewKey ? current.comparisonOpen : false,
              }))
            }
            onComparisonOpenChange={(comparisonOpen) =>
              setPhotoComparisonState((current) => ({
                key: reviewKey,
                primaryPhotoSet:
                  current.key === reviewKey
                    ? current.primaryPhotoSet
                    : "ACCEPTANCE",
                comparisonOpen,
              }))
            }
            onAcceptanceReferencesChange={(references) =>
              setAcceptanceMediaState({ key: reviewKey, references })
            }
          />
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
        lowerTitle="Очередь ремонта"
        lowerDescription="Результат выбранной очереди ремонта."
        bareLowerContent
        lowerContent={
          <div className="flex min-h-full flex-col gap-4 xl:h-full xl:min-h-0">
            {selectedSubtask ? (
              <div className="flex min-w-0 flex-col gap-3 xl:min-h-0 xl:flex-1">
                <RepairStageSection
                  key={selectedSubtask.id}
                  accessToken={accessToken}
                  task={task}
                  subtask={selectedSubtask}
                  availableSubtasks={orderedSubtasks}
                  onSubtaskChange={setSelectedSubtaskId}
                  taskBoardAvailable={taskBoardAvailable}
                  comparisonPhotoSet={
                    photoComparison.comparisonOpen
                      ? photoComparison.primaryPhotoSet === "ACCEPTANCE"
                        ? "TASK"
                        : "ACCEPTANCE"
                      : null
                  }
                  decisions={workReviewDecisions}
                  canReview={mode === "ACCEPTANCE" && canEdit}
                  onWorkDecision={(lineId, decision) =>
                    setWorkReviewState((current) => ({
                      key: reviewKey,
                      decisions: {
                        ...(current.key === reviewKey ? current.decisions : {}),
                        [lineId]: decision,
                      },
                    }))
                  }
                />
              </div>
            ) : (
              <p className="text-muted-foreground">Очереди ремонта не найдены.</p>
            )}
            {decisionControls && !headerActionsContainer ? (
              <div className="sticky bottom-0 mt-auto bg-card py-2">
                <Separator className="mb-2" />
                {decisionControls}
              </div>
            ) : null}
          </div>
        }
      />
      {decisionControls && headerActionsContainer
        ? createPortal(decisionControls, headerActionsContainer)
        : null}
    </>
  )
}
