import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
  type RepairEstimateCatalogIndex,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogSnapshotDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import {
  amendMaintenanceEstimate,
  completeMaintenanceEstimate,
  createMaintenanceEstimate,
  createMaintenanceIdempotencyKey,
  getMaintenanceEstimate,
  listMaintenanceEstimates,
  replaceMaintenanceEstimate,
  type MaintenanceEstimate,
  type MaintenanceEstimateLineInput,
  type MaintenanceEstimateWrite,
  type MaintenancePlanStageInput,
  type MaintenanceRoutingSnapshot,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  currentMaintenanceAccessToken,
  type MaintenanceAccessTokenProvider,
} from "@/features/repair-estimates/api/maintenance-auth"
import type {
  AmendCompletedRepairEstimateCommand,
  CompleteRepairEstimateCommand,
  RepairEstimateDraftCommand,
  RepairEstimateDto,
  RepairEstimateLineDto,
  RepairEstimateSummaryDto,
  RepairEstimateTaskPlanCommandDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"
import type { RepairEstimatesClient } from "@/features/repair-estimates/ports/repair-estimates-client"
import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import { ApiError } from "@/lib/api-client"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function canonicalUuid(value: string) {
  return UUID_PATTERN.test(value) ? value : createMaintenanceIdempotencyKey()
}

function currentRevision(estimate: MaintenanceEstimate) {
  const revision = estimate.revisions.find(
    (candidate) => candidate.revision === estimate.currentRevision
  )
  if (!revision) {
    throw new Error("Сервис ремонта вернул смету без текущей ревизии.")
  }
  return revision
}

function toEstimateLine(
  value: MaintenanceEstimate["revisions"][number]["lines"][number],
  customQueueBinding: RepairEstimateLineDto["customQueueBinding"] = null
): RepairEstimateLineDto {
  const quantity = Number(value.quantity)
  const rework =
    value.disposition && value.lineageRootLineId
      ? {
          disposition: value.disposition,
          sourceRepairId: value.sourceRepairId ?? null,
          sourceLineId: value.sourceLineId ?? null,
          lineageRootLineId: value.lineageRootLineId,
        }
      : null
  return {
    id: value.id,
    sourceLineKey: value.id,
    lineType: value.lineType,
    description: value.description,
    lineComment: value.lineType === "WORK" ? (value.comment ?? "") : "",
    unit: value.unit ?? "",
    quantity: Number.isFinite(quantity) ? quantity : 0,
    normativeMinutes: value.normativeMinutes,
    unitPrice: value.unitPrice,
    lineTotal: value.lineTotal,
    catalogSnapshot: value.catalogSnapshot
      ? {
          nodeId: value.catalogSnapshot.nodeId,
          name: value.catalogSnapshot.name,
          nodeType:
            value.catalogSnapshot.nodeType === "WORK"
              ? "WORK"
              : value.catalogSnapshot.nodeType === "OPTION"
                ? "OPTION"
                : "MATERIAL",
          furnitureEquipment: value.catalogSnapshot.furnitureEquipment ?? null,
          characteristic: value.catalogSnapshot.characteristic ?? null,
        }
      : null,
    customQueueBinding: value.catalogSnapshot ? null : customQueueBinding,
    maintenanceMediaReferences: value.mediaReferences,
    rework,
  }
}

function customQueueBindingFromPlanStage(
  stage: MaintenancePlanStageInput
): NonNullable<RepairEstimateLineDto["customQueueBinding"]> | null {
  if (
    stage.kind !== "REPAIR_WORK" ||
    !stage.routing.queueId.trim() ||
    !stage.routing.queueName.trim() ||
    (stage.routing.queueType !== "REPAIR" &&
      stage.routing.queueType !== "HOLDING")
  ) {
    return null
  }
  return {
    queueId: stage.routing.queueId,
    queueName: stage.routing.queueName,
    queueKind: stage.routing.queueType,
  }
}

function customQueueBindingsByLineId(plan: MaintenancePlanStageInput[]) {
  const bindings = new Map<
    string,
    NonNullable<RepairEstimateLineDto["customQueueBinding"]>
  >()
  plan.forEach((stage) => {
    const binding = customQueueBindingFromPlanStage(stage)
    if (!binding) return
    stage.includedLineIds.forEach((lineId) => {
      if (!bindings.has(lineId)) bindings.set(lineId, binding)
    })
  })
  return bindings
}

function toEstimatePlan(
  value: MaintenancePlanStageInput
): RepairEstimateTaskPlanDto {
  return {
    id: value.id,
    kind: value.kind,
    includedLineIds: [...value.includedLineIds],
    primaryLineId: value.primaryLineId,
    groupComment: value.groupComment,
    queueId: value.routing.queueId,
    queueName: value.routing.queueName,
    routeQueueKind:
      value.routing.queueType === "MOVEMENT" ||
      value.routing.queueType === "REPAIR" ||
      value.routing.queueType === "HOLDING"
        ? value.routing.queueType
        : null,
    sortOrder: value.order,
    generationStatus: "UNKNOWN",
    workflowRequestRef: null,
  }
}

async function toEstimateDto(
  estimate: MaintenanceEstimate,
  rentalItemsClient: EstimateRentalItemsClient,
  deliveryState?: RepairEstimateDto["deliveryState"]
): Promise<RepairEstimateDto> {
  const revision = currentRevision(estimate)
  const customQueueBindings = customQueueBindingsByLineId(revision.plan)
  const rentalItem = await rentalItemsClient.resolveById(
    estimate.warehouseId,
    estimate.rentalItemId
  )
  return {
    id: estimate.id,
    version: estimate.version,
    status: estimate.lifecycle,
    warehouseId: estimate.warehouseId,
    rentalItemId: estimate.rentalItemId,
    cabinNumber: rentalItem?.number ?? "",
    authorName: estimate.actor.actorId,
    sourceParty: revision.sourceParty ?? "",
    dispatchDate: revision.dispatchDate,
    comment: "",
    totalAmount: revision.total,
    lines: revision.lines.map((line) =>
      toEstimateLine(line, customQueueBindings.get(line.id) ?? null)
    ),
    media: [],
    maintenanceMediaReferences: estimate.mediaReferences,
    coverMediaId: estimate.coverMediaId,
    repairId: estimate.repairId,
    completionMode: null,
    movementRequired: revision.plan.some(
      (stage) => stage.kind !== "REPAIR_WORK"
    ),
    taskPlans: revision.plan
      .map(toEstimatePlan)
      .sort((left, right) => left.sortOrder - right.sortOrder),
    createdAt: estimate.createdAt,
    updatedAt: revision.recordedAt,
    deliveryState,
  }
}

function toSummary(estimate: RepairEstimateDto): RepairEstimateSummaryDto {
  return {
    id: estimate.id,
    version: estimate.version,
    status: estimate.status,
    warehouseId: estimate.warehouseId,
    rentalItemId: estimate.rentalItemId,
    cabinNumber: estimate.cabinNumber,
    authorName: estimate.authorName,
    sourceParty: estimate.sourceParty,
    dispatchDate: estimate.dispatchDate,
    totalAmount: estimate.totalAmount,
    createdAt: estimate.createdAt,
    updatedAt: estimate.updatedAt,
  }
}

function requireDispatchDate(value: string | null) {
  if (!value) throw new Error("Укажите дату осмотра.")
  return value
}

function requireExpectedVersion(value: number | null) {
  if (value === null) {
    throw new Error("Для изменения сметы требуется версия сервера.")
  }
  return value
}

function routeFromPlan(
  plan: RepairEstimateTaskPlanCommandDto,
  queues: WorkQueueDto[],
  includesCustomWork: boolean,
  stageNumber: number
): MaintenanceRoutingSnapshot {
  if (
    includesCustomWork &&
    (plan.kind !== "REPAIR_WORK" ||
      (plan.routeQueueKind !== "REPAIR" && plan.routeQueueKind !== "HOLDING"))
  ) {
    throw new Error(
      "Пользовательская работа может быть направлена только в очередь ремонта или ожидания."
    )
  }
  const active = queues.filter((queue) => queue.active && !queue.hidden)
  const exactByDefinitionId = plan.queueId
    ? active.find((queue) => queue.definitionId === plan.queueId)
    : undefined
  const movementCandidates =
    !plan.queueId &&
    plan.kind !== "REPAIR_WORK" &&
    plan.routeQueueKind === "MOVEMENT"
      ? active.filter((queue) => queue.type === "MOVEMENT")
      : []
  const exact =
    exactByDefinitionId ??
    (movementCandidates.length === 1 ? movementCandidates[0] : undefined)
  if (exact) {
    if (
      includesCustomWork &&
      exact.type !== "REPAIR" &&
      exact.type !== "HOLDING"
    ) {
      throw new Error(
        `Пользовательская работа не может быть направлена в очередь «${exact.name}».`
      )
    }
    if (exact.type === "FURNITURE_MOVEMENT") {
      throw new Error(
        `Очередь «${exact.name}» предназначена для перемещения мебели и не может маршрутизировать этап ремонта.`
      )
    }
    return {
      queueId: exact.definitionId,
      queueName: exact.name,
      queueType: exact.type,
    }
  }
  if (movementCandidates.length > 1) {
    throw new Error(
      "Для перемещения подключите к складу ровно одну активную очередь типа «Перемещение»."
    )
  }
  if (
    !plan.queueId &&
    plan.kind !== "REPAIR_WORK" &&
    plan.routeQueueKind === "MOVEMENT"
  ) {
    throw new Error(
      "Для выбранного склада ещё не материализована активная очередь типа «Перемещение». Проверьте «Настройки доски задач → Каталог очередей» и синхронизацию склада."
    )
  }
  if (plan.queueName?.trim()) {
    throw new Error(
      `Очередь «${plan.queueName.trim()}» не материализована для выбранного склада или отключена. Проверьте «Настройки доски задач → Каталог очередей» и синхронизацию склада.`
    )
  }
  throw new Error(
    `Для рабочего этапа №${stageNumber} не определена доступная очередь. Проверьте привязку категории в «Конструкторе каталога смет» и «Настройки доски задач → Каталог очередей».`
  )
}

function catalogSnapshot(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogSnapshotDto,
  catalogIndex: RepairEstimateCatalogIndex
) {
  const nodeId = line.catalogSnapshot?.nodeId
  if (!nodeId) return null
  const node = catalog.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) {
    throw new Error(`Позиция каталога «${line.description}» больше недоступна.`)
  }
  const effectiveRouting = catalogIndex.getEffectiveQueueBinding(node.id)
  const routing =
    effectiveRouting?.queueId &&
    effectiveRouting.queueName &&
    effectiveRouting.queueKind
      ? {
          queueId: effectiveRouting.queueId,
          queueName: effectiveRouting.queueName,
          queueType: effectiveRouting.queueKind,
        }
      : null
  return {
    catalogVersionId: node.catalogVersionId,
    nodeId: node.id,
    nodeType: node.nodeType,
    name: node.name,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes ?? 0,
    routing,
    furnitureEquipment: node.furnitureEquipment ?? null,
    forcesCapitalRepair: node.forcesCapitalRepair,
    characteristic: node.characteristic,
  }
}

