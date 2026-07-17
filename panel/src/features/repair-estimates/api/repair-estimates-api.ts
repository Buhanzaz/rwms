import {
  createRepairEstimateCatalogIndex,
  getOperationalMaintenanceCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import { httpRepairEstimatesAdapter } from "@/features/repair-estimates/adapters/http-repair-estimates-adapter"
import { LocalStorageRepairEstimatesAdapter } from "@/features/repair-estimates/adapters/local-storage-repair-estimates-adapter"
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
  RepairEstimateDto,
  RepairEstimateEditorDraft,
  RepairEstimateMediaRotationDegrees,
  RepairEstimateStatus,
  RepairEstimateTaskPlanCommandDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RepairEstimateWorkflowClient } from "@/features/repair-estimates/ports/repair-estimate-workflow-client"
import type { RepairEstimatesClient } from "@/features/repair-estimates/ports/repair-estimates-client"
import {
  createRepairTaskFromCompletedEstimate,
  getRepairTaskSnapshotBySourceEstimateId,
  syncRepairTaskFromCompletedEstimate,
} from "@/features/repair-tasks/api/repair-tasks-api"
import {
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
  updateRentalItemStatusForRepairWorkflow,
} from "@/features/rental-items/api/rental-items-api"
import { isLinkedReturnEstimate } from "@/features/logistics/api/logistics-api"
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"

export const REPAIR_ESTIMATES_QUERY_KEY = ["repair-estimates"] as const
export const ESTIMATE_RENTAL_ITEMS_QUERY_KEY = [
  "repair-estimates",
  "rental-items",
] as const

const fixtureMediaClient = new IndexedDbRepairEstimateMediaAdapter()
const localRepairEstimatesAdapter = new LocalStorageRepairEstimatesAdapter(
  panelEstimateRentalItemsClient
)
const repairEstimatesClient: RepairEstimatesClient =
  DEV_MAINTENANCE_FIXTURES_ENABLED
    ? localRepairEstimatesAdapter
    : httpRepairEstimatesAdapter
const repairEstimateWorkflowClient: RepairEstimateWorkflowClient =
  localRepairEstimatesAdapter

let repairCycleCompletionQueue: Promise<void> = Promise.resolve()
const REPAIR_CYCLE_COMPLETION_LOCK_NAME = "rwms:repair-cycle:completion"

function runRepairCycleCompletion<T>(operation: () => Promise<T>) {
  const withLock = () => {
    if (typeof navigator !== "undefined" && navigator.locks) {
      return navigator.locks.request(
        REPAIR_CYCLE_COMPLETION_LOCK_NAME,
        operation
      )
    }
    return operation()
  }
  const result = repairCycleCompletionQueue.then(withLock, withLock)
  repairCycleCompletionQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

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

export async function getRepairEstimate(
  estimateId: string,
  warehouseId: string
) {
  const estimate = await repairEstimatesClient.getById(estimateId, warehouseId)
  if (!estimate) {
    return null
  }

  return hydrateEstimateMedia(estimate)
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

export function getRepairEstimateWorkflowRequestStatus(
  warehouseId: string,
  requestId: string
) {
  return repairEstimateWorkflowClient.getRequestStatus({
    warehouseId,
    requestId,
  })
}

export function updateRepairEstimateMediaRotation(
  mediaId: string,
  rotationDegrees: RepairEstimateMediaRotationDegrees
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    throw new Error(
      "Поворот медиа недоступен: защищённый HTTP runtime media-service ещё не подключён."
    )
  }
  return fixtureMediaClient.updateRotation(mediaId, rotationDegrees)
}

function buildDraftCommand(params: {
  draft: RepairEstimateEditorDraft
  warehouseId: string
  media: RepairEstimateDraftCommand["media"]
}): RepairEstimateDraftCommand {
  assertEstimateLinesValid(params.draft.lines)

  return {
    estimateId: params.draft.estimateId,
    expectedVersion: params.draft.expectedVersion,
    warehouseId: params.warehouseId,
    rentalItemId: params.draft.rentalItemId,
    sourceParty: params.draft.sourceParty,
    destinationText: params.draft.destinationParty,
    dispatchDate: params.draft.dispatchDate,
    comment: params.draft.comment,
    lines: params.draft.lines.map((line) => ({ ...line })),
    media: DEV_MAINTENANCE_FIXTURES_ENABLED
      ? fixtureMediaClient.dehydrate(params.media)
      : params.media,
  }
}

async function hydrateEstimateMedia(estimate: RepairEstimateDto) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return estimate
  return {
    ...estimate,
    media: await fixtureMediaClient.hydrate(estimate.media),
  }
}

async function hydrateCommittedEstimateMedia(estimate: RepairEstimateDto) {
  try {
    return await hydrateEstimateMedia(estimate)
  } catch {
    // Persistence already committed. Return durable refs and let a later detail
    // query retry media hydration instead of reporting the mutation as failed.
    return estimate
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
    queueCode: plan.queueCode,
    queueId: plan.queueId,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    generationStatus: plan.generationStatus,
  }
}

