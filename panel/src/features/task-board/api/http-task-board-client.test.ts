import { afterEach, describe, expect, it, vi } from "vitest"

import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  moveHttpTaskBoardEntry,
} from "@/features/task-board/api/http-task-board-client"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const queueId = "00000000-0000-4000-8000-000000000002"
const entryId = "00000000-0000-4000-8000-000000000003"
const taskId = "00000000-0000-4000-8000-000000000004"
const externalTaskId = "00000000-0000-4000-8000-000000000005"

const boardResponse = {
  warehouseId,
  columns: [
    {
      queueId,
      queueCode: "REPAIR",
      queueName: "Ремонт",
      queueType: "REPAIR",
      sortOrder: 10,
      virtual: false,
      entries: [
        {
          id: entryId,
          version: 7,
          taskId,
          externalTaskId,
          taskVersion: 42,
          title: "Замена панели",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          queueId,
          queueCode: "REPAIR",
          routeIndex: 0,
          queuePosition: 3,
          entryType: "REAL",
          status: "WAITING",
          taskText: null,
          plannedDurationMinutes: 30,
          activeStartedAt: null,
          pausedAt: null,
          activeWorkSeconds: 0,
          assignments: [],
        },
        {
          id: "00000000-0000-4000-8000-000000000006",
          version: 3,
          taskId,
          externalTaskId,
          taskVersion: 42,
          title: "Финишная проверка",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          queueId,
          queueCode: "REPAIR",
          routeIndex: 1,
          queuePosition: 4,
          entryType: "SHADOW",
          status: "WAITING",
          taskText: null,
          plannedDurationMinutes: 15,
          activeStartedAt: null,
          pausedAt: null,
          activeWorkSeconds: 0,
          assignments: [],
        },
      ],
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
    expect(entry.detailsHref).toBe(`/repairs?repairId=${externalTaskId}`)

    await completeHttpTaskBoardEntry("task-board-token", entry)
    await moveHttpTaskBoardEntry("task-board-token", entry, queueId, 1)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board?includeShadow=true`
    )
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
    expect(JSON.parse(String(fetchMock.mock.calls[2]![1]?.body))).toEqual({
      expectedVersion: 7,
      targetQueueId: queueId,
      targetIndex: 1,
    })
  })

  it("loads eligible groups only from the public queue route", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json([]))
    vi.stubGlobal("fetch", fetchMock)

    await listHttpEligibleWorkerGroups("token", warehouseId, queueId)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/api/task-board/warehouses/${warehouseId}/task-board/queues/${queueId}/eligible-groups`
    )
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
})
