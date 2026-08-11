import { beforeEach, describe, expect, it, vi } from "vitest"

const lifecycle = vi.hoisted(() => ({
  accept: vi.fn(),
  createDirect: vi.fn(),
  createRework: vi.fn(),
  get: vi.fn(),
  listAcceptance: vi.fn(),
  listRepairs: vi.fn(),
  queue: vi.fn(),
  replacePlan: vi.fn(),
  writeOff: vi.fn(),
  uuid: vi.fn(() => "00000000-0000-4000-8000-000000000099"),
}))
const getBoard = vi.hoisted(() => vi.fn())
const listQueues = vi.hoisted(() => vi.fn())
const catalog = vi.hoisted(() => vi.fn())

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
    queueMaintenanceRepair: lifecycle.queue,
    replaceMaintenanceRepairPlan: lifecycle.replacePlan,
  })
)

vi.mock(
  "@/features/write-offs/property-dispositions-api",
  async (importOriginal) => ({
    ...(await importOriginal()),
    writeOffRepairDisposition: lifecycle.writeOff,
  })
)

vi.mock("@/features/task-board/api/task-board-api", () => ({
  getTaskBoard: getBoard,
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: { listQueues },
}))

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  async (importOriginal) => ({
    ...(await importOriginal()),
    getOperationalRepairEstimateCatalog: catalog,
  })
)

