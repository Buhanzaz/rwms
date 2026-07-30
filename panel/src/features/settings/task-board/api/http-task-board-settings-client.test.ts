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

  it("sends only ordered class bindings when saving a queue", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      async () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        })
    )
    const client = new HttpTaskBoardSettingsClient()

    await client.createQueue("token", "warehouse/id", {
      version: 0,
      definitionId: "definition-id",
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
          notifyUrgent: true,
        },
      ],
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/work-queues"
    )
    expect(init?.method).toBe("POST")
    expect(JSON.parse(String(init?.body))).toEqual({
      version: 0,
      definitionId: "definition-id",
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
          notifyUrgent: true,
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

    await client.createQueueDefinition("token", {
      version: 0,
      name: "Внешние работы",
      description: null,
      type: "REPAIR",
    })
    await client.deleteQueueDefinition("token", "definition/id", 4)

    expect(
      fetchMock.mock.calls.map(([input, init]) => ({
        path: new URL(String(input)).pathname,
        search: new URL(String(input)).search,
        method: init?.method,
      }))
    ).toEqual([
      {
        path: "/api/task-board/queue-definitions",
        search: "",
        method: "POST",
      },
      {
        path: "/api/task-board/queue-definitions/definition%2Fid",
        search: "?expectedVersion=4",
        method: "DELETE",
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

    await client.setWorkerCurrentGroup(
      "token",
      "warehouse/id",
      "worker/id",
      4,
      "group/id"
    )
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
        path: "/api/task-board/warehouses/warehouse%2Fid/workers/worker%2Fid/current-group",
        method: "PUT",
        body: { expectedVersion: 4, workerGroupId: "group/id" },
      },
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
