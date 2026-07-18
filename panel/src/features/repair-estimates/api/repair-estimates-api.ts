import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { HttpMaintenanceRepairEstimatesAdapter } from "@/features/repair-estimates/adapters/http-maintenance-repair-estimates-adapter"
import { panelEstimateRentalItemsClient } from "@/features/repair-estimates/adapters/panel-estimate-rental-items-client"
import {
  assertEstimateLinesValid,
  buildRepairEstimateTaskPlans,
  finalizeTaskPlans,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  AmendCompletedRepairEstimateInput,
  CompleteRepairEstimateInput,
  EstimateRentalItemSearchQuery,
  RepairEstimateDraftCommand,
  RepairEstimateEditorDraft,
  RepairEstimateStatus,
  RepairEstimateTaskPlanCommandDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RepairEstimatesClient } from "@/features/repair-estimates/ports/repair-estimates-client"

export const REPAIR_ESTIMATES_QUERY_KEY = ["repair-estimates"] as const
export const ESTIMATE_RENTAL_ITEMS_QUERY_KEY = [
  "repair-estimates",
  "rental-items",
] as const

const repairEstimatesClient: RepairEstimatesClient =
  new HttpMaintenanceRepairEstimatesAdapter(panelEstimateRentalItemsClient)

const INITIAL_RENTAL_ITEM_PAGE_SIZE = 100

export function repairEstimateListQueryKey(
  warehouseId: string,
  status: RepairEstimateStatus
) {
  return [...REPAIR_ESTIMATES_QUERY_KEY, "list", warehouseId, status] as const
}

export function repairEstimateDetailQueryKey(
  warehouseId: string,
  estimateId: string | null
) {
  return [
    ...REPAIR_ESTIMATES_QUERY_KEY,
    "detail",
    warehouseId,
    estimateId,
  ] as const
}

export function estimateRentalItemsQueryKey(warehouseId: string) {
  return [...ESTIMATE_RENTAL_ITEMS_QUERY_KEY, warehouseId] as const
}

export function listRepairEstimates(
  warehouseId: string,
  status: RepairEstimateStatus
) {
  return repairEstimatesClient.list({ warehouseId, status })
}

export function getRepairEstimate(estimateId: string, warehouseId: string) {
  return repairEstimatesClient.getById(estimateId, warehouseId)
}

export async function listEstimateRentalItems(warehouseId: string) {
  const page = await panelEstimateRentalItemsClient.search({
    warehouseId,
    search: "",
    page: 0,
    size: INITIAL_RENTAL_ITEM_PAGE_SIZE,
  })
  return page.items
}

export function searchEstimateRentalItems(
  query: EstimateRentalItemSearchQuery
) {
  return panelEstimateRentalItemsClient.search(query)
}

export function resolveEstimateRentalItem(
  warehouseId: string,
  rentalItemId: string
) {
  return panelEstimateRentalItemsClient.resolveById(warehouseId, rentalItemId)
}

function buildDraftCommand(params: {
  draft: RepairEstimateEditorDraft
  warehouseId: string
}): RepairEstimateDraftCommand {
  assertEstimateLinesValid(params.draft.lines)
  if (params.draft.pendingUploads.length > 0 || params.draft.media.length > 0) {
    throw new Error(
      "Фото для смет временно недоступны: media-service ещё не подтверждает владельца MAINTENANCE_ESTIMATE."
    )
  }
  return {
    estimateId: params.draft.estimateId,
    expectedVersion: params.draft.expectedVersion,
    warehouseId: params.warehouseId,
    rentalItemId: params.draft.rentalItemId,
    sourceParty: params.draft.sourceParty,
    destinationText: null,
    dispatchDate: params.draft.dispatchDate,
    comment: "",
    lines: params.draft.lines.map((line) => ({ ...line })),
    media: [],
    maintenanceMediaReferences: params.draft.maintenanceMediaReferences ?? [],
  }
}

function toTaskPlanCommand(
  plan: RepairEstimateTaskPlanDto
): RepairEstimateTaskPlanCommandDto {
  return {
    id: plan.id,
    kind: plan.kind,
    includedLineIds: [...plan.includedLineIds],
    primaryLineId: plan.primaryLineId,
    groupComment: plan.groupComment,
    queueId: plan.queueId ?? null,
    queueCode: plan.queueCode,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    generationStatus: plan.generationStatus,
  }
}

export function saveRepairEstimateDraft(params: {
  draft: RepairEstimateEditorDraft
  warehouseId: string
}) {
  return repairEstimatesClient.saveDraft(buildDraftCommand(params))
}

export async function prepareRepairEstimateCompletion(
  draft: RepairEstimateEditorDraft
) {
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  return {
    catalog,
    issues: validateAutoCompletion(draft.lines, catalog),
    taskPlans: buildRepairEstimateTaskPlans(draft.lines, catalog),
  }
}

function completionPlans(input: CompleteRepairEstimateInput) {
  const emptyEstimate = input.draft.lines.length === 0
  return getOperationalRepairEstimateCatalog().then((snapshot) => {
    const catalog = createRepairEstimateCatalogIndex(snapshot)
    const autoIssues = validateAutoCompletion(input.draft.lines, catalog)
    if (input.completionMode === "AUTO" && autoIssues.length > 0) {
      throw new Error(autoIssues.slice(0, 3).join("; "))
    }
    const basePlans = emptyEstimate
      ? []
      : input.completionMode === "AUTO"
        ? buildRepairEstimateTaskPlans(input.draft.lines, catalog)
        : input.taskPlans
    return finalizeTaskPlans({
      plans: basePlans,
      completionMode: emptyEstimate ? "MANUAL" : input.completionMode,
      movementRequired: emptyEstimate ? false : input.movementRequired,
    })
  })
}

export async function completeRepairEstimate(
  input: CompleteRepairEstimateInput
) {
  const taskPlans = await completionPlans(input)
  return repairEstimatesClient.complete({
    ...buildDraftCommand(input),
    completionMode:
      input.draft.lines.length === 0 ? "MANUAL" : input.completionMode,
    movementRequired:
      input.draft.lines.length === 0 ? false : input.movementRequired,
    taskPlans: taskPlans.map(toTaskPlanCommand),
  })
}

export async function amendCompletedRepairEstimate(
  input: AmendCompletedRepairEstimateInput
) {
  if (input.draft.estimateId === null || input.draft.expectedVersion === null) {
    throw new Error("Для дополнения нужна сохранённая завершённая смета")
  }
  if (!input.reason.trim()) {
    throw new Error("Укажите причину дополнения сметы")
  }
  const taskPlans = await completionPlans(input)
  return repairEstimatesClient.amendCompleted({
    ...buildDraftCommand(input),
    estimateId: input.draft.estimateId,
    expectedVersion: input.draft.expectedVersion,
    expectedLinkedRepairVersion: input.expectedTaskVersion,
    reason: input.reason,
    completionMode:
      input.draft.lines.length === 0 ? "MANUAL" : input.completionMode,
    movementRequired:
      input.draft.lines.length === 0 ? false : input.movementRequired,
    taskPlans: taskPlans.map(toTaskPlanCommand),
  })
}