import { HttpMaintenanceRepairTasksAdapter } from "@/features/repair-tasks/adapters/http-maintenance-repair-tasks-adapter"
import type {
  MaintenanceEstimateLine,
  MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import type { RepairTaskWriteCommand } from "@/features/repair-tasks/model/repair-task"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import { RepairTaskQueueDraftPersistedError } from "@/features/repair-tasks/ports/repair-tasks-client"
import type { TaskBoardSnapshotDto } from "@/features/task-board/model/task-board"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const rentalItemId = "00000000-0000-4000-8000-000000000002"
const repairId = "00000000-0000-4000-8000-000000000003"
const stageId = "00000000-0000-4000-8000-000000000004"
const queueId = "00000000-0000-4000-8000-000000000005"
const legacyQueueId = "00000000-0000-4000-8000-000000000015"
const databaseQueueId = "00000000-0000-4000-8000-000000000025"
const workQueueId = "00000000-0000-4000-8000-000000000035"
const databaseWorkQueueId = "00000000-0000-4000-8000-000000000045"
const externalTaskId = "00000000-0000-4000-8000-000000000006"
const entryId = "00000000-0000-4000-8000-000000000007"
const workLineId = "00000000-0000-4000-8000-000000000008"
const materialLineId = "00000000-0000-4000-8000-000000000009"
const catalogVersionId = "00000000-0000-4000-8000-000000000010"
const workNodeId = "00000000-0000-4000-8000-000000000011"
const materialNodeId = "00000000-0000-4000-8000-000000000012"
const characteristicId = "00000000-0000-4000-8000-000000000013"
const evidenceId = "00000000-0000-4000-8000-000000000031"
const evidenceMediaId = "00000000-0000-4000-8000-000000000032"
const workerId = "00000000-0000-4000-8000-000000000033"
const workerGroupId = "00000000-0000-4000-8000-000000000034"

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
    priority: 2,
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
            queueName: "Ремонт",
            queueType: "REPAIR",
          },
          workLines: [],
          materialLines: [],
          primaryLineId: null,
          groupComment: "",
          evidence: [],
          taskDeadline: null,
          taskSync: {
            externalTaskId,
            taskBoardEntryId: entryId,
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
    complexity: {
      type: "LIGHT",
      name: "Лёгкий ремонт",
      color: "#22C55E",
      plannedMinutes: "30",
      forcedCapital: false,
    },
    movementToRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
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
  selectedDate: "2026-07-18",
  availableDates: ["2026-07-18"],
  queues: [
    {
      key: "queue-repair",
      label: "Ремонт",
      kind: "REPAIR",
      settingsQueueId: queueId,
      settingsCollapsed: false,
      entries: [
        {
          id: entryId,
          version: 2,
          warehouseId,
          queueKey: "queue-repair",
          queueId,
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
          scheduledDate: "2026-07-18",
          priority: 2,
          pinned: true,
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
              workerId,
              workerName: "Иван",
              workerGroupId,
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

const workLine = {
  id: workLineId,
  sourceLineKey: workLineId,
  lineType: "WORK" as const,
  description: "Заменить окно",
  lineComment: "Сначала снять створку",
  unit: "шт.",
  quantity: 1,
  unitPrice: "100.00",
  lineTotal: "100.00",
  catalogSnapshot: {
    nodeId: workNodeId,
    name: "Заменить окно",
    nodeType: "WORK" as const,
    furnitureEquipment: null,
    characteristic: null,
  },
  maintenanceMediaReferences: [],
}

const materialLine = {
  id: materialLineId,
  sourceLineKey: materialLineId,
  lineType: "MATERIAL" as const,
  description: "Оконный блок",
  lineComment: "",
  unit: "шт.",
  quantity: 1,
  unitPrice: "250.00",
  lineTotal: "250.00",
  catalogSnapshot: {
    nodeId: materialNodeId,
    name: "Оконный блок",
    nodeType: "MATERIAL" as const,
    furnitureEquipment: null,
    characteristic: {
      characteristicId,
      characteristicName: "Железная дверь",
    },
  },
  maintenanceMediaReferences: [],
}

const customWorkLine = {
  id: "00000000-0000-4000-8000-000000000029",
  sourceLineKey: "00000000-0000-4000-8000-000000000029",
  lineType: "WORK" as const,
  description: "Подтянуть крепления",
  lineComment: "Нужна стремянка",
  unit: "ед",
  quantity: 1,
  normativeMinutes: 45,
  unitPrice: "0.00",
  lineTotal: "0.00",
  catalogSnapshot: null,
  customQueueBinding: {
    queueId,
    queueName: "Ремонт",
    queueKind: "REPAIR" as const,
  },
  maintenanceMediaReferences: [],
}

const maintenanceWorkLine: MaintenanceEstimateLine = {
  id: workLineId,
  catalogSnapshot: {
    catalogVersionId,
    nodeId: workNodeId,
    nodeType: "WORK",
    name: "Заменить окно",
    unit: "шт.",
    unitPrice: "100.00",
    durationMinutes: 30,
    routing: {
      queueId,
      queueName: "Ремонт",
      queueType: "REPAIR",
    },
    furnitureEquipment: null,
    forcesCapitalRepair: true,
    characteristic: null,
  },
  lineType: "WORK",
  description: "Заменить окно",
  unit: "шт.",
  quantity: "1",
  normativeMinutes: 30,
  unitPrice: "100.00",
  comment: "Сначала снять створку",
  mediaReferences: [],
  lineTotal: "100.00",
}

const maintenanceMaterialLine: MaintenanceEstimateLine = {
  id: materialLineId,
  catalogSnapshot: {
    catalogVersionId,
    nodeId: materialNodeId,
    nodeType: "MATERIAL",
    name: "Оконный блок",
    unit: "шт.",
    unitPrice: "250.00",
    durationMinutes: 0,
    routing: {
      queueId,
      queueName: "Ремонт",
      queueType: "REPAIR",
    },
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristic: {
      characteristicId,
      characteristicName: "Железная дверь",
    },
  },
  lineType: "MATERIAL",
  description: "Оконный блок",
  unit: "шт.",
  quantity: "1",
  normativeMinutes: 0,
  unitPrice: "250.00",
  comment: null,
  mediaReferences: [],
  lineTotal: "250.00",
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
  coverMediaId: null,
  priority: 2,
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  subtasks: [
    {
      id: stageId,
      kind: "REPAIR_WORK",
      status: "WAITING",
      workLines: [workLine],
      materialLines: [],
      groupComment: "",
      queueId,
      queueName: "Ремонт",
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
        id: workQueueId,
        definitionId: queueId,
        name: "Ремонт",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
    catalog.mockResolvedValue({
      nodes: [
        {
          id: workNodeId,
          catalogVersionId,
          name: "Заменить окно",
          nodeType: "WORK",
          parentId: null,
          active: true,
          unit: "шт.",
          unitPrice: "100.00",
          durationMinutes: 30,
          showInMainMenu: true,
          routeQueueKind: "REPAIR",
          queueDefinitionId: queueId,
          queueDefinitionName: "Ремонт",
          includeInEstimate: true,
          commonItem: false,
          furnitureCategory: false,
          furnitureEquipment: null,
          forcesCapitalRepair: true,
          characteristic: null,
          comment: null,
        },
        {
          id: materialNodeId,
          catalogVersionId,
          name: "Оконный блок",
          nodeType: "MATERIAL",
          parentId: null,
          active: true,
          unit: "шт.",
          unitPrice: "250.00",
          durationMinutes: 0,
          showInMainMenu: true,
          routeQueueKind: "REPAIR",
          queueDefinitionId: queueId,
          queueDefinitionName: "Ремонт",
          includeInEstimate: true,
          commonItem: false,
          furnitureCategory: false,
          furnitureEquipment: null,
          forcesCapitalRepair: false,
          characteristic: {
            characteristicId,
            characteristicName: "Железная дверь",
          },
          comment: null,
        },
      ],
      links: [],
    })
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
      entryType: "REAL",
      scheduledDate: "2026-07-18",
      priority: 2,
      pinned: true,
      queuePosition: 1,
      plannedDurationMinutes: 30,
      activeWorkSeconds: 1200,
      workerGroup: { id: workerGroupId, name: "Бригада 1" },
    })
    expect(task?.subtasks[0]?.assignments[0]?.worker?.name).toBe("Иван")
  })

  it("marks a queued ordinary repair as awaiting movement until work reaches the board", async () => {
    const source = repair()
    source.executionState = "QUEUED"
    source.movementToRepair = true
    source.plan.stages[0]!.state = "QUEUED"
    source.plan.stages[0]!.taskSync = {
      ...source.plan.stages[0]!.taskSync,
      taskBoardRegistrationVersion: null,
      generationState: "PENDING_GENERATION",
      delivery: {
        state: "PENDING",
        attempts: 0,
        updatedAt: "2026-07-18T10:00:00Z",
      },
    }
    lifecycle.get.mockResolvedValue(source)
    lifecycle.listAcceptance.mockResolvedValue({ items: [] })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.awaitingMovement).toBe(true)
  })

  it("does not keep a repair awaiting movement after its work is registered on the board", async () => {
    const source = repair()
    source.executionState = "QUEUED"
    source.movementToRepair = true
    lifecycle.get.mockResolvedValue(source)
    lifecycle.listAcceptance.mockResolvedValue({ items: [] })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.awaitingMovement).toBe(false)
  })

  it("uses the maintenance category normative when the board entry is no longer visible", async () => {
    const historicalRepair = repair("PENDING")
    historicalRepair.plan.stages[0].workLines = [maintenanceWorkLine]
    lifecycle.get.mockResolvedValue(historicalRepair)
    lifecycle.listAcceptance.mockResolvedValue({
      items: [
        {
          repairId,
          readyAt: "2026-07-18T10:05:00Z",
        },
      ],
    })
    const historicalBoard = structuredClone(board)
    historicalBoard.queues.forEach((queue) => {
      queue.entries = []
    })
    historicalBoard.totalEntries = 0
    historicalBoard.realEntries = 0
    getBoard.mockResolvedValue(historicalBoard)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.subtasks[0]).toMatchObject({
      taskBoardEntryId: entryId,
      plannedDurationMinutes: 30,
      activeWorkSeconds: 0,
    })
    expect(task?.subtasks[0]?.workLines[0]?.normativeMinutes).toBe(30)
  })

  it("serializes unique stage lines with exact operational catalog snapshots", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    command.subtasks[0].workLines = [workLine]
    command.subtasks[0].materialLines = [materialLine]
    command.subtasks[0].groupComment = "Монтажная группа"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    const request = lifecycle.createDirect.mock.calls[0]?.[2]
    expect(request).toMatchObject({
      warehouseId,
      rentalItemId,
      dispatchDate: "2026-07-18",
      sourceParty: "WMS-панель",
    })
    expect(request.lines).toHaveLength(2)
    expect(
      new Set(request.lines.map((line: { id: string }) => line.id)).size
    ).toBe(2)
    expect(request.lines).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: workLineId,
          lineType: "WORK",
          unit: "шт.",
          catalogSnapshot: expect.objectContaining({
            catalogVersionId,
            nodeId: workNodeId,
            nodeType: "WORK",
            routing: {
              queueId,
              queueName: "Ремонт",
              queueType: "REPAIR",
            },
            forcesCapitalRepair: true,
            characteristic: null,
          }),
        }),
        expect.objectContaining({
          id: materialLineId,
          lineType: "MATERIAL",
          unit: "шт.",
          catalogSnapshot: expect.objectContaining({
            catalogVersionId,
            nodeId: materialNodeId,
            nodeType: "MATERIAL",
            forcesCapitalRepair: false,
            characteristic: {
              characteristicId,
              characteristicName: "Железная дверь",
            },
          }),
        }),
      ])
    )
    expect(request.plan).toEqual([
      expect.objectContaining({
        includedLineIds: [workLineId, materialLineId],
        primaryLineId: workLineId,
        groupComment: "Монтажная группа",
      }),
    ])
  })

  it("serializes a custom work line without a catalog snapshot", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    command.subtasks[0].workLines = [customWorkLine]
    command.subtasks[0].groupComment = "Нужна стремянка"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    const request = lifecycle.createDirect.mock.calls[0]?.[2]
    expect(request.lines).toEqual([
      expect.objectContaining({
        id: customWorkLine.id,
        catalogSnapshot: null,
        lineType: "WORK",
        description: customWorkLine.description,
        unit: "ед",
        normativeMinutes: 45,
        comment: customWorkLine.lineComment,
      }),
    ])
    expect(request.plan).toEqual([
      expect.objectContaining({
        includedLineIds: [customWorkLine.id],
        primaryLineId: customWorkLine.id,
        groupComment: "Нужна стремянка",
        routing: {
          queueId,
          queueName: "Ремонт",
          queueType: "REPAIR",
        },
      }),
    ])
  })

  it("serializes a custom material with its declared unit and a zero duration", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    const customMaterialLine = {
      ...customWorkLine,
      id: "00000000-0000-4000-8000-000000000030",
      sourceLineKey: "00000000-0000-4000-8000-000000000030",
      lineType: "MATERIAL" as const,
      description: "Крепёж",
      unit: "упак.",
      normativeMinutes: 90,
    }
    command.subtasks[0].workLines = [customWorkLine]
    command.subtasks[0].materialLines = [customMaterialLine]
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    expect(lifecycle.createDirect.mock.calls[0]?.[2].lines).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: customMaterialLine.id,
          catalogSnapshot: null,
          lineType: "MATERIAL",
          unit: "упак.",
          normativeMinutes: 0,
        }),
      ])
    )
  })

  it("serializes a material-only repair stage with a null primary line", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    const standaloneMaterial = {
      ...customWorkLine,
      id: "00000000-0000-4000-8000-000000000035",
      sourceLineKey: "00000000-0000-4000-8000-000000000035",
      lineType: "MATERIAL" as const,
      description: "Герметик",
      unit: "туба",
      normativeMinutes: 0,
    }
    command.subtasks[0].workLines = []
    command.subtasks[0].materialLines = [standaloneMaterial]
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.saveDraft(command)

    expect(lifecycle.createDirect.mock.calls[0]?.[2].plan).toEqual([
      expect.objectContaining({
        includedLineIds: [standaloneMaterial.id],
        primaryLineId: null,
        routing: {
          queueId,
          queueName: "Ремонт",
          queueType: "REPAIR",
        },
      }),
    ])
  })

  it("projects server work, material and group content into the repair subtask", async () => {
    const contentRepair = structuredClone(repair())
    contentRepair.plan.stages[0].workLines = [maintenanceWorkLine]
    contentRepair.plan.stages[0].materialLines = [maintenanceMaterialLine]
    contentRepair.plan.stages[0].primaryLineId = workLineId
    contentRepair.plan.stages[0].groupComment = "Монтажная группа"
    lifecycle.get.mockResolvedValue(contentRepair)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.subtasks[0]).toMatchObject({
      groupComment: "Монтажная группа",
      workLines: [
        {
          id: workLineId,
          lineType: "WORK",
          description: "Заменить окно",
          lineComment: "Сначала снять створку",
          quantity: 1,
          catalogSnapshot: {
            nodeId: workNodeId,
            nodeType: "WORK",
          },
        },
      ],
      materialLines: [
        {
          id: materialLineId,
          lineType: "MATERIAL",
          description: "Оконный блок",
          quantity: 1,
        },
      ],
    })
  })

  it("reconstructs a custom work line binding from its containing repair stage", async () => {
    const contentRepair = structuredClone(repair())
    contentRepair.plan.stages[0].routing = {
      queueId,
      queueName: "Ожидание проверки",
      queueType: "HOLDING",
    }
    contentRepair.plan.stages[0].workLines = [
      {
        ...maintenanceWorkLine,
        id: customWorkLine.id,
        catalogSnapshot: null,
        description: customWorkLine.description,
        comment: customWorkLine.lineComment,
      },
    ]
    contentRepair.plan.stages[0].primaryLineId = customWorkLine.id
    lifecycle.get.mockResolvedValue(contentRepair)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.subtasks[0]?.workLines).toEqual([
      expect.objectContaining({
        id: customWorkLine.id,
        lineType: "WORK",
        catalogSnapshot: null,
        customQueueBinding: {
          queueId,
          queueName: "Ожидание проверки",
          queueKind: "HOLDING",
        },
        lineComment: "Нужна стремянка",
      }),
    ])
  })

  it("projects worker evidence and resolves display names from task-board assignments", async () => {
    const evidenceRepair = structuredClone(repair("PENDING"))
    evidenceRepair.plan.stages[0].evidence = [
      {
        evidenceId,
        entryId,
        workerId,
        workerGroupId,
        mediaId: evidenceMediaId,
        mediaGeneration: 4,
        capturedAt: "2026-07-18T09:45:00Z",
        recordedAt: "2026-07-18T10:00:00Z",
        state: "READY",
      },
    ]
    lifecycle.get.mockResolvedValue(evidenceRepair)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.getById(repairId, warehouseId)

    expect(task?.subtasks[0]?.evidence).toEqual([
      {
        evidenceId,
        entryId,
        workerId,
        workerDisplayName: "Иван",
        workerGroupId,
        workerGroupName: "Бригада 1",
        mediaId: evidenceMediaId,
        mediaGeneration: 4,
        capturedAt: "2026-07-18T09:45:00Z",
        recordedAt: "2026-07-18T10:00:00Z",
        state: "READY",
      },
    ])
  })

  it("keeps maintenance draft repairs in the repairs list", async () => {
    lifecycle.listRepairs.mockResolvedValue({ items: [repair()] })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const tasks = await adapter.list(warehouseId)

    expect(lifecycle.listRepairs).toHaveBeenCalledWith("token", warehouseId)
    expect(tasks).toEqual([
      expect.objectContaining({
        id: repairId,
        status: "DRAFT",
      }),
    ])
  })

  it("fails the repair projection when task-board enrichment is unavailable", async () => {
    lifecycle.listRepairs.mockResolvedValue({ items: [repair()] })
    getBoard.mockRejectedValue(new Error("task-board unavailable"))
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.list(warehouseId)).rejects.toThrow(
      "task-board unavailable"
    )
  })

  it("rejects a repair priority outside the service contract", async () => {
    lifecycle.listRepairs.mockResolvedValue({
      items: [{ ...repair(), priority: 0 }],
    })
    getBoard.mockResolvedValue(board)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.list(warehouseId)).rejects.toThrow(
      "некорректный приоритет"
    )
  })

  it("reports the persisted repair UUID when queueing fails", async () => {
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockRejectedValue(new Error("task-board unavailable"))
    lifecycle.get.mockResolvedValue({ ...repair(), version: 4 })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const queued = adapter.queue(writeCommand)

    await expect(queued).rejects.toMatchObject({
      name: "RepairTaskQueueDraftPersistedError",
      taskId: repairId,
      expectedVersion: 4,
      code: null,
      message: `Черновик ремонта ${repairId} сохранён, но постановка в очередь не выполнена: task-board unavailable`,
    })
    await expect(queued).rejects.toBeInstanceOf(
      RepairTaskQueueDraftPersistedError
    )
    expect(lifecycle.queue).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      3,
      2,
      expect.any(String),
      false,
      "AUTO",
      null
    )
  })

  it("preserves the booked-unit replacement code after persisting the repair draft", async () => {
    const queueError = new ApiError(
      "Бытовка забронирована и требует замены.",
      409,
      "BOOKED_UNIT_REPLACEMENT_REQUIRED"
    )
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockRejectedValue(queueError)
    lifecycle.get.mockResolvedValue({ ...repair(), version: 4 })
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.queue(writeCommand)).rejects.toMatchObject({
      name: "RepairTaskQueueDraftPersistedError",
      taskId: repairId,
      expectedVersion: 4,
      code: "BOOKED_UNIT_REPLACEMENT_REQUIRED",
      cause: queueError,
    })
  })

  it("uses the persisted draft on retry instead of creating another repair", async () => {
    const persistedDraft = { ...repair(), version: 4 }
    const replacedDraft = { ...repair(), version: 5 }
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      version: 6,
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue
      .mockRejectedValueOnce(new Error("task-board unavailable"))
      .mockResolvedValueOnce(repairCommandResult(queuedRepair, "DELIVERED"))
    lifecycle.get.mockResolvedValue(persistedDraft)
    lifecycle.replacePlan.mockResolvedValue(replacedDraft)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    let persistedError: RepairTaskQueueDraftPersistedError | null = null
    try {
      await adapter.queue(writeCommand)
    } catch (error) {
      expect(error).toBeInstanceOf(RepairTaskQueueDraftPersistedError)
      persistedError = error as RepairTaskQueueDraftPersistedError
    }

    expect(persistedError).not.toBeNull()
    const retryCommand = {
      ...structuredClone(writeCommand),
      taskId: persistedError!.taskId,
      expectedVersion: persistedError!.expectedVersion,
    }
    const task = await adapter.queue(retryCommand)

    expect(task.status).toBe("QUEUED")
    expect(lifecycle.createDirect).toHaveBeenCalledTimes(1)
    expect(lifecycle.replacePlan).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      4,
      expect.any(Array),
      expect.any(Array),
      [],
      null
    )
    expect(lifecycle.queue).toHaveBeenLastCalledWith(
      "token",
      warehouseId,
      repairId,
      5,
      2,
      expect.any(String),
      false,
      "AUTO",
      null
    )
  })

  it("returns success when a failed queue response already persisted a queued repair", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      version: 4,
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockRejectedValue(new Error("connection reset"))
    lifecycle.get.mockResolvedValue(queuedRepair)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const task = await adapter.queue(writeCommand)

    expect(task).toMatchObject({
      id: repairId,
      version: 4,
      status: "QUEUED",
    })
    expect(lifecycle.createDirect).toHaveBeenCalledTimes(1)
  })

  it("does not mask permanent task-board delivery failure for a queued repair", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      version: 4,
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(
      repairCommandResult(queuedRepair, "QUARANTINED")
    )
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.queue(writeCommand)).rejects.toThrow(
      `Ремонт ${repairId} не поставлен в очередь: maintenance-service зафиксировал необратимую ошибку формирования или доставки этапов.`
    )
    expect(lifecycle.get).not.toHaveBeenCalled()
  })

  it("confirms a queued repair when the immediate draft response has stale quarantined delivery", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      version: 4,
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(
      repairCommandResult(repair(), "QUARANTINED")
    )
    lifecycle.get.mockResolvedValue(queuedRepair)
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    const task = await adapter.queue(writeCommand)

    expect(task).toMatchObject({
      id: repairId,
      version: 4,
      status: "QUEUED",
    })
    expect(lifecycle.get).toHaveBeenCalledTimes(1)
    expect(wait).toHaveBeenCalledWith(100)
  })

  it("fails when the first GET confirms quarantined stage delivery", async () => {
    const failedRepair = structuredClone(repair())
    failedRepair.plan.stages[0].taskSync.delivery.state = "QUARANTINED"
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue.mockResolvedValue(
      repairCommandResult(repair(), "QUARANTINED")
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
    expect(wait).toHaveBeenCalledWith(100)
  })

  it("returns an immediately queued repair without polling", async () => {
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
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
    const fixedDateCommand: RepairTaskWriteCommand = {
      ...structuredClone(writeCommand),
      movementToRepair: true,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
    }

    const task = await adapter.queue(fixedDateCommand)

    expect(task).toMatchObject({
      status: "QUEUED",
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
    })
    expect(lifecycle.queue).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      3,
      2,
      expect.any(String),
      true,
      "FIXED_DATE",
      "2026-08-12"
    )
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

  it("retries the latest persisted draft version after async queue confirmation times out", async () => {
    const readError = new Error("maintenance read unavailable")
    const queuedRepair: MaintenanceRepair = {
      ...repair(),
      executionState: "QUEUED",
      version: 9,
    }
    lifecycle.createDirect.mockResolvedValue(repair())
    lifecycle.queue
      .mockResolvedValueOnce(repairCommandResult(repair(), "PENDING"))
      .mockResolvedValueOnce(repairCommandResult(queuedRepair, "DELIVERED"))
    lifecycle.get
      .mockResolvedValueOnce({ ...repair(), version: 4 })
      .mockResolvedValueOnce({ ...repair(), version: 5 })
      .mockResolvedValueOnce({ ...repair(), version: 6 })
      .mockResolvedValueOnce({ ...repair(), version: 7 })
      .mockRejectedValueOnce(readError)
    lifecycle.replacePlan.mockResolvedValue({ ...repair(), version: 8 })
    const wait = vi.fn(async (delayMs: number) => void delayMs)
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token",
      wait
    )

    let timeoutError: RepairTaskQueueDraftPersistedError | null = null
    try {
      await adapter.queue(writeCommand)
    } catch (error) {
      expect(error).toBeInstanceOf(RepairTaskQueueDraftPersistedError)
      timeoutError = error as RepairTaskQueueDraftPersistedError
    }

    expect(timeoutError).toMatchObject({
      taskId: repairId,
      expectedVersion: 7,
      cause: readError,
    })
    expect(timeoutError?.message).toBe(
      `Ремонт ${repairId} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`
    )

    const retryCommand = {
      ...structuredClone(writeCommand),
      taskId: timeoutError!.taskId,
      expectedVersion: timeoutError!.expectedVersion,
    }
    const task = await adapter.queue(retryCommand)

    expect(task.status).toBe("QUEUED")
    expect(lifecycle.createDirect).toHaveBeenCalledTimes(1)
    expect(lifecycle.replacePlan).toHaveBeenCalledWith(
      "token",
      warehouseId,
      repairId,
      7,
      expect.any(Array),
      expect.any(Array),
      [],
      null
    )
    expect(lifecycle.queue).toHaveBeenLastCalledWith(
      "token",
      warehouseId,
      repairId,
      8,
      2,
      expect.any(String),
      false,
      "AUTO",
      null
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
    command.coverMediaId = rentalItemId
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
      expect.any(Array),
      mediaReferences,
      rentalItemId
    )
  })

  it("preserves repair photos and stage content when only the order changes", async () => {
    const mediaReferences = [{ mediaId: rentalItemId, generation: 4 }]
    const currentRepair = structuredClone(repair())
    currentRepair.mediaReferences = mediaReferences
    currentRepair.plan.stages[0].workLines = [maintenanceWorkLine]
    currentRepair.plan.stages[0].materialLines = [maintenanceMaterialLine]
    currentRepair.plan.stages[0].primaryLineId = workLineId
    currentRepair.plan.stages[0].groupComment = "Монтажная группа"
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
      expect.arrayContaining([
        expect.objectContaining({ id: workLineId }),
        expect.objectContaining({ id: materialLineId }),
      ]),
      [
        expect.objectContaining({
          id: stageId,
          includedLineIds: [workLineId, materialLineId],
          primaryLineId: workLineId,
          groupComment: "Монтажная группа",
        }),
      ],
      mediaReferences,
      null
    )
  })

  it("uses the exact active queue UUID when several queues share a type", async () => {
    listQueues.mockResolvedValue([
      {
        id: "00000000-0000-4000-8000-000000000021",
        definitionId: "00000000-0000-4000-8000-000000000121",
        name: "Кузовной ремонт",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000022",
        definitionId: "00000000-0000-4000-8000-000000000122",
        name: "Электрика",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000023",
        definitionId: "00000000-0000-4000-8000-000000000123",
        name: "Пол",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000024",
        definitionId: "00000000-0000-4000-8000-000000000124",
        name: "Кровля",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: databaseWorkQueueId,
        definitionId: databaseQueueId,
        name: "Ремонт окон",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000026",
        definitionId: "00000000-0000-4000-8000-000000000126",
        name: "Отделка",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
    lifecycle.createDirect.mockResolvedValue(repair())
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = databaseQueueId
    command.subtasks[0].queueName = "Ремонт окон"
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
              queueName: "Ремонт окон",
              queueType: "REPAIR",
            },
          }),
        ],
      })
    )
  })

  it("rejects a stale UUID instead of guessing from queues with the same type", async () => {
    listQueues.mockResolvedValue([
      {
        id: "00000000-0000-4000-8000-000000000031",
        definitionId: "00000000-0000-4000-8000-000000000131",
        name: "Кузовной ремонт",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
      {
        id: "00000000-0000-4000-8000-000000000032",
        definitionId: "00000000-0000-4000-8000-000000000132",
        name: "Ремонт окон",
        type: "REPAIR",
        active: true,
        hidden: false,
      },
    ])
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = legacyQueueId
    command.subtasks[0].queueName = "Старая очередь"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(command)).rejects.toThrow(
      "Очередь «Старая очередь» не материализована для выбранного склада или отключена. Проверьте «Настройки доски задач → Каталог очередей» и синхронизацию склада."
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })

  it("rejects furniture movement as a repair-stage route", async () => {
    listQueues.mockResolvedValue([
      {
        id: queueId,
        definitionId: queueId,
        name: "Перемещение мебели",
        type: "FURNITURE_MOVEMENT",
        active: true,
        hidden: false,
      },
    ])
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(writeCommand)).rejects.toThrow(
      "предназначена для перемещения мебели"
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })

  it("does not let custom work resolve to a movement queue", async () => {
    const command = structuredClone(writeCommand)
    command.subtasks[0].workLines = [customWorkLine]
    command.subtasks[0].queueName = "Перемещение на ремонт"
    command.subtasks[0].routeQueueKind = "MOVEMENT"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(command)).rejects.toThrow(
      "Пользовательская работа"
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })

  it("keeps routing fail-closed when no matching active visible queue exists", async () => {
    listQueues.mockResolvedValue([
      {
        id: databaseWorkQueueId,
        definitionId: databaseQueueId,
        name: "Ремонт окон",
        type: "REPAIR",
        active: false,
        hidden: true,
      },
    ])
    const command = structuredClone(writeCommand)
    command.subtasks[0].queueId = databaseQueueId
    command.subtasks[0].queueName = "Ремонт окон"
    const adapter = new HttpMaintenanceRepairTasksAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.saveDraft(command)).rejects.toThrow(
      "Очередь «Ремонт окон» не материализована для выбранного склада или отключена. Проверьте «Настройки доски задач → Каталог очередей» и синхронизацию склада."
    )
    expect(lifecycle.createDirect).not.toHaveBeenCalled()
  })
})
