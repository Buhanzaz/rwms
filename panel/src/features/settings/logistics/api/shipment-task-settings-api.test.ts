import { afterEach, describe, expect, it, vi } from "vitest"

import {
  getShipmentTaskSettings,
  updateShipmentTaskSettings,
} from "@/features/settings/logistics/api/shipment-task-settings-api"
import { ApiError } from "@/lib/api-client"

const warehouseId = "warehouse/id"
const setting = {
  warehouseId,
  version: 4,
  maxCabinsPerShipmentTask: 3,
  updatedBy: "admin-1",
  updatedAt: "2026-08-10T10:00:00Z",
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe("shipment task settings API", () => {
  it("loads a warehouse-scoped cap through the public logistics route", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(setting))

    await expect(
      getShipmentTaskSettings("access-token", warehouseId)
    ).resolves.toEqual(setting)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/logistics/v1/warehouses/warehouse%2Fid/shipment-task-settings"
    )
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("saves the cap with the loaded version fence", async () => {
    const saved = {
      ...setting,
      version: 5,
      maxCabinsPerShipmentTask: 5,
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(saved))

    await expect(
      updateShipmentTaskSettings("access-token", warehouseId, {
        expectedVersion: 4,
        maxCabinsPerShipmentTask: 5,
      })
    ).resolves.toEqual(saved)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/logistics/v1/warehouses/warehouse%2Fid/shipment-task-settings"
    )
    expect(init?.method).toBe("PUT")
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      expectedVersion: 4,
      maxCabinsPerShipmentTask: 5,
    })
  })

  it("does not send invalid client caps", async () => {
    await expect(
      updateShipmentTaskSettings("access-token", warehouseId, {
        expectedVersion: 4,
        maxCabinsPerShipmentTask: 101,
      })
    ).rejects.toThrow("Укажите целое количество бытовок от 1 до 100.")
  })

  it("rejects an incomplete shipment cap response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({ ...setting, updatedAt: null })
    )

    await expect(
      getShipmentTaskSettings("access-token", warehouseId)
    ).rejects.toThrow(
      "Сервис логистики вернул некорректную настройку лимита бытовок."
    )
  })

  it("propagates a version conflict", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        { detail: "Конфликт версий", code: "SHIPMENT_TASK_SETTINGS_CONFLICT" },
        409
      )
    )

    const request = updateShipmentTaskSettings("access-token", warehouseId, {
      expectedVersion: 4,
      maxCabinsPerShipmentTask: 5,
    })

    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.toMatchObject({
      status: 409,
      code: "SHIPMENT_TASK_SETTINGS_CONFLICT",
      message: "Конфликт версий",
    })
  })
})
