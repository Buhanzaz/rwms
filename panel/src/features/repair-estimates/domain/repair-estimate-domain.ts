import type { RepairEstimateCatalogIndex } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  EstimateRentalItemOptionDto,
  MoneyDecimal,
  RepairEstimateCompletionMode,
  RepairEstimateDto,
  RepairEstimateEditorDraft,
  RepairEstimateLineDto,
  RepairEstimateLineType,
  RepairEstimateTaskPlanCommandDto,
  RepairEstimateTaskPlanKind,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"

function createOpaqueId(prefix: string) {
  void prefix
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID()
  }
  throw new Error("Браузер не поддерживает безопасные UUID.")
}

function decimalToMinor(value: MoneyDecimal) {
  const normalized = value.trim().replace(",", ".")
  const match = /^(\d+)(?:\.(\d{0,2}))?$/.exec(normalized)

  if (!match) {
    throw new Error(
      "Цена должна быть неотрицательным числом с точностью до копеек"
    )
  }

  const whole = BigInt(match[1])
  const fraction = BigInt((match[2] ?? "").padEnd(2, "0"))
  return whole * 100n + fraction
}

function minorToDecimal(value: bigint): MoneyDecimal {
  const whole = value / 100n
  const fraction = (value % 100n).toString().padStart(2, "0")
  return `${whole}.${fraction}`
}

function normalizeQuantity(value: number) {
  if (!Number.isFinite(value) || value < 0) return 0
  return Math.round(value * 1000) / 1000
}

function quantityToThousandths(value: number) {
  return BigInt(Math.round(normalizeQuantity(value) * 1000))
}

export function normalizeMoney(value: MoneyDecimal): MoneyDecimal {
  return minorToDecimal(decimalToMinor(value || "0"))
}

export function calculateLineTotal(
  unitPrice: MoneyDecimal,
  quantity: number
): MoneyDecimal {
  const product =
    decimalToMinor(unitPrice || "0") * quantityToThousandths(quantity)
  return minorToDecimal((product + 500n) / 1000n)
}

export function normalizeEstimateLine(
  line: RepairEstimateLineDto
): RepairEstimateLineDto {
  const quantity = normalizeQuantity(line.quantity)
  const unitPrice = normalizeMoney(line.unitPrice || "0")

  return {
    ...line,
    sourceLineKey: line.sourceLineKey.trim(),
    lineType: line.lineType ?? "WORK",
    description: line.description ?? "",
    lineComment: line.lineComment ?? "",
    unit: line.unit.trim() || "ед",
    quantity,
    unitPrice,
    lineTotal: calculateLineTotal(unitPrice, quantity),
  }
}

export function assertEstimateLinesValid(lines: RepairEstimateLineDto[]) {
  const sourceLineKeys = new Set<string>()
  const lineIds = new Set<string>()

  lines.forEach((line, index) => {
    const label = `Строка ${index + 1}`
    const sourceLineKey = line.sourceLineKey.trim()
    if (!sourceLineKey) {
      throw new Error(`${label}: не задан sourceLineKey`)
    }
    if (sourceLineKeys.has(sourceLineKey)) {
      throw new Error(`${label}: sourceLineKey должен быть уникальным`)
    }
    sourceLineKeys.add(sourceLineKey)

    if (!line.id || lineIds.has(line.id)) {
      throw new Error(`${label}: идентификатор строки должен быть уникальным`)
    }
    lineIds.add(line.id)

    if (
      !Number.isFinite(line.quantity) ||
      line.quantity < 0 ||
      Math.round(line.quantity * 1000) !== line.quantity * 1000
    ) {
      throw new Error(
        `${label}: количество должно быть неотрицательным числом с точностью до трёх знаков`
      )
    }
    decimalToMinor(line.unitPrice)
  })
}

