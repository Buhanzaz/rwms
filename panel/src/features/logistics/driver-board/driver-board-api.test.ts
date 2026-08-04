import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createManualMovement,
  moveDriverBoardTask,
  pinDriverBoardTask,
  promoteCapitalRepair,
  returnCapitalRepair,
  scheduleCapitalRepair,
} from "@/features/logistics/driver-board/driver-board-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const EXTERNAL_TASK_ID = "00000000-0000-4000-8000-000000000002"
const REPAIR_ID = "00000000-0000-4000-8000-000000000003"
const IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000004"

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe("driver board API", () => {
  it("moves a card through the public gateway with both task-board versions", async () => {
    const moved = {
      externalTaskId: EXTERNAL_TASK_ID,
      scheduledDate: "2026-08-02",
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(moved))

    await moveDriverBoardTask({
      accessToken: "driver-token",
      externalTaskId: EXTERNAL_TASK_ID,
      command: {
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
        expectedEntryVersion: 11,
        targetLane: "SCHEDULED",
        targetDate: "2026-08-02",
        targetIndex: 3,
      },
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board/tasks/${EXTERNAL_TASK_ID}/move`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer driver-token"
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      expectedTaskVersion: 7,
      expectedEntryVersion: 11,
      targetLane: "SCHEDULED",
      targetDate: "2026-08-02",
      targetIndex: 3,
    })
  })

  it("serializes an insertion into the current driver queue", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ externalTaskId: EXTERNAL_TASK_ID }))

    await moveDriverBoardTask({
      accessToken: "driver-token",
      externalTaskId: EXTERNAL_TASK_ID,
      command: {
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
        expectedEntryVersion: 11,
        targetLane: "CURRENT",
        targetDate: "2026-08-01",
        targetIndex: 2,
      },
    })

    const [, init] = fetchMock.mock.calls[0]!
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      expectedTaskVersion: 7,
      expectedEntryVersion: 11,
      targetLane: "CURRENT",
      targetDate: "2026-08-01",
      targetIndex: 2,
    })
  })

  it("promotes a capital repair with one idempotent public command", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ id: "driver-task" }, 201))

    await promoteCapitalRepair({
      accessToken: "driver-token",
      repairId: REPAIR_ID,
      warehouseId: WAREHOUSE_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board/capital-repairs/${REPAIR_ID}/promote`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
    })
  })

  it("returns an unfinished capital movement through its logistics workflow", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }))

    await returnCapitalRepair({
      accessToken: "driver-token",
      externalTaskId: EXTERNAL_TASK_ID,
      warehouseId: WAREHOUSE_ID,
      expectedTaskVersion: 7,
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board/tasks/${EXTERNAL_TASK_ID}/return-to-capital-repairs`
    )
    expect(init?.method).toBe("POST")
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      expectedTaskVersion: 7,
    })
  })

  it("schedules a capital repair directly on a selected calendar position", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        jsonResponse({ externalTaskId: EXTERNAL_TASK_ID }, 201)
      )

    await scheduleCapitalRepair({
      accessToken: "driver-token",
      repairId: REPAIR_ID,
      warehouseId: WAREHOUSE_ID,
      targetDate: "2026-08-04",
      targetIndex: 2,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board/capital-repairs/${REPAIR_ID}/schedule`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      targetDate: "2026-08-04",
      targetIndex: 2,
    })
  })

  it("creates one general movement that ignores repair-place allocation", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ id: "manual-driver-task" }, 201))

    await createManualMovement({
      accessToken: "driver-token",
      command: {
        warehouseId: WAREHOUSE_ID,
        cabinId: REPAIR_ID,
        comment: "Переставить к зоне отгрузки",
        priority: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      },
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-tasks`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      cabinId: REPAIR_ID,
      repairId: null,
      sourceType: "MANUAL",
      sourceId: IDEMPOTENCY_KEY,
      kind: "GENERAL_MOVEMENT",
      planningMode: "AUTO",
      scheduledDate: null,
      priority: 2,
      activateNow: true,
      comment: "Переставить к зоне отгрузки",
    })
  })

  it("pins a driver task through the existing task-board command", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ warehouseId: WAREHOUSE_ID }))

    await pinDriverBoardTask({
      accessToken: "driver-token",
      warehouseId: WAREHOUSE_ID,
      taskId: EXTERNAL_TASK_ID,
      expectedTaskVersion: 7,
      pinned: true,
    })

    const [input, init] = fetchMock.mock.calls[0]!
    expect(String(input)).toBe(
      `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/warehouses/${WAREHOUSE_ID}/task-board/tasks/${EXTERNAL_TASK_ID}/pin`
    )
    expect(init?.method).toBe("POST")
    expect(JSON.parse(String(init?.body))).toEqual({
      expectedTaskVersion: 7,
      pinned: true,
    })
  })
})
