import type {
  RepairEstimateCompletionMode,
  RepairEstimateEditorDraft,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { RepairWorkCompletionDialog } from "@/features/repair-estimates/repair-work-completion-dialog"

type RepairEstimateCompletionDialogProps = {
  open: boolean
  draft: RepairEstimateEditorDraft
  pending: boolean
  error: string | null
  mode?: "COMPLETE" | "AMEND"
  initialMovementRequired?: boolean
  initialTaskPlans?: RepairEstimateTaskPlanDto[]
  onOpenChange: (open: boolean) => void
  onComplete: (params: {
    completionMode: RepairEstimateCompletionMode
    movementRequired: boolean
    taskPlans: RepairEstimateTaskPlanDto[]
  }) => void
}

function amendmentInitialPlans(params: {
  draft: RepairEstimateEditorDraft
  stored: RepairEstimateTaskPlanDto[]
  prepared: RepairEstimateTaskPlanDto[]
}) {
  const lineIds = new Set(params.draft.lines.map((line) => line.id))
  const lineById = new Map(params.draft.lines.map((line) => [line.id, line]))
  const existingPlans = params.stored
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .flatMap((plan) => {
      if (plan.kind !== "REPAIR_WORK") return [plan]
      const includedLineIds = plan.includedLineIds.filter((lineId) =>
        lineIds.has(lineId)
      )
      if (includedLineIds.length === 0) return []
      const retainedPrimary = plan.primaryLineId
        ? lineById.get(plan.primaryLineId)
        : null
      const primaryLineId =
        retainedPrimary?.lineType === "WORK" &&
        includedLineIds.includes(retainedPrimary.id)
          ? retainedPrimary.id
          : (includedLineIds.find(
              (lineId) => lineById.get(lineId)?.lineType === "WORK"
            ) ?? null)
      return [{ ...plan, includedLineIds, primaryLineId }]
    })
  const coveredLineIds = new Set(
    existingPlans.flatMap((plan) => plan.includedLineIds)
  )
  const newPlans = params.prepared.flatMap((plan) => {
    const includedLineIds = plan.includedLineIds.filter(
      (lineId) => !coveredLineIds.has(lineId)
    )
    if (includedLineIds.length === 0) return []
    includedLineIds.forEach((lineId) => coveredLineIds.add(lineId))
    const primaryLineId =
      includedLineIds.find(
        (lineId) => lineById.get(lineId)?.lineType === "WORK"
      ) ?? null
    return [
      {
        ...plan,
        id: `task-plan-amend-unassigned-${includedLineIds.join("-")}`,
        includedLineIds,
        primaryLineId,
      },
    ]
  })

  return [...existingPlans, ...newPlans].map((plan, index) => ({
    ...plan,
    sortOrder: (index + 1) * 10,
  }))
}

export function RepairEstimateCompletionDialog({
  open,
  draft,
  pending,
  error,
  mode = "COMPLETE",
  initialMovementRequired,
  initialTaskPlans,
  onOpenChange,
  onComplete,
}: RepairEstimateCompletionDialogProps) {
  return (
    <RepairWorkCompletionDialog
      open={open}
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
      previewKey={`${mode}:${initialTaskPlans?.map((plan) => `${plan.id}:${plan.sortOrder}`).join("|") ?? "new"}`}
      allowEmpty
      initialMovementRequired={initialMovementRequired}
      reconcileInitialPlans={
        mode === "AMEND" && initialTaskPlans
          ? (prepared) =>
              amendmentInitialPlans({
                draft,
                stored: initialTaskPlans,
                prepared,
              })
          : undefined
      }
      onOpenChange={onOpenChange}
      onComplete={onComplete}
    />
  )
}
