import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    logisticsApiBaseUrl: "https://gateway.example.test/api/logistics",
  }),
}))

import {
  MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS,
  createEquipmentMovementTask,
  equipmentMovementWorkerOperationCount,
  getEquipmentMovementTask,
  type CreateEquipmentMovementTaskInput,
} from "@/features/logistics/api/equipment-movement-tasks-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const SOURCE_RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const TARGET_RENTAL_ITEM_ID = "33333333-3333-4333-8333-333333333333"
const EQUIPMENT_ID = "44444444-4444-4444-8444-444444444444"
const TASK_ID = "55555555-5555-4555-8555-555555555555"
const TASK_LINE_ID = "66666666-6666-4666-8666-666666666666"
const RESERVATION_ID = "77777777-7777-4777-8777-777777777777"
const BOARD_TASK_ID = "88888888-8888-4888-8888-888888888888"
const IDEMPOTENCY_KEY = "99999999-9999-4999-8999-999999999999"

const input: CreateEquipmentMovementTaskInput = {
  warehouseId: WAREHOUSE_ID,
  unitNumber: "БЫТ-011",
  plannedDurationMinutes: null,
  deadlineAt: "2026-07-21T10:30:00.000Z",
  lines: [
    {
      equipmentId: EQUIPMENT_ID,
      sourceRentalItemId: null,
      sourceLocationKind: "STOCK",
      expectedSourceBalanceVersion: 7,
      targetRentalItemId: TARGET_RENTAL_ITEM_ID,
      targetLocationKind: "CABIN_NON_RENTED",
      quantity: 2,
    },
  ],
}

function jsonResponse(body: unknown, status = 201) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

function taskResponse() {
  return {
    id: TASK_ID,
    version: 0,
    warehouseId: WAREHOUSE_ID,
    externalTaskId: TASK_ID,
    taskBoardTaskId: BOARD_TASK_ID,
    taskBoardTaskVersion: 0,
    taskBoardDoneAt: null,
    unitNumber: "БЫТ-011",
    plannedDurationMinutes: null,
    deadlineAt: input.deadlineAt,
    state: "AWAITING_WORKER",
    terminalState: null,
    failureCode: null,
    lines: [
      {
        id: TASK_LINE_ID,
        version: 1,
        lineNumber: 1,
        equipmentId: EQUIPMENT_ID,
        equipmentName: "Стол офисный",
        sourceWarehouseId: WAREHOUSE_ID,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        expectedSourceBalanceVersion: 7,
        targetWarehouseId: WAREHOUSE_ID,
        targetRentalItemId: TARGET_RENTAL_ITEM_ID,
        targetLocationKind: "CABIN_NON_RENTED",
        quantity: 2,
        reservationId: RESERVATION_ID,
        reservationVersion: 1,
        state: "RESERVED",
        createdAt: "2026-07-20T10:00:00.000Z",
        updatedAt: "2026-07-20T10:00:01.000Z",
      },
    ],
    createdAt: "2026-07-20T10:00:00.000Z",
    updatedAt: "2026-07-20T10:00:01.000Z",
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("equipment movement task HTTP adapter", () => {
  it("creates one same-origin logistics task with an idempotency key", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(taskResponse()))
    vi.stubGlobal("fetch", fetchMock)

    const task = await createEquipmentMovementTask({
      accessToken: "access-token",
      idempotencyKey: IDEMPOTENCY_KEY,
      input,
    })

    expect(fetchMock).toHaveBeenCalledWith(
      "https://gateway.example.test/api/logistics/v1/equipment-movement-tasks",
      expect.objectContaining({ method: "POST" })
    )
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual(input)
    expect(task).toMatchObject({
      id: TASK_ID,
      state: "AWAITING_WORKER",
      lines: [
        expect.objectContaining({
          equipmentId: EQUIPMENT_ID,
          state: "RESERVED",
        }),
      ],
    })
  })

  it("counts cabin-to-cabin as two worker operations", () => {
    expect(
      equipmentMovementWorkerOperationCount([
        ...input.lines,
        {
          ...input.lines[0]!,
          sourceRentalItemId: SOURCE_RENTAL_ITEM_ID,
          sourceLocationKind: "CABIN_NON_RENTED",
        },
      ])
    ).toBe(3)
  })

  it("loads the authoritative movement task composition", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(taskResponse(), 200))
    vi.stubGlobal("fetch", fetchMock)

    const task = await getEquipmentMovementTask("access-token", TASK_ID)

    expect(fetchMock).toHaveBeenCalledWith(
      `https://gateway.example.test/api/logistics/v1/equipment-movement-tasks/${TASK_ID}`,
      expect.objectContaining({
        headers: expect.any(Headers),
      })
    )
    expect(task.lines).toEqual([
      expect.objectContaining({
        equipmentName: "Стол офисный",
        quantity: 2,
        targetRentalItemId: TARGET_RENTAL_ITEM_ID,
      }),
    ])
  })

  it("rejects a task over the worker-operation limit before sending it", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    const tooManyLines = Array.from(
      { length: MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS + 1 },
      () => ({ ...input.lines[0]! })
    )

    await expect(
      createEquipmentMovementTask({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input: { ...input, lines: tooManyLines },
      })
    ).rejects.toThrow("не более 10 действий")
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
