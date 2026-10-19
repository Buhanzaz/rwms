import type {
  InventoryCatalogPlanLine,
  InventoryManualPlanLine,
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
  planComment: string | null,
  routingCatalogNodeId?: string
): InventoryCatalogPlanLine | InventoryManualPlanLine {
  const groupComment =
    line.lineType === "WORK"
      ? planComment?.trim() || line.lineComment.trim() || null
      : null
  if (line.catalogSnapshot) {
    return {
      aggregationKind: "CATALOG" as const,
      catalogNodeId: line.catalogSnapshot.nodeId,
      routingCatalogNodeId: null,
      description: null,
      type: null,
      unit: null,
      quantity: decimal(line.quantity),
      unitPriceMinor: null,
      normativeMinutes: null,
      groupComment,
      mediaReferences:
        line.lineType === "WORK"
          ? [...(line.maintenanceMediaReferences ?? [])]
          : [],
    }
  }
  if (line.lineType !== "WORK" && line.lineType !== "MATERIAL") {
    throw new Error("Для ручной позиции укажите тип работы или материала")
  }
  if (!routingCatalogNodeId) {
    throw new Error("Для ручной позиции выберите план ремонтных работ")
  }
  return {
    aggregationKind: "MANUAL" as const,
    catalogNodeId: null,
    routingCatalogNodeId,
    description: line.description.trim(),
    type: line.lineType,
    unit: line.unit.trim(),
    quantity: decimal(line.quantity),
    unitPriceMinor: minor(line.unitPrice),
    normativeMinutes: "0",
    groupComment,
    mediaReferences:
      line.lineType === "WORK"
        ? [...(line.maintenanceMediaReferences ?? [])]
        : [],
  }
}

export function buildInventoryPlanSelection(input: {
  completionMode: RepairEstimateCompletionMode
  movementToRepair: boolean
  forceCapitalRepair?: boolean
  logisticsPlanningMode: LogisticsPlanningMode | null
  logisticsScheduledDate: string | null
  priority: RepairPriority
  coverMediaId: string | null
  taskPlans: RepairEstimateTaskPlanDto[]
  lines: RepairEstimateLineDto[]
}): Exclude<InventoryPlanSelection, null> {
  if (input.movementToRepair && input.forceCapitalRepair === true) {
    throw new Error(
      "Перемещение на ремонт и капитальный ремонт нельзя выбрать одновременно"
    )
  }
  if (!input.movementToRepair) {
    if (
      input.logisticsPlanningMode !== null ||
      input.logisticsScheduledDate !== null
    ) {
      throw new Error(
        "Параметры логистической очереди доступны только для перемещения на ремонт."
      )
    }
  } else if (
    input.logisticsPlanningMode === null ||
    (input.logisticsPlanningMode === "AUTO" &&
      input.logisticsScheduledDate !== null) ||
    (input.logisticsPlanningMode === "FIXED_DATE" &&
      !input.logisticsScheduledDate)
  ) {
    throw new Error(
      "Дата логистического задания должна быть задана только при выборе конкретной даты."
    )
  }
  const repairWorkPlans = input.taskPlans.filter(
    (candidate) => candidate.kind === "REPAIR_WORK"
  )
  const planByIncludedLineId = new Map<string, RepairEstimateTaskPlanDto>()
  for (const plan of repairWorkPlans) {
    for (const lineId of plan.includedLineIds) {
      if (planByIncludedLineId.has(lineId)) {
        throw new Error("Строка не может входить в несколько планов работ")
      }
      planByIncludedLineId.set(lineId, plan)
    }
  }
  if (input.completionMode === "AUTO") {
    if (input.lines.some((line) => line.catalogSnapshot === null)) {
      throw new Error(
        "Автоматический режим доступен только для позиций каталога"
      )
    }
    const lines = input.lines.map((line) =>
      planLine(line, planByIncludedLineId.get(line.id)?.groupComment ?? null)
    )
    if (lines.some((line) => line.aggregationKind !== "CATALOG")) {
      throw new Error(
        "Автоматический режим доступен только для позиций каталога"
      )
    }
    const catalogLines = lines.filter(
      (line): line is InventoryCatalogPlanLine =>
        line.aggregationKind === "CATALOG"
    )
    return {
      mode: "AUTO",
      priority: input.priority,
      coverMediaId: input.coverMediaId,
      movementToRepair: input.movementToRepair,
      forceCapitalRepair: input.forceCapitalRepair === true,
      logisticsPlanningMode: input.logisticsPlanningMode,
      logisticsScheduledDate: input.logisticsScheduledDate,
      lines: catalogLines,
      stages: [],
    }
  }
  const lineById = new Map(input.lines.map((line) => [line.id, line]))
  const routingCatalogNodeIdByPlan = new Map<
    RepairEstimateTaskPlanDto,
    string
  >()
  for (const plan of repairWorkPlans) {
    if (plan.includedLineIds.some((lineId) => !lineById.has(lineId))) {
      throw new Error("План работ содержит строку, отсутствующую в смете")
    }
    const primary =
      plan.primaryLineId && plan.includedLineIds.includes(plan.primaryLineId)
        ? lineById.get(plan.primaryLineId)
        : undefined
    const catalogNodeId =
      plan.routingCatalogNodeId?.trim() ||
      (primary?.lineType === "WORK"
        ? primary.catalogSnapshot?.nodeId.trim()
        : undefined)
    if (!catalogNodeId) {
      throw new Error(
        "Для ручного этапа выберите основной вид работ из каталога"
      )
    }
    routingCatalogNodeIdByPlan.set(plan, catalogNodeId)
  }
  if (repairWorkPlans.length === 0) {
    throw new Error("Ручной план должен содержать хотя бы один этап работ")
  }
  const lines = input.lines.map((line) => {
    const plan = planByIncludedLineId.get(line.id)
    if (line.catalogSnapshot) {
      return planLine(line, plan?.groupComment ?? null)
    }
    return planLine(
      line,
      plan?.groupComment ?? null,
      plan ? routingCatalogNodeIdByPlan.get(plan) : undefined
    )
  })
  const workStages = repairWorkPlans.map((plan, index) => ({
    catalogNodeId: routingCatalogNodeIdByPlan.get(plan)!,
    kind: "REPAIR_WORK" as const,
    order: index,
  }))
  return {
    mode: "MANUAL",
    priority: input.priority,
    coverMediaId: input.coverMediaId,
    movementToRepair: input.movementToRepair,
    forceCapitalRepair: input.forceCapitalRepair === true,
    logisticsPlanningMode: input.logisticsPlanningMode,
    logisticsScheduledDate: input.logisticsScheduledDate,
    lines,
    stages: workStages,
  }
}
