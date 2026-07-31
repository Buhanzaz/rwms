import type {
  InventoryCatalogPlanLine,
  InventoryManualPlanLine,
  InventoryMediaReference,
  InventoryPlanSelection,
} from "@/features/inventory/model/inventory-service"
import type {
  LogisticsPlanningMode,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairPriority,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"

function decimal(value: number) {
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error("Количество в смете должно быть больше нуля")
  }
  return value.toString()
}

function minor(value: string) {
  const normalized = value.trim().replace(",", ".")
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) {
    throw new Error(`Некорректная цена: ${value}`)
  }
  return Math.round(Number(normalized) * 100)
}

function planLine(
  line: RepairEstimateLineDto,
  media: InventoryMediaReference[],
  planComment: string | null
): InventoryCatalogPlanLine | InventoryManualPlanLine {
  const groupComment = planComment?.trim() || line.lineComment.trim() || null
  if (line.catalogSnapshot) {
    return {
      aggregationKind: "CATALOG" as const,
      catalogNodeId: line.catalogSnapshot.nodeId,
      description: null,
      type: null,
      unit: null,
      quantity: decimal(line.quantity),
      unitPriceMinor: null,
      normativeMinutes: null,
      groupComment,
      mediaReferences: media,
    }
  }
  if (line.lineType !== "WORK" && line.lineType !== "MATERIAL") {
    throw new Error("Для ручной позиции укажите тип работы или материала")
  }
  return {
    aggregationKind: "MANUAL" as const,
    catalogNodeId: null,
    description: line.description.trim(),
    type: line.lineType,
    unit: line.unit.trim(),
    quantity: decimal(line.quantity),
    unitPriceMinor: minor(line.unitPrice),
    normativeMinutes: "0",
    groupComment,
    mediaReferences: media,
  }
}

export function buildInventoryPlanSelection(input: {
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  movementCatalogNodeId?: string | null
  priority: RepairPriority
  coverMediaId: string | null
  taskPlans: RepairEstimateTaskPlanDto[]
  lines: RepairEstimateLineDto[]
  media: InventoryMediaReference[]
}): Exclude<InventoryPlanSelection, null> {
  if (!input.movementRequired) {
    if (
      input.logisticsPlanningMode !== "AUTO" ||
      input.logisticsScheduledDate !== null
    ) {
      throw new Error(
        "Параметры логистической очереди недоступны без перемещения."
      )
    }
  } else if (
    (input.logisticsPlanningMode === "AUTO" &&
      input.logisticsScheduledDate !== null) ||
    (input.logisticsPlanningMode === "FIXED_DATE" &&
      !input.logisticsScheduledDate)
  ) {
    throw new Error(
      "Дата логистического задания должна быть задана только при выборе конкретной даты."
    )
  }
  if (input.movementRequired && !input.movementCatalogNodeId) {
    throw new Error("В каталоге не настроено расположение для перемещения.")
  }
  const planCommentByLine = new Map<string, string>()
  for (const plan of input.taskPlans.filter(
    (candidate) => candidate.kind === "REPAIR_WORK"
  )) {
    for (const lineId of plan.includedLineIds) {
      const existing = planCommentByLine.get(lineId)
      if (existing !== undefined && existing !== plan.groupComment) {
        throw new Error("Строка не может входить в несколько планов работ")
      }
      planCommentByLine.set(lineId, plan.groupComment)
    }
  }
  const lines = input.lines.map((line) =>
    planLine(line, input.media, planCommentByLine.get(line.id) ?? null)
  )
  if (input.completionMode === "AUTO") {
    if (lines.some((line) => line.aggregationKind !== "CATALOG")) {
      throw new Error(
        "Автоматический режим доступен только для позиций каталога"
      )
    }
    const catalogLines = lines.filter(
      (line): line is InventoryCatalogPlanLine =>
        line.aggregationKind === "CATALOG"
    )
    const autoWorkStageCount = input.lines.filter(
      (line) => line.lineType === "WORK"
    ).length
    const stages = input.movementRequired
      ? [
          {
            catalogNodeId: input.movementCatalogNodeId!,
            kind: "MOVE_TO_REPAIR" as const,
            order: 0,
          },
          {
            catalogNodeId: input.movementCatalogNodeId!,
            kind: "MOVE_FROM_REPAIR" as const,
            order: autoWorkStageCount + 1,
          },
        ]
      : []
    return {
      mode: "AUTO",
      priority: input.priority,
      coverMediaId: input.coverMediaId,
      logisticsPlanningMode: input.logisticsPlanningMode,
      logisticsScheduledDate: input.logisticsScheduledDate,
      lines: catalogLines,
      stages,
    }
  }
  const lineById = new Map(input.lines.map((line) => [line.id, line]))
  const selected = input.taskPlans
    .filter((plan) => plan.kind === "REPAIR_WORK")
    .map((plan) => {
      const primary = plan.primaryLineId
        ? lineById.get(plan.primaryLineId)
        : undefined
      const catalogNodeId = primary?.catalogSnapshot?.nodeId
      if (!catalogNodeId) {
        throw new Error(
          "Для ручного этапа выберите основной вид работ из каталога"
        )
      }
      return catalogNodeId
    })
  const unique = Array.from(new Set(selected))
  if (unique.length !== selected.length || unique.length === 0) {
    throw new Error("Ручной план должен содержать уникальные этапы работ")
  }
  const workStages = unique.map((catalogNodeId, index) => ({
    catalogNodeId,
    kind: "REPAIR_WORK" as const,
    order: index + (input.movementRequired ? 1 : 0),
  }))
  return {
    mode: "MANUAL",
    priority: input.priority,
    coverMediaId: input.coverMediaId,
    logisticsPlanningMode: input.logisticsPlanningMode,
    logisticsScheduledDate: input.logisticsScheduledDate,
    lines,
    stages: input.movementRequired
      ? [
          {
            catalogNodeId: input.movementCatalogNodeId!,
            kind: "MOVE_TO_REPAIR",
            order: 0,
          },
          ...workStages,
          {
            catalogNodeId: input.movementCatalogNodeId!,
            kind: "MOVE_FROM_REPAIR",
            order: workStages.length + 1,
          },
        ]
      : workStages,
  }
}
