import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"
import {
  REPAIR_ESTIMATES_QUERY_KEY,
  completeRepairEstimate,
  repairEstimateDetailQueryKey,
  saveRepairEstimateDraft,
} from "@/features/repair-estimates/api/repair-estimates-api"
import {
  assertEstimateLinesValid,
  calculateEstimateTotal,
  createNewEstimateDraft,
  toEstimateEditorDraft,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateDto,
  RepairEstimateEditorDraft,
  LogisticsEstimateSeed,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  RepairEstimateCatalogPicker,
  type RepairEstimateCatalogPager,
} from "@/features/repair-estimates/repair-estimate-catalog-picker"
import { RepairEstimateCompletionDialog } from "@/features/repair-estimates/repair-estimate-completion-dialog"
import { RepairEstimateCompletedWorkspace } from "@/features/repair-estimates/repair-estimate-completed-workspace"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimatePhotos } from "@/features/repair-estimates/repair-estimate-photos"
import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
import {
  REPAIR_TASKS_QUERY_KEY,
  writeOffRepairDraft,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { RepairTaskWriteOffDialog } from "@/features/repair-tasks/repair-task-write-off-dialog"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

export type RepairEstimateEditorWorkspaceProps = {
  warehouseId: string
  estimate: RepairEstimateDto | null
  loading?: boolean
  onClose: () => void
  onSaved: (estimate: RepairEstimateDto) => void | Promise<void>
  seed?: LogisticsEstimateSeed
  initialRentalItemId?: string
  onBeforePersist?: () => Promise<void>
  onPersistFailed?: () => Promise<void>
}

export function RepairEstimateEditorWorkspace({
  warehouseId,
  estimate,
  loading = false,
  onClose,
  onSaved,
  seed,
  initialRentalItemId,
  onBeforePersist,
  onPersistFailed,
}: RepairEstimateEditorWorkspaceProps) {
  const editorKey = estimate
    ? `${estimate.id}:${estimate.version}`
    : seed
      ? `return:${seed.returnTaskId}:${seed.returnTaskVersion}`
      : `new:${warehouseId}:${initialRentalItemId ?? "unselected"}`

  if (loading) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Загрузка сметы...
      </p>
    )
  }

  if (estimate?.status === "COMPLETED") {
    return (
      <RepairEstimateCompletedWorkspace
        key={editorKey}
        warehouseId={warehouseId}
        estimate={estimate}
      />
    )
  }

  return (
    <RepairEstimateEditorContent
      key={editorKey}
      warehouseId={warehouseId}
      estimate={estimate}
      onClose={onClose}
      onSaved={onSaved}
      seed={seed}
      initialRentalItemId={initialRentalItemId}
      onBeforePersist={onBeforePersist}
      onPersistFailed={onPersistFailed}
    />
  )
}

