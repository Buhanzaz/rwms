import { beforeEach, describe, expect, it, vi } from "vitest"

const lifecycle = vi.hoisted(() => ({
  amend: vi.fn(),
  complete: vi.fn(),
  create: vi.fn(),
  get: vi.fn(),
  list: vi.fn(),
  replace: vi.fn(),
  uuid: vi.fn(() => "00000000-0000-4000-8000-000000000099"),
}))
const catalog = vi.hoisted(() => vi.fn())
const listQueues = vi.hoisted(() => vi.fn())

vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  async (importOriginal) => ({
    ...(await importOriginal()),
    amendMaintenanceEstimate: lifecycle.amend,
    completeMaintenanceEstimate: lifecycle.complete,
    createMaintenanceEstimate: lifecycle.create,
    createMaintenanceIdempotencyKey: lifecycle.uuid,
    getMaintenanceEstimate: lifecycle.get,
    listMaintenanceEstimates: lifecycle.list,
    replaceMaintenanceEstimate: lifecycle.replace,
  })
)

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  async (importOriginal) => ({
    ...(await importOriginal()),
    getOperationalRepairEstimateCatalog: catalog,
  })
)

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: { listQueues },
}))

import { HttpMaintenanceRepairEstimatesAdapter } from "@/features/repair-estimates/adapters/http-maintenance-repair-estimates-adapter"
import type { MaintenanceEstimate } from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import type {
  CompleteRepairEstimateCommand,
  RepairEstimateDraftCommand,
} from "@/features/repair-estimates/model/repair-estimate"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const rentalItemId = "00000000-0000-4000-8000-000000000002"
const estimateId = "00000000-0000-4000-8000-000000000003"
const lineId = "00000000-0000-4000-8000-000000000004"
const planId = "00000000-0000-4000-8000-000000000005"
const queueId = "00000000-0000-4000-8000-000000000006"
const workQueueId = "00000000-0000-4000-8000-000000000036"
const foreignQueueId = "00000000-0000-4000-8000-000000000016"

function estimate(
  lifecycleState: MaintenanceEstimate["lifecycle"],
  version: number
): MaintenanceEstimate {
  return {
    id: estimateId,
    warehouseId,
    rentalItemId,
    version,
    lifecycle: lifecycleState,
    currentRevision: 1,
    revisions: [
      {
        revision: 1,
        dispatchDate: "2026-07-18",
        sourceParty: "Арендатор",
        lines: [
          {
            id: lineId,
            catalogSnapshot: null,
            lineType: "WORK",
            description: "Ручная работа",
            unit: "ед",
            quantity: "1.5",
            normativeMinutes: 45,
            unitPrice: "10.00",
            lineTotal: "15.00",
            comment: null,
            mediaReferences: [],
          },
        ],
        plan: [
          {
            id: planId,
            kind: "REPAIR_WORK",
            order: 0,
            routing: {
              queueId,
              queueName: "Ремонт",
              queueType: "REPAIR",
            },
            includedLineIds: [lineId],
            primaryLineId: lineId,
            groupComment: "Монтажная группа",
            taskDeadline: null,
          },
        ],
        total: "15.00",
        reason: null,
        recordedAt: "2026-07-18T10:00:00Z",
      },
    ],
    repairId: null,
    mediaReferences: [],
    createdAt: "2026-07-18T09:00:00Z",
    completedAt: lifecycleState === "COMPLETED" ? "2026-07-18T10:00:00Z" : null,
    actor: { actorId: "user-1", actorType: "USER" },
  }
}

const rentalItemsClient: EstimateRentalItemsClient = {
  search: vi.fn(),
  resolveById: vi.fn(async () => ({
    id: rentalItemId,
    warehouseId,
    number: "CAB-17",
  })),
}

const draft: RepairEstimateDraftCommand = {
  estimateId: null,
  expectedVersion: null,
  warehouseId,
  rentalItemId,
  sourceParty: "Арендатор",
  dispatchDate: "2026-07-18",
  comment: "",
  lines: [
    {
      id: lineId,
      sourceLineKey: lineId,
      lineType: "WORK",
      description: "Ручная работа",
      lineComment: "",
      unit: "ед",
      quantity: 1.5,
      normativeMinutes: 45,
      unitPrice: "10.00",
      lineTotal: "15.00",
      catalogSnapshot: null,
    },
  ],
  media: [],
}

const completeCommand: CompleteRepairEstimateCommand = {
  ...draft,
  priority: 1,
  completionMode: "MANUAL",
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  taskPlans: [
    {
      id: planId,
      kind: "REPAIR_WORK",
      includedLineIds: [lineId],
      primaryLineId: lineId,
      groupComment: "Монтажная группа",
      queueId,
      queueName: "Ремонт",
      routeQueueKind: "REPAIR",
      sortOrder: 10,
      generationStatus: "PENDING_GENERATION",
    },
  ],
}

