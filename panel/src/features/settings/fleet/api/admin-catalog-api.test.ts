import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createAdminCatalogResource,
  deleteAdminCatalogResource,
  listAdminCatalogResources,
  relocateAdminCatalogResource,
  updateAdminCatalogResource,
  type AdminCatalogKind,
  type AdminCatalogResource,
} from "@/features/settings/fleet/api/admin-catalog-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const TARGET_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const RESOURCE_ID = "33333333-3333-4333-8333-333333333333"

const vehicleResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "КамАЗ",
  registration_number: "А123ВС77",
  active: true,
  notes: "Манипулятор",
  capacity: 2,
  load_profiles: [],
}

const trailerResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "Тонар",
  registration_number: "АА123477",
  active: true,
  notes: "",
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

function resource(kind: AdminCatalogKind): AdminCatalogResource {
  const value = kind === "vehicle" ? vehicleResponse : trailerResponse
  return {
    id: value.id,
    version: value.version,
    warehouseId: value.warehouse_id,
    name: value.name,
    registrationNumber: value.registration_number,
    active: value.active,
    notes: value.notes,
    capacity: kind === "vehicle" ? 2 : null,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe("admin vehicle and trailer catalog API", () => {
  it.each([
    ["vehicle", "vehicles", vehicleResponse],
    ["trailer", "trailers", trailerResponse],
  ] as const)(
    "lists %s resources by canonical warehouse UUID",
    async (kind, path, response) => {
      const fetchMock = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(jsonResponse([response]))

      await expect(
        listAdminCatalogResources("admin-token", WAREHOUSE_ID, kind)
      ).resolves.toEqual([resource(kind)])

      const [input, init] = fetchMock.mock.calls[0]!
      expect(new URL(String(input)).pathname).toBe(
        `/api/logistics-planner/v1/admin/warehouses/${WAREHOUSE_ID}/${path}`
      )
      expect(new Headers(init?.headers).get("Authorization")).toBe(
        "Bearer admin-token"
      )
    }
  )

  it("creates a vehicle configuration with a stable idempotency key", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(vehicleResponse, 201))

    await createAdminCatalogResource(
      "admin-token",
      WAREHOUSE_ID,
      "vehicle",
      {
        name: " КамАЗ ",
        registrationNumber: " А123ВС77 ",
        capacity: 2,
        active: true,
        notes: " Манипулятор ",
      },
      "44444444-4444-4444-8444-444444444444"
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      `/api/logistics-planner/v1/admin/warehouses/${WAREHOUSE_ID}/vehicle-configurations`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(
      "44444444-4444-4444-8444-444444444444"
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      vehicle: {
        name: "КамАЗ",
        registration_number: "А123ВС77",
        active: true,
        notes: "Манипулятор",
        capacity: 2,
      },
      load_profiles: [],
    })
  })

  it("creates a trailer in the selected warehouse with an idempotency key", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(trailerResponse, 201))

    await createAdminCatalogResource(
      "admin-token",
      WAREHOUSE_ID,
      "trailer",
      {
        name: "Тонар",
        registrationNumber: "АА123477",
        capacity: null,
        active: true,
        notes: "",
      },
      "55555555-5555-4555-8555-555555555555"
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      `/api/logistics-planner/v1/admin/warehouses/${WAREHOUSE_ID}/trailers`
    )
    expect(init?.method).toBe("POST")
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe(
      "55555555-5555-4555-8555-555555555555"
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Тонар",
      registration_number: "АА123477",
      active: true,
      notes: "",
    })
  })

  it("updates a trailer with its exact optimistic version", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ ...trailerResponse, version: 5 }))

    await updateAdminCatalogResource(
      "admin-token",
      "trailer",
      resource("trailer"),
      {
        name: "Тонар 2",
        registrationNumber: "АА123477",
        capacity: null,
        active: false,
        notes: "Резерв",
      }
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      `/api/logistics-planner/v1/admin/trailers/${RESOURCE_ID}`
    )
    expect(init?.method).toBe("PATCH")
    expect(JSON.parse(String(init?.body))).toEqual({
      expected_version: 4,
      name: "Тонар 2",
      registration_number: "АА123477",
      active: false,
      notes: "Резерв",
    })
  })

  it.each([
    ["vehicle", "vehicles", vehicleResponse],
    ["trailer", "trailers", trailerResponse],
  ] as const)(
    "relocates a %s with the target canonical warehouse UUID",
    async (kind, path, response) => {
      const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
        jsonResponse({
          ...response,
          version: 5,
          warehouse_id: TARGET_WAREHOUSE_ID,
        })
      )

      await expect(
        relocateAdminCatalogResource(
          "admin-token",
          kind,
          resource(kind),
          TARGET_WAREHOUSE_ID
        )
      ).resolves.toMatchObject({
        version: 5,
        warehouseId: TARGET_WAREHOUSE_ID,
      })

      const [input, init] = fetchMock.mock.calls[0]!
      expect(new URL(String(input)).pathname).toBe(
        `/api/logistics-planner/v1/admin/${path}/${RESOURCE_ID}/relocate`
      )
      expect(init?.method).toBe("POST")
      expect(JSON.parse(String(init?.body))).toEqual({
        expected_version: 4,
        target_warehouse_id: TARGET_WAREHOUSE_ID,
      })
    }
  )

  it("deletes only the fenced catalog version", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }))

    await deleteAdminCatalogResource(
      "admin-token",
      "vehicle",
      resource("vehicle")
    )

    const [input, init] = fetchMock.mock.calls[0]!
    const url = new URL(String(input))
    expect(url.pathname).toBe(
      `/api/logistics-planner/v1/admin/vehicles/${RESOURCE_ID}`
    )
    expect(url.searchParams.get("expected_version")).toBe("4")
    expect(init?.method).toBe("DELETE")
  })

  it("rejects a response with a malformed warehouse identifier", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse([{ ...vehicleResponse, warehouse_id: "not-a-uuid" }])
    )

    await expect(
      listAdminCatalogResources("admin-token", WAREHOUSE_ID, "vehicle")
    ).rejects.toMatchObject({
      status: 502,
      code: "INVALID_API_RESPONSE",
    })
  })
})