export function assertTaskPlansValid(
  plans: RepairEstimateTaskPlanCommandDto[],
  lines: RepairEstimateLineDto[]
) {
  const lineIds = new Set(lines.map((line) => line.id))
  const planIds = new Set<string>()
  plans.forEach((plan, index) => {
    const label = `План ${index + 1}`
    if (!plan.id || planIds.has(plan.id)) {
      throw new Error(`${label}: идентификатор плана должен быть уникальным`)
    }
    planIds.add(plan.id)
    if (plan.kind !== "REPAIR_WORK") {
      if (
        !["MOVE_TO_REPAIR", "MOVE_FROM_REPAIR"].includes(plan.kind) ||
        plan.primaryLineId !== null ||
        plan.includedLineIds.length !== 0
      ) {
        throw new Error(`${label}: состав плана перемещения некорректен`)
      }
      return
    }
    if (
      plan.includedLineIds.length === 0 ||
      new Set(plan.includedLineIds).size !== plan.includedLineIds.length ||
      plan.includedLineIds.some((lineId) => !lineIds.has(lineId))
    ) {
      throw new Error(`${label}: состав строк плана некорректен`)
    }
    const includedLines = plan.includedLineIds
      .map((lineId) => lines.find((line) => line.id === lineId))
      .filter((line): line is RepairEstimateLineDto => Boolean(line))
    if (!plan.primaryLineId) {
      if (includedLines.some((line) => line.lineType === "WORK")) {
        throw new Error(`${label}: не задана основная строка работы`)
      }
      return
    }
    if (
      !lineIds.has(plan.primaryLineId) ||
      !plan.includedLineIds.includes(plan.primaryLineId)
    ) {
      throw new Error(`${label}: основная строка не принадлежит смете`)
    }
    const primaryLine = lines.find((line) => line.id === plan.primaryLineId)
    if (primaryLine?.lineType !== "WORK") {
      throw new Error(`${label}: основной строкой должна быть работа`)
    }
  })
}

export function calculateEstimateTotal(lines: RepairEstimateLineDto[]) {
  return minorToDecimal(
    lines
      .map(normalizeEstimateLine)
      .reduce((total, line) => total + decimalToMinor(line.lineTotal), 0n)
  )
}

export function formatMoneyDecimal(value: MoneyDecimal) {
  try {
    const minor = decimalToMinor(value)
    const whole = (minor / 100n)
      .toString()
      .replace(/\B(?=(\d{3})+(?!\d))/g, "\u00a0")
    const fraction = (minor % 100n).toString().padStart(2, "0")
    return `${whole},${fraction}`
  } catch {
    return value
  }
}

export function createManualEstimateLine(): RepairEstimateLineDto {
  const id = createOpaqueId("estimate-line")
  return {
    id,
    sourceLineKey: id,
    lineType: "WORK",
    description: "",
    lineComment: "",
    unit: "ед",
    quantity: 1,
    unitPrice: "0.00",
    lineTotal: "0.00",
    catalogSnapshot: null,
  }
}

/** Browser-local calendar date; warehouse time-zone ownership remains backend work. */
export function toLocalCalendarDateValue(date: Date) {
  const year = date.getFullYear().toString().padStart(4, "0")
  const month = (date.getMonth() + 1).toString().padStart(2, "0")
  const day = date.getDate().toString().padStart(2, "0")
  return `${year}-${month}-${day}`
}

export function createNewEstimateDraft(): RepairEstimateEditorDraft {
  return {
    estimateId: null,
    expectedVersion: null,
    rentalItemId: "",
    sourceParty: "",
    destinationParty: "",
    dispatchDate: toLocalCalendarDateValue(new Date()),
    comment: "",
    lines: [],
    media: [],
    pendingUploads: [],
  }
}

export function applyEstimateRentalItemSelection(
  draft: RepairEstimateEditorDraft,
  rentalItem: Pick<
    EstimateRentalItemOptionDto,
    "id" | "counterparty" | "arrivalDate"
  >
): RepairEstimateEditorDraft {
  return {
    ...draft,
    rentalItemId: rentalItem.id,
    sourceParty: rentalItem.counterparty ?? "",
    dispatchDate: rentalItem.arrivalDate ?? null,
  }
}

export function toEstimateEditorDraft(
  estimate: RepairEstimateDto
): RepairEstimateEditorDraft {
  return {
    estimateId: estimate.id,
    expectedVersion: estimate.version,
    rentalItemId: estimate.rentalItemId,
    sourceParty: estimate.sourceParty,
    destinationParty:
      estimate.destinationText ?? estimate.destinationParty ?? "",
    dispatchDate: estimate.dispatchDate,
    comment: estimate.comment,
    lines: estimate.lines.map((line) => ({ ...line })),
    media: estimate.media.map((media) => ({
      ...media,
      variants: {
        small: { ...media.variants.small },
        largeWebp: { ...media.variants.largeWebp },
      },
    })),
    pendingUploads: [],
  }
}

function mergeLineComments(current: string, incoming: string) {
  const values = [...current.split(";"), ...incoming.split(";")]
    .map((value) => value.trim())
    .filter(Boolean)
  return Array.from(new Set(values)).join("; ")
}

function nodeLineType(
  node: RepairEstimateCatalogNodeDto
): RepairEstimateLineType {
  return node.nodeType === "WORK" ? "WORK" : "MATERIAL"
}

function nodeMoney(node: RepairEstimateCatalogNodeDto) {
  return normalizeMoney(node.unitPrice ?? "0")
}

