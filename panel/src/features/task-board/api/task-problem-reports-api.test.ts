import { afterEach, describe, expect, it, vi } from "vitest"

import {
  listTaskProblemReports,
  markTaskProblemReportRead,
  taskProblemReportsQueryKey,
} from "@/features/task-board/api/task-problem-reports-api"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const reportId = "00000000-0000-4000-8000-000000000002"

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const page = {
  reports: [
    {
      reportId,
      entryId: "00000000-0000-4000-8000-000000000003",
      taskId: "00000000-0000-4000-8000-000000000004",
      routeIndex: 0,
      entryTitle: "Проверить замок",
      workerName: "Иван Петров",
      comment: "Не закрывается.",
      occurredAt: "2026-09-09T10:00:00Z",
      recordedAt: "2026-09-09T10:01:00Z",
      readAt: null,
      attachments: [
        {
          evidenceId: "00000000-0000-4000-8000-000000000005",
          state: "READY",
          capturedAt: "2026-09-09T10:00:00Z",
          recordedAt: "2026-09-09T10:01:00Z",
          mediaId: "00000000-0000-4000-8000-000000000006",
          mediaGeneration: 1,
          reviewReason: null,
          contentType: "image/jpeg",
          readPath: "/api/media/v1/assets/asset/content",
          thumbnailPath: "/api/media/v1/assets/asset/thumbnail",
        },
      ],
    },
  ],
  nextCursor: "older-page",
  unreadCount: 1,
}

describe("task problem reports API", () => {
  afterEach(() => vi.unstubAllGlobals())

  it("loads a cursor page and sends a personal read receipt", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(page))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listTaskProblemReports("token", warehouseId, "older page")
    ).resolves.toMatchObject(page)
    await markTaskProblemReportRead("token", warehouseId, reportId)

    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/warehouses/${warehouseId}/task-problem-reports?limit=50&cursor=older+page`
    )
    expect(
      new Headers(fetchMock.mock.calls[0]![1]?.headers).get("Authorization")
    ).toBe("Bearer token")
    expect(String(fetchMock.mock.calls[1]![0])).toContain(`${reportId}/read`)
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: "PUT" })
    expect(taskProblemReportsQueryKey(warehouseId, "user-1")).toEqual([
      "task-board",
      "problem-reports",
      warehouseId,
      "user-1",
    ])
  })
})
