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
const legacyQueueId = "00000000-0000-4000-8000-000000000015"
const databaseQueueId = "00000000-0000-4000-8000-000000000025"
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

function repairCommandResult(
  persistedRepair: MaintenanceRepair,
  deliveryState: "PENDING" | "RETRY_PENDING" | "DELIVERED" | "QUARANTINED"
) {
  return {
    repair: persistedRepair,
    affectedSourceRepairs: [],
    delivery: {
      state: deliveryState,
      attempts: 1,
      updatedAt: "2026-07-18T10:00:00Z",
    },
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
  search: vi.fn(),
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
  maintenanceMediaReferences: [],
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

  it("returns an immediately queued repair without polling", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(
      repairCommandResult(queuedRepair, "DELIVERED")
    )
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    const task = await adapter.queue(writeCommand)

    expect(task.status).toBe("QUEUED")
    expect(lifecycle.get).not.toHaveBeenCalled()
    expect(wait).not.toHaveBeenCalled()
  })

  it("polls a pending draft until the persisted repair is queued", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(repairCommandResult(repair(), "PENDING"))
    lifecycle.get
      .mockResolvedValueOnce(repair())
      .mockResolvedValueOnce(queuedRepair)
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    const task = await adapter.queue(writeCommand)

    expect(task.status).toBe("QUEUED")
    expect(lifecycle.get).toHaveBeenCalledTimes(2)
    expect(lifecycle.get).toHaveBeenNthCalledWith(
      1,
      "token",
      warehouseId,
      repairId
    )
    expect(lifecycle.get).toHaveBeenNthCalledWith(
      2,
      "token",
      warehouseId,
      repairId
    )
    expect(wait).toHaveBeenNthCalledWith(1, 100)
    expect(wait).toHaveBeenNthCalledWith(2, 200)
  })

  it("fails closed when persisted stage generation permanently fails", async () => {
    const failedRepair = structuredClone(repair())
    failedRepair.plan.stages[0].taskSync.generationState = "FAILED"
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(
      repairCommandResult(repair(), "RETRY_PENDING")
    )
    lifecycle.get.mockResolvedValue(failedRepair)
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    await expect(adapter.queue(writeCommand)).rejects.toThrow(
      `Ремонт ${repairId} не поставлен в очередь: maintenance-service зафиксировал необратимую ошибку формирования или доставки этапов.`
    )
    expect(lifecycle.get).toHaveBeenCalledTimes(1)
    expect(wait).toHaveBeenCalledTimes(1)
  })

  it("times out without presenting a persisted draft as queued", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(repairCommandResult(repair(), "PENDING"))
    lifecycle.get.mockResolvedValue(repair())
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    await expect(adapter.queue(writeCommand)).rejects.toThrow(
      `Ремонт ${repairId} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`
    )
    expect(lifecycle.get).toHaveBeenCalledTimes(5)
    expect(wait.mock.calls.map(([delayMs]) => delayMs)).toEqual([
      100, 200, 400, 800, 1_000,
    ])
  })

  it("atomically replaces an existing draft plan with its ready repair photos", async () => {
    const mediaReferences = [{ mediaId: rentalItemId, generation: 4 }]
    const savedRepair = { ...repair(), mediaReferences }
    lifecycle.replacePlan.mockResolvedValue(savedRepair)
    const command = structuredClone(writeCommand)
    command.taskId = repairId
    command.expectedVersion = 3
    command.maintenanceMediaReferences = mediaReferences
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    expect(lifecycle.replacePlan).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      3,
      expect.any(Array),
      mediaReferences
    )
  })

  it("preserves repair photos when only the stage order changes", async () => {
    const mediaReferences = [{ mediaId: rentalItemId, generation: 4 }]
    const currentRepair = { ...repair(), mediaReferences }
    lifecycle.get.mockResolvedValue(currentRepair)
    lifecycle.replacePlan.mockResolvedValue(currentRepair)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.updateSubtasks({
      taskId: repairId,
      expectedVersion: 3,
      warehouseId,
      orderedSubtaskIds: [stageId],
    })

    expect(lifecycle.replacePlan).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      3,
      expect.any(Array),
      mediaReferences
    )
  })

  it("resolves a reviewed legacy queue ID by its exact database queue code", async () => {
    listQueues.mockResolvedValue([
      {
        id: "00000000-0000-4000-8000-000000000021",
        code: "REPAIR_BODY",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000022",
        code: "REPAIR_ELECTRICAL",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000023",
        code: "REPAIR_FLOOR",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000024",
        code: "REPAIR_ROOF",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: databaseQueueId,
        code: "REPAIR_WINDOWS",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000026",
        code: "REPAIR_FINISHING",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = legacyQueueId
    command.subtasks[0].queueCode = "REPAIR_WINDOWS"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    expect(lifecycle.createDirect).toHaveBeenCalledWith(
      "token",
      expect.any(String),
      expect.objectContaining({
        plan: [
          expect.objectContaining({
            routing: {
              queueId: databaseQueueId,
              queueCode: "REPAIR_WINDOWS",
              queueKind: "REPAIR",
            },
          }),
        ],
      })
    )
  })

  it("keeps an unmatched route kind ambiguous when several active queues exist", async () => {
    listQueues.mockResolvedValue([
      {
        id: "00000000-0000-4000-8000-000000000031",
        code: "REPAIR_BODY",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000032",
        code: "REPAIR_WINDOWS",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = legacyQueueId
    command.subtasks[0].queueCode = "LEGACY_REPAIR"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(command)).rejects.toThrow(
      "Для этапа 0 выберите конкретную очередь."
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })

  it("keeps routing fail-closed when no matching active visible queue exists", async () => {
    listQueues.mockResolvedValue([
      {
        id: databaseQueueId,
        code: "REPAIR_WINDOWS",
        type: "REPAIR",
        active: false,
        hidden: true,
      },
    ])
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = legacyQueueId
    command.subtasks[0].queueCode = "REPAIR_WINDOWS"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(command)).rejects.toThrow(
      "Для этапа 0 не настроена активная очередь."
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })
})
