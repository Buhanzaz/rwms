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

    await client.listQueueDefinitions("token")
    await client.listQueues("token", "warehouse/id")
    await client.listClasses("token")

    expect(
      fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)
    ).toEqual([
      "/api/task-board/queue-definitions",
      "/api/task-board/warehouses/warehouse%2Fid/work-queues",
      "/api/task-board/worker-classes",
    ])
  })

  it("uses the dedicated driver endpoint for warehouse-local settings", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()

    await client.updateDriverQueue("token", "warehouse/id", {
      expectedVersion: 4,
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      resultPhotoMinCount: 1,
      bindings: [
        {
          workerClassId: "class-id",
          order: 0,
          stopTaskOnTake: true,
          participationPolicy: "PRIMARY",
          notifyOnPrimaryTake: false,
        },
      ],
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/driver-queue"
    )
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(String(init?.body))).toEqual({
      expectedVersion: 4,
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      resultPhotoMinCount: 1,
      bindings: [
        {
          workerClassId: "class-id",
          order: 0,
          stopTaskOnTake: true,
          participationPolicy: "PRIMARY",
          notifyOnPrimaryTake: false,
        },
      ],
    })
  })

  it("uses the system queue catalog endpoints with aggregate versions", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()
    const request = {
      version: 0,
      name: "Внешние работы",
      description: null,
      type: "REPAIR" as const,
      purpose: "GENERAL" as const,
      sortOrder: 4,
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      resultPhotoMinCount: 1,
      bindings: [],
    }

    await client.createQueueDefinition("token", request)
    await client.updateQueueDefinition("token", "definition/id", {
      ...request,
      version: 4,
    })
    await client.deleteQueueDefinition("token", "definition/id", 4)
    await client.reorderQueueDefinitions("token", [
      { definitionId: "definition/id", expectedVersion: 4 },
    ])

    expect(
      fetchMock.mock.calls.map(([input, init]) => ({
        path: new URL(String(input)).pathname,
        search: new URL(String(input)).search,
        method: init?.method,
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      }))
    ).toEqual([
      {
        path: "/api/task-board/queue-definitions",
        search: "",
        method: "POST",
        body: request,
      },
      {
        path: "/api/task-board/queue-definitions/definition%2Fid",
        search: "",
        method: "PUT",
        body: { ...request, version: 4 },
      },
      {
        path: "/api/task-board/queue-definitions/definition%2Fid",
        search: "?expectedVersion=4",
        method: "DELETE",
        body: undefined,
      },
      {
        path: "/api/task-board/queue-definitions/order",
        search: "",
        method: "PUT",
        body: {
          definitions: [{ definitionId: "definition/id", expectedVersion: 4 }],
        },
      },
    ])
  })

  it("enables preserved worker credentials through the public gateway", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()

    await client.enableWorkerCredentials(
      "token",
      "warehouse/id",
      "worker/id",
      7
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/workers/worker%2Fid/credentials/enable"
    )
    expect(init?.method).toBe("POST")
    expect(init?.body).toBe(JSON.stringify({ expectedVersion: 7 }))
  })

  it("uses warehouse-scoped workforce operational commands with CAS bodies", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()

    await client.disableGroup(
      "token",
      "warehouse/id",
      "group/id",
      8,
      "Пересменка"
    )
    await client.enableGroup("token", "warehouse/id", "group/id", 9, null)

    expect(
      fetchMock.mock.calls.map(([input, init]) => ({
        path: new URL(String(input)).pathname,
        method: init?.method,
        body: JSON.parse(String(init?.body)),
      }))
    ).toEqual([
      {
        path: "/api/task-board/warehouses/warehouse%2Fid/worker-groups/group%2Fid/disable",
        method: "POST",
        body: { expectedVersion: 8, reason: "Пересменка" },
      },
      {
        path: "/api/task-board/warehouses/warehouse%2Fid/worker-groups/group%2Fid/enable",
        method: "POST",
        body: { expectedVersion: 9, reason: null },
      },
    ])
  })
})