function toLineInput(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogSnapshotDto,
  catalogIndex: RepairEstimateCatalogIndex
): MaintenanceEstimateLineInput {
  if (!line.description.trim()) {
    throw new Error("Укажите описание каждой строки сметы.")
  }
  const snapshot = catalogSnapshot(line, catalog, catalogIndex)
  const lineType = snapshot
    ? snapshot.nodeType === "WORK"
      ? "WORK"
      : "MATERIAL"
    : line.lineType
  const unit = snapshot ? snapshot.unit : line.unit?.trim() || null
  if (!snapshot && !unit) {
    throw new Error("Укажите единицу измерения пользовательской строки.")
  }
  const normativeMinutes = snapshot
    ? snapshot.durationMinutes
    : lineType === "MATERIAL"
      ? 0
      : line.normativeMinutes
  if (
    !snapshot &&
    lineType === "WORK" &&
    (!Number.isInteger(normativeMinutes) ||
      normativeMinutes === undefined ||
      normativeMinutes <= 0)
  ) {
    throw new Error("Укажите время выполнения пользовательской работы.")
  }
  return {
    id: canonicalUuid(line.id),
    catalogSnapshot: snapshot,
    lineType,
    description: line.description.trim(),
    unit,
    quantity: String(line.quantity),
    normativeMinutes,
    unitPrice: line.unitPrice,
    comment: lineType === "WORK" ? line.lineComment.trim() || null : null,
    mediaReferences:
      lineType === "WORK" ? (line.maintenanceMediaReferences ?? []) : [],
  }
}

