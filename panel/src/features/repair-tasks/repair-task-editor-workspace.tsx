import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useNavigate } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowLeft01Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"

import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import {
  assertEstimateLinesValid,
  calculateEstimateTotal,
  toLocalCalendarDateValue,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import {
  RepairEstimateCatalogPicker,
  type RepairEstimateCatalogPager,
} from "@/features/repair-estimates/repair-estimate-catalog-picker"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { PreviousMaintenancePhotos } from "@/features/repair-estimates/previous-maintenance-photos"
import {
  RepairWorkCompletionDialog,
  type RepairWorkCompletionResult,
} from "@/features/repair-estimates/repair-work-completion-dialog"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import { CabinFurniturePanel } from "@/features/rental-items/cabin-furniture-panel"
import {
  REPAIR_TASKS_QUERY_KEY,
  queueRepairTask,
  repairTaskDetailQueryKey,
  saveRepairTaskDraft,
  writeOffRepairDraft,
} from "@/features/repair-tasks/api/repair-tasks-api"
import {
  assertRepairTaskCanBeQueued,
  createNewRepairTaskDraft,
  toRepairTaskEditorDraft,
} from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RepairTaskDto,
  RepairTaskEditorDraft,
  RepairTaskReworkSeed,
} from "@/features/repair-tasks/model/repair-task"
import { RepairTaskQueueDraftPersistedError } from "@/features/repair-tasks/ports/repair-tasks-client"
import { RepairTaskWriteOffDialog } from "@/features/repair-tasks/repair-task-write-off-dialog"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import {
  maintenanceRepairMediaOwner,
  type ReadyMediaReference,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import { ApiError } from "@/lib/api-client"
import {
  getMaintenanceReworkCandidates,
  type MaintenanceReworkCandidateLine,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"

type RepairTaskEditorWorkspaceProps = {
  accessToken: string | null
  warehouseId: string
  task: RepairTaskDto | null
  readOnly?: boolean
  canManage?: boolean
  sourceTask?: RepairTaskDto | null
  loading?: boolean
  seed?: RepairTaskReworkSeed
  initialRentalItemId?: string
  onBack?: () => void
  onClose: () => void
  onSaved: (task: RepairTaskDto) => void
}

function ReworkCandidates({
  candidates,
  selectedLineages,
  loading,
  error,
  disabled,
  onRepeat,
}: {
  candidates: MaintenanceReworkCandidateLine[]
  selectedLineages: ReadonlySet<string>
  loading: boolean
  error: boolean
  disabled: boolean
  onRepeat: (candidate: MaintenanceReworkCandidateLine) => void
}) {
  const available = candidates.filter(
    (candidate) => !selectedLineages.has(candidate.lineageRootLineId)
  )
  return (
    <section className="mb-4 flex flex-col gap-2" aria-label="Выполненные позиции">
      <div>
        <h3 className="font-heading text-sm font-medium">
          Уже выполненные позиции
        </h3>
        <p className="text-xs text-muted-foreground">
          Выберите отдельно работы и материалы, которые нужно выполнить
          повторно.
        </p>
      </div>
      {loading ? (
        <p role="status" className="text-sm text-muted-foreground">
          Загружаем цепочку ремонта…
        </p>
      ) : error ? (
        <p role="alert" className="text-sm text-destructive">
          Не удалось загрузить выполненные позиции.
        </p>
      ) : available.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Все доступные позиции уже выбраны либо в цепочке их нет.
        </p>
      ) : (
        available.map((candidate) => (
          <Card key={candidate.lineageRootLineId} size="sm">
            <CardContent className="flex flex-wrap items-center gap-3">
              <Badge variant="secondary">
                {candidate.line.lineType === "WORK" ? "Работа" : "Материал"}
              </Badge>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">
                  {candidate.line.description}
                </p>
                <p className="text-xs text-muted-foreground">
                  {candidate.line.quantity} {candidate.line.unit || "ед"} ·{" "}
                  {candidate.line.unitPrice} ₽
                </p>
              </div>
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={disabled}
                onClick={() => onRepeat(candidate)}
              >
                Переделать
              </Button>
            </CardContent>
          </Card>
        ))
      )}
    </section>
  )
}

export function RepairTaskEditorWorkspace({
  accessToken,
  warehouseId,
  task,
  readOnly = false,
  canManage = false,
  sourceTask,
  loading = false,
  seed,
  initialRentalItemId,
  onBack,
  onClose,
  onSaved,
}: RepairTaskEditorWorkspaceProps) {
  const editorKey = task
    ? `${task.id}:${task.version}`
    : seed
      ? `rework:${seed.sourceRepairTaskId}:${seed.sourceRepairTaskVersion}`
      : `new:${warehouseId}:${initialRentalItemId ?? "unselected"}`
  if (loading) {
    return (
      <>
        <PageToolbar>
          <Button type="button" variant="outline" onClick={onBack ?? onClose}>
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
        </PageToolbar>
        <p role="status" className="text-sm text-muted-foreground">
          Загрузка задания...
        </p>
      </>
    )
  }
  return (
    <RepairTaskEditorContent
      key={editorKey}
      accessToken={accessToken}
      warehouseId={warehouseId}
      task={task}
      readOnly={readOnly}
      canManage={canManage}
      sourceTask={sourceTask}
      seed={seed}
      initialRentalItemId={initialRentalItemId}
      onBack={onBack}
      onClose={onClose}
      onSaved={onSaved}
    />
  )
}

function RepairTaskEditorContent({
  accessToken,
  warehouseId,
  task,
  readOnly = false,
  canManage = false,
  sourceTask,
  seed,
  initialRentalItemId,
  onBack,
  onClose,
  onSaved,
}: Omit<RepairTaskEditorWorkspaceProps, "loading">) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [draft, setDraft] = useState<RepairTaskEditorDraft>(() =>
    task
      ? toRepairTaskEditorDraft(task)
      : {
          ...createNewRepairTaskDraft(
            toLocalCalendarDateValue(new Date()),
            seed
          ),
          rentalItemId: seed?.rentalItemId ?? initialRentalItemId ?? "",
        }
  )
  const planSource = task ?? sourceTask ?? null
  const editingQueuedTask = task?.status === "QUEUED"
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [showBeforePhotos, setShowBeforePhotos] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [writeOffError, setWriteOffError] = useState<string | null>(null)
  const reworkCandidatesQuery = useQuery({
    queryKey: [
      "maintenance",
      "rework-candidates",
      warehouseId,
      draft.sourceRepairTaskId,
    ],
    queryFn: () =>
      getMaintenanceReworkCandidates(
        accessToken!,
        warehouseId,
        draft.sourceRepairTaskId!
      ),
    enabled: Boolean(
      accessToken &&
        draft.kind === "REWORK" &&
        draft.sourceRepairTaskId
    ),
  })
  const mediaOwner = draft.taskId
    ? maintenanceRepairMediaOwner(draft.taskId, warehouseId)
    : null
  const pendingUploadsRef = useRef(draft.pendingUploads)

  useEffect(() => {
    pendingUploadsRef.current = draft.pendingUploads
  }, [draft.pendingUploads])

  useEffect(() => {
    return () => {
      pendingUploadsRef.current.forEach((upload) =>
        URL.revokeObjectURL(upload.previewUrl)
      )
    }
  }, [])

  function handleSuccess(saved: RepairTaskDto) {
    queryClient.setQueryData(
      repairTaskDetailQueryKey(warehouseId, saved.id),
      saved
    )
    void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    onSaved(saved)
  }

  function updateReadyMediaReferences(references: ReadyMediaReference[]) {
    setDraft((current) => {
      const previous = current.maintenanceMediaReferences
      const unchanged =
        previous.length === references.length &&
        previous.every(
          (reference, index) =>
            reference.mediaId === references[index]?.mediaId &&
            reference.generation === references[index]?.generation
        )
      const coverStillReady =
        current.coverMediaId &&
        references.some(
          (reference) => reference.mediaId === current.coverMediaId
        )
      return unchanged && (coverStillReady || !current.coverMediaId)
        ? current
        : {
            ...current,
            maintenanceMediaReferences: references,
            coverMediaId: coverStillReady ? current.coverMediaId : null,
          }
    })
  }

  function normalizeReworkLines(lines: RepairEstimateLineDto[]) {
    if (draft.kind !== "REWORK") return lines
    return lines.map((line) =>
      line.rework
        ? line
        : {
            ...line,
            rework: {
              disposition: "ADDED" as const,
              sourceRepairId: null,
              sourceLineId: null,
              lineageRootLineId: line.id,
            },
          }
    )
  }

  function repeatCandidate(candidate: MaintenanceReworkCandidateLine) {
    const value = candidate.line
    const quantity = Number(value.quantity)
    const line: RepairEstimateLineDto = {
      id: crypto.randomUUID(),
      sourceLineKey: candidate.sourceLineId,
      lineType: value.lineType,
      description: value.description,
      lineComment: value.comment ?? "",
      unit: value.unit ?? "",
      quantity: Number.isFinite(quantity) && quantity > 0 ? quantity : 1,
      normativeMinutes: value.normativeMinutes,
      unitPrice: value.unitPrice,
      lineTotal: value.lineTotal,
      catalogSnapshot: value.catalogSnapshot
        ? {
            nodeId: value.catalogSnapshot.nodeId,
            name: value.catalogSnapshot.name,
            nodeType:
              value.catalogSnapshot.nodeType === "WORK"
                ? "WORK"
                : "MATERIAL",
            furnitureEquipment:
              value.catalogSnapshot.furnitureEquipment ?? null,
            characteristic: value.catalogSnapshot.characteristic ?? null,
          }
        : null,
      customQueueBinding: null,
      maintenanceMediaReferences: [...value.mediaReferences],
      rework: {
        disposition: "REPEAT",
        sourceRepairId: candidate.sourceRepairId,
        sourceLineId: candidate.sourceLineId,
        lineageRootLineId: candidate.lineageRootLineId,
      },
    }
    setDraft((current) => ({ ...current, lines: [...current.lines, line] }))
  }

  async function ensureMediaOwner() {
    if (draft.taskId) {
      return maintenanceRepairMediaOwner(draft.taskId, warehouseId)
    }
    if (readOnly) {
      throw new Error("Для добавления фотографий нужен доступ EDIT")
    }
    if (!validateDraft()) {
      throw new Error("Сначала заполните обязательные поля ремонта")
    }

    const saved = await saveRepairTaskDraft({ draft, warehouseId })
    queryClient.setQueryData(
      repairTaskDetailQueryKey(warehouseId, saved.id),
      saved
    )
    setDraft(toRepairTaskEditorDraft(saved))
    void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    return maintenanceRepairMediaOwner(saved.id, warehouseId)
  }

  const saveMutation = useMutation({
    mutationFn: () => {
      if (readOnly) {
        throw new Error("Для сохранения ремонта нужен доступ EDIT")
      }

      return saveRepairTaskDraft({ draft, warehouseId })
    },
    onMutate: () => setError(null),
    onSuccess: handleSuccess,
    onError: (unknownError) => {
      if (unknownError instanceof ApiError && unknownError.status === 409) {
        void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
        setError(
          "Ремонт уже изменён или задание уже начато. Данные обновлены — откройте ремонт снова."
        )
        return
      }
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось сохранить задание"
      )
    },
  })
  const queueMutation = useMutation({
    mutationFn: (completion: RepairWorkCompletionResult) => {
      if (readOnly) {
        throw new Error("Для постановки ремонта в очередь нужен доступ EDIT")
      }

      return queueRepairTask({ draft, warehouseId, ...completion })
    },
    onSuccess: handleSuccess,
    onError: (unknownError) => {
      if (unknownError instanceof RepairTaskQueueDraftPersistedError) {
        setDraft((current) => ({
          ...current,
          taskId: unknownError.taskId,
          expectedVersion: unknownError.expectedVersion,
        }))
        void queryClient.invalidateQueries({
          queryKey: REPAIR_TASKS_QUERY_KEY,
        })
      }
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось завершить задание"
      )
    },
  })
  const writeOffMutation = useMutation({
    mutationFn: (writeOffReason: string) => {
      if (readOnly || !canManage) {
        throw new Error("Для списания бытовки нужен доступ MANAGE")
      }

      return writeOffRepairDraft({
        warehouseId,
        origin: draft.origin,
        taskId: draft.taskId,
        expectedVersion: draft.expectedVersion,
        rentalItemId: draft.rentalItemId,
        sourceEstimateId: null,
        sourceEstimateVersion: null,
        reason: draft.reason,
        dispatchDate: draft.dispatchDate,
        comment: draft.comment,
        lines: draft.lines,
        media: draft.media,
        maintenanceMediaReferences: draft.maintenanceMediaReferences,
        coverMediaId: draft.coverMediaId,
        pendingUploads: draft.pendingUploads,
        writeOffReason,
      })
    },
    onSuccess: (saved) => {
      setWriteOffOpen(false)
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      void queryClient.invalidateQueries({
        queryKey: ["rental-item-filter-options"],
      })
      void queryClient.invalidateQueries({ queryKey: ["rental-item"] })
      navigate(`/write-offs?writeOffId=${encodeURIComponent(saved.id)}`, {
        ...workspaceEntryNavigationOptions,
        replace: true,
      })
    },
    onError: (unknownError) =>
      setWriteOffError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось списать бытовку"
      ),
  })
  const mutationPending =
    saveMutation.isPending ||
    queueMutation.isPending ||
    writeOffMutation.isPending
  const interactionDisabled = readOnly || mutationPending
  const beforePhotosButton = (
    <Button
      type="button"
      variant={showBeforePhotos ? "secondary" : "outline"}
      size="sm"
      aria-pressed={showBeforePhotos}
      disabled={!draft.rentalItemId}
      onClick={() => setShowBeforePhotos((current) => !current)}
    >
      {showBeforePhotos ? "Скрыть до" : "Показать до"}
    </Button>
  )
  const totalAmount = useMemo(() => {
    try {
      return calculateEstimateTotal(draft.lines)
    } catch {
      return "—"
    }
  }, [draft.lines])

  function validateDraft(forQueue = false) {
    if (!draft.rentalItemId) {
      setError("Выберите бытовку")
      return false
    }
    if (draft.kind === "REWORK" && !draft.reason.trim()) {
      setError("Укажите причину доработки")
      return false
    }
    if (
      draft.maintenanceMediaReferences.length > 0 &&
      !draft.coverMediaId
    ) {
      setError("Выберите титульную фотографию")
      return false
    }
    if (draft.lines.length === 0 && !planSource?.subtasks.length) {
      setError("Добавьте хотя бы одну работу или материал")
      return false
    }
    try {
      assertEstimateLinesValid(draft.lines)
      if (
        forQueue &&
        !(draft.lines.length === 0 && planSource?.subtasks.length)
      ) {
        assertRepairTaskCanBeQueued(draft.lines)
      }
      setError(null)
      return true
    } catch (unknownError) {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Проверьте строки задания"
      )
      return false
    }
  }

  function closeEditor() {
    draft.pendingUploads.forEach((upload) =>
      URL.revokeObjectURL(upload.previewUrl)
    )
    onClose()
  }

  const handleCatalogPagerChange = useCallback(
    (nextPager: RepairEstimateCatalogPager | null) => {
      setCatalogPager(nextPager)
    },
    []
  )

  const information = (
    <div className="flex flex-col gap-4">
      <RepairWorkInformationFields
        warehouseId={warehouseId}
        rentalItemId={draft.rentalItemId}
        rentalItemScope="REPAIR"
        contextLabel={draft.kind === "REWORK" ? "Причина" : "Источник"}
        contextValue={draft.reason}
        dispatchDate={draft.dispatchDate}
        comment={draft.comment}
        showComment={false}
        showContext={draft.kind === "REWORK"}
        showDispatchDate={false}
        disabled={interactionDisabled || Boolean(task)}
        readOnly={readOnly}
        rentalItemDisabled={Boolean(task) || draft.kind === "REWORK"}
        rentalItemInvalid={Boolean(error && !draft.rentalItemId)}
        onRentalItemChange={(rentalItem) =>
          setDraft((current) => ({ ...current, rentalItemId: rentalItem.id }))
        }
        onContextChange={(reason) =>
          setDraft((current) => ({ ...current, reason }))
        }
        onDispatchDateChange={(dispatchDate) =>
          setDraft((current) => ({ ...current, dispatchDate }))
        }
        onCommentChange={(comment) =>
          setDraft((current) => ({ ...current, comment }))
        }
      />
      {accessToken && draft.rentalItemId ? (
        <CabinFurniturePanel
          accessToken={accessToken}
          warehouseId={warehouseId}
          rentalItemId={draft.rentalItemId}
          disabled={interactionDisabled}
        />
      ) : null}
    </div>
  )

  const taskLines = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        {draft.kind === "REWORK" ? (
          <ReworkCandidates
            candidates={reworkCandidatesQuery.data?.items ?? []}
            selectedLineages={new Set(
              draft.lines
                .map((line) => line.rework?.lineageRootLineId)
                .filter((value): value is string => Boolean(value))
            )}
            loading={reworkCandidatesQuery.isLoading}
            error={reworkCandidatesQuery.isError}
            disabled={interactionDisabled}
            onRepeat={repeatCandidate}
          />
        ) : null}
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={interactionDisabled}
          mode="TASK"
          customWorkLinesOnly
          accessToken={accessToken}
          warehouseId={warehouseId}
          onChange={(lines) =>
            setDraft((current) => ({
              ...current,
              lines: normalizeReworkLines(lines),
            }))
          }
        />
      </div>
      <div className="shrink-0">
        <Separator />
        <div className="flex justify-end pt-3 text-sm font-medium">
          Итого: {totalAmount}
        </div>
      </div>
    </div>
  )

  const toolbarActions = (
    <>
      {canManage && !readOnly && !editingQueuedTask ? (
        <Button
          type="button"
          variant="destructive"
          disabled={
            !draft.rentalItemId || mutationPending || draft.kind === "REWORK"
          }
          onClick={() => {
            setWriteOffError(null)
            setWriteOffOpen(true)
          }}
        >
          Списать
        </Button>
      ) : null}
      <Button
        type="button"
        variant="outline"
        disabled={mutationPending}
        onClick={closeEditor}
      >
        {readOnly ? "Закрыть" : "Отмена"}
      </Button>
      {!readOnly ? (
        <>
          <Button
            type="button"
            variant="outline"
            disabled={mutationPending}
            onClick={() => {
              if (validateDraft()) {
                saveMutation.mutate()
              }
            }}
          >
            {saveMutation.isPending
              ? "Сохранение..."
              : editingQueuedTask
                ? "Сохранить изменения"
                : "Сохранить черновик"}
          </Button>
          {!editingQueuedTask ? (
            <Button
              type="button"
              disabled={mutationPending}
              onClick={() => {
                if (validateDraft(true)) {
                  setCompletionOpen(true)
                }
              }}
            >
              {queueMutation.isPending ? "Завершение..." : "Завершить"}
            </Button>
          ) : null}
        </>
      ) : null}
    </>
  )

  const controls = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-24 flex-1 overflow-y-auto pr-1">
        <RepairEstimateCatalogPicker
          lines={draft.lines}
          readOnly={interactionDisabled}
          excludeFurniture
          onChange={(lines) =>
            setDraft((current) => ({
              ...current,
              lines: normalizeReworkLines(lines),
            }))
          }
          onPagerChange={handleCatalogPagerChange}
        />
      </div>
      <Separator />
      <div className="flex shrink-0 items-center gap-2">
        <Button
          type="button"
          variant={catalogPager?.canGoBack ? "default" : "outline"}
          size="icon-sm"
          aria-label="Предыдущая страница каталога"
          disabled={!catalogPager?.canGoBack || interactionDisabled}
          onClick={() => catalogPager?.goBack()}
        >
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        </Button>
        <Button
          type="button"
          variant={catalogPager?.canGoForward ? "default" : "outline"}
          size="icon-sm"
          aria-label="Следующая страница каталога"
          disabled={!catalogPager?.canGoForward || interactionDisabled}
          onClick={() => catalogPager?.goForward()}
        >
          <HugeiconsIcon icon={ArrowRight01Icon} data-icon="inline-start" />
        </Button>
      </div>
    </div>
  )

  return (
    <>
      <PageToolbar>
        <Button type="button" variant="outline" onClick={onBack ?? closeEditor}>
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
        <PageToolbarActions>{toolbarActions}</PageToolbarActions>
      </PageToolbar>
      <RepairEstimateWorkspaceLayout
        ariaLabel="Редактор ремонтного задания"
        informationDescription={
          task
            ? "Работы, материалы и фотографии можно менять, пока задание не начато."
            : draft.kind === "REWORK"
              ? "Укажите причину доработки; бытовка определяется исходным ремонтом."
              : "Выберите бытовку и составьте план ремонта."
        }
        message={
          error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null
        }
        photos={
          <ServiceOwnerPhotos
            accessToken={accessToken}
            owner={mediaOwner}
            ensureOwner={ensureMediaOwner}
            readOnly={interactionDisabled}
            title="Фотографии ремонта"
            toolbarAction={beforePhotosButton}
            coverMediaId={draft.coverMediaId}
            requireCover
            onReadyReferencesChange={updateReadyMediaReferences}
            onCoverMediaIdChange={(coverMediaId) =>
              setDraft((current) => ({ ...current, coverMediaId }))
            }
          />
        }
        information={information}
        estimate={
          showBeforePhotos ? (
            <PreviousMaintenancePhotos
              accessToken={accessToken}
              warehouseId={warehouseId}
              rentalItemId={draft.rentalItemId}
              currentOwner={
                draft.taskId
                  ? {
                      ownerType: "MAINTENANCE_REPAIR",
                      ownerId: draft.taskId,
                    }
                  : null
              }
              currentCreatedAt={task?.createdAt ?? null}
            />
          ) : (
            taskLines
          )
        }
        controls={controls}
      />
      <RepairWorkCompletionDialog
        open={completionOpen && !readOnly && !editingQueuedTask}
        accessToken={accessToken}
        warehouseId={warehouseId}
        lines={draft.lines}
        pending={queueMutation.isPending}
        error={completionOpen ? error : null}
        title="Завершение задания"
        description="Проверьте необходимость перемещения и очереди пользовательских работ."
        completeLabel="Создать задание"
        pendingLabel="Создание..."
        previewKey={`direct-repair:${draft.taskId ?? "new"}:${draft.expectedVersion ?? 0}`}
        initialCompletionMode="MANUAL"
        initialMovementRequired={planSource?.subtasks.some(
          (subtask) => subtask.kind !== "REPAIR_WORK"
        )}
        initialLogisticsPlanningMode={
          planSource?.logisticsPlanningMode ?? "AUTO"
        }
        initialLogisticsScheduledDate={
          planSource?.logisticsScheduledDate ?? null
        }
        reconcileInitialPlans={
          draft.lines.length === 0 && planSource
            ? () =>
                planSource.subtasks.map((subtask) => ({
                  id: subtask.id,
                  kind: subtask.kind,
                  includedLineIds: [],
                  primaryLineId: null,
                  groupComment: subtask.groupComment,
                  queueId: subtask.queueId ?? null,
                  queueName: subtask.queueName,
                  routeQueueKind: subtask.routeQueueKind,
                  sortOrder: subtask.sortOrder,
                  generationStatus: "UNKNOWN",
                  workflowRequestRef: null,
                }))
            : undefined
        }
        onOpenChange={(open) => {
          setCompletionOpen(open)
          setError(null)
        }}
        onComplete={(completion) => {
          if (!readOnly) {
            queueMutation.mutate(completion)
          }
        }}
      />
      <RepairTaskWriteOffDialog
        open={writeOffOpen && canManage && !readOnly}
        pending={writeOffMutation.isPending}
        error={writeOffError}
        onOpenChange={(open) => {
          setWriteOffOpen(open)
          if (!open) setWriteOffError(null)
        }}
        onConfirm={(reason) => {
          if (canManage && !readOnly) {
            writeOffMutation.mutate(reason)
          }
        }}
      />
    </>
  )
}
