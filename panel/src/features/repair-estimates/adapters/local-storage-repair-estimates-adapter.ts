import {
  assertEstimateLinesValid,
  assertTaskPlansValid,
  calculateEstimateTotal,
  normalizeEstimateLine,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  AmendCompletedRepairEstimateCommand,
  CompleteRepairEstimateCommand,
  RepairEstimateDraftCommand,
  RepairEstimateDto,
  RepairEstimateSummaryDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"
import type { RepairEstimatesClient } from "@/features/repair-estimates/ports/repair-estimates-client"
import type {
  RepairEstimateWorkflowClient,
  RepairEstimateWorkflowRequestQuery,
} from "@/features/repair-estimates/ports/repair-estimate-workflow-client"

export const REPAIR_ESTIMATES_MOCK_STORAGE_KEY = "rwms:repair-estimates:v1"
const CURRENT_MOCK_AUTHOR_NAME = "Текущий пользователь"
const UNKNOWN_AUTHOR_NAME = "Не указан"

type RepairEstimatesStorageEnvelope = {
  service: "repair-estimates"
  schemaVersion: 1
  revision: number
  estimates: RepairEstimateDto[]
}

function createOpaqueId(prefix: string) {
  const suffix =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${suffix}`
}

function emptyEnvelope(): RepairEstimatesStorageEnvelope {
  return {
    service: "repair-estimates",
    schemaVersion: 1,
    revision: 0,
    estimates: [],
  }
}

function readEnvelope() {
  const value = window.localStorage.getItem(REPAIR_ESTIMATES_MOCK_STORAGE_KEY)
  if (!value) {
    return emptyEnvelope()
  }

  try {
    const parsed = JSON.parse(value) as Partial<RepairEstimatesStorageEnvelope>
    if (
      parsed.service !== "repair-estimates" ||
      parsed.schemaVersion !== 1 ||
      !Array.isArray(parsed.estimates)
    ) {
      return emptyEnvelope()
    }
    return {
      ...(parsed as RepairEstimatesStorageEnvelope),
      estimates: parsed.estimates.map((estimate) =>
        normalizeStoredEstimate(estimate)
      ),
    }
  } catch {
    return emptyEnvelope()
  }
}

function writeEnvelope(envelope: RepairEstimatesStorageEnvelope) {
  window.localStorage.setItem(
    REPAIR_ESTIMATES_MOCK_STORAGE_KEY,
    JSON.stringify(envelope)
  )
}

let mutationQueue: Promise<void> = Promise.resolve()
const ESTIMATE_MUTATION_LOCK_NAME = "rwms:repair-estimates:mutation"

function runWithOriginMutationLock<T>(operation: () => Promise<T>) {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(ESTIMATE_MUTATION_LOCK_NAME, operation)
  }
  return operation()
}

function runSerializedMutation<T>(operation: () => Promise<T>) {
  const run = () => runWithOriginMutationLock(operation)
  const result = mutationQueue.then(run, run)
  mutationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function normalizeAuthorName(value: unknown) {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : UNKNOWN_AUTHOR_NAME
}

function normalizeStoredEstimate(value: unknown): RepairEstimateDto {
  const estimate = value as RepairEstimateDto
  return {
    ...estimate,
    taskPlans: (Array.isArray(estimate.taskPlans) ? estimate.taskPlans : [])
      .map((plan) => ({
        ...plan,
        kind: plan.kind ?? "REPAIR_WORK",
      }))
      .sort((left, right) => left.sortOrder - right.sortOrder),
  }
}

function cloneEstimate(estimate: RepairEstimateDto): RepairEstimateDto {
  return {
    ...structuredClone(estimate),
    authorName: normalizeAuthorName(estimate.authorName),
    taskPlans: estimate.taskPlans
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)
      .map((plan) => structuredClone(plan)),
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
    authorName: normalizeAuthorName(estimate.authorName),
    sourceParty: estimate.sourceParty,
    destinationText:
      estimate.destinationText ?? estimate.destinationParty ?? null,
    destinationParty: estimate.destinationParty,
    dispatchDate: estimate.dispatchDate,
    totalAmount: estimate.totalAmount,
    createdAt: estimate.createdAt,
    updatedAt: estimate.updatedAt,
  }
}

function validateDateOnly(value: string | null) {
  if (!value) {
    return
  }

  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) {
    throw new Error("Дата прибытия должна быть календарной датой")
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day
  ) {
    throw new Error("Дата прибытия должна быть календарной датой")
  }
}

export class LocalStorageRepairEstimatesAdapter
  implements RepairEstimatesClient, RepairEstimateWorkflowClient
{
  private readonly rentalItemsClient: EstimateRentalItemsClient

  constructor(rentalItemsClient: EstimateRentalItemsClient) {
    this.rentalItemsClient = rentalItemsClient
  }

  async list(query: { warehouseId: string; status: "DRAFT" | "COMPLETED" }) {
    return readEnvelope()
      .estimates.filter(
        (estimate) =>
          estimate.warehouseId === query.warehouseId &&
          estimate.status === query.status
      )
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
      .map(toSummary)
  }

  async getById(id: string, warehouseId: string) {
    const estimate = readEnvelope().estimates.find(
      (item) => item.id === id && item.warehouseId === warehouseId
    )
    return estimate ? cloneEstimate(estimate) : null
  }

  async getRequestStatus(query: RepairEstimateWorkflowRequestQuery) {
    for (const estimate of readEnvelope().estimates) {
      if (estimate.warehouseId !== query.warehouseId) {
        continue
      }
      for (const plan of estimate.taskPlans) {
        if (plan.workflowRequestRef?.id === query.requestId) {
          return { ...plan.workflowRequestRef }
        }
      }
    }
    return null
  }

  async saveDraft(command: RepairEstimateDraftCommand) {
    return runSerializedMutation(() => this.persist(command, "DRAFT"))
  }

  async complete(command: CompleteRepairEstimateCommand) {
    return runSerializedMutation(() => this.persist(command, "COMPLETED"))
  }

  async amendCompleted(command: AmendCompletedRepairEstimateCommand) {
    return runSerializedMutation(() => this.persist(command, "COMPLETED", true))
  }

  async restoreAfterWorkflowFailure(
    previous: RepairEstimateDto | null,
    savedEstimateId: string,
    expectedSavedVersion: number
  ) {
    return runSerializedMutation(async () => {
      const envelope = readEnvelope()
      const saved = envelope.estimates.find(
        (estimate) => estimate.id === savedEstimateId
      )
      if (!saved || saved.version !== expectedSavedVersion) {
        throw new Error("Смета изменилась после неудачной операции")
      }
      const tasksWithoutSaved = envelope.estimates.filter(
        (estimate) => estimate.id !== savedEstimateId
      )
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        estimates: previous
          ? [...tasksWithoutSaved, cloneEstimate(previous)]
          : tasksWithoutSaved,
      })
    })
  }

  private async persist(
    command:
      | RepairEstimateDraftCommand
      | CompleteRepairEstimateCommand
      | AmendCompletedRepairEstimateCommand,
    status: "DRAFT" | "COMPLETED",
    completedAmendment = false
  ) {
    if (!command.rentalItemId) {
      throw new Error("Выберите бытовку")
    }
    validateDateOnly(command.dispatchDate)

    assertEstimateLinesValid(command.lines)
    if (command.media.length > 20) {
      throw new Error("К одной смете можно прикрепить не более 20 фотографий")
    }

    const rentalItem =
      (await this.rentalItemsClient.resolveById(
        command.warehouseId,
        command.rentalItemId
      )) ??
      (command.estimateId
        ? await this.rentalItemsClient.resolveLinkedReturnEstimate(
            command.warehouseId,
            command.rentalItemId,
            command.estimateId
          )
        : null)
    if (!rentalItem) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    if (rentalItem.warehouseId !== command.warehouseId) {
      throw new Error("Бытовка принадлежит другому складу")
    }

    // Re-read under the shared mutation queue immediately before the synchronous
    // commit. This is the mock equivalent of an id + warehouse + version CAS.
    const envelope = readEnvelope()
    const estimateById = command.estimateId
      ? envelope.estimates.find((item) => item.id === command.estimateId)
      : null
    if (estimateById && estimateById.warehouseId !== command.warehouseId) {
      throw new Error("Смету нельзя перенести на другой склад")
    }
    const existing = command.estimateId
      ? envelope.estimates.find(
          (item) =>
            item.id === command.estimateId &&
            item.warehouseId === command.warehouseId
        )
      : null

    if (command.estimateId && !existing) {
      throw new Error("Смета не найдена")
    }
    if (completedAmendment && existing?.status !== "COMPLETED") {
      throw new Error("Дополнить можно только завершённую смету")
    }
    if (
      completedAmendment &&
      existing &&
      existing.rentalItemId !== command.rentalItemId
    ) {
      throw new Error("Бытовку завершённой сметы нельзя изменить")
    }
    if (existing?.status === "COMPLETED" && !completedAmendment) {
      throw new Error("Завершённая смета доступна только для чтения")
    }
    if (
      existing &&
      (command.expectedVersion === null ||
        command.expectedVersion !== existing.version)
    ) {
      throw new Error(
        "Смета была изменена. Обновите данные и повторите действие"
      )
    }
    if (!existing && command.expectedVersion !== null) {
      throw new Error("Версия новой сметы должна быть пустой")
    }

    const now = new Date().toISOString()
    const lines = command.lines.map(normalizeEstimateLine)
    const destinationText =
      command.destinationText !== undefined
        ? command.destinationText?.trim() || null
        : command.destinationParty !== undefined
          ? command.destinationParty?.trim() || null
          : (existing?.destinationText ?? existing?.destinationParty ?? null)
    const completion =
      status === "COMPLETED" ? (command as CompleteRepairEstimateCommand) : null
    let persistedTaskPlans: RepairEstimateTaskPlanDto[] = []
    if (completion) {
      assertTaskPlansValid(completion.taskPlans, lines)
      if (
        completion.taskPlans.some(
          (plan) => plan.generationStatus !== "PENDING_GENERATION"
        )
      ) {
        throw new Error("Новые планы должны ожидать внешней генерации")
      }
      if (completion.completionMode === "AUTO") {
        const workLineIds = lines
          .filter((line) => line.lineType === "WORK")
          .map((line) => line.id)
        const plannedWorkLineIds = completion.taskPlans.flatMap((plan) =>
          plan.includedLineIds.filter((lineId) => workLineIds.includes(lineId))
        )
        if (
          completion.taskPlans.some(
            (plan) =>
              plan.primaryLineId !== null &&
              !plan.queueCode?.trim() &&
              !plan.routeQueueKind
          ) ||
          plannedWorkLineIds.length !== workLineIds.length ||
          new Set(plannedWorkLineIds).size !== workLineIds.length
        ) {
          throw new Error(
            "Автоматическое завершение требует единственного маршрутизированного плана для каждой работы"
          )
        }
      }

      persistedTaskPlans = completion.taskPlans.map((plan) => {
        const hasBinding = Boolean(
          plan.queueCode?.trim() || plan.routeQueueKind
        )
        return {
          ...structuredClone(plan),
          workflowRequestRef: hasBinding
            ? {
                id: createOpaqueId("workflow-request"),
                status: "PENDING_EXTERNAL_DISPATCH" as const,
              }
            : null,
        }
      })
    }
    const saved: RepairEstimateDto = {
      id: existing?.id ?? createOpaqueId("repair-estimate"),
      version: (existing?.version ?? 0) + 1,
      status,
      warehouseId: command.warehouseId,
      rentalItemId: rentalItem.id,
      cabinNumber: rentalItem.number,
      authorName: existing
        ? normalizeAuthorName(existing.authorName)
        : CURRENT_MOCK_AUTHOR_NAME,
      sourceParty: command.sourceParty.trim(),
      destinationText,
      destinationParty: destinationText,
      dispatchDate: command.dispatchDate,
      comment: command.comment.trim(),
      totalAmount: calculateEstimateTotal(lines),
      lines,
      media: command.media.map((media) => structuredClone(media)),
      completionMode: completion?.completionMode ?? null,
      movementRequired: completion?.movementRequired ?? null,
      taskPlans: persistedTaskPlans,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    }
    const estimates = existing
      ? envelope.estimates.map((item) => (item.id === saved.id ? saved : item))
      : [...envelope.estimates, saved]
    writeEnvelope({
      ...envelope,
      revision: envelope.revision + 1,
      estimates,
    })

    return cloneEstimate(saved)
  }
}