describe("maintenance repair estimates adapter", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    catalog.mockResolvedValue({ nodes: [], links: [] })
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
  })

  it("keeps the server UUID/version/decimal quantity authoritative", async () => {
    lifecycle.create.mockResolvedValue(estimate("DRAFT", 0))
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const saved = await adapter.saveDraft(draft)

    expect(saved.id).toBe(estimateId)
    expect(saved.version).toBe(0)
    expect(saved.cabinNumber).toBe("CAB-17")
    expect(saved.lines[0]).toMatchObject({
      quantity: 1.5,
      lineType: "WORK",
    })
    expect(lifecycle.create).toHaveBeenCalledWith(
      "token",
      expect.any(String),
      expect.objectContaining({
        warehouseId,
        rentalItemId,
        lines: [
          expect.objectContaining({
            quantity: "1.5",
            lineType: "WORK",
            unit: "ед",
            normativeMinutes: 45,
          }),
        ],
      })
    )
  })

  it("reports the persisted server draft when completion fails", async () => {
    lifecycle.create.mockResolvedValue(estimate("DRAFT", 0))
    lifecycle.complete.mockRejectedValue(new Error("queue unavailable"))
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.complete(completeCommand)).rejects.toThrow(
      `Черновик ${estimateId} сохранён, но завершение не выполнено: queue unavailable`
    )
    expect(lifecycle.complete).toHaveBeenCalledWith(
      "token",
      warehouseId,
      estimateId,
      0,
      1,
      expect.any(String),
      false,
      "AUTO",
      null,
      false
    )
    expect(lifecycle.create).toHaveBeenCalledWith(
      "token",
      expect.any(String),
      expect.objectContaining({
        plan: [
          expect.objectContaining({
            includedLineIds: [lineId],
            primaryLineId: lineId,
            groupComment: "Монтажная группа",
          }),
        ],
      })
    )
  })

  it("reads stage line references, primary line and group comment back", async () => {
    lifecycle.get.mockResolvedValue(estimate("DRAFT", 2))
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    const loaded = await adapter.getById(estimateId, warehouseId)

    expect(loaded?.taskPlans).toEqual([
      expect.objectContaining({
        includedLineIds: [lineId],
        primaryLineId: lineId,
        groupComment: "Монтажная группа",
      }),
    ])
    expect(loaded?.lines[0]).toMatchObject({ normativeMinutes: 45 })
  })

  it("serializes a custom work line without a catalog snapshot and reconstructs its plan binding", async () => {
    lifecycle.create.mockResolvedValue(estimate("DRAFT", 0))
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )
    const customBinding = {
      queueId,
      queueName: "Ремонт",
      queueKind: "REPAIR" as const,
    }

    const saved = await adapter.saveDraft({
      ...draft,
      lines: [
        {
          ...draft.lines[0],
          lineType: "WORK",
          customQueueBinding: customBinding,
        },
      ],
      taskPlans: completeCommand.taskPlans,
    })

    expect(lifecycle.create).toHaveBeenCalledWith(
      "token",
      expect.any(String),
      expect.objectContaining({
        lines: [expect.objectContaining({ catalogSnapshot: null })],
        plan: [
          expect.objectContaining({
            includedLineIds: [lineId],
            routing: {
              queueId,
              queueName: "Ремонт",
              queueType: "REPAIR",
            },
          }),
        ],
      })
    )
    expect(saved.lines[0]).toMatchObject({
      lineType: "WORK",
      catalogSnapshot: null,
      normativeMinutes: 45,
      customQueueBinding: customBinding,
    })
  })

  it("does not let a custom work line resolve to a movement queue", async () => {
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(
      adapter.saveDraft({
        ...draft,
        lines: [
          {
            ...draft.lines[0],
            lineType: "WORK",
            customQueueBinding: {
              queueId,
              queueName: "Перемещение на ремонт",
              queueKind: "REPAIR",
            },
          },
        ],
        taskPlans: completeCommand.taskPlans.map((plan) => ({
          ...plan,
          queueName: "Перемещение на ремонт",
          routeQueueKind: "MOVEMENT",
        })),
      })
    ).rejects.toThrow("Пользовательская работа")
    expect(lifecycle.create).not.toHaveBeenCalled()
  })

  it("rejects a custom work line before routing it to a furniture movement queue", async () => {
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
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(adapter.complete(completeCommand)).rejects.toThrow(
      "Пользовательская работа"
    )
    expect(lifecycle.create).not.toHaveBeenCalled()
  })

  it("rejects a route whose queue UUID is not active in the current warehouse", async () => {
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await expect(
      adapter.complete({
        ...completeCommand,
        taskPlans: completeCommand.taskPlans.map((plan) => ({
          ...plan,
          queueId: foreignQueueId,
        })),
      })
    ).rejects.toThrow(
      "Очередь «Ремонт» не материализована для выбранного склада или отключена. Проверьте «Настройки доски задач → Каталог очередей» и синхронизацию склада."
    )
    expect(lifecycle.create).not.toHaveBeenCalled()
  })

  it("passes both estimate and linked-repair CAS versions on amendment", async () => {
    lifecycle.amend.mockResolvedValue({
      estimate: estimate("COMPLETED", 4),
      delivery: { state: "DELIVERED" },
    })
    const adapter = new HttpMaintenanceRepairEstimatesAdapter(
      rentalItemsClient,
      async () => "token"
    )

    await adapter.amendCompleted({
      ...completeCommand,
      estimateId,
      expectedVersion: 3,
      expectedLinkedRepairVersion: 8,
      reason: "Уточнён объём",
    })

    expect(lifecycle.amend).toHaveBeenCalledWith(
      "token",
      warehouseId,
      estimateId,
      expect.any(String),
      expect.objectContaining({
        expectedVersion: 3,
        expectedLinkedRepairVersion: 8,
        reason: "Уточнён объём",
      })
    )
  })
})
