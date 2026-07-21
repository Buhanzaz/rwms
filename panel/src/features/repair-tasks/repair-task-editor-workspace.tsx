import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
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
import {
  RepairWorkCompletionDialog,
  type RepairWorkCompletionResult,
} from "@/features/repair-estimates/repair-work-completion-dialog"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
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
import { RepairTaskWriteOffDialog } from "@/features/repair-tasks/repair-task-write-off-dialog"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import {
  maintenanceRepairMediaOwner,
  type ReadyMediaReference,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

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
  onClose: () => void
  onSaved: (task: RepairTaskDto) => void
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
      <p role="status" className="text-sm text-muted-foreground">
        Загрузка задания...
      </p>
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
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [writeOffError, setWriteOffError] = useState<string | null>(null)
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
      return unchanged
        ? current
        : { ...current, maintenanceMediaReferences: references }
    })
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
    onSuccess: handleSuccess,
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось сохранить задание"
      ),
  })
  const queueMutation = useMutation({
    mutationFn: (completion: RepairWorkCompletionResult) => {
      if (readOnly) {
        throw new Error("Для постановки ремонта в очередь нужен доступ EDIT")
      }

      return queueRepairTask({ draft, warehouseId, ...completion })
    },
    onSuccess: handleSuccess,
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось завершить задание"
      ),
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
    <RepairWorkInformationFields
      warehouseId={warehouseId}
      rentalItemId={draft.rentalItemId}
      contextLabel={draft.kind === "REWORK" ? "Причина" : "Источник"}
      contextValue={draft.reason}
      dispatchDate={draft.dispatchDate}
      comment={draft.comment}
      showComment={false}
      disabled={interactionDisabled || Boolean(task)}
      readOnly={readOnly}
      rentalItemDisabled={Boolean(task) || draft.kind === "REWORK"}
      rentalItemInvalid={Boolean(error && !draft.rentalItemId)}
      onRentalItemChange={(rentalItemId) =>
        setDraft((current) => ({ ...current, rentalItemId }))
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
  )

  const taskLines = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={interactionDisabled}
          mode="TASK"
          catalogOnly
          onChange={(lines) => setDraft((current) => ({ ...current, lines }))}
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

  const controls = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-24 flex-1 overflow-y-auto pr-1">
        <RepairEstimateCatalogPicker
          lines={draft.lines}
          readOnly={interactionDisabled}
          excludeFurniture
          onChange={(lines) => setDraft((current) => ({ ...current, lines }))}
          onPagerChange={handleCatalogPagerChange}
        />
      </div>
      <Separator />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
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
        <div className="flex flex-wrap justify-end gap-2">
          {canManage && !readOnly ? (
            <Button
              type="button"
              variant="destructive"
              disabled={
                !draft.rentalItemId ||
                mutationPending ||
                draft.kind === "REWORK"
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
                  : "Сохранить черновик"}
              </Button>
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
            </>
          ) : null}
        </div>
      </div>
    </div>
  )

  const catalogAction = (
    <Button variant="outline" size="sm" asChild>
      <Link to="/settings/estimates-repairs">
        <HugeiconsIcon icon={Settings02Icon} data-icon="inline-start" />
        Настроить каталог
      </Link>
    </Button>
  )

  return (
    <>
      <RepairEstimateWorkspaceLayout
        ariaLabel="Редактор ремонтного задания"
        informationDescription={
          task
            ? "Сервис разрешает менять у черновика только план этапов."
            : draft.kind === "REWORK"
              ? "Укажите причину доработки; бытовка определяется исходным ремонтом."
              : "Выберите бытовку, источник и дату прибытия."
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
            onReadyReferencesChange={updateReadyMediaReferences}
          />
        }
        information={information}
        estimate={taskLines}
        controls={controls}
        catalogAction={readOnly ? undefined : catalogAction}
      />
      <RepairWorkCompletionDialog
        open={completionOpen && !readOnly}
        lines={draft.lines}
        pending={queueMutation.isPending}
        error={completionOpen ? error : null}
        title="Завершение задания"
        description="Выберите режим, перемещения, очереди и порядок этапов. Задание будет поставлено на доску одной командой."
        completeLabel="Создать задание"
        pendingLabel="Создание..."
        previewKey={`direct-repair:${draft.taskId ?? "new"}:${draft.expectedVersion ?? 0}`}
        initialCompletionMode="MANUAL"
        initialMovementRequired={planSource?.subtasks.some(
          (subtask) => subtask.kind !== "REPAIR_WORK"
        )}
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
                  queueCode: subtask.queueCode,
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
