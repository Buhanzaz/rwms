import { afterEach, describe, expect, it, vi } from "vitest"

import {
  moveDriverBoardTask,
  promoteCapitalRepair,
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
      targetDate: "2026-08-02",
      targetIndex: 3,
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
})
