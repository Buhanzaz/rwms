import { useCallback, useMemo, useState, type ReactNode } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Separator } from "@/components/ui/separator"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowLeft01Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"
import {
  REPAIR_ESTIMATES_QUERY_KEY,
  completeRepairEstimate,
  repairEstimateDetailQueryKey,
  saveRepairEstimateDraft,
} from "@/features/repair-estimates/api/repair-estimates-api"
import { awaitReturnEstimateInspection } from "@/features/repair-estimates/api/return-estimate-inspection-api"
import {
  requiresUnaccountedFurnitureConfirmation,
  UnaccountedFurnitureConfirmationRequiredError,
} from "@/features/repair-estimates/api/unaccounted-furniture-confirmation"
import {
  applyEstimateRentalItemSelection,
  assertEstimateLinesValid,
  calculateEstimateTotal,
  createNewEstimateDraft,
  toEstimateEditorDraft,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  CompleteRepairEstimateInput,
  RepairEstimateDto,
  RepairEstimateEditorDraft,
  RepairEstimateLineDto,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  RepairEstimateCatalogPicker,
  type RepairEstimateCatalogPager,
} from "@/features/repair-estimates/repair-estimate-catalog-picker"
import { RepairEstimateCompletionDialog } from "@/features/repair-estimates/repair-estimate-completion-dialog"
import { ForceCapitalRepairField } from "@/features/repair-estimates/force-capital-repair-field"
import { RepairEstimateCompletedWorkspace } from "@/features/repair-estimates/repair-estimate-completed-workspace"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { PreviousMaintenancePhotos } from "@/features/repair-estimates/previous-maintenance-photos"
import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"
import { CabinFurniturePanel } from "@/features/rental-items/cabin-furniture-panel"
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
  authorDisplayName?: string
}

type RepairEstimateCompletionParameters = Omit<
  CompleteRepairEstimateInput,
  "draft" | "warehouseId"
>

