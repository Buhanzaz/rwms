import type {
  LogisticsPlanningMode,
  RepairEstimateCompletionMode,
  RepairEstimateEditorDraft,
  RepairEstimateTaskPlanDto,
  RepairPriority,
} from "@/features/repair-estimates/model/repair-estimate"
import { RepairWorkCompletionDialog } from "@/features/repair-estimates/repair-work-completion-dialog"

type RepairEstimateCompletionDialogProps = {
  open: boolean
  accessToken: string | null
  warehouseId: string
  draft: RepairEstimateEditorDraft
  pending: boolean
  error: string | null
  mode?: "COMPLETE" | "AMEND"
  initialMovementToRepair?: boolean
  initialLogisticsPlanningMode?: LogisticsPlanningMode
  initialLogisticsScheduledDate?: string | null
  onOpenChange: (open: boolean) => void
  onComplete: (params: {
    completionMode: RepairEstimateCompletionMode
    movementToRepair: boolean
    logisticsPlanningMode: LogisticsPlanningMode
    logisticsScheduledDate: string | null
    taskPlans: RepairEstimateTaskPlanDto[]
    priority: RepairPriority
  }) => void
}

export function RepairEstimateCompletionDialog({
  open,
  accessToken,
  warehouseId,
  draft,
  pending,
  error,
  mode = "COMPLETE",
  initialMovementToRepair,
  initialLogisticsPlanningMode,
  initialLogisticsScheduledDate,
  onOpenChange,
  onComplete,
}: RepairEstimateCompletionDialogProps) {
  return (
    <RepairWorkCompletionDialog
      open={open}
      accessToken={accessToken}
      warehouseId={warehouseId}
      lines={draft.lines}
      pending={pending}
      error={error}
      title={mode === "AMEND" ? "Дополнение сметы" : "Завершение сметы"}
      description={
        mode === "AMEND"
          ? "Изменения сметы и связанного задания сохранятся одной командой."
          : "Смета и результат ремонтного цикла сохранятся одной командой. Завершённая смета станет доступна только для чтения."
      }
      completeLabel={
        mode === "AMEND" ? "Сохранить изменения" : "Завершить смету"
      }
      pendingLabel="Завершение..."
      previewKey={mode}
      allowEmpty
      selectPriority={mode === "COMPLETE"}
      logisticsSelectionAvailable={mode === "COMPLETE"}
      initialMovementToRepair={initialMovementToRepair}
      initialLogisticsPlanningMode={initialLogisticsPlanningMode}
      initialLogisticsScheduledDate={initialLogisticsScheduledDate}
      onOpenChange={onOpenChange}
      onComplete={onComplete}
    />
  )
}
