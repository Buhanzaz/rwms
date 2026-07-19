import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  REPAIR_ESTIMATES_QUERY_KEY,
  amendCompletedRepairEstimate,
  repairEstimateDetailQueryKey,
} from "@/features/repair-estimates/api/repair-estimates-api"
import {
  assertEstimateLinesValid,
  calculateEstimateTotal,
  toEstimateEditorDraft,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateDto,
  RepairEstimateEditorDraft,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  RepairEstimateCatalogPicker,
  type RepairEstimateCatalogPager,
} from "@/features/repair-estimates/repair-estimate-catalog-picker"
import { RepairEstimateCompletionDialog } from "@/features/repair-estimates/repair-estimate-completion-dialog"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimateLinesSnapshot } from "@/features/repair-estimates/repair-estimate-lines-snapshot"
import {
  RepairEstimateWorkspaceLayout,
  RepairWorkDetailWorkspaceLayout,
} from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
import { RepairWorkInformationSnapshot } from "@/features/repair-estimates/repair-work-information-snapshot"
import {
  REPAIR_TASKS_QUERY_KEY,
  getRepairTaskBySourceEstimateId,
  repairTaskBySourceEstimateQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

function plansInTaskOrder(
  estimate: RepairEstimateDto,
  task: RepairTaskDto | null
) {
  const storedPlans = estimate.taskPlans
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
  if (!task) {
    return storedPlans
  }

  const planById = new Map(storedPlans.map((plan) => [plan.id, plan]))
  const usedPlanIds = new Set<string>()
  const orderedPlans: RepairEstimateTaskPlanDto[] = task.subtasks.map(
    (subtask) => {
      const stored = planById.get(subtask.id)
      if (stored) {
        usedPlanIds.add(stored.id)
        return {
          ...stored,
          kind: subtask.kind,
          groupComment: subtask.groupComment,
          queueCode: subtask.queueCode,
          queueId: subtask.queueId,
          routeQueueKind: subtask.routeQueueKind,
          sortOrder: subtask.sortOrder,
        }
      }
      const includedLines = [...subtask.workLines, ...subtask.materialLines]
      return {
        id: subtask.id,
        kind: subtask.kind,
        includedLineIds: includedLines.map((line) => line.id),
        primaryLineId: subtask.workLines[0]?.id ?? null,
        groupComment: subtask.groupComment,
        queueCode: subtask.queueCode,
        queueId: subtask.queueId,
        routeQueueKind: subtask.routeQueueKind,
        sortOrder: subtask.sortOrder,
        generationStatus: "PENDING_GENERATION" as const,
        workflowRequestRef: null,
      }
    }
  )
  storedPlans.forEach((plan) => {
    if (!usedPlanIds.has(plan.id)) {
      orderedPlans.push(plan)
    }
  })
  return orderedPlans.map((plan, index) => ({
    ...plan,
    sortOrder: (index + 1) * 10,
  }))
}

export function RepairEstimateCompletedWorkspace({
  warehouseId,
  estimate,
  readOnly = false,
}: {
  warehouseId: string
  estimate: RepairEstimateDto
  readOnly?: boolean
}) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<RepairEstimateEditorDraft>(() =>
    toEstimateEditorDraft(estimate)
  )
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [amendmentReason, setAmendmentReason] = useState("")
  const [expectedTaskVersion, setExpectedTaskVersion] = useState<number | null>(
    null
  )
  const [amendmentTaskPlans, setAmendmentTaskPlans] = useState(() =>
    plansInTaskOrder(estimate, null)
  )
  const [amendmentMovementRequired, setAmendmentMovementRequired] = useState(
    estimate.movementRequired ?? false
  )
  const pendingUploadsRef = useRef(draft.pendingUploads)
  const linkedTaskQuery = useQuery({
    queryKey: repairTaskBySourceEstimateQueryKey(warehouseId, estimate.id),
    queryFn: () => getRepairTaskBySourceEstimateId(estimate.id, warehouseId),
  })
  const linkedTask = linkedTaskQuery.data ?? null
  const canAmend =
    !readOnly &&
    linkedTaskQuery.isSuccess &&
    (!linkedTask ||
      (linkedTask.status === "QUEUED" &&
        linkedTask.startedAt === null &&
        linkedTask.subtasks.every((subtask) => subtask.status === "WAITING")))

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

  const mutation = useMutation({
    mutationFn: (params: {
      completionMode: RepairEstimateCompletionMode
      movementRequired: boolean
      taskPlans: RepairEstimateTaskPlanDto[]
    }) => {
      if (!canAmend) {
        throw new Error("Для дополнения сметы нужен доступ EDIT")
      }

      return amendCompletedRepairEstimate({
        draft,
        warehouseId,
        expectedTaskVersion,
        reason: amendmentReason,
        ...params,
      })
    },
    onSuccess: (saved) => {
      setCompletionOpen(false)
      setEditing(false)
      setDraft(toEstimateEditorDraft(saved))
      setError(null)
      queryClient.setQueryData(
        repairEstimateDetailQueryKey(warehouseId, saved.id),
        saved
      )
      void queryClient.invalidateQueries({
        queryKey: [...REPAIR_ESTIMATES_QUERY_KEY, "list"],
      })
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    },
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось дополнить смету"
      ),
  })

  const totalAmount = useMemo(() => {
    try {
      return calculateEstimateTotal(draft.lines)
    } catch {
      return "—"
    }
  }, [draft.lines])

  const handleCatalogPagerChange = useCallback(
    (nextPager: RepairEstimateCatalogPager | null) => {
      setCatalogPager(nextPager)
    },
    []
  )

  function resetEditing() {
    draft.pendingUploads.forEach((upload) =>
      URL.revokeObjectURL(upload.previewUrl)
    )
    setDraft(toEstimateEditorDraft(estimate))
    setEditing(false)
    setCompletionOpen(false)
    setExpectedTaskVersion(null)
    setAmendmentTaskPlans(plansInTaskOrder(estimate, null))
    setAmendmentMovementRequired(estimate.movementRequired ?? false)
    setError(null)
    setAmendmentReason("")
  }

  function beginEditing() {
    if (readOnly) {
      return
    }

    setDraft(toEstimateEditorDraft(estimate))
    setExpectedTaskVersion(linkedTask?.version ?? null)
    setAmendmentTaskPlans(plansInTaskOrder(estimate, linkedTask))
    setAmendmentMovementRequired(
      linkedTask
        ? linkedTask.subtasks.some((subtask) => subtask.kind !== "REPAIR_WORK")
        : (estimate.movementRequired ?? false)
    )
    setError(null)
    setAmendmentReason("")
    setEditing(true)
  }

  function validateDraft() {
    try {
      if (!amendmentReason.trim()) {
        setError("Укажите причину дополнения сметы")
        return false
      }
      assertEstimateLinesValid(draft.lines)
      setError(null)
      return true
    } catch (unknownError) {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Проверьте строки сметы"
      )
      return false
    }
  }

  if (!editing) {
    const taskLink = linkedTask ? (
      <Button variant="outline" size="sm" asChild>
        <Link
          to={`/repairs?repairId=${encodeURIComponent(linkedTask.id)}`}
          state={workspaceEntryNavigationOptions.state}
        >
          Перейти в задание
        </Link>
      </Button>
    ) : undefined

    return (
      <RepairWorkDetailWorkspaceLayout
        ariaLabel={`Завершённая смета бытовки ${estimate.cabinNumber}`}
        photos={
          <p className="text-sm text-muted-foreground">
            Фото для смет временно недоступны: media-service ещё не подтверждает
            владельца MAINTENANCE_ESTIMATE.
          </p>
        }
        information={
          <RepairWorkInformationSnapshot
            cabinNumber={estimate.cabinNumber}
            contextLabel="От кого"
            contextValue={estimate.sourceParty}
            dispatchDate={estimate.dispatchDate}
            comment={estimate.comment}
            authorName={estimate.authorName}
            authorLabel="Идентификатор автора"
            showComment={false}
            status={<Badge variant="secondary">Завершена</Badge>}
          />
        }
        informationDescription="Сохранённые сведения завершённой сметы."
        informationAction={taskLink}
        lowerTitle="Смета"
        lowerDescription="Сохранённые работы, материалы и итоговая стоимость."
        lowerAction={
          canAmend ? (
            <Button type="button" size="sm" onClick={beginEditing}>
              Дополнить смету
            </Button>
          ) : undefined
        }
        lowerContent={<RepairEstimateLinesSnapshot lines={estimate.lines} />}
      />
    )
  }

  const mutationPending = mutation.isPending
  const information = (
    <RepairWorkInformationFields
      warehouseId={warehouseId}
      rentalItemId={draft.rentalItemId}
      rentalItemNumber={estimate.cabinNumber}
      contextLabel="От кого"
      contextValue={draft.sourceParty}
      dispatchDate={draft.dispatchDate}
      comment={draft.comment}
      showComment={false}
      disabled={mutationPending}
      readOnly
      onRentalItemChange={() => undefined}
      onContextChange={(sourceParty) =>
        setDraft((current) => ({ ...current, sourceParty }))
      }
      onDispatchDateChange={(dispatchDate) =>
        setDraft((current) => ({ ...current, dispatchDate }))
      }
      onCommentChange={(comment) =>
        setDraft((current) => ({ ...current, comment }))
      }
    />
  )
  const estimateLines = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={mutationPending}
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
      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        <RepairEstimateCatalogPicker
          lines={draft.lines}
          readOnly={mutationPending}
          onChange={(lines) => setDraft((current) => ({ ...current, lines }))}
          onPagerChange={handleCatalogPagerChange}
        />
      </div>
      <Separator />
      <Field data-disabled={mutationPending}>
        <FieldLabel htmlFor="estimate-amendment-reason">
          Причина дополнения
        </FieldLabel>
        <Textarea
          id="estimate-amendment-reason"
          value={amendmentReason}
          disabled={mutationPending}
          onChange={(event) => setAmendmentReason(event.target.value)}
        />
      </Field>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant={catalogPager?.canGoBack ? "default" : "outline"}
            size="icon-sm"
            aria-label="Предыдущая страница каталога"
            disabled={!catalogPager?.canGoBack || mutationPending}
            onClick={() => catalogPager?.goBack()}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} />
          </Button>
          <Button
            type="button"
            variant={catalogPager?.canGoForward ? "default" : "outline"}
            size="icon-sm"
            aria-label="Следующая страница каталога"
            disabled={!catalogPager?.canGoForward || mutationPending}
            onClick={() => catalogPager?.goForward()}
          >
            <HugeiconsIcon icon={ArrowRight01Icon} />
          </Button>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={mutationPending}
            onClick={resetEditing}
          >
            Отменить изменения
          </Button>
          <Button
            type="button"
            disabled={mutationPending}
            onClick={() => {
              if (validateDraft()) {
                setCompletionOpen(true)
              }
            }}
          >
            Сохранить изменения
          </Button>
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
        ariaLabel="Дополнение завершённой сметы"
        message={
          error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null
        }
        photos={
          <p className="text-sm text-muted-foreground">
            Фото для смет временно недоступны: media-service ещё не подтверждает
            владельца MAINTENANCE_ESTIMATE.
          </p>
        }
        information={information}
        estimate={estimateLines}
        controls={controls}
        catalogAction={catalogAction}
      />

      <RepairEstimateCompletionDialog
        open={completionOpen && !readOnly}
        draft={draft}
        pending={mutation.isPending}
        error={error}
        mode="AMEND"
        initialMovementRequired={amendmentMovementRequired}
        initialTaskPlans={amendmentTaskPlans}
        onOpenChange={setCompletionOpen}
        onComplete={(params) => {
          if (!readOnly) {
            setError(null)
            mutation.mutate(params)
          }
        }}
      />
    </>
  )
}