async function serviceWrite(
  accessToken: string,
  command:
    | RepairEstimateDraftCommand
    | CompleteRepairEstimateCommand
    | AmendCompletedRepairEstimateCommand
): Promise<MaintenanceEstimateWrite> {
  if (command.media.length > 0) {
    throw new Error(
      "Фото для maintenance пока недоступны: media-service не подтвердил владельца MAINTENANCE_ESTIMATE."
    )
  }
  const maintenanceMediaReferences = command.maintenanceMediaReferences ?? []
  if (maintenanceMediaReferences.length > 0 && !command.coverMediaId) {
    throw new Error("Выберите титульную фотографию.")
  }
  if (
    command.coverMediaId &&
    !maintenanceMediaReferences.some(
      (reference) => reference.mediaId === command.coverMediaId
    )
  ) {
    throw new Error("Титульная фотография отсутствует среди готовых фото.")
  }
  const taskPlans = "taskPlans" in command ? (command.taskPlans ?? []) : []
  const [catalog, queues] = await Promise.all([
    getOperationalRepairEstimateCatalog(),
    taskPlans.length > 0
      ? taskBoardSettingsClient.listQueues(accessToken, command.warehouseId)
      : Promise.resolve([]),
  ])
  const catalogIndex = createRepairEstimateCatalogIndex(catalog)
  const lines = command.lines.map((line) =>
    toLineInput(line, catalog, catalogIndex)
  )
  const commandLineById = new Map(command.lines.map((line) => [line.id, line]))
  const lineIdByCommandId = new Map(
    command.lines.map((line, index) => [line.id, lines[index].id])
  )
  const maintenanceLineId = (lineId: string) => {
    const mapped = lineIdByCommandId.get(lineId)
    if (!mapped) {
      throw new Error(`Этап ссылается на отсутствующую строку ${lineId}.`)
    }
    return mapped
  }
  return {
    dispatchDate: requireDispatchDate(command.dispatchDate),
    sourceParty: command.sourceParty.trim() || null,
    lines,
    plan: taskPlans.map((plan, index) => ({
      id: canonicalUuid(plan.id),
      kind: plan.kind,
      order: index,
      routing: routeFromPlan(
        plan,
        queues,
        plan.includedLineIds.some((lineId) => {
          const line = commandLineById.get(lineId)
          return line?.catalogSnapshot === null && line.lineType === "WORK"
        }),
        index + 1
      ),
      includedLineIds: plan.includedLineIds.map(maintenanceLineId),
      primaryLineId: plan.primaryLineId
        ? maintenanceLineId(plan.primaryLineId)
        : null,
      groupComment: plan.groupComment,
      taskDeadline: null,
    })),
    mediaReferences: maintenanceMediaReferences,
    coverMediaId: command.coverMediaId ?? null,
  }
}

