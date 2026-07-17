import { afterEach, describe, expect, it, vi } from "vitest"

import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
} from "@/features/task-board/api/http-task-board-client"

const boardResponse = {
  warehouseId: "00000000-0000-4000-8000-000000000001",
  columns: [
    {
      queueId: "00000000-0000-4000-8000-000000000002",
      queueCode: "REPAIR",
      queueName: "Ремонт",
      queueType: "REPAIR",
      sortOrder: 10,
      virtual: false,
      entries: [
        {
          id: "00000000-0000-4000-8000-000000000003",
          version: 7,
          taskId: "00000000-0000-4000-8000-000000000004",
          externalTaskId: "00000000-0000-4000-8000-000000000005",
          taskVersion: 42,
          title: "Замена панели",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          queueId: "00000000-0000-4000-8000-000000000002",
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
          taskId: "00000000-0000-4000-8000-000000000004",
          externalTaskId: "00000000-0000-4000-8000-000000000005",
          taskVersion: 42,
          title: "Финишная проверка",
          unitNumber: "CAB-17",
          taskStatus: "ACTIVE",
          queueId: "00000000-0000-4000-8000-000000000002",
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

describe("public task-board HTTP client", () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("uses the public board route and entry CAS for operator completion", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(boardResponse), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({}), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    const board = await getHttpTaskBoard(
      "task-board-token",
      boardResponse.warehouseId
    )
    const entry = board.queues[0]!.entries[0]!
    expect(board.queues[0]!.entries.map((item) => item.routeLength)).toEqual([
      2, 2,
    ])
    await completeHttpTaskBoardEntry("task-board-token", entry)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/api/task-board/warehouses/${boardResponse.warehouseId}/task-board?includeShadow=true`
    )
    const [completeUrl, completeInit] = fetchMock.mock.calls[1] as [
      string,
      RequestInit,
    ]
    expect(completeUrl).toContain(
      `/api/task-board/warehouses/${boardResponse.warehouseId}/task-board/entries/00000000-0000-4000-8000-000000000003/complete`
    )
    expect(JSON.parse(String(completeInit.body))).toEqual({
      expectedVersion: 7,
    })
    expect(new Headers(completeInit.headers).get("Authorization")).toBe(
      "Bearer task-board-token"
    )
  })
})