function RepairEstimateEditorToolbar({
  onClose,
  actions,
}: {
  onClose: () => void
  actions?: ReactNode
}) {
  return (
    <PageToolbar>
      <PageToolbarContent>
        <Button type="button" variant="outline" onClick={onClose}>
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
      </PageToolbarContent>
      {actions ? (
        <PageToolbarActions className="w-full sm:w-auto">
          {actions}
        </PageToolbarActions>
      ) : null}
    </PageToolbar>
  )
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
  authorDisplayName,
}: RepairEstimateEditorWorkspaceProps) {
  const editorKey = estimate
    ? `${estimate.id}:${estimate.version}`
    : `new:${warehouseId}:${initialRentalItemId ?? "unselected"}`

  if (loading) {
    return (
      <>
        <RepairEstimateEditorToolbar onClose={onClose} />
        <p role="status" className="text-sm text-muted-foreground">
          Загрузка сметы...
        </p>
      </>
    )
  }

  if (estimate?.status === "COMPLETED") {
    return (
      <>
        <RepairEstimateEditorToolbar onClose={onClose} />
        <RepairEstimateCompletedWorkspace
          key={editorKey}
          accessToken={accessToken}
          warehouseId={warehouseId}
          estimate={estimate}
          readOnly={readOnly}
          authorDisplayName={authorDisplayName}
        />
      </>
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
  const [inventoryCompletionNotice, setInventoryCompletionNotice] = useState<{
    saved: RepairEstimateDto
    message: string
  } | null>(null)
  const [
    unaccountedFurnitureConfirmation,
    setUnaccountedFurnitureConfirmation,
  ] = useState<RepairEstimateCompletionParameters | null>(null)
  const [showBeforePhotos, setShowBeforePhotos] = useState(false)
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

  async function ensureMediaOwner(lines?: RepairEstimateLineDto[]) {
    if (draft.estimateId) {
      return maintenanceEstimateMediaOwner(draft.estimateId, warehouseId)
    }
    if (readOnly) {
      throw new Error("Для добавления фотографий нужен доступ EDIT")
    }
    const ownerDraft = lines ? { ...draft, lines } : draft
    if (!validateDraft(ownerDraft)) {
      throw new Error("Сначала заполните обязательные поля сметы")
    }

    const saved = await saveRepairEstimateDraft({
      draft: ownerDraft,
      warehouseId,
    })
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
    mutationFn: async (params: RepairEstimateCompletionParameters) => {
      if (readOnly) {
        throw new Error("Для завершения сметы нужен доступ EDIT")
      }

      const saved = await completeRepairEstimate({
        draft,
        warehouseId,
        ...params,
      })
      try {
        const inspection = accessToken
          ? await awaitReturnEstimateInspection(
              accessToken,
              warehouseId,
              saved.id
            )
          : null
        return { saved, inspection, inspectionReadFailed: !accessToken }
      } catch {
        return { saved, inspection: null, inspectionReadFailed: true }
      }
    },
    onSuccess: async ({ saved, inspection, inspectionReadFailed }) => {
      setCompletionOpen(false)
      setUnaccountedFurnitureConfirmation(null)
      queryClient.setQueryData(
        repairEstimateDetailQueryKey(warehouseId, saved.id),
        saved
      )
      void queryClient.invalidateQueries({
        queryKey: [...REPAIR_ESTIMATES_QUERY_KEY, "list"],
      })
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      void queryClient.invalidateQueries({
        queryKey: ["rental-item-filter-options"],
      })
      void queryClient.invalidateQueries({ queryKey: ["rental-item"] })
      if (inspection?.state === "CONFIRMED" && inspection.cabinNumber) {
        setInventoryCompletionNotice({
          saved,
          message: `Бытовка №${inspection.cabinNumber} добавлена в инвентаризацию`,
        })
        return
      }
      if (inspectionReadFailed || inspection?.state === "PENDING") {
        setInventoryCompletionNotice({
          saved,
          message:
            "Смета завершена, но подтверждение добавления бытовки в инвентаризацию пока не получено.",
        })
        return
      }
      try {
        await onSaved(saved)
      } catch (cause) {
        setError(
          cause instanceof Error
            ? `Смета завершена, но редактор не закрыт: ${cause.message}`
            : "Смета завершена, но редактор не закрыт"
        )
      }
    },
    onError: (unknownError, params) => {
      if (
        requiresUnaccountedFurnitureConfirmation(unknownError) &&
        !params.allowUnaccountedFurniture
      ) {
        if (
          unknownError instanceof UnaccountedFurnitureConfirmationRequiredError
        ) {
          setDraft((current) => ({
            ...current,
            estimateId: unknownError.savedEstimateId,
            expectedVersion: unknownError.savedEstimateVersion,
          }))
        }
        setCompletionOpen(false)
        setUnaccountedFurnitureConfirmation(params)
        setError(null)
        return
      }
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось завершить смету"
      )
    },
  })

  const totalAmount = useMemo(() => {
    try {
      return calculateEstimateTotal(draft.lines)
    } catch {
      return "—"
    }
  }, [draft.lines])

  function validateDraft(candidate: RepairEstimateEditorDraft = draft) {
    if (!candidate.rentalItemId) {
      setError("Выберите бытовку")
      return false
    }

    try {
      assertEstimateLinesValid(candidate.lines)
      if (
        (candidate.maintenanceMediaReferences?.length ?? 0) > 0 &&
        !candidate.coverMediaId
      ) {
        throw new Error("Выберите титульную фотографию")
      }
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

  function confirmUnaccountedFurnitureCompletion() {
    if (!unaccountedFurnitureConfirmation || completeMutation.isPending) return
    setError(null)
    completeMutation.mutate({
      ...unaccountedFurnitureConfirmation,
      allowUnaccountedFurniture: true,
    })
  }

  const mutationPending = saveMutation.isPending || completeMutation.isPending
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
      <ForceCapitalRepairField
        id="estimate-force-capital-repair"
        checked={draft.forceCapitalRepair}
        disabled={interactionDisabled}
        onCheckedChange={(forceCapitalRepair) =>
          setDraft((current) => ({ ...current, forceCapitalRepair }))
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

  const estimateLines = (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
        <RepairEstimateLinesEditor
          lines={draft.lines}
          readOnly={interactionDisabled}
          customWorkLinesOnly
          accessToken={accessToken}
          warehouseId={warehouseId}
          mediaOwner={mediaOwner}
          ensureMediaOwner={ensureMediaOwner}
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

  const editorActions = readOnly ? (
    <Button
      type="button"
      variant="outline"
      disabled={mutationPending}
      onClick={closeEditor}
    >
      Закрыть
    </Button>
  ) : (
    <>
      <Button
        type="button"
        variant="outline"
        disabled={mutationPending}
        onClick={closeEditor}
      >
        Отмена
      </Button>
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
        {saveMutation.isPending ? "Сохранение..." : "Сохранить черновик"}
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
            accessToken={accessToken}
            mediaOwner={mediaOwner}
            ensureMediaOwner={ensureMediaOwner}
            onChange={(lines) => setDraft((current) => ({ ...current, lines }))}
            onPagerChange={handleCatalogPagerChange}
          />
        </div>
      )}

      {!readOnly ? (
        <>
          <Separator />
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
        </>
      ) : null}
    </div>
  )

  return (
    <>
      <RepairEstimateEditorToolbar
        onClose={closeEditor}
        actions={editorActions}
      />
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
            shrinkToContainer
            toolbarAction={beforePhotosButton}
            authoritativeReadyReferences={
              draft.maintenanceMediaReferences ?? []
            }
            coverMediaId={draft.coverMediaId ?? null}
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
                draft.estimateId
                  ? {
                      ownerType: "MAINTENANCE_ESTIMATE",
                      ownerId: draft.estimateId,
                    }
                  : null
              }
              currentCreatedAt={estimate?.createdAt ?? null}
            />
          ) : (
            estimateLines
          )
        }
        controls={controls}
      />

      <RepairEstimateCompletionDialog
        open={completionOpen && !readOnly}
        accessToken={accessToken}
        warehouseId={warehouseId}
        draft={draft}
        pending={completeMutation.isPending}
        error={error}
        onOpenChange={setCompletionOpen}
        onComplete={(params) => {
          if (!readOnly) {
            setError(null)
            completeMutation.mutate({
              ...params,
              allowUnaccountedFurniture: false,
            })
          }
        }}
      />
      <UnaccountedFurnitureConfirmationDialog
        open={unaccountedFurnitureConfirmation !== null}
        pending={completeMutation.isPending}
        onOpenChange={(open) => {
          if (!open && !completeMutation.isPending) {
            setUnaccountedFurnitureConfirmation(null)
          }
        }}
        onConfirm={confirmUnaccountedFurnitureCompletion}
      />
      <Dialog open={inventoryCompletionNotice !== null}>
        <DialogContent
          showCloseButton={false}
          onEscapeKeyDown={(event) => event.preventDefault()}
          onInteractOutside={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>{inventoryCompletionNotice?.message}</DialogTitle>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              onClick={async () => {
                const notice = inventoryCompletionNotice
                if (!notice) return
                setInventoryCompletionNotice(null)
                try {
                  await onSaved(notice.saved)
                } catch (cause) {
                  setError(
                    cause instanceof Error
                      ? `Смета завершена, но редактор не закрыт: ${cause.message}`
                      : "Смета завершена, но редактор не закрыт"
                  )
                }
              }}
            >
              Окей
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function UnaccountedFurnitureConfirmationDialog({
  open,
  pending,
  onOpenChange,
  onConfirm,
}: {
  open: boolean
  pending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!pending) onOpenChange(nextOpen)
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Выполнить без учёта допоборудования?</DialogTitle>
          <DialogDescription>
            В наполнении бытовки нет учтённой мебели. Мебель из сметы попадёт в
            отдельное утверждение как утрата, но остаток дополнительного
            оборудования на складе не изменится.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => onOpenChange(false)}
          >
            Вернуться к смете
          </Button>
          <Button type="button" disabled={pending} onClick={onConfirm}>
            {pending ? "Выполняем…" : "Выполнить без учёта склада"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
