import { afterEach, describe, expect, it, vi } from "vitest"

import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  pinHttpTaskBoardEntry,
  reorderHttpTaskBoardEntry,
  setHttpFutureTaskBoardEntryAvailability,
  updateHttpTaskBoardWorkerPlan,
} from "@/features/task-board/api/http-task-board-client"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const queueId = "00000000-0000-4000-8000-000000000002"
const furnitureQueueId = "00000000-0000-4000-8000-000000000007"
const entryId = "00000000-0000-4000-8000-000000000003"
const taskId = "00000000-0000-4000-8000-000000000004"
const externalTaskId = "00000000-0000-4000-8000-000000000005"
const repairSourceId = "repair id/with space"

const boardResponse = {
  warehouseId,
  columns: [
    {
      queueId,
      queueName: "Ремонт",
      queueType: "REPAIR",
      queueVersion: 4,
      workerFeedEnabled: true,
      sortOrder: 10,
      availableTaskLimit: 6,
      entries: [
        {
          id: entryId,
          version: 7,
          taskId,
          externalTaskId,
          source: { type: "MAINTENANCE_REPAIR", sourceId: repairSourceId },
          taskVersion: 42,
          title: "Замена панели",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          scheduledDate: "2026-07-18",
          priority: 1,
          pinned: true,
          queueId,
          routeIndex: 0,
          queuePosition: 3,
          entryType: "REAL",
          status: "WAITING",
          taskText: null,
          plannedDurationMinutes: 30,
          activeStartedAt: null,
          pausedAt: null,
          activeWorkSeconds: 0,
          timerSnapshot: {
            countedActiveSeconds: 600,
            remainingSeconds: 1_200,
            remainingPercent: 66.6667,
            timerState: "BREAK",
            nextTransitionAt: "2026-07-18T10:15:00Z",
            serverTime: "2026-07-18T10:05:00Z",
          },
          assignments: [],
        },
        {
          id: "00000000-0000-4000-8000-000000000006",
          version: 3,
          taskId,
          externalTaskId,
          source: { type: "MAINTENANCE_REPAIR", sourceId: repairSourceId },
          taskVersion: 42,
          title: "Финишная проверка",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          scheduledDate: "2026-07-18",
          priority: 1,
          pinned: true,
          queueId,
          routeIndex: 1,
          queuePosition: 4,
          entryType: "SHADOW",
          status: "WAITING",
          taskText: null,
          plannedDurationMinutes: 15,
          activeStartedAt: null,
          pausedAt: null,
          activeWorkSeconds: 0,
          timerSnapshot: null,
          assignments: [],
        },
      ],
    },
    {
      queueId: furnitureQueueId,
      queueName: "Перемещение мебели",
      queueType: "FURNITURE_MOVEMENT",
      queueVersion: 2,
      workerFeedEnabled: false,
      sortOrder: 20,
      availableTaskLimit: 6,
      entries: [],
    },
  ],
}

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