async function persistWithMedia<T extends RepairEstimateDto>(params: {
  draft: RepairEstimateEditorDraft
  warehouseId: string
  persist: (media: RepairEstimateDraftCommand["media"]) => Promise<T>
}) {
  assertEstimateLinesValid(params.draft.lines)

  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    if (params.draft.pendingUploads.length > 0) {
      throw new Error(
        "Загрузка медиа недоступна: защищённый HTTP runtime media-service ещё не подключён."
      )
    }
    return params.persist(params.draft.media)
  }

  const existing = params.draft.estimateId
    ? await repairEstimatesClient.getById(
        params.draft.estimateId,
        params.warehouseId
      )
    : null
  const uploaded = await fixtureMediaClient.upload(params.draft.pendingUploads)
  const media = fixtureMediaClient.dehydrate([
    ...params.draft.media,
    ...uploaded,
  ])
  let saved: T

  try {
    saved = await params.persist(media)
  } catch (error) {
    try {
      await fixtureMediaClient.discard(uploaded.map((item) => item.id))
    } catch {
      // Compensation is best-effort; preserve the persistence error.
    }
    throw error
  }

  const committedIds = new Set(saved.media.map((item) => item.id))
  const removedIds =
    existing?.media
      .map((item) => item.id)
      .filter((id) => !committedIds.has(id)) ?? []
  try {
    await fixtureMediaClient.discard(removedIds)
  } catch {
    // Cleanup is post-commit and must not turn a successful save into failure.
  }

  return hydrateCommittedEstimateMedia(saved)
}

export function saveRepairEstimateDraft(params: {
  draft: RepairEstimateEditorDraft
  warehouseId: string
}) {
  return persistWithMedia({
    draft: params.draft,
    warehouseId: params.warehouseId,
    persist: (media) =>
      repairEstimatesClient.saveDraft(buildDraftCommand({ ...params, media })),
  })
}

export async function prepareRepairEstimateCompletion(
  draft: RepairEstimateEditorDraft,
  warehouseId: string
) {
  const snapshot = await getOperationalMaintenanceCatalog(warehouseId)
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  return {
    catalog,
    issues: validateAutoCompletion(draft.lines, catalog),
    taskPlans: buildRepairEstimateTaskPlans(draft.lines, catalog),
  }
}

export async function completeRepairEstimate(
  input: CompleteRepairEstimateInput
) {
  const snapshot = await getOperationalMaintenanceCatalog(input.warehouseId)
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  const autoIssues = validateAutoCompletion(input.draft.lines, catalog)
  if (input.completionMode === "AUTO" && autoIssues.length > 0) {
    throw new Error(autoIssues.slice(0, 3).join("; "))
  }

  const emptyEstimate = input.draft.lines.length === 0
  const basePlans = emptyEstimate
    ? []
    : input.completionMode === "AUTO"
      ? buildRepairEstimateTaskPlans(input.draft.lines, catalog)
      : input.taskPlans
  const taskPlans = finalizeTaskPlans({
    plans: basePlans,
    completionMode: input.completionMode,
    movementRequired: emptyEstimate ? false : input.movementRequired,
  })

  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return persistWithMedia({
      draft: input.draft,
      warehouseId: input.warehouseId,
      persist: (media) =>
        repairEstimatesClient.complete({
          ...buildDraftCommand({
            draft: input.draft,
            warehouseId: input.warehouseId,
            media,
          }),
          completionMode: emptyEstimate ? "MANUAL" : input.completionMode,
          movementRequired: emptyEstimate ? false : input.movementRequired,
          taskPlans: taskPlans.map(toTaskPlanCommand),
        }),
    })
  }

  const linkedReturnEstimate = input.draft.estimateId
    ? await isLinkedReturnEstimate(
        input.warehouseId,
        input.draft.rentalItemId,
        input.draft.estimateId
      )
    : false

  return persistWithMedia({
    draft: input.draft,
    warehouseId: input.warehouseId,
    persist: (media) =>
      runRepairCycleCompletion(async () => {
        const previous = input.draft.estimateId
          ? await repairEstimatesClient.getById(
              input.draft.estimateId,
              input.warehouseId
            )
          : null
        let saved: RepairEstimateDto | null = null
        try {
          saved = await repairEstimatesClient.complete({
            ...buildDraftCommand({
              draft: input.draft,
              warehouseId: input.warehouseId,
              media,
            }),
            completionMode: emptyEstimate ? "MANUAL" : input.completionMode,
            movementRequired: emptyEstimate ? false : input.movementRequired,
            taskPlans: taskPlans.map(toTaskPlanCommand),
          })

          if (emptyEstimate) {
            await updateRentalItemStatusForRepairWorkflow({
              rentalItemId: saved.rentalItemId,
              warehouseId: saved.warehouseId,
              status: "FREE",
              allowedSourceStatuses: [
                ...RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
                ...(linkedReturnEstimate
                  ? (["WAITING_ESTIMATE_CONFIRMATION"] as const)
                  : []),
              ],
            })
          } else {
            await createRepairTaskFromCompletedEstimate({
              estimate: saved,
              taskPlans,
              allowWaitingEstimateConfirmation: linkedReturnEstimate,
            })
          }
          return saved
        } catch (error) {
          if (saved) {
            try {
              await localRepairEstimatesAdapter.restoreAfterWorkflowFailure(
                previous,
                saved.id,
                saved.version
              )
            } catch {
              throw new Error(
                "Не удалось согласованно завершить ремонтный цикл. Обновите данные перед повтором."
              )
            }
          }
          throw error
        }
      }),
  })
}

