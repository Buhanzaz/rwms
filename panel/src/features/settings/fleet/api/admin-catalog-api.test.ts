import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createAdminCatalogResource,
  defaultAdminVehicleSpecification,
  deleteAdminCatalogResource,
  emptyAdminPhysicalSpecification,
  listAdminCatalogResources,
  physicalFieldNames,
  relocateAdminCatalogResource,
  updateAdminCatalogResource,
  vehicleLoadProfileTypes,
  type AdminCatalogKind,
  type AdminCatalogResource,
} from "@/features/settings/fleet/api/admin-catalog-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const TARGET_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const RESOURCE_ID = "33333333-3333-4333-8333-333333333333"
const TRAILER_ID = "44444444-4444-4444-8444-444444444444"

const physicalResponse = {
  ...Object.fromEntries(
    Object.values(physicalFieldNames).map((field) => [field, null])
  ),
  tare_weight_kg: 12_000,
  length_mm: 8_000,
}
const physical = {
  ...emptyAdminPhysicalSpecification(),
  tareWeightKg: 12_000,
  lengthMm: 8_000,
}
const vehicleSpecification = {
  ...defaultAdminVehicleSpecification(),
  vehicleType: "CRANE",
  manufacturer: "КамАЗ",
  isHgv: true,
  canUseTrailer: true,
  defaultTrailerId: TRAILER_ID,
  combinedLengthWithTrailerMm: 12_000,
  couplingLengthMm: 900,
  heightSafetyMarginMm: 100,
  averageSpeedCity: 30,
  averageSpeedRegion: 60,
  loadProfiles: [
    { configurationType: "EMPTY_TRUCK" as const, maxActualAxleLoadKg: 7_500 },
  ],
}

const vehicleResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "КамАЗ",
  registration_number: "А123ВС77",
  active: true,
  notes: "Манипулятор",
  capacity: 2,
  ...physicalResponse,
  vehicle_type: "CRANE",
  manufacturer: "КамАЗ",
  model: null,
  is_hgv: true,
  can_use_trailer: true,
  default_trailer_id: TRAILER_ID,
  combined_length_with_trailer_mm: 12_000,
  coupling_length_mm: 900,
  height_safety_margin_mm: 100,
  width_safety_margin_mm: 0,
  weight_safety_margin_kg: 0,
  average_speed_city: 30,
  average_speed_region: 60,
  load_profiles: [
    { configuration_type: "EMPTY_TRUCK", max_actual_axle_load_kg: 7_500 },
  ],
}

const trailerResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "Тонар",
  registration_number: "АА123477",
  active: true,
  notes: "",
  ...physicalResponse,
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
    physical,
    vehicle: kind === "vehicle" ? vehicleSpecification : null,
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
        physical,
        vehicle: vehicleSpecification,
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
        ...physicalResponse,
        vehicle_type: "CRANE",
        manufacturer: "КамАЗ",
        model: null,
        is_hgv: true,
        can_use_trailer: true,
        default_trailer_id: TRAILER_ID,
        combined_length_with_trailer_mm: 12_000,
        coupling_length_mm: 900,
        height_safety_margin_mm: 100,
        width_safety_margin_mm: 0,
        weight_safety_margin_kg: 0,
        average_speed_city: 30,
        average_speed_region: 60,
      },
      load_profiles: [
        {
          configuration_type: "EMPTY_TRUCK",
          max_actual_axle_load_kg: 7_500,
        },
      ],
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
        physical,
        vehicle: null,
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
      ...physicalResponse,
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
        physical,
        vehicle: null,
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
      ...physicalResponse,
    })
  })

  it("atomically updates a vehicle without losing its physical and axle-load data", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ ...vehicleResponse, version: 5 }))

    await updateAdminCatalogResource(
      "admin-token",
      "vehicle",
      resource("vehicle"),
      {
        ...resource("vehicle"),
        name: "КамАЗ 2",
      }
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      `/api/logistics-planner/v1/admin/vehicles/${RESOURCE_ID}/configuration`
    )
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(String(init?.body))).toMatchObject({
      vehicle: {
        expected_version: 4,
        name: "КамАЗ 2",
        tare_weight_kg: 12_000,
        vehicle_type: "CRANE",
        default_trailer_id: TRAILER_ID,
      },
      load_profiles: [
        {
          configuration_type: "EMPTY_TRUCK",
          max_actual_axle_load_kg: 7_500,
        },
      ],
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

  it.each([
    { ...vehicleResponse, tare_weight_kg: undefined },
    { ...vehicleResponse, load_profiles: undefined },
    {
      ...vehicleResponse,
      load_profiles: [
        { configuration_type: "UNKNOWN", max_actual_axle_load_kg: 5000 },
      ],
    },
    {
      ...vehicleResponse,
      load_profiles: [
        { configuration_type: "EMPTY_TRUCK", max_actual_axle_load_kg: 0 },
      ],
    },
    { ...vehicleResponse, height_mm: -1 },
    { ...vehicleResponse, width_mm: 2350.5 },
    { ...vehicleResponse, axle_count: 0 },
    { ...vehicleResponse, height_safety_margin_mm: -1 },
    { ...vehicleResponse, average_speed_city: 0 },
    { ...vehicleResponse, is_hgv: "false" },
    { ...vehicleResponse, can_use_trailer: false },
    {
      ...vehicleResponse,
      load_profiles: [
        vehicleResponse.load_profiles[0],
        vehicleResponse.load_profiles[0],
      ],
    },
  ])("rejects malformed physical vehicle data: %j", async (response) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse([response]))

    await expect(
      listAdminCatalogResources("admin-token", WAREHOUSE_ID, "vehicle")
    ).rejects.toMatchObject({
      status: 502,
      code: "INVALID_API_RESPONSE",
    })
  })

  it("rejects vehicle-only fields on a trailer before sending a request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")

    await expect(
      createAdminCatalogResource(
        "admin-token",
        WAREHOUSE_ID,
        "trailer",
        {
          ...resource("trailer"),
          capacity: 2,
        },
        "55555555-5555-4555-8555-555555555555"
      )
    ).rejects.toThrow("Проверьте характеристики")
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("round-trips all measured profiles and distinguishes unknown fields from false and zero", async () => {
    const profiles = vehicleLoadProfileTypes.map(
      (configuration_type, index) => ({
        configuration_type,
        max_actual_axle_load_kg: 6000 + index * 100,
      })
    )
    const response = {
      ...vehicleResponse,
      tare_weight_kg: null,
      is_hgv: false,
      can_use_trailer: null,
      default_trailer_id: null,
      weight_safety_margin_kg: 0,
      average_speed_city: 37.5,
      load_profiles: profiles,
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse([response]))
      .mockResolvedValueOnce(jsonResponse({ ...response, version: 5 }))
    const [loaded] = await listAdminCatalogResources(
      "admin-token",
      WAREHOUSE_ID,
      "vehicle"
    )
    expect(loaded!.physical.tareWeightKg).toBeNull()
    expect(loaded!.vehicle).toMatchObject({
      isHgv: false,
      canUseTrailer: null,
      defaultTrailerId: null,
      weightSafetyMarginKg: 0,
      averageSpeedCity: 37.5,
    })
    await updateAdminCatalogResource("admin-token", "vehicle", loaded!, {
      ...loaded!,
      name: "Новое имя",
    })
    const body = JSON.parse(String(fetchMock.mock.calls[1]![1]?.body))
    expect(body.load_profiles).toEqual(profiles)
    expect(body.vehicle).toMatchObject({
      expected_version: 4,
      tare_weight_kg: null,
      is_hgv: false,
      can_use_trailer: null,
      default_trailer_id: null,
      weight_safety_margin_kg: 0,
      average_speed_city: 37.5,
    })
  })

  it.each([0, -1, 12.5, Number.POSITIVE_INFINITY])(
    "rejects an invalid physical input %s before fetch",
    async (value) => {
      const fetchMock = vi.spyOn(globalThis, "fetch")
      const existing = resource("vehicle")
      await expect(
        updateAdminCatalogResource("admin-token", "vehicle", existing, {
          ...existing,
          physical: { ...existing.physical, heightMm: value },
        })
      ).rejects.toThrow("Проверьте характеристики")
      expect(fetchMock).not.toHaveBeenCalled()
    }
  )

  it("propagates version conflicts without an unfenced retry", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        jsonResponse(
          {
            title: "Conflict",
            detail: "Catalog version changed",
            status: 409,
            code: "CATALOG_VERSION_CONFLICT",
          },
          409
        )
      )
    const existing = resource("vehicle")
    await expect(
      updateAdminCatalogResource("admin-token", "vehicle", existing, existing)
    ).rejects.toMatchObject({ status: 409, code: "CATALOG_VERSION_CONFLICT" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(
      JSON.parse(String(fetchMock.mock.calls[0]![1]?.body)).vehicle
        .expected_version
    ).toBe(4)
  })
})
