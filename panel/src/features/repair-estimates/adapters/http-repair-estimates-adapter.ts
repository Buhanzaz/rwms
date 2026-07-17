import {
  amendMaintenanceEstimate,
  completeMaintenanceEstimate,
  createMaintenanceEstimate,
  getMaintenanceEstimate,
  listMaintenanceEstimates,
  replaceMaintenanceEstimate,
} from "@/features/maintenance/api/maintenance-api"
import { withIdempotencyKey } from "@/features/maintenance/maintenance-runtime"
import {
  toCanonicalMedia,
  toEstimateLineInput,
  toPlanStageInput,
  toViewEstimate,
} from "@/features/maintenance/adapters/maintenance-view-mappers"
import type {
  AmendCompletedRepairEstimateCommand,
  CompleteRepairEstimateCommand,
  RepairEstimateDraftCommand,
  RepairEstimateTaskPlanCommandDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RepairEstimatesClient } from "@/features/repair-estimates/ports/repair-estimates-client"

function dispatchDate(value: string | null) {
  if (!value) throw new Error("Укажите дату прибытия.")
  return value
}

async function createBody(command: RepairEstimateDraftCommand) {
  return {
    warehouseId: command.warehouseId,
    rentalItemId: command.rentalItemId,
    dispatchDate: dispatchDate(command.dispatchDate),
    sourceParty: command.sourceParty.trim() || null,
    lines: await Promise.all(command.lines.map(toEstimateLineInput)),
    plan: [],
    mediaReferences: toCanonicalMedia(command.media),
  }
}

async function replacementBody(
  command: RepairEstimateDraftCommand,
  expectedVersion: number,
  plans: RepairEstimateTaskPlanCommandDto[] = []
) {
  return {
    expectedVersion,
    dispatchDate: dispatchDate(command.dispatchDate),
    sourceParty: command.sourceParty.trim() || null,
    lines: await Promise.all(command.lines.map(toEstimateLineInput)),
    plan: await Promise.all(
      plans.map((plan, index) => toPlanStageInput(plan, index))
    ),
    mediaReferences: toCanonicalMedia(command.media),
  }
}

async function saveDraft(command: RepairEstimateDraftCommand) {
  if (command.estimateId === null) {
    const body = await createBody(command)
    return withIdempotencyKey("maintenance.estimate.create", body, (key) =>
      createMaintenanceEstimate(key, body)
    )
  }
  if (command.expectedVersion === null) {
    throw new Error("Для изменения сметы нужна её актуальная версия.")
  }
  return replaceMaintenanceEstimate(
    command.warehouseId,
    command.estimateId,
    await replacementBody(command, command.expectedVersion)
  )
}

async function persistBeforeCompletion(command: CompleteRepairEstimateCommand) {
  if (command.estimateId === null) {
    const create = {
      ...(await createBody(command)),
      plan: await Promise.all(
        command.taskPlans.map((plan, index) => toPlanStageInput(plan, index))
      ),
    }
    return withIdempotencyKey("maintenance.estimate.create", create, (key) =>
      createMaintenanceEstimate(key, create)
    )
  }
  if (command.expectedVersion === null) {
    throw new Error("Для завершения сметы нужна её актуальная версия.")
  }
  return replaceMaintenanceEstimate(
    command.warehouseId,
    command.estimateId,
    await replacementBody(command, command.expectedVersion, command.taskPlans)
  )
}

export const httpRepairEstimatesAdapter: RepairEstimatesClient = {
  async list(query) {
    const page = await listMaintenanceEstimates(query.warehouseId, query.status)
    return page.items.map(toViewEstimate)
  },

  async getById(id, warehouseId) {
    return toViewEstimate(await getMaintenanceEstimate(warehouseId, id))
  },

  async saveDraft(command) {
    return toViewEstimate(await saveDraft(command))
  },

  async complete(command) {
    const saved = await persistBeforeCompletion(command)
    const intent = {
      estimateId: saved.id,
      expectedVersion: saved.version,
    }
    const result = await withIdempotencyKey(
      "maintenance.estimate.complete",
      intent,
      (key) =>
        completeMaintenanceEstimate(
          saved.warehouseId,
          saved.id,
          key,
          saved.version
        )
    )
    return toViewEstimate(result.estimate)
  },

  async amendCompleted(command: AmendCompletedRepairEstimateCommand) {
    const body = {
      expectedVersion: command.expectedVersion,
      expectedLinkedRepairVersion: command.expectedLinkedRepairVersion,
      dispatchDate: dispatchDate(command.dispatchDate),
      reason: command.amendmentReason,
      sourceParty: command.sourceParty.trim() || null,
      lines: await Promise.all(command.lines.map(toEstimateLineInput)),
      plan: await Promise.all(
        command.taskPlans.map((plan, index) => toPlanStageInput(plan, index))
      ),
      mediaReferences: toCanonicalMedia(command.media),
    }
    const intent = {
      estimateId: command.estimateId,
      ...body,
    }
    const result = await withIdempotencyKey(
      "maintenance.estimate.amend",
      intent,
      (key) =>
        amendMaintenanceEstimate(
          command.warehouseId,
          command.estimateId,
          key,
          body
        )
    )
    return toViewEstimate(result.estimate)
  },
}