export async function amendCompletedRepairEstimate(
  input: AmendCompletedRepairEstimateInput
) {
  if (input.draft.estimateId === null || input.draft.expectedVersion === null) {
    throw new Error("Для дополнения нужна сохранённая завершённая смета")
  }

  const snapshot = await getOperationalMaintenanceCatalog(input.warehouseId)
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  const autoIssues = validateAutoCompletion(input.draft.lines, catalog)
  if (input.completionMode === "AUTO" && autoIssues.length > 0) {
    throw new Error(autoIssues.slice(0, 3).join("; "))
  }

  const emptyEstimate = input.draft.lines.length === 0
  const basePlans = emptyEstimate
    ? []
    : input.completionMode === "AUTO"
      ? buildRepairEstimateTaskPlans(input.draft.lines, catalog)
      : input.taskPlans
  const taskPlans = finalizeTaskPlans({
    plans: basePlans,
    completionMode: input.completionMode,
    movementRequired: emptyEstimate ? false : input.movementRequired,
  })

  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return persistWithMedia({
      draft: input.draft,
      warehouseId: input.warehouseId,
      persist: (media) =>
        repairEstimatesClient.amendCompleted({
          ...buildDraftCommand({
            draft: input.draft,
            warehouseId: input.warehouseId,
            media,
          }),
          estimateId: input.draft.estimateId!,
          expectedVersion: input.draft.expectedVersion!,
          expectedLinkedRepairVersion: input.expectedTaskVersion,
          amendmentReason: input.amendmentReason.trim(),
          completionMode: emptyEstimate ? "MANUAL" : input.completionMode,
          movementRequired: emptyEstimate ? false : input.movementRequired,
          taskPlans: taskPlans.map(toTaskPlanCommand),
        }),
    })
  }

  return persistWithMedia({
    draft: input.draft,
    warehouseId: input.warehouseId,
    persist: (media) =>
      runRepairCycleCompletion(async () => {
        const estimateId = input.draft.estimateId!
        const expectedVersion = input.draft.expectedVersion!
        const previous = await repairEstimatesClient.getById(
          estimateId,
          input.warehouseId
        )
        if (!previous || previous.status !== "COMPLETED") {
          throw new Error("Завершённая смета не найдена")
        }
        if (
          previous.version !== expectedVersion ||
          previous.rentalItemId !== input.draft.rentalItemId
        ) {
          throw new Error(
            "Смета была изменена. Обновите данные и повторите действие"
          )
        }

        const linkedTask = await getRepairTaskSnapshotBySourceEstimateId(
          previous.id,
          previous.warehouseId
        )
        if ((linkedTask?.version ?? null) !== input.expectedTaskVersion) {
          throw new Error(
            "Связанное задание было изменено. Обновите данные и повторите действие"
          )
        }
        if (linkedTask) {
          if (
            linkedTask.rentalItemId !== previous.rentalItemId ||
            linkedTask.status !== "QUEUED" ||
            linkedTask.startedAt !== null ||
            linkedTask.subtasks.some((subtask) => subtask.status !== "WAITING")
          ) {
            throw new Error("Начатое задание нельзя изменить из сметы")
          }
          if (emptyEstimate) {
            throw new Error(
              "Смету со связанным заданием нельзя дополнить до пустой"
            )
          }
        }

        let saved: RepairEstimateDto | null = null
        try {
          saved = await repairEstimatesClient.amendCompleted({
            ...buildDraftCommand({
              draft: input.draft,
              warehouseId: input.warehouseId,
              media,
            }),
            estimateId,
            expectedVersion,
            expectedLinkedRepairVersion: input.expectedTaskVersion,
            amendmentReason: input.amendmentReason.trim(),
            completionMode: emptyEstimate ? "MANUAL" : input.completionMode,
            movementRequired: emptyEstimate ? false : input.movementRequired,
            taskPlans: taskPlans.map(toTaskPlanCommand),
          })

          if (!emptyEstimate) {
            await syncRepairTaskFromCompletedEstimate({
              estimate: saved,
              taskPlans,
              expectedTaskVersion: input.expectedTaskVersion,
            })
          }
          return saved
        } catch (error) {
          if (saved) {
            try {
              await localRepairEstimatesAdapter.restoreAfterWorkflowFailure(
                previous,
                saved.id,
                saved.version
              )
            } catch {
              throw new Error(
                "Не удалось согласованно дополнить смету. Обновите данные перед повтором."
              )
            }
          }
          throw error
        }
      }),
  })
}
