import { beforeEach, describe, expect, it, vi } from "vitest"
import {
  LocalStorageRepairTasksAdapter,
  REPAIR_TASKS_MOCK_STORAGE_KEY,
} from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import type { RepairTaskFromInventoryFindingCommand } from "@/features/repair-tasks/model/repair-task"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"

const command: RepairTaskFromInventoryFindingCommand = {
  warehouseId: "spb",
  rentalItemId: "r-1",
  cabinNumber: "БЫТ-1",
  authorName: "Кладовщик",
  sourceInventoryId: "inventory-1",
  sourceInventoryFindingId: "finding-1",
  reason: "Инвентаризация",
  dispatchDate: "2026-07-11",
  comment: "",
  media: [],
  subtasks: [
    {
      id: "subtask-1",
      kind: "REPAIR_WORK",
      status: "WAITING",
      workLines: [
        {
          id: "work-1",
          sourceLineKey: "work-1",
          lineType: "WORK",
          description: "Замена двери",
          lineComment: "",
          unit: "шт",
          quantity: 1,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: null,
        },
      ],
      materialLines: [],
      groupComment: "",
      queueCode: null,
      routeQueueKind: null,
      sortOrder: 10,
      queuePosition: 0,
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
    },
  ],
}

describe("LocalStorageRepairTasksAdapter inventory upsert", () => {
  beforeEach(() => window.localStorage.clear())

  it("uses the fallback lease and keeps the source key idempotent without Web Locks", async () => {
    expect(navigator.locks).toBeUndefined()
    const rentalItemsClient: RepairTaskRentalItemsClient = {
      resolveById: vi.fn(async () => ({
        id: command.rentalItemId,
        warehouseId: command.warehouseId,
        number: command.cabinNumber,
      })),
      updateStatus: vi.fn(async () => undefined),
    }
    const setItem = vi.spyOn(window.localStorage, "setItem")
    const first = new LocalStorageRepairTasksAdapter(rentalItemsClient)
    const second = new LocalStorageRepairTasksAdapter(rentalItemsClient)

    const [left, right] = await Promise.all([
      first.upsertByInventoryFinding(command),
      second.upsertByInventoryFinding(command),
    ])

    expect(left.id).toBe(right.id)
    expect(await first.list(command.warehouseId)).toHaveLength(1)
    expect(
      setItem.mock.calls.some(
        ([key]) => key === "rwms:repair-tasks:mutation:lease"
      )
    ).toBe(true)
    const envelope = JSON.parse(
      window.localStorage.getItem(REPAIR_TASKS_MOCK_STORAGE_KEY) ?? "{}"
    ) as { tasks?: unknown[] }
    expect(envelope.tasks).toHaveLength(1)
  })
})