export function applyCatalogNodesToEstimateLines(params: {
  lines: RepairEstimateLineDto[]
  nodes: RepairEstimateCatalogNodeDto[]
  quantity: number
  comment: string
  locationTitle?: string | null
}) {
  let nextLines = params.lines.map((line) => normalizeEstimateLine(line))
  const quantity = Math.max(1, Math.trunc(params.quantity || 1))

  params.nodes.forEach((node) => {
    if (
      !node.includeInEstimate ||
      !["WORK", "MATERIAL", "OPTION"].includes(node.nodeType)
    ) {
      return
    }

    const description =
      node.nodeType === "WORK" && params.locationTitle
        ? `${node.name} ${params.locationTitle}`
        : node.name
    const existingIndex = nextLines.findIndex(
      (line) =>
        line.catalogSnapshot?.code === node.code &&
        line.description.trim() === description.trim()
    )

    if (existingIndex >= 0) {
      nextLines = nextLines.map((line, index) => {
        if (index !== existingIndex) {
          return line
        }

        return normalizeEstimateLine({
          ...line,
          quantity: line.quantity + quantity,
          lineComment: mergeLineComments(line.lineComment, params.comment),
        })
      })
      return
    }

    const id = createOpaqueId("estimate-line")
    nextLines = [
      ...nextLines,
      normalizeEstimateLine({
        id,
        sourceLineKey: id,
        lineType: nodeLineType(node),
        description,
        lineComment: params.comment.trim(),
        unit: node.unit?.trim() || "ед",
        quantity,
        unitPrice: nodeMoney(node),
        lineTotal: "0.00",
        catalogSnapshot: {
          nodeId: node.id,
          code: node.code,
          name: node.name,
          nodeType:
            node.nodeType === "WORK"
              ? "WORK"
              : node.nodeType === "OPTION"
                ? "OPTION"
                : "MATERIAL",
          furnitureEquipment: node.furnitureEquipment ?? null,
        },
      }),
    ]
  })

  return nextLines
}

export function getRepairEstimateCatalogQuantityError(
  node: RepairEstimateCatalogNodeDto,
  quantity: number
) {
  if (node.nodeType !== "MATERIAL" || node.furnitureEquipment === null) {
    return null
  }

  return Number.isInteger(quantity) && quantity > 0
    ? null
    : "Количество мебели должно быть целым положительным числом"
}

function lineQueueBinding(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogIndex
) {
  const nodeId = line.catalogSnapshot?.nodeId
  return nodeId ? catalog.getEffectiveQueueBinding(nodeId) : null
}

function commentsForLines(lines: RepairEstimateLineDto[]) {
  return Array.from(
    new Set(lines.map((line) => line.lineComment.trim()).filter(Boolean))
  ).join("; ")
}

export function buildRepairEstimateTaskPlans(
  lines: RepairEstimateLineDto[],
  catalog: RepairEstimateCatalogIndex
): RepairEstimateTaskPlanDto[] {
  const groups: Array<{
    queueId: string | null
    queueCode: string | null
    routeQueueKind: RepairEstimateTaskPlanDto["routeQueueKind"]
    bindingKey: string
    lines: RepairEstimateLineDto[]
  }> = []
  let current: (typeof groups)[number] | null = null

  lines.forEach((line) => {
    if (line.lineType === "WORK") {
      const binding = lineQueueBinding(line, catalog)
      const queueId = binding?.queueId ?? null
      const queueCode = binding?.queueCode ?? null
      const routeQueueKind = binding?.queueKind ?? null
      const bindingKey = queueCode
        ? `QUEUE:${queueCode}`
        : routeQueueKind
          ? `KIND:${routeQueueKind}`
          : "UNBOUND"
      if (!current || current.bindingKey !== bindingKey) {
        current = {
          queueId,
          queueCode,
          routeQueueKind,
          bindingKey,
          lines: [line],
        }
        groups.push(current)
      } else {
        current.lines.push(line)
      }
      return
    }

    current?.lines.push(line)
  })

  const plans: RepairEstimateTaskPlanDto[] = groups.map((group, index) => {
    const primaryLine = group.lines.find((line) => line.lineType === "WORK")!
    return {
      id: createOpaqueId("task-plan"),
      kind: "REPAIR_WORK",
      includedLineIds: group.lines.map((line) => line.id),
      primaryLineId: primaryLine.id,
      groupComment: commentsForLines(group.lines),
      queueId: group.queueId,
      queueCode: group.queueCode,
      routeQueueKind: group.routeQueueKind,
      sortOrder: (index + 1) * 10,
      generationStatus: "PENDING_GENERATION",
      workflowRequestRef: null,
    }
  })
  const assignedLineIds = new Set(
    groups.flatMap((group) => group.lines.map((line) => line.id))
  )
  const unassignedLines = lines.filter((line) => !assignedLineIds.has(line.id))
  let currentStandalone: (typeof groups)[number] | null = null
  unassignedLines.forEach((line) => {
    const binding =
      line.lineType === "MATERIAL" ? lineQueueBinding(line, catalog) : null
    const queueId = binding?.queueId ?? null
    const queueCode = binding?.queueCode ?? null
    const routeQueueKind = binding?.queueKind ?? null
    const bindingKey = queueCode
      ? `QUEUE:${queueCode}`
      : routeQueueKind
        ? `KIND:${routeQueueKind}`
        : "UNBOUND"

    if (!currentStandalone || currentStandalone.bindingKey !== bindingKey) {
      currentStandalone = {
        queueId,
        queueCode,
        routeQueueKind,
        bindingKey,
        lines: [line],
      }
      groups.push(currentStandalone)
      return
    }

    currentStandalone.lines.push(line)
  })

  groups.slice(plans.length).forEach((group) => {
    plans.push({
      id: createOpaqueId("task-plan-standalone"),
      kind: "REPAIR_WORK",
      includedLineIds: group.lines.map((line) => line.id),
      primaryLineId: null,
      groupComment: commentsForLines(group.lines),
      queueId: group.queueId,
      queueCode: group.queueCode,
      routeQueueKind: group.routeQueueKind,
      sortOrder: (plans.length + 1) * 10,
      generationStatus: "PENDING_GENERATION",
      workflowRequestRef: null,
    })
  })
  return plans
}

