import { beforeEach, describe, expect, it, vi } from "vitest"

const lifecycle = vi.hoisted(() => ({
  accept: vi.fn(),
  createDirect: vi.fn(),
  createRework: vi.fn(),
  get: vi.fn(),
  listAcceptance: vi.fn(),
  listRepairs: vi.fn(),
  listWriteOffs: vi.fn(),
  queue: vi.fn(),
  replacePlan: vi.fn(),
  writeOff: vi.fn(),
  uuid: vi.fn(() => "00000000-0000-4000-8000-000000000099"),
}))
const getBoard = vi.hoisted(() => vi.fn())
const listQueues = vi.hoisted(() => vi.fn())

vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  async (importOriginal) => ({
    ...(await importOriginal()),
    acceptMaintenanceRepair: lifecycle.accept,
    createDirectMaintenanceRepair: lifecycle.createDirect,
    createMaintenanceIdempotencyKey: lifecycle.uuid,
    createMaintenanceRework: lifecycle.createRework,
    getMaintenanceRepair: lifecycle.get,
    listMaintenanceAcceptance: lifecycle.listAcceptance,
    listMaintenanceRepairs: lifecycle.listRepairs,
    listMaintenanceWriteOffs: lifecycle.listWriteOffs,
    queueMaintenanceRepair: lifecycle.queue,
    replaceMaintenanceRepairPlan: lifecycle.replacePlan,
    writeOffMaintenanceRepair: lifecycle.writeOff,
  })
)

vi.mock("@/features/task-board/api/task-board-api", () => ({
  getTaskBoard: getBoard,
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: { listQueues },
}))

import { HttpMaintenanceRepairTasksAdapter } from "@/features/repair-tasks/adapters/http-maintenance-repair-tasks-adapter"
import type { MaintenanceRepair } from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import type { RepairTaskWriteCommand } from "@/features/repair-tasks/model/repair-task"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import type { TaskBoardSnapshotDto } from "@/features/task-board/model/task-board"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const rentalItemId = "00000000-0000-4000-8000-000000000002"
const repairId = "00000000-0000-4000-8000-000000000003"
const stageId = "00000000-0000-4000-8000-000000000004"
const queueId = "00000000-0000-4000-8000-000000000005"
const externalTaskId = "00000000-0000-4000-8000-000000000006"
const entryId = "00000000-0000-4000-8000-000000000007"

function repair(
  acceptanceState: MaintenanceRepair["acceptanceState"] = "NOT_READY"
): MaintenanceRepair {
  return {
    id: repairId,
    rootRepairId: repairId,
    sourceRepairId: null,
    estimateId: null,
    warehouseId,
    rentalItemId,
    origin: "DIRECT_REPAIR",
    kind: "PRIMARY",
    executionState: acceptanceState === "PENDING" ? "COMPLETED" : "DRAFT",
    acceptanceState,
    version: 3,
    dispatchDate: "2026-07-18",
    sourceParty: "Арендатор",
    plan: {
      repairId,
      repairVersion: 3,
      stages: [
        {
          id: stageId,
          kind: "REPAIR_WORK",
          order: 0,
          state: acceptanceState === "PENDING" ? "DONE" : "PLANNED",
          routing: {
            queueId,
            queueCode: "REPAIR",
            queueKind: "REPAIR",
          },
          taskDeadline: null,
          taskSync: {
            externalTaskId,
            taskBoardRegistrationVersion: 1,
            generationState: "GENERATED",
            delivery: {
              state: "DELIVERED",
              attempts: 1,
              updatedAt: "2026-07-18T10:00:00Z",
            },
          },
          completedAt:
            acceptanceState === "PENDING" ? "2026-07-18T10:00:00Z" : null,
        },
      ],
    },
    inventorySource: null,
    lease: null,
    mediaReferences: [],
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T10:00:00Z",
    actor: { actorId: "user-1", actorType: "USER" },
  }
}

