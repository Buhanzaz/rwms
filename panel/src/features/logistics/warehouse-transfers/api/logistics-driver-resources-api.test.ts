import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createContractorDriver,
  listLogisticsDriverResources,
  parseLogisticsDriverResource,
} from "@/features/logistics/warehouse-transfers/api/logistics-driver-resources-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DRIVER_ID = "22222222-2222-4222-8222-222222222222"
const AT = "2026-09-02T05:30:00Z"

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("public logistics driver resources", () => {
  it("reads available and planned incoming drivers only through the public boundary", async () => {
    const response = [
      {
        workerId: DRIVER_ID,
        displayName: "Петров Пётр",
        employmentType: "STAFF",
        phone: null,
        operationalWarehouseId: WAREHOUSE_ID,
        availableFrom: AT,
        availableUntil: null,
        availabilityKind: "INCOMING",
      },
    ]
    const fetchMock = vi.fn().mockResolvedValue(json(response))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listLogisticsDriverResources({
        accessToken: "task-board-token",
        warehouseId: WAREHOUSE_ID,
        at: AT,
        includeIncoming: true,
      })
    ).resolves.toEqual(response)

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/task-board/warehouses/${WAREHOUSE_ID}/logistics-drivers`
    )
    expect(url.pathname).not.toContain("/internal/")
    expect(url.searchParams.get("at")).toBe(AT)
    expect(url.searchParams.get("includeIncoming")).toBe("true")
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer task-board-token"
    )
  })

  it("keeps the backward-compatible two-field directory row valid", () => {
    expect(
      parseLogisticsDriverResource({
        workerId: DRIVER_ID,
        displayName: "Штатный водитель",
      })
    ).toEqual({ workerId: DRIVER_ID, displayName: "Штатный водитель" })
  })

  it("creates a reusable contractor with the exact canonical profile", async () => {
    const contractor = {
      workerId: DRIVER_ID,
      version: 0,
      homeWarehouseId: WAREHOUSE_ID,
      displayName: "Наёмный водитель",
      phone: "+79990000000",
      comment: "Собственный автомобиль",
      active: true,
      employmentType: "CONTRACTOR",
    }
    const fetchMock = vi.fn().mockResolvedValue(json(contractor, 201))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      createContractorDriver({
        accessToken: "task-board-token",
        warehouseId: WAREHOUSE_ID,
        contractor: {
          contractorId: DRIVER_ID,
          displayName: contractor.displayName,
          phone: contractor.phone,
          comment: contractor.comment,
        },
      })
    ).resolves.toEqual(contractor)

    const [rawUrl, init] = fetchMock.mock.calls[0]
    expect(new URL(rawUrl).pathname).toBe(
      `/api/task-board/warehouses/${WAREHOUSE_ID}/logistics-drivers/contractors`
    )
    expect(JSON.parse(init.body)).toEqual({
      contractorId: DRIVER_ID,
      displayName: contractor.displayName,
      phone: contractor.phone,
      comment: contractor.comment,
    })
    expect(JSON.parse(init.body)).not.toHaveProperty("password")
  })

  it("rejects obsolete availability fields in a contractor response", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json(
        {
          workerId: DRIVER_ID,
          version: 0,
          homeWarehouseId: WAREHOUSE_ID,
          displayName: "Наёмный водитель",
          phone: "+79990000000",
          availableFrom: AT,
          comment: null,
          active: true,
          employmentType: "CONTRACTOR",
        },
        201
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      createContractorDriver({
        accessToken: "task-board-token",
        warehouseId: WAREHOUSE_ID,
        contractor: {
          contractorId: DRIVER_ID,
          displayName: "Наёмный водитель",
          phone: "+79990000000",
          comment: null,
        },
      })
    ).rejects.toThrow("Сервис задач вернул некорректный ресурс водителя")
  })

  it("rejects malformed or overprivileged driver projections", () => {
    for (const invalid of [
      { workerId: "not-a-uuid", displayName: "Водитель" },
      { workerId: DRIVER_ID, displayName: "Водитель", login: "secret" },
      {
        workerId: DRIVER_ID,
        displayName: "Водитель",
        availabilityKind: "IN_TRANSIT",
      },
    ]) {
      expect(() => parseLogisticsDriverResource(invalid)).toThrow(
        "Сервис задач вернул некорректный ресурс водителя"
      )
    }
  })
})
