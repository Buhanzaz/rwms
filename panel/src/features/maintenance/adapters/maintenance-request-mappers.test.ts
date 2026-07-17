import { beforeEach, describe, expect, it, vi } from "vitest"

import type {
  MaintenanceEstimate,
  MaintenanceRepair,
} from "@/features/maintenance/api/maintenance-api"
import { httpRepairEstimatesAdapter } from "@/features/repair-estimates/adapters/http-repair-estimates-adapter"
import type {
  AmendCompletedRepairEstimateCommand,
  CompleteRepairEstimateCommand,
  RepairEstimateTaskPlanCommandDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { httpRepairTasksAdapter } from "@/features/repair-tasks/adapters/http-repair-tasks-adapter"
import type {
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"

const api = vi.hoisted(() => ({
  acceptMaintenanceRepair: vi.fn(),
  amendMaintenanceEstimate: vi.fn(),
  completeMaintenanceEstimate: vi.fn(),
  createDirectMaintenanceRepair: vi.fn(),
  createMaintenanceEstimate: vi.fn(),
  createMaintenanceRework: vi.fn(),
  getMaintenanceEstimate: vi.fn(),
  getMaintenanceRepair: vi.fn(),
  listMaintenanceAcceptanceProjection: vi.fn(),
  listMaintenanceEstimates: vi.fn(),
  listMaintenanceRepairs: vi.fn(),
  listMaintenanceWriteOffProjection: vi.fn(),
  queueMaintenanceRepair: vi.fn(),
  replaceMaintenanceEstimate: vi.fn(),
  replaceMaintenanceRepairPlan: vi.fn(),
  writeOffMaintenanceRepair: vi.fn(),
}))

vi.mock("@/features/maintenance/api/maintenance-api", () => api)
vi.mock("@/features/maintenance/maintenance-runtime", () => ({
  canonicalClientUuid: async (value: string) => value,
  withIdempotencyKey: async (
    _scope: string,
    _command: unknown,
    operation: (key: string) => Promise<unknown>
  ) => operation("00000000-0000-4000-8000-000000000099"),
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const RENTAL_ITEM_ID = "00000000-0000-4000-8000-000000000002"
const ESTIMATE_ID = "00000000-0000-4000-8000-000000000003"
const REPAIR_ID = "00000000-0000-4000-8000-000000000004"
const FIRST_STAGE_ID = "00000000-0000-4000-8000-000000000010"
const SECOND_STAGE_ID = "00000000-0000-4000-8000-000000000020"
const FIRST_QUEUE_ID = "00000000-0000-4000-8000-000000000011"
const SECOND_QUEUE_ID = "00000000-0000-4000-8000-000000000021"
const NOW = "2026-07-17T10:00:00Z"

function estimatePlans(): RepairEstimateTaskPlanCommandDto[] {
  return [
    {
      id: FIRST_STAGE_ID,
      kind: "MOVE_TO_REPAIR",
      includedLineIds: [],
      primaryLineId: null,
      groupComment: "",
      queueCode: "MOVE",
      queueId: FIRST_QUEUE_ID,
      routeQueueKind: "MOVEMENT",
      sortOrder: 10,
      generationStatus: "PENDING_GENERATION",
    },
    {
      id: SECOND_STAGE_ID,
      kind: "REPAIR_WORK",
      includedLineIds: [],
      primaryLineId: null,
      groupComment: "",
      queueCode: "REPAIR",
      queueId: SECOND_QUEUE_ID,
      routeQueueKind: "REPAIR",
      sortOrder: 20,
      generationStatus: "PENDING_GENERATION",
    },
  ]
}

function canonicalPlan() {
  return estimatePlans().map((plan, index) => ({
    id: plan.id,
    kind: plan.kind,
    order: index,
    routing: {
      queueId: plan.queueId!,
      queueCode: plan.queueCode!,
      queueKind: plan.routeQueueKind!,
    },
    taskDeadline: null,
  }))
}

function estimate(): MaintenanceEstimate {
  return {
    id: ESTIMATE_ID,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: RENTAL_ITEM_ID,
    version: 1,
    lifecycle: "COMPLETED",
    currentRevision: 1,
    revisions: [
      {
        revision: 1,
        dispatchDate: "2026-07-18",
        sourceParty: null,
        lines: [],
        plan: canonicalPlan(),
        total: "0.00",
        reason: null,
        recordedAt: NOW,
      },
    ],
    repairId: REPAIR_ID,
    mediaReferences: [],
    createdAt: NOW,
    completedAt: NOW,
    actor: {
      actorId: "00000000-0000-4000-8000-000000000030",
      actorType: "USER",
    },
  }
}

function subtask(
  id: string,
  queueId: string,
  queueCode: string,
  kind: RepairTaskSubtaskDto["kind"],
  sortOrder: number
): RepairTaskSubtaskDto {
  return {
    id,
    kind,
    status: "WAITING",
    workLines: [],
    materialLines: [],
    groupComment: "",
    queueCode,
    queueId,
    routeQueueKind: kind === "REPAIR_WORK" ? "REPAIR" : "MOVEMENT",
    sortOrder,
    queuePosition: sortOrder,
    plannedDurationMinutes: null,
    photoRequired: false,
    startedAt: null,
    completedAt: null,
    activeStartedAt: null,
    activeWorkSeconds: 0,
    workerGroup: null,
    assignments: [],
    resultMedia: [],
    assigneeName: null,
  }
}

function subtasks() {
  return [
    subtask(FIRST_STAGE_ID, FIRST_QUEUE_ID, "MOVE", "MOVE_TO_REPAIR", 10),
    subtask(SECOND_STAGE_ID, SECOND_QUEUE_ID, "REPAIR", "REPAIR_WORK", 20),
  ]
}

function repair(): MaintenanceRepair {
  return {
    id: REPAIR_ID,
    rootRepairId: REPAIR_ID,
    sourceRepairId: null,
    estimateId: null,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: RENTAL_ITEM_ID,
    origin: "DIRECT_REPAIR",
    kind: "PRIMARY",
    executionState: "DRAFT",
    acceptanceState: "NOT_READY",
    version: 3,
    dispatchDate: "2026-07-18",
    sourceParty: null,
    plan: {
      repairId: REPAIR_ID,
      repairVersion: 3,
      stages: canonicalPlan().map((stage) => ({
        ...stage,
        state: "PLANNED" as const,
        taskSync: {
          externalTaskId: "00000000-0000-4000-8000-000000000040",
          taskBoardRegistrationVersion: null,
          generationState: "PENDING_GENERATION" as const,
          delivery: { state: "PENDING" as const, attempts: 0, updatedAt: NOW },
        },
        completedAt: null,
      })),
    },
    lease: null,
    mediaReferences: [],
    createdAt: NOW,
    updatedAt: NOW,
    actor: {
      actorId: "00000000-0000-4000-8000-000000000030",
      actorType: "USER",
    },
  }
}

function completeCommand(): CompleteRepairEstimateCommand {
  return {
    estimateId: null,
    expectedVersion: null,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: RENTAL_ITEM_ID,
    sourceParty: "",
    dispatchDate: "2026-07-18",
    comment: "",
    lines: [],
    media: [],
    completionMode: "MANUAL",
    movementRequired: true,
    taskPlans: estimatePlans(),
  }
}

function repairCommand(taskId: string | null): RepairTaskWriteCommand {
  return {
    taskId,
    expectedVersion: taskId ? 3 : null,
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: RENTAL_ITEM_ID,
    reason: "",
    dispatchDate: "2026-07-18",
    comment: "",
    media: [],
    subtasks: subtasks(),
  }
}

describe("maintenance request stage order mapping", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.createMaintenanceEstimate.mockResolvedValue(estimate())
    api.completeMaintenanceEstimate.mockResolvedValue({
      estimate: estimate(),
      repair: null,
      delivery: { state: "PENDING", attempts: 0, updatedAt: NOW },
    })
    api.amendMaintenanceEstimate.mockResolvedValue({
      estimate: estimate(),
      repair: null,
      delivery: { state: "PENDING", attempts: 0, updatedAt: NOW },
    })
    api.createDirectMaintenanceRepair.mockResolvedValue(repair())
    api.replaceMaintenanceRepairPlan.mockResolvedValue(repair())
    api.getMaintenanceRepair.mockResolvedValue(repair())
  })

  it("sends zero-based estimate stage order for create and pre-start amendment", async () => {
    const create = completeCommand()
    const created = await httpRepairEstimatesAdapter.complete(create)

    expect(api.createMaintenanceEstimate).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        plan: [
          expect.objectContaining({ order: 0 }),
          expect.objectContaining({ order: 1 }),
        ],
      })
    )
    expect(create.taskPlans.map((stage) => stage.sortOrder)).toEqual([10, 20])
    expect(created.taskPlans.map((stage) => stage.sortOrder)).toEqual([10, 20])

    const amendment: AmendCompletedRepairEstimateCommand = {
      ...completeCommand(),
      estimateId: ESTIMATE_ID,
      expectedVersion: 1,
      expectedLinkedRepairVersion: 3,
      amendmentReason: "Уточнение маршрута",
    }
    await httpRepairEstimatesAdapter.amendCompleted(amendment)

    expect(api.amendMaintenanceEstimate).toHaveBeenCalledWith(
      WAREHOUSE_ID,
      ESTIMATE_ID,
      expect.any(String),
      expect.objectContaining({
        plan: [
          expect.objectContaining({ order: 0 }),
          expect.objectContaining({ order: 1 }),
        ],
      })
    )
  })

  it("sends zero-based direct-repair order and keeps legacy view sortOrder", async () => {
    const command = repairCommand(null)
    const saved = await httpRepairTasksAdapter.saveDraft(command)

    expect(api.createDirectMaintenanceRepair).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        plan: [
          expect.objectContaining({ order: 0 }),
          expect.objectContaining({ order: 1 }),
        ],
      })
    )
    expect(command.subtasks.map((stage) => stage.sortOrder)).toEqual([10, 20])
    expect(saved.subtasks.map((stage) => stage.sortOrder)).toEqual([10, 20])
  })

  it("uses zero-based order for plan replacement and explicit reorder", async () => {
    await httpRepairTasksAdapter.saveDraft(repairCommand(REPAIR_ID))
    expect(api.replaceMaintenanceRepairPlan).toHaveBeenLastCalledWith(
      WAREHOUSE_ID,
      REPAIR_ID,
      expect.objectContaining({
        stages: [
          expect.objectContaining({ order: 0 }),
          expect.objectContaining({ order: 1 }),
        ],
      })
    )

    await httpRepairTasksAdapter.updateSubtasks({
      taskId: REPAIR_ID,
      expectedVersion: 3,
      warehouseId: WAREHOUSE_ID,
      orderedSubtaskIds: [SECOND_STAGE_ID, FIRST_STAGE_ID],
    })
    expect(api.replaceMaintenanceRepairPlan).toHaveBeenLastCalledWith(
      WAREHOUSE_ID,
      REPAIR_ID,
      {
        expectedVersion: 3,
        stages: [
          expect.objectContaining({ id: SECOND_STAGE_ID, order: 0 }),
          expect.objectContaining({ id: FIRST_STAGE_ID, order: 1 }),
        ],
      }
    )
  })
})
