import { getOperationalRepairEstimateCatalog } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
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

function lineType(
  value: MaintenanceEstimate["revisions"][number]["lines"][number]
): RepairEstimateLineDto["lineType"] {
  if (value.catalogSnapshot?.nodeType === "WORK") return "WORK"
  if (
    value.catalogSnapshot?.nodeType === "MATERIAL" ||
    value.catalogSnapshot?.nodeType === "OPTION"
  ) {
    return "MATERIAL"
  }
  return "UNSPECIFIED"
}

function toEstimateLine(
  value: MaintenanceEstimate["revisions"][number]["lines"][number]
): RepairEstimateLineDto {
  const quantity = Number(value.quantity)
  return {
    id: value.id,
    sourceLineKey: value.id,
    lineType: lineType(value),
    description: value.description,
    lineComment: value.comment ?? "",
    unit: value.catalogSnapshot?.unit ?? "",
    quantity: Number.isFinite(quantity) ? quantity : 0,
    unitPrice: value.unitPrice,
    lineTotal: value.lineTotal,
    catalogSnapshot: value.catalogSnapshot
      ? {
          nodeId: value.catalogSnapshot.nodeId,
          code: value.catalogSnapshot.code,
          name: value.catalogSnapshot.name,
          nodeType:
            value.catalogSnapshot.nodeType === "WORK"
              ? "WORK"
              : value.catalogSnapshot.nodeType === "OPTION"
                ? "OPTION"
                : "MATERIAL",
        }
      : null,
    maintenanceMediaReferences: value.mediaReferences,
  }
}

function toEstimatePlan(
  value: MaintenancePlanStageInput
): RepairEstimateTaskPlanDto {
  return {
    id: value.id,
    kind: value.kind,
    includedLineIds: [],
    primaryLineId: null,
    groupComment: "",
    queueId: value.routing.queueId,
    queueCode: value.routing.queueCode,
    routeQueueKind:
      value.routing.queueKind === "MOVEMENT" ||
      value.routing.queueKind === "REPAIR" ||
      value.routing.queueKind === "HOLDING"
        ? value.routing.queueKind
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
    destinationText: null,
    destinationParty: null,
    dispatchDate: revision.dispatchDate,
    comment: "",
    totalAmount: revision.total,
    lines: revision.lines.map(toEstimateLine),
    media: [],
    maintenanceMediaReferences: estimate.mediaReferences,
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
    destinationText: null,
    destinationParty: null,
    dispatchDate: estimate.dispatchDate,
    totalAmount: estimate.totalAmount,
    createdAt: estimate.createdAt,
    updatedAt: estimate.updatedAt,
  }
}

function requireDispatchDate(value: string | null) {
  if (!value) throw new Error("Укажите дату прибытия.")
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
  queues: WorkQueueDto[]
): MaintenanceRoutingSnapshot {
  const active = queues.filter((queue) => queue.active && !queue.hidden)
  const exact = plan.queueId
    ? active.find((queue) => queue.id === plan.queueId)
    : plan.queueCode
      ? active.find((queue) => queue.code === plan.queueCode)
      : null
  if (exact) {
    return {
      queueId: exact.id,
      queueCode: exact.code,
      queueKind: exact.type,
    }
  }

  const byKind = plan.routeQueueKind
    ? active.filter((queue) => queue.type === plan.routeQueueKind)
    : []
  if (byKind.length === 1) {
    return {
      queueId: byKind[0].id,
      queueCode: byKind[0].code,
      queueKind: byKind[0].type,
    }
  }

  throw new Error(
    byKind.length > 1
      ? `Для этапа ${plan.sortOrder} выберите конкретную очередь.`
      : `Для этапа ${plan.sortOrder} не настроена активная очередь.`
  )
}

function catalogSnapshot(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogSnapshotDto
) {
  const nodeId = line.catalogSnapshot?.nodeId
  if (!nodeId) return null
  const node = catalog.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) {
    throw new Error(`Позиция каталога «${line.description}» больше недоступна.`)
  }
  const routing =
    node.workQueueId && node.workQueueCode && node.routeQueueKind
      ? {
          queueId: node.workQueueId,
          queueCode: node.workQueueCode,
          queueKind: node.routeQueueKind,
        }
      : null
  return {
    catalogVersionId: node.catalogVersionId,
    nodeId: node.id,
    code: node.code,
    nodeType: node.nodeType,
    name: node.name,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes ?? 0,
    routing,
  }
}

function toLineInput(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogSnapshotDto
): MaintenanceEstimateLineInput {
  if (!line.description.trim()) {
    throw new Error("Укажите описание каждой строки сметы.")
  }
  return {
    id: canonicalUuid(line.id),
    catalogSnapshot: catalogSnapshot(line, catalog),
    description: line.description.trim(),
    quantity: String(line.quantity),
    unitPrice: line.unitPrice,
    comment: line.lineComment.trim() || null,
    mediaReferences: line.maintenanceMediaReferences ?? [],
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
  const taskPlans = "taskPlans" in command ? command.taskPlans : []
  const [catalog, queues] = await Promise.all([
    getOperationalRepairEstimateCatalog(),
    taskPlans.length > 0
      ? taskBoardSettingsClient.listQueues(accessToken, command.warehouseId)
      : Promise.resolve([]),
  ])
  return {
    dispatchDate: requireDispatchDate(command.dispatchDate),
    sourceParty: command.sourceParty.trim() || null,
    lines: command.lines.map((line) => toLineInput(line, catalog)),
    plan: taskPlans.map((plan, index) => ({
      id: canonicalUuid(plan.id),
      kind: plan.kind,
      order: index,
      routing: routeFromPlan(plan, queues),
      taskDeadline: null,
    })),
    mediaReferences: command.maintenanceMediaReferences ?? [],
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

  async list(query: { warehouseId: string; status: "DRAFT" | "COMPLETED" }) {
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
        createMaintenanceIdempotencyKey()
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