export class HttpMaintenanceRepairEstimatesAdapter implements RepairEstimatesClient {
  private readonly rentalItemsClient: EstimateRentalItemsClient
  private readonly tokenProvider: MaintenanceAccessTokenProvider

  constructor(
    rentalItemsClient: EstimateRentalItemsClient,
    tokenProvider: MaintenanceAccessTokenProvider = currentMaintenanceAccessToken
  ) {
    this.rentalItemsClient = rentalItemsClient
    this.tokenProvider = tokenProvider
  }

  async list(query: { warehouseId: string; status?: "DRAFT" | "COMPLETED" }) {
    const accessToken = await this.tokenProvider()
    const page = await listMaintenanceEstimates(
      accessToken,
      query.warehouseId,
      query.status
    )
    const estimates = await Promise.all(
      page.items.map((estimate) =>
        toEstimateDto(estimate, this.rentalItemsClient)
      )
    )
    return estimates.map(toSummary)
  }

  async getById(id: string, warehouseId: string) {
    const accessToken = await this.tokenProvider()
    try {
      return await toEstimateDto(
        await getMaintenanceEstimate(accessToken, warehouseId, id),
        this.rentalItemsClient
      )
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  async saveDraft(command: RepairEstimateDraftCommand) {
    const accessToken = await this.tokenProvider()
    const write = await serviceWrite(accessToken, command)
    const estimate = command.estimateId
      ? await replaceMaintenanceEstimate(
          accessToken,
          command.warehouseId,
          command.estimateId,
          requireExpectedVersion(command.expectedVersion),
          write
        )
      : await createMaintenanceEstimate(
          accessToken,
          createMaintenanceIdempotencyKey(),
          {
            warehouseId: command.warehouseId,
            rentalItemId: command.rentalItemId,
            ...write,
          }
        )
    return toEstimateDto(estimate, this.rentalItemsClient)
  }

  async complete(command: CompleteRepairEstimateCommand) {
    const accessToken = await this.tokenProvider()
    const write = await serviceWrite(accessToken, command)
    const draft = command.estimateId
      ? await replaceMaintenanceEstimate(
          accessToken,
          command.warehouseId,
          command.estimateId,
          requireExpectedVersion(command.expectedVersion),
          write
        )
      : await createMaintenanceEstimate(
          accessToken,
          createMaintenanceIdempotencyKey(),
          {
            warehouseId: command.warehouseId,
            rentalItemId: command.rentalItemId,
            ...write,
          }
        )
    try {
      const result = await completeMaintenanceEstimate(
        accessToken,
        command.warehouseId,
        draft.id,
        draft.version,
        command.priority,
        createMaintenanceIdempotencyKey(),
        command.logisticsPlanningMode,
        command.logisticsScheduledDate
      )
      return toEstimateDto(
        result.estimate,
        this.rentalItemsClient,
        result.delivery.state
      )
    } catch (error) {
      throw new Error(
        `Черновик ${draft.id} сохранён, но завершение не выполнено: ${error instanceof Error ? error.message : "неизвестная ошибка"}`,
        { cause: error }
      )
    }
  }

  async amendCompleted(command: AmendCompletedRepairEstimateCommand) {
    const accessToken = await this.tokenProvider()
    if (!command.reason.trim()) {
      throw new Error("Укажите причину дополнения сметы.")
    }
    const write = await serviceWrite(accessToken, command)
    const result = await amendMaintenanceEstimate(
      accessToken,
      command.warehouseId,
      command.estimateId,
      createMaintenanceIdempotencyKey(),
      {
        expectedVersion: command.expectedVersion,
        expectedLinkedRepairVersion: command.expectedLinkedRepairVersion,
        reason: command.reason.trim(),
        ...write,
      }
    )
    return toEstimateDto(
      result.estimate,
      this.rentalItemsClient,
      result.delivery.state
    )
  }
}
