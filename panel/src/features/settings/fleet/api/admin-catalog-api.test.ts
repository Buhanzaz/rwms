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
  tare_weight_t: 12,
  max_gross_weight_t: 20,
  length_m: 8,
  width_m: 2.5,
  height_m: 3.2,
  axle_count: 3,
  axle_load_t: 7.5,
  payload_capacity_t: 10,
  platform_length_m: 7,
  platform_height_from_ground_m: 1.2,
  max_platform_payload_t: 7,
  max_cargo_length_m: 7,
  max_cargo_width_m: 2.6,
  max_cargo_height_m: 3,
  max_cargo_weight_t: 6,
}
const physical = {
  ...emptyAdminPhysicalSpecification(),
  tareWeightT: 12,
  maxGrossWeightT: 20,
  lengthM: 8,
  widthM: 2.5,
  heightM: 3.2,
  axleCount: 3,
  axleLoadT: 7.5,
  payloadCapacityT: 10,
  platformLengthM: 7,
  platformHeightFromGroundM: 1.2,
  maxPlatformPayloadT: 7,
  maxCargoLengthM: 7,
  maxCargoWidthM: 2.6,
  maxCargoHeightM: 3,
  maxCargoWeightT: 6,
}
const vehicleSpecification = {
  ...defaultAdminVehicleSpecification(),
  vehicleType: "CRANE",
  manufacturer: "Test",
  isHgv: true,
  hasTrailer: true,
  defaultTrailerId: TRAILER_ID,
  couplingLengthM: 0.9,
  heightSafetyMarginM: 0.1,
  widthSafetyMarginM: 0,
  weightSafetyMarginT: 0,
  averageSpeedCity: 30,
  averageSpeedRegion: 60,
  loadProfiles: [
    { configurationType: "EMPTY_TRUCK" as const, maxActualAxleLoadT: 7.5 },
  ],
}

const vehicleResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "Truck",
  registration_number: "A123BC77",
  active: true,
  notes: "Main truck",
  capacity: 2,
  ...physicalResponse,
  vehicle_type: "CRANE",
  manufacturer: "Test",
  model: null,
  is_hgv: true,
  has_trailer: true,
  default_trailer_id: TRAILER_ID,
  coupling_length_m: 0.9,
  height_safety_margin_m: 0.1,
  width_safety_margin_m: 0,
  weight_safety_margin_t: 0,
  average_speed_city: 30,
  average_speed_region: 60,
  load_profiles: [
    { configuration_type: "EMPTY_TRUCK", max_actual_axle_load_t: 7.5 },
  ],
}

const trailerResponse = {
  id: RESOURCE_ID,
  version: 4,
  warehouse_id: WAREHOUSE_ID,
  name: "Trailer",
  registration_number: "AA123477",
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
        name: " Truck ",
        registrationNumber: " A123BC77 ",
        capacity: 2,
        active: true,
        notes: " Main truck ",
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
        name: "Truck",
        registration_number: "A123BC77",
        active: true,
        notes: "Main truck",
        capacity: 2,
        ...physicalResponse,
        vehicle_type: "CRANE",
        manufacturer: "Test",
        model: null,
        is_hgv: true,
        has_trailer: true,
        default_trailer_id: TRAILER_ID,
        coupling_length_m: 0.9,
        height_safety_margin_m: 0.1,
        width_safety_margin_m: 0,
        weight_safety_margin_t: 0,
        average_speed_city: 30,
        average_speed_region: 60,
      },
      load_profiles: [
        {
          configuration_type: "EMPTY_TRUCK",
          max_actual_axle_load_t: 7.5,
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
        name: "Trailer",
        registrationNumber: "AA123477",
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
      name: "Trailer",
      registration_number: "AA123477",
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
        name: "Trailer 2",
        registrationNumber: "AA123477",
        capacity: null,
        active: false,
        notes: "Updated",
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
      name: "Trailer 2",
      registration_number: "AA123477",
      active: false,
      notes: "Updated",
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
        name: "Truck 2",
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
        name: "Truck 2",
        tare_weight_t: 12,
        vehicle_type: "CRANE",
        has_trailer: true,
        default_trailer_id: TRAILER_ID,
      },
      load_profiles: [
        {
          configuration_type: "EMPTY_TRUCK",
          max_actual_axle_load_t: 7.5,
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
    { ...vehicleResponse, tare_weight_t: undefined },
    { ...vehicleResponse, load_profiles: undefined },
    {
      ...vehicleResponse,
      load_profiles: [
        { configuration_type: "UNKNOWN", max_actual_axle_load_t: 5 },
      ],
    },
    {
      ...vehicleResponse,
      load_profiles: [
        { configuration_type: "EMPTY_TRUCK", max_actual_axle_load_t: 0 },
      ],
    },
    { ...vehicleResponse, height_m: -1 },
    { ...vehicleResponse, axle_count: 0 },
    { ...vehicleResponse, height_safety_margin_m: -1 },
    { ...vehicleResponse, average_speed_city: 0 },
    { ...vehicleResponse, is_hgv: "false" },
    { ...vehicleResponse, has_trailer: undefined },
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
    ).rejects.toThrow(/Проверьте характеристики/)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("round-trips all measured profiles and preserves null and zero values", async () => {
    const profiles = vehicleLoadProfileTypes.map(
      (configuration_type, index) => ({
        configuration_type,
        max_actual_axle_load_t: 6 + index * 0.1,
      })
    )
    const response = {
      ...vehicleResponse,
      tare_weight_t: null,
      is_hgv: false,
      has_trailer: false,
      default_trailer_id: null,
      weight_safety_margin_t: 0,
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
    expect(loaded!.physical.tareWeightT).toBeNull()
    expect(loaded!.vehicle).toMatchObject({
      isHgv: false,
      hasTrailer: false,
      defaultTrailerId: null,
      weightSafetyMarginT: 0,
      averageSpeedCity: 37.5,
    })
    await updateAdminCatalogResource("admin-token", "vehicle", loaded!, {
      ...loaded!,
      name: "Truck updated",
    })
    const body = JSON.parse(String(fetchMock.mock.calls[1]![1]?.body))
    expect(body.load_profiles).toEqual(profiles)
    expect(body.vehicle).toMatchObject({
      expected_version: 4,
      tare_weight_t: null,
      is_hgv: false,
      has_trailer: false,
      default_trailer_id: null,
      weight_safety_margin_t: 0,
      average_speed_city: 37.5,
    })
  })

  it.each([0, -1, Number.POSITIVE_INFINITY, Number.NaN])(
    "rejects an invalid physical input %s before fetch",
    async (value) => {
      const fetchMock = vi.spyOn(globalThis, "fetch")
      const existing = resource("vehicle")
      await expect(
        updateAdminCatalogResource("admin-token", "vehicle", existing, {
          ...existing,
          physical: { ...existing.physical, heightM: value },
        })
      ).rejects.toThrow(/Проверьте характеристики/)
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