const board: TaskBoardSnapshotDto = {
  warehouseId,
  queues: [
    {
      key: "queue-repair",
      label: "Ремонт",
      kind: "REPAIR",
      queueCode: "REPAIR",
      settingsQueueId: queueId,
      settingsCollapsed: false,
      entries: [
        {
          id: entryId,
          version: 2,
          warehouseId,
          queueKey: "queue-repair",
          queueId,
          queueCode: "REPAIR",
          entryType: "REAL",
          routeIndex: 0,
          routeLength: 1,
          queuePosition: 1,
          taskId: repairId,
          externalTaskId,
          taskVersion: 3,
          title: "Ремонт",
          unitNumber: "CAB-17",
          taskStatus: "DONE",
          status: "DONE",
          taskText: null,
          plannedDurationMinutes: 30,
          activeStartedAt: null,
          pausedAt: null,
          activeWorkSeconds: 1200,
          assignments: [
            {
              id: "assignment-1",
              version: 1,
              workerId: "worker-1",
              workerName: "Иван",
              workerGroupId: "group-1",
              workerGroupName: "Бригада 1",
              status: "DONE",
              assignedAt: "2026-07-18T09:00:00Z",
              startedAt: "2026-07-18T09:10:00Z",
              pausedAt: null,
              finishedAt: "2026-07-18T10:00:00Z",
            },
          ],
          detailsHref: `/repairs?repairId=${repairId}`,
        },
      ],
    },
  ],
  totalEntries: 1,
  realEntries: 1,
  shadowEntries: 0,
}

const rentalItemsClient: RepairTaskRentalItemsClient = {
  resolveById: vi.fn(async () => ({
    id: rentalItemId,
    warehouseId,
    number: "CAB-17",
  })),
}

const writeCommand: RepairTaskWriteCommand = {
  taskId: null,
  expectedVersion: null,
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  warehouseId,
  rentalItemId,
  reason: "Арендатор",
  dispatchDate: "2026-07-18",
  comment: "",
  media: [],
  subtasks: [
    {
      id: stageId,
      kind: "REPAIR_WORK",
      status: "WAITING",
      workLines: [],
      materialLines: [],
      groupComment: "",
      queueId,
      queueCode: "REPAIR",
      routeQueueKind: "REPAIR",
      sortOrder: 0,
      queuePosition: 0,
      plannedDurationMinutes: null,
      startedAt: null,
      completedAt: null,
      activeStartedAt: null,
      activeWorkSeconds: 0,
      workerGroup: null,
      assignments: [],
    },
  ],
}

describe("maintenance repair tasks adapter", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getBoard.mockResolvedValue(board)
    listQueues.mockResolvedValue([
      {
        id: queueId,
        code: "REPAIR",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
  })

  it("enriches a maintenance repair with real task-board and acceptance facts", async () => {
    lifecycle.get.mockResolvedValue(repair("PENDING"))
    lifecycle.listAcceptance.mockResolvedValue({
      items: [
        {
          repairId,
          readyAt: "2026-07-18T10:05:00Z",
        },
      ],
    })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task).toMatchObject({
      id: repairId,
      sourceParty: "Арендатор",
      actorId: "user-1",
      readyAt: "2026-07-18T10:05:00Z",
      taskBoardAvailable: true,
    })
    expect(task?.subtasks[0]).toMatchObject({
      externalTaskId,
      plannedDurationMinutes: 30,
      activeWorkSeconds: 1200,
      workerGroup: { id: "group-1", name: "Бригада 1" },
    })
    expect(task?.subtasks[0]?.assignments[0]?.worker?.name).toBe("Иван")
  })

  it("fails task-board enrichment closed without inventing assignments", async () => {
    lifecycle.listRepairs.mockResolvedValue({ items: [repair()] })
    getBoard.mockRejectedValue(new Error("task-board unavailable"))
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const [task] = await adapter.list(warehouseId)

    expect(task.taskBoardAvailable).toBe(false)
    expect(task.subtasks[0]?.assignments).toEqual([])
    expect(task.subtasks[0]?.workerGroup).toBeNull()
  })

  it("reports the persisted repair UUID when queueing fails", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockRejectedValue(new Error("task-board unavailable"))
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.queue(writeCommand)).rejects.toThrow(
      `Черновик ремонта ${repairId} сохранён, но постановка в очередь не выполнена: task-board unavailable`
    )
    expect(lifecycle.queue).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      3,
      expect.any(String)
    )
  })
})