export function createRepairEstimateMovementTaskPlan(
  kind: Exclude<RepairEstimateTaskPlanKind, "REPAIR_WORK">
): RepairEstimateTaskPlanDto {
  return {
    id: createOpaqueId(
      kind === "MOVE_TO_REPAIR" ? "move-to-repair" : "move-from-repair"
    ),
    kind,
    includedLineIds: [],
    primaryLineId: null,
    groupComment: "",
    queueId: null,
    queueCode: null,
    routeQueueKind: "MOVEMENT",
    sortOrder: 0,
    generationStatus: "PENDING_GENERATION",
    workflowRequestRef: null,
  }
}

export function applyRepairEstimateMovementPlans(params: {
  plans: RepairEstimateTaskPlanDto[]
  movementRequired: boolean
  movementPlans?: RepairEstimateTaskPlanDto[]
}) {
  const workPlans = params.plans.filter((plan) => plan.kind === "REPAIR_WORK")
  if (!params.movementRequired) {
    return workPlans.map((plan, index) => ({
      ...plan,
      sortOrder: (index + 1) * 10,
    }))
  }

  const candidates = [...params.plans, ...(params.movementPlans ?? [])]
  const moveTo =
    candidates.find((plan) => plan.kind === "MOVE_TO_REPAIR") ??
    createRepairEstimateMovementTaskPlan("MOVE_TO_REPAIR")
  const moveFrom =
    candidates.find((plan) => plan.kind === "MOVE_FROM_REPAIR") ??
    createRepairEstimateMovementTaskPlan("MOVE_FROM_REPAIR")
  return [moveTo, ...workPlans, moveFrom].map((plan, index) => ({
    ...plan,
    sortOrder: (index + 1) * 10,
  }))
}

export function validateAutoCompletion(
  lines: RepairEstimateLineDto[],
  catalog: RepairEstimateCatalogIndex
) {
  return lines.flatMap((line, index) => {
    if (line.lineType !== "WORK") {
      return []
    }

    if (!line.catalogSnapshot?.nodeId) {
      return [`Строка ${index + 1}: узел каталога не найден`]
    }

    if (!catalog.nodesById.has(line.catalogSnapshot.nodeId)) {
      return [`Строка ${index + 1}: узел каталога не найден`]
    }

    if (!catalog.getEffectiveQueueBinding(line.catalogSnapshot.nodeId)) {
      return [`Строка ${index + 1}: не задан маршрут очереди`]
    }

    return []
  })
}

export function finalizeTaskPlans(params: {
  plans: RepairEstimateTaskPlanDto[]
  completionMode: RepairEstimateCompletionMode
  movementRequired?: boolean
}) {
  const plans = params.movementRequired
    ? params.plans.some((plan) => plan.kind !== "REPAIR_WORK")
      ? params.plans
      : applyRepairEstimateMovementPlans({
          plans: params.plans,
          movementRequired: true,
        })
    : params.plans.filter((plan) => plan.kind === "REPAIR_WORK")

  return plans.map((plan, index) => {
    const queueCode = plan.queueCode?.trim() || null

    return {
      ...plan,
      queueCode,
      includedLineIds: [...plan.includedLineIds],
      sortOrder: (index + 1) * 10,
      generationStatus: "PENDING_GENERATION" as const,
      workflowRequestRef: null,
    }
  })
}
