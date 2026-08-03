import { beforeEach, describe, expect, it, vi } from "vitest"

const settings = vi.hoisted(() => ({
  listGroups: vi.fn(),
  listQueues: vi.fn(),
  listWorkers: vi.fn(),
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: settings,
}))

import { HttpRepairWorkerDirectoryAdapter } from "@/features/repair-tasks/adapters/http-repair-worker-directory-adapter"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const WORKER_CLASS_ID = "22222222-2222-4222-8222-222222222222"
const GROUP_ID = "33333333-3333-4333-8333-333333333333"
const WORKER_ID = "44444444-4444-4444-8444-444444444444"
const QUEUE_ID = "66666666-6666-4666-8666-666666666666"

const driverClass = {
  id: WORKER_CLASS_ID,
  version: 1,
  name: "Водитель",
  description: null,
  comment: null,
  sortOrder: 1,
  active: true,
}

beforeEach(() => {
  settings.listQueues.mockResolvedValue([
    {
      id: QUEUE_ID,
      version: 1,
      warehouseId: WAREHOUSE_ID,
      purpose: "LOGISTICS_DRIVER",
      name: "Перемещение",
      description: null,
      type: "MOVEMENT",
      sortOrder: 0,
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      resultPhotoMinCount: 0,
      bindings: [
        {
          id: "77777777-7777-4777-8777-777777777777",
          version: 1,
          workerClass: driverClass,
          order: 0,
          primary: true,
          stopTaskOnTake: false,
          notifyUrgent: false,
        },
      ],
    },
  ])
  settings.listWorkers.mockResolvedValue([
    {
      id: WORKER_ID,
      version: 1,
      warehouseId: WAREHOUSE_ID,
      displayName: "Алексей Водитель",
      firstName: "Алексей",
      lastName: "Водитель",
      middleName: null,
      active: true,
      comment: null,
      appLogin: null,
      credentialStatus: "NOT_CONFIGURED",
      credentialError: null,
      qualifications: [
        {
          id: "99999999-9999-4999-8999-999999999999",
          version: 1,
          workerClass: driverClass,
          active: true,
          comment: null,
        },
      ],
    },
    {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      version: 1,
      warehouseId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      displayName: "Водитель другого города",
      firstName: null,
      lastName: null,
      middleName: null,
      active: true,
      comment: null,
      appLogin: null,
      credentialStatus: "NOT_CONFIGURED",
      credentialError: null,
      qualifications: [
        {
          id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          version: 1,
          workerClass: driverClass,
          active: true,
          comment: null,
        },
      ],
    },
  ])
  settings.listGroups.mockResolvedValue([
    {
      id: GROUP_ID,
      version: 1,
      warehouseId: WAREHOUSE_ID,
      workerClass: driverClass,
      name: "Водители",
      description: null,
      active: true,
      members: [
        {
          id: "55555555-5555-4555-8555-555555555555",
          version: 1,
          workerId: WORKER_ID,
          workerName: "Алексей Водитель",
          active: true,
        },
      ],
    },
  ])
})

describe("HttpRepairWorkerDirectoryAdapter", () => {
  it("uses active standalone workers qualified for this warehouse's driver queue", async () => {
    const adapter = new HttpRepairWorkerDirectoryAdapter()

    await expect(
      adapter.listGroups(
        {
          warehouseId: WAREHOUSE_ID,
          queueId: null,
          routeQueueKind: "MOVEMENT",
          purpose: "DRIVER_DIRECTORY",
        },
        "settings-token"
      )
    ).resolves.toEqual([
      {
        id: QUEUE_ID,
        warehouseId: WAREHOUSE_ID,
        name: "Перемещение",
        active: true,
        queueIds: [QUEUE_ID],
        routeQueueKinds: ["MOVEMENT"],
        members: [{ id: WORKER_ID, name: "Алексей Водитель" }],
      },
    ])

    expect(settings.listGroups).not.toHaveBeenCalled()
  })

  it("keeps the group directory for ordinary task assignment", async () => {
    const adapter = new HttpRepairWorkerDirectoryAdapter()

    await expect(
      adapter.listGroups(
        {
          warehouseId: WAREHOUSE_ID,
          queueId: null,
          routeQueueKind: "MOVEMENT",
          purpose: "TASK_ASSIGNMENT",
        },
        "settings-token"
      )
    ).resolves.toEqual([
      {
        id: GROUP_ID,
        warehouseId: WAREHOUSE_ID,
        name: "Водители",
        active: true,
        queueIds: [QUEUE_ID],
        routeQueueKinds: ["MOVEMENT"],
        members: [{ id: WORKER_ID, name: "Алексей Водитель" }],
      },
    ])

    expect(settings.listGroups).toHaveBeenCalledWith(
      "settings-token",
      WAREHOUSE_ID
    )
  })
})
