import type {
  InventoryCatalogPlanLine,
  InventoryManualPlanLine,
  InventoryMediaReference,
  InventoryPlanSelection,
} from "@/features/inventory/model/inventory-service"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
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
  taskPlans: RepairEstimateTaskPlanDto[]
  lines: RepairEstimateLineDto[]
  media: InventoryMediaReference[]
}): Exclude<InventoryPlanSelection, null> {
  if (input.movementRequired) {
    throw new Error(
      "Для перемещения не задан маршрут локаций. Сохраните осмотр без перемещения."
    )
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
    return { mode: "AUTO", lines: catalogLines, stages: [] }
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
  return {
    mode: "MANUAL",
    lines,
    stages: unique.map((catalogNodeId, order) => ({
      catalogNodeId,
      kind: "REPAIR_WORK",
      order,
    })),
  }
}