describe("public task-board HTTP client", () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("loads the public snapshot, maps source links and sends CAS commands", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(boardResponse))
      .mockResolvedValueOnce(json({}))
      .mockResolvedValueOnce(json(boardResponse))
    vi.stubGlobal("fetch", fetchMock)

    const board = await getHttpTaskBoard("task-board-token", warehouseId)
    const entry = board.queues[0]!.entries[0]!
    expect(board.queues[0]!.entries.map((item) => item.routeLength)).toEqual([
      2, 2,
    ])
    expect(entry.source).toEqual({
      type: "MAINTENANCE_REPAIR",
      sourceId: repairSourceId,
    })
    expect(entry.detailsHref).toBe(
      `/repairs?repairId=${encodeURIComponent(repairSourceId)}`
    )
    expect(entry.timerSnapshot).toEqual({
      countedActiveSeconds: 600,
      remainingSeconds: 1_200,
      remainingPercent: 66.6667,
      timerState: "BREAK",
      nextTransitionAt: "2026-07-18T10:15:00Z",
      serverTime: "2026-07-18T10:05:00Z",
    })
    expect(board.queues[1]).toMatchObject({
      key: furnitureQueueId,
      version: 2,
      kind: "FURNITURE_MOVEMENT",
      label: "Перемещение мебели",
      settingsQueueId: furnitureQueueId,
      workerFeedEnabled: false,
    })

    await completeHttpTaskBoardEntry("task-board-token", entry)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board`
    )
    expect(String(fetchMock.mock.calls[0]![0])).not.toContain("?")
    const [completeUrl, completeInit] = fetchMock.mock.calls[1] as [
      string,
      RequestInit,
    ]
    expect(completeUrl).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/entries/${entryId}/complete`
    )
    expect(JSON.parse(String(completeInit.body))).toEqual({
      expectedVersion: 7,
    })
    expect(new Headers(completeInit.headers).get("Authorization")).toBe(
      "Bearer task-board-token"
    )
  })

  it("does not map missing or unknown sources to repair links", async () => {
    const response = {
      ...boardResponse,
      columns: boardResponse.columns.map((column) => ({
        ...column,
        entries: column.entries.map((entry, index) => ({
          ...entry,
          source:
            index === 0
              ? null
              : { type: "UNSUPPORTED_SOURCE", sourceId: externalTaskId },
        })),
      })),
    }
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(response)))

    const board = await getHttpTaskBoard("task-board-token", warehouseId)

    expect(board.queues[0]!.entries.map((entry) => entry.source)).toEqual([
      null,
      null,
    ])
    expect(board.queues[0]!.entries.map((entry) => entry.detailsHref)).toEqual([
      null,
      null,
    ])
  })

  it("loads eligible groups only from the public queue route", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json([]))
    vi.stubGlobal("fetch", fetchMock)

    await listHttpEligibleWorkerGroups("token", warehouseId, queueId)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/queues/${queueId}/eligible-groups`
    )
  })

  it("pins a task with task-version CAS", async () => {
    const fetchMock = vi.fn().mockImplementation(() => json(boardResponse))
    vi.stubGlobal("fetch", fetchMock)
    const entry = (await getHttpTaskBoard("task-board-token", warehouseId))
      .queues[0]!.entries[0]!

    await pinHttpTaskBoardEntry("task-board-token", entry, false)

    expect(String(fetchMock.mock.calls[0]![0])).not.toContain("?")
    const [pinUrl, pinInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(pinUrl).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/tasks/${taskId}/pin`
    )
    expect(JSON.parse(String(pinInit.body))).toEqual({
      expectedTaskVersion: 42,
      pinned: false,
    })
  })

  it("updates the WorkerApp plan with queue-version CAS", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        id: queueId,
        version: 5,
        workerFeedEnabled: false,
        availableTaskLimit: 3,
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    const result = await updateHttpTaskBoardWorkerPlan({
      accessToken: "task-board-token",
      warehouseId,
      queueId,
      expectedVersion: 4,
      workerFeedEnabled: false,
      availableTaskLimit: 3,
    })

    expect(result).toEqual({
      id: queueId,
      version: 5,
      workerFeedEnabled: false,
      availableTaskLimit: 3,
    })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(
      `/api/task-board/warehouses/${warehouseId}/work-queues/${queueId}/worker-plan`
    )
    expect(init.method).toBe("PUT")
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 4,
      workerFeedEnabled: false,
      availableTaskLimit: 3,
    })
  })

  it("reorders a real waiting entry with entry and queue CAS", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(boardResponse))
      .mockResolvedValueOnce(json(boardResponse))
    vi.stubGlobal("fetch", fetchMock)
    const board = await getHttpTaskBoard("task-board-token", warehouseId)
    const queue = board.queues[0]!
    const entry = queue.entries[0]!

    const reordered = await reorderHttpTaskBoardEntry({
      accessToken: "task-board-token",
      entry,
      expectedQueueVersion: queue.version,
      targetEntryId: queue.entries[1]!.id,
      targetIndex: 1,
    })

    expect(reordered.warehouseId).toBe(warehouseId)
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/entries/${entryId}/reorder`
    )
    expect(init.method).toBe("POST")
    expect(JSON.parse(String(init.body))).toEqual({
      expectedEntryVersion: 7,
      expectedQueueVersion: 4,
      targetEntryId: "00000000-0000-4000-8000-000000000006",
      targetIndex: 1,
    })
  })

  it("changes future availability with entry-version CAS", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(boardResponse))
      .mockResolvedValueOnce(json(boardResponse))
    vi.stubGlobal("fetch", fetchMock)
    const futureEntry = (
      await getHttpTaskBoard("task-board-token", warehouseId)
    ).queues[0]!.entries[1]!

    const updated = await setHttpFutureTaskBoardEntryAvailability({
      accessToken: "task-board-token",
      entry: futureEntry,
      available: true,
    })

    expect(updated.warehouseId).toBe(warehouseId)
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/entries/${futureEntry.id}/future-availability`
    )
    expect(init.method).toBe("POST")
    expect(JSON.parse(String(init.body))).toEqual({
      expectedEntryVersion: 3,
      available: true,
    })
  })

  it.each([
    [409, "Версия записи устарела"],
    [403, "Недостаточно прав"],
  ])(
    "preserves service Problem Details for HTTP %s",
    async (status, detail) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(json({ detail }, status))
      )

      await expect(
        getHttpTaskBoard("token", warehouseId)
      ).rejects.toMatchObject({
        name: "ApiError",
        status,
        message: detail,
      } satisfies Partial<ApiError>)
    }
  )

  it("rejects malformed snapshots instead of falling back to browser state", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ warehouseId })))

    await expect(getHttpTaskBoard("token", warehouseId)).rejects.toThrow(
      "некорректный ответ"
    )
  })

  it.each([
    ["a column without a real queue", { queueId: null }],
    ["an unsupported queue type", { queueType: "UNKNOWN_QUEUE" }],
    ["a column without a queue version", { queueVersion: null }],
    ["a column without WorkerApp visibility", { workerFeedEnabled: null }],
  ])("rejects %s instead of creating a fallback queue", async (_, patch) => {
    const response = {
      ...boardResponse,
      columns: [
        {
          ...boardResponse.columns[0],
          ...patch,
        },
      ],
    }
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(response)))

    await expect(getHttpTaskBoard("token", warehouseId)).rejects.toThrow(
      "некорректный ответ"
    )
  })
})
