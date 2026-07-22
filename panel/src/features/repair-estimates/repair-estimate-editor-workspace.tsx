import { useCallback, useMemo, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowLeft01Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"
import {
  REPAIR_ESTIMATES_QUERY_KEY,
  completeRepairEstimate,
  repairEstimateDetailQueryKey,
  saveRepairEstimateDraft,
} from "@/features/repair-estimates/api/repair-estimates-api"
import {
  applyEstimateRentalItemSelection,
  assertEstimateLinesValid,
  calculateEstimateTotal,
  createNewEstimateDraft,
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
import { RepairEstimateCompletedWorkspace } from "@/features/repair-estimates/repair-estimate-completed-workspace"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
import { REPAIR_TASKS_QUERY_KEY } from "@/features/repair-tasks/api/repair-tasks-api"
import {
  maintenanceEstimateMediaOwner,
  type ReadyMediaReference,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

export type RepairEstimateEditorWorkspaceProps = {
  accessToken: string | null
  warehouseId: string
  estimate: RepairEstimateDto | null
  readOnly?: boolean
  loading?: boolean
  onClose: () => void
  onSaved: (estimate: RepairEstimateDto) => void | Promise<void>
  initialRentalItemId?: string
}

export function RepairEstimateEditorWorkspace({
  accessToken,
  warehouseId,
  estimate,
  readOnly = false,
  loading = false,
  onClose,
  onSaved,
  initialRentalItemId,
}: RepairEstimateEditorWorkspaceProps) {
  const editorKey = estimate
    ? `${estimate.id}:${estimate.version}`
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
        accessToken={accessToken}
        warehouseId={warehouseId}
        estimate={estimate}
        readOnly={readOnly}
      />
    )
  }

  return (
    <RepairEstimateEditorContent
      key={editorKey}
      accessToken={accessToken}
      warehouseId={warehouseId}
      estimate={estimate}
      readOnly={readOnly}
      onClose={onClose}
      onSaved={onSaved}
      initialRentalItemId={initialRentalItemId}
    />
  )
}

function RepairEstimateEditorContent({
  accessToken,
  warehouseId,
  estimate,
  readOnly = false,
  onClose,
  onSaved,
  initialRentalItemId,
}: Omit<RepairEstimateEditorWorkspaceProps, "loading">) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<RepairEstimateEditorDraft>(() =>
    estimate
      ? toEstimateEditorDraft(estimate)
      : {
          ...createNewEstimateDraft(),
          rentalItemId: initialRentalItemId ?? "",
        }
  )
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const mediaOwner = draft.estimateId
    ? maintenanceEstimateMediaOwner(draft.estimateId, warehouseId)
    : null

  function updateReadyMediaReferences(references: ReadyMediaReference[]) {
    setDraft((current) => {
      const previous = current.maintenanceMediaReferences ?? []
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
    if (draft.estimateId) {
      return maintenanceEstimateMediaOwner(draft.estimateId, warehouseId)
    }
    if (readOnly) {
      throw new Error("Для добавления фотографий нужен доступ EDIT")
    }
    if (!validateDraft()) {
      throw new Error("Сначала заполните обязательные поля сметы")
    }

    const saved = await saveRepairEstimateDraft({ draft, warehouseId })
    queryClient.setQueryData(
      repairEstimateDetailQueryKey(warehouseId, saved.id),
      saved
    )
    setDraft(toEstimateEditorDraft(saved))
    void queryClient.invalidateQueries({
      queryKey: [...REPAIR_ESTIMATES_QUERY_KEY, "list"],
    })
    return maintenanceEstimateMediaOwner(saved.id, warehouseId)
  }
  const saveMutation = useMutation({
    mutationFn: () => {
      if (readOnly) {
        throw new Error("Для сохранения сметы нужен доступ EDIT")
      }

      return saveRepairEstimateDraft({ draft, warehouseId })
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
            ? `Смета сохранена, но редактор не закрыт: ${cause.message}`
            : "Смета сохранена, но редактор не закрыт"
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
      if (readOnly) {
        throw new Error("Для завершения сметы нужен доступ EDIT")
      }

      return completeRepairEstimate({
        draft,
        warehouseId,
        ...params,
      })
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
            ? `Смета завершена, но редактор не закрыт: ${cause.message}`
            : "Смета завершена, но редактор не закрыт"
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
    onClose()
  }

  const mutationPending = saveMutation.isPending || completeMutation.isPending
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
      showComment={false}
      disabled={interactionDisabled}
      readOnly={readOnly}
      rentalItemInvalid={Boolean(error && !draft.rentalItemId)}
      onRentalItemChange={(rentalItem) =>
        setDraft((current) =>
          applyEstimateRentalItemSelection(current, rentalItem)
        )
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
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={interactionDisabled}
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
      {readOnly ? (
        <p className="text-sm text-muted-foreground">
          Смета доступна только для чтения.
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
          <ServiceOwnerPhotos
            accessToken={accessToken}
            owner={mediaOwner}
            ensureOwner={ensureMediaOwner}
            readOnly={interactionDisabled}
            title="Фотографии сметы"
            onReadyReferencesChange={updateReadyMediaReferences}
          />
        }
        information={information}
        estimate={estimateLines}
        controls={controls}
      />

      <RepairEstimateCompletionDialog
        open={completionOpen && !readOnly}
        draft={draft}
        pending={completeMutation.isPending}
        error={error}
        onOpenChange={setCompletionOpen}
        onComplete={(params) => {
          if (!readOnly) {
            setError(null)
            completeMutation.mutate(params)
          }
        }}
      />
    </>
  )
}
