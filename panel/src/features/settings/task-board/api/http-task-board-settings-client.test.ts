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
    await client.bootstrapReviewedData(
      "token",
      "warehouse/id",
      "00000000-0000-4000-8000-000000000001"
    )

    expect(
      fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)
    ).toEqual([
      "/api/task-board/warehouses/warehouse%2Fid/work-queues",
      "/api/task-board/worker-classes",
      "/api/task-board/warehouses/warehouse%2Fid/reviewed-bootstrap",
    ])
    expect(fetchMock.mock.calls[2]?.[1]?.method).toBe("POST")
    expect(
      new Headers(fetchMock.mock.calls[2]?.[1]?.headers).get("Idempotency-Key")
    ).toBe("00000000-0000-4000-8000-000000000001")
  })
})
