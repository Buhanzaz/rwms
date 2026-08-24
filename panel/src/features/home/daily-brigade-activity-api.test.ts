import { afterEach, describe, expect, it, vi } from "vitest"

import { getDailyBrigadeActivity } from "@/features/home/daily-brigade-activity-api"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("daily brigade activity API", () => {
  it("accepts the authoritative take-to-finish interval", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          warehouseId: "warehouse-1",
          localDate: "2026-08-24",
          serverTime: "2026-08-24T09:30:00Z",
          intervals: [
            {
              workerGroupId: "group-1",
              workerGroupName: "Электрики",
              taskId: "task-1",
              entryId: "entry-1",
              queueId: "queue-1",
              queueName: "Электрика",
              title: "230306",
              unitNumber: "230306",
              taskText: "Замена розетки",
              priority: 3,
              startedAt: "2026-08-24T06:34:27Z",
              finishedAt: "2026-08-24T08:06:13Z",
              status: "DONE",
              source: {
                type: "MAINTENANCE_REPAIR",
                sourceId: "repair-1",
              },
            },
          ],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    const result = await getDailyBrigadeActivity("token", "warehouse-1")

    expect(result.intervals[0]).toMatchObject({
      startedAt: "2026-08-24T06:34:27Z",
      finishedAt: "2026-08-24T08:06:13Z",
      status: "DONE",
    })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it("rejects a completed interval without its finish timestamp", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            warehouseId: "warehouse-1",
            localDate: "2026-08-24",
            serverTime: "2026-08-24T09:30:00Z",
            intervals: [
              {
                workerGroupId: "group-1",
                workerGroupName: "Электрики",
                taskId: "task-1",
                entryId: "entry-1",
                queueId: "queue-1",
                queueName: "Электрика",
                title: "230306",
                unitNumber: null,
                taskText: null,
                priority: 3,
                startedAt: "2026-08-24T06:34:27Z",
                finishedAt: null,
                status: "DONE",
                source: null,
              },
            ],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      getDailyBrigadeActivity("token", "warehouse-1")
    ).rejects.toThrow("некорректную статистику дня")
  })
})
