import { describe, expect, it, vi } from "vitest"

import { HttpTaskBoardSettingsClient } from "@/features/settings/task-board/api/http-task-board-settings-client"

describe("HttpTaskBoardSettingsClient gateway routes", () => {
  it("uses the same-origin public task-board prefix", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("[]", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()

    await client.listQueues("token", "warehouse/id")
    await client.listClasses("token")

    expect(
      fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)
    ).toEqual([
      "/api/task-board/warehouses/warehouse%2Fid/work-queues",
      "/api/task-board/worker-classes",
    ])
  })
})
