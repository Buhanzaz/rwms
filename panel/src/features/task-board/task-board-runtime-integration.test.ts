import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { BrowserLogisticsPreparationTaskClient } from "@/features/logistics/adapters/browser-logistics-preparation-task-client"
import { BrowserWarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/adapters/browser-warehouse-transfer-task-client"
import { MockRepairWorkerDirectoryAdapter } from "@/features/repair-tasks/adapters/mock-repair-worker-directory-adapter"
import { BrowserContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/browser-contents-transfer-task-client"
import {
  createMockTaskBoardProjection,
  mergeQueueCollapsedSettings,
} from "@/features/task-board/domain/task-board-domain"
import {
  BrowserTaskBoardClient,
  SPB_WAREHOUSE_ID,
  taskBoardMockClient,
} from "@/features/task-board/mock"

describe("task-board browser runtime integration", () => {
  beforeEach(() => {
    taskBoardMockClient.resetForTests()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it("projects configured queue order and keeps the virtual unassigned column", async () => {
    const snapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    const projection = createMockTaskBoardProjection(snapshot)

    expect(projection.now).toBe(snapshot.now)
    expect(projection.queues.at(-1)?.kind).toBe("HOLDING")
    expect(projection.queues.at(-2)?.key).toBe("unassigned")
    expect(projection.queues.some((queue) => queue.settingsCollapsed)).toBe(
      snapshot.queues.some((queue) => queue.collapsed)
    )
    expect(projection.realEntries).toBeGreaterThan(0)
    expect(projection.shadowEntries).toBeGreaterThan(0)
  })

  it("merges changed collapsed settings without resetting a local toggle", async () => {
    const projection = createMockTaskBoardProjection(
      await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    )
    const initial = mergeQueueCollapsedSettings({
      current: new Set(),
      previous: null,
      queues: projection.queues,
      reset: true,
    })
    const locallyOpened = new Set(initial.collapsed)
    locallyOpened.delete(projection.queues.at(-1)!.key)
    const unchanged = mergeQueueCollapsedSettings({
      current: locallyOpened,
      previous: initial.settings,
      queues: projection.queues,
      reset: false,
    })
    expect(unchanged.collapsed.has(projection.queues.at(-1)!.key)).toBe(false)

    const changedQueues = projection.queues.map((queue) =>
      queue.key === "code:ELECTRICS"
        ? { ...queue, settingsCollapsed: true }
        : queue
    )
    const changed = mergeQueueCollapsedSettings({
      current: unchanged.collapsed,
      previous: unchanged.settings,
      queues: changedQueues,
      reset: false,
    })
    expect(changed.collapsed.has("code:ELECTRICS")).toBe(true)
  })

  it("keeps canonical warehouse UUID partitions isolated in the browser test fixture", async () => {
    const spbSnapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    const mskSnapshot = await taskBoardMockClient.getSnapshot(
      "00000000-0000-0000-0000-000000000002"
    )

    expect(spbSnapshot.queues).toHaveLength(8)
    expect(spbSnapshot.workers).toHaveLength(18)
    expect(spbSnapshot.tasks.some((task) => task.demo)).toBe(true)
    expect(mskSnapshot.queues).toHaveLength(8)
    expect(mskSnapshot.workers).toHaveLength(0)
  })

  it("projects advancing simulated time when the mock clock is running", async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date("2026-07-12T09:00:00.000Z"))
    const clock = await taskBoardMockClient.getSimulationClock()
    await taskBoardMockClient.updateSimulationClock({
      expectedVersion: clock.version,
      mode: "SIMULATION",
      now: "2026-07-12T09:00:00.000Z",
      running: true,
      speed: 60,
    })
    const first = createMockTaskBoardProjection(
      await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    )
    vi.advanceTimersByTime(2_000)
    const second = createMockTaskBoardProjection(
      await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    )

    expect(Date.parse(second.now!) - Date.parse(first.now!)).toBe(120_000)
  })

  it("registers logistics tasks idempotently in the shared board", async () => {
    const preparation = new BrowserLogisticsPreparationTaskClient()
    const command = {
      serviceWarehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: "shipment:test:prepare",
      cabinNumber: "БЫТ-001",
      taskText: "Принести 2 стула",
    }

    const first = await preparation.dispatch("dev", command)
    const second = await preparation.dispatch("dev", command)
    const task = await taskBoardMockClient.findByExternalTaskId(
      SPB_WAREHOUSE_ID,
      command.externalTaskId
    )

    expect(second.boardTaskId).toBe(first.boardTaskId)
    expect(task?.description).toContain("2 стула")
  })

  it("registers contents and warehouse transfers through the same runtime", async () => {
    const contents = new BrowserContentsTransferTaskClient()
    const contentsTask = await contents.dispatch("dev", {
      serviceWarehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: "contents:test:1",
      source: {
        rentalItemId: "source",
        number: "БЫТ-001",
        expectedVersion: 0,
      },
      target: {
        rentalItemId: "target",
        number: "БЫТ-002",
        expectedVersion: 0,
      },
      items: [{ name: "Стул", quantity: 2 }],
    })
    const transfers = new BrowserWarehouseTransferTaskClient()
    const transferTask = await transfers.dispatch("dev", {
      externalTaskId: "warehouse-transfer:test:1",
      serviceWarehouseId: SPB_WAREHOUSE_ID,
      direction: "SOURCE",
      sourceWarehouse: {
        id: SPB_WAREHOUSE_ID,
        code: "СПБ",
        name: "Склад СПБ",
        city: "Санкт-Петербург",
      },
      destinationWarehouse: {
        id: "00000000-0000-0000-0000-000000000002",
        code: "МСК",
        name: "Склад МСК",
        city: "Москва",
      },
      cabin: {
        rentalItemId: "target",
        cabinNumber: "БЫТ-002",
        contentsSnapshot: [{ name: "Стул", quantity: 2 }],
      },
      plannedDate: "2026-07-12",
      driverName: "Алексей Соколов",
    })

    expect(contentsTask.boardTaskId).not.toBe(transferTask.boardTaskId)
    expect(
      await taskBoardMockClient.findByExternalTaskId(
        SPB_WAREHOUSE_ID,
        "contents:test:1"
      )
    ).not.toBeNull()
    expect(
      await taskBoardMockClient.findByExternalTaskId(
        SPB_WAREHOUSE_ID,
        "warehouse-transfer:test:1"
      )
    ).not.toBeNull()
  })

  it("derives eligible crews and logistics drivers from mock settings", async () => {
    const client = new BrowserTaskBoardClient()
    const directory = new MockRepairWorkerDirectoryAdapter(client)
    const movementGroups = await directory.listGroups({
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      routeQueueKind: "MOVEMENT",
    })
    const logisticsDirectory = await directory.listGroups({
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      purpose: "DRIVER_DIRECTORY",
    })

    expect(movementGroups.map((group) => group.name)).toEqual([
      "Разнорабочие 1",
      "Разнорабочие 2",
    ])
    expect(logisticsDirectory.some((group) => group.name === "Водители")).toBe(
      true
    )
    expect(logisticsDirectory).toHaveLength(1)
    expect(
      logisticsDirectory
        .find((group) => group.name === "Водители")
        ?.members.map((worker) => worker.name)
    ).toEqual(["Алексей Соколов", "Дмитрий Орлов"])
  })
})
