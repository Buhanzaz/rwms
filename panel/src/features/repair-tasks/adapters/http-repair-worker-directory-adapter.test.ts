import { beforeEach, describe, expect, it, vi } from "vitest"

const settings = vi.hoisted(() => ({
  listClasses: vi.fn(),
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

const driverClass = {
  id: WORKER_CLASS_ID,
  version: 1,
  code: "DRIVER_WORKER",
  name: "Водитель",
  description: null,
  comment: null,
  sortOrder: 1,
  active: true,
}

beforeEach(() => {
  settings.listClasses.mockResolvedValue([driverClass])
  settings.listQueues.mockResolvedValue([])
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
      qualifications: [],
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
          roleInGroup: null,
          active: true,
        },
      ],
    },
  ])
})

describe("HttpRepairWorkerDirectoryAdapter", () => {
  it("uses active members of the configured DRIVER_WORKER group as drivers", async () => {
    const adapter = new HttpRepairWorkerDirectoryAdapter()

    await expect(
      adapter.listGroups(
        {
          warehouseId: WAREHOUSE_ID,
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          purpose: "DRIVER_DIRECTORY",
        },
        "settings-token"
      )
    ).resolves.toEqual([
      {
        id: GROUP_ID,
        warehouseId: WAREHOUSE_ID,
        name: "Водители",
        active: true,
        queueCodes: [],
        routeQueueKinds: [],
        members: [{ id: WORKER_ID, name: "Алексей Водитель" }],
      },
    ])
  })
})