function RepairEstimateEditorContent({
  warehouseId,
  estimate,
  onClose,
  onSaved,
  seed,
  initialRentalItemId,
  onBeforePersist,
  onPersistFailed,
}: Omit<RepairEstimateEditorWorkspaceProps, "loading">) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [draft, setDraft] = useState<RepairEstimateEditorDraft>(() =>
    estimate
      ? toEstimateEditorDraft(estimate)
      : seed
        ? {
            ...createNewEstimateDraft(),
            rentalItemId: seed.rentalItemId,
            sourceParty: seed.sourceParty,
            dispatchDate: seed.dispatchDate,
            media: seed.media,
            pendingUploads: seed.pendingUploads,
            lines: seed.replacementLines,
          }
        : {
            ...createNewEstimateDraft(),
            rentalItemId: initialRentalItemId ?? "",
          }
  )
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [writeOffOpen, setWriteOffOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [writeOffError, setWriteOffError] = useState<string | null>(null)
  const pendingUploadsRef = useRef(draft.pendingUploads)
  const readOnly = estimate?.status === "COMPLETED"

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

  const saveMutation = useMutation({
    mutationFn: async () => {
      let claimed = false
      if (onBeforePersist) {
        await onBeforePersist()
        claimed = true
      }
      try {
        return await saveRepairEstimateDraft({ draft, warehouseId })
      } catch (cause) {
        if (claimed && onPersistFailed) {
          try {
            await onPersistFailed()
          } catch {
            // Preserve the estimate persistence error; claim recovery is best-effort.
          }
        }
        throw cause
      }
    },
    onSuccess: async (saved) => {
      queryClient.setQueryData(
        repairEstimateDetailQueryKey(warehouseId, saved.id),
        saved
      )
      try {
        await onSaved(saved)
      } catch (cause) {
        setError(
          cause instanceof Error
            ? `Смета сохранена, но связь с возвратом не создана: ${cause.message}`
            : "Смета сохранена, но связь с возвратом не создана"
        )
        return
      }
      void queryClient.invalidateQueries({
        queryKey: [...REPAIR_ESTIMATES_QUERY_KEY, "list"],
      })
    },
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось сохранить смету"
      ),
  })

  const completeMutation = useMutation({
    mutationFn: async (params: {
      completionMode: RepairEstimateCompletionMode
      movementRequired: boolean
      taskPlans: RepairEstimateTaskPlanDto[]
    }) => {
      let claimed = false
      if (onBeforePersist) {
        await onBeforePersist()
        claimed = true
      }
      try {
        return await completeRepairEstimate({
          draft,
          warehouseId,
          ...params,
        })
      } catch (cause) {
        if (claimed && onPersistFailed) {
          try {
            await onPersistFailed()
          } catch {
            // Preserve the estimate persistence error; claim recovery is best-effort.
          }
        }
        throw cause
      }
    },
    onSuccess: async (saved) => {
      setCompletionOpen(false)
      queryClient.setQueryData(
        repairEstimateDetailQueryKey(warehouseId, saved.id),
        saved
      )
      try {
        await onSaved(saved)
      } catch (cause) {
        setError(
          cause instanceof Error
            ? `Смета завершена, но связь с возвратом не создана: ${cause.message}`
            : "Смета завершена, но связь с возвратом не создана"
        )
        return
      }
      void queryClient.invalidateQueries({
        queryKey: [...REPAIR_ESTIMATES_QUERY_KEY, "list"],
      })
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      void queryClient.invalidateQueries({
        queryKey: ["rental-item-filter-options"],
      })
      void queryClient.invalidateQueries({ queryKey: ["rental-item"] })
    },
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось завершить смету"
      ),
  })

  const writeOffMutation = useMutation({
    mutationFn: (writeOffReason: string) =>
      writeOffRepairDraft({
        warehouseId,
        origin: "ESTIMATE",
        taskId: null,
        expectedVersion: null,
        rentalItemId: draft.rentalItemId,
        sourceEstimateId: draft.estimateId,
        sourceEstimateVersion: draft.expectedVersion,
        reason: "Ремонт по смете",
        dispatchDate: draft.dispatchDate,
        comment: draft.comment,
        lines: draft.lines,
        media: draft.media,
        pendingUploads: draft.pendingUploads,
        writeOffReason,
      }),
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

  const totalAmount = useMemo(() => {
    try {
      return calculateEstimateTotal(draft.lines)
    } catch {
      return "—"
    }
  }, [draft.lines])

  function validateDraft() {
    if (!draft.rentalItemId) {
      setError("Выберите бытовку")
      return false
    }

    try {
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

  function closeEditor() {
    draft.pendingUploads.forEach((upload) =>
      URL.revokeObjectURL(upload.previewUrl)
    )
    onClose()
  }

  const mutationPending =
    saveMutation.isPending ||
    completeMutation.isPending ||
    writeOffMutation.isPending
  const interactionDisabled = readOnly || mutationPending
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
      rentalItemNumber={estimate?.cabinNumber}
      contextLabel="От кого"
      contextValue={draft.sourceParty}
      dispatchDate={draft.dispatchDate}
      comment={draft.comment}
      disabled={interactionDisabled}
      rentalItemDisabled={Boolean(seed)}
      readOnly={readOnly}
      rentalItemInvalid={Boolean(error && !draft.rentalItemId)}
      onRentalItemChange={(rentalItemId) =>
        setDraft((current) => ({ ...current, rentalItemId }))
      }
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
      {seed?.replacementWarnings.length ? (
        <p role="status" className="text-sm text-muted-foreground">
          {seed.replacementWarnings.join("; ")}
        </p>
      ) : null}
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={interactionDisabled}
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
      {readOnly ? (
        <p className="text-sm text-muted-foreground">
          Завершённая смета доступна только для чтения.
        </p>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto pr-1">
          <RepairEstimateCatalogPicker
            lines={draft.lines}
            readOnly={interactionDisabled}
            onChange={(lines) => setDraft((current) => ({ ...current, lines }))}
            onPagerChange={handleCatalogPagerChange}
          />
        </div>
      )}

      <Separator />

      <div className="flex flex-wrap items-center justify-between gap-2">
        {!readOnly ? (
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
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          {!readOnly ? (
            <Button
              type="button"
              variant="destructive"
              disabled={!draft.rentalItemId || mutationPending}
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
                  if (validateDraft()) {
                    setCompletionOpen(true)
                  }
                }}
              >
                Завершить
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
        message={
          error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null
        }
        photos={
          <RepairEstimatePhotos
            media={draft.media}
            pendingUploads={draft.pendingUploads}
            readOnly={interactionDisabled}
            onMediaChange={(media) =>
              setDraft((current) => ({ ...current, media }))
            }
            onPendingUploadsChange={(pendingUploads) =>
              setDraft((current) => ({ ...current, pendingUploads }))
            }
          />
        }
        information={information}
        estimate={estimateLines}
        controls={controls}
        catalogAction={readOnly ? undefined : catalogAction}
      />

      <RepairEstimateCompletionDialog
        warehouseId={warehouseId}
        open={completionOpen}
        draft={draft}
        pending={completeMutation.isPending}
        error={error}
        onOpenChange={setCompletionOpen}
        onComplete={(params) => {
          setError(null)
          completeMutation.mutate(params)
        }}
      />
      <RepairTaskWriteOffDialog
        open={writeOffOpen}
        pending={writeOffMutation.isPending}
        error={writeOffError}
        onOpenChange={(open) => {
          setWriteOffOpen(open)
          if (!open) setWriteOffError(null)
        }}
        onConfirm={(reason) => writeOffMutation.mutate(reason)}
      />
    </>
  )
}
