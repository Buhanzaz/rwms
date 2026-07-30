import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    assetApiBaseUrl: "https://gateway.example.test/api/asset",
  }),
}))

import {
  addAssetRentalItemManualNote,
  AssetRentalItemConflictError,
  cancelHtmlImport,
  commitHtmlImport,
  createCabinCatalogItem,
  createAssetRentalItem,
  createHtmlImport,
  deleteCabinCatalogItem,
  getCabinSettings,
  getAssetRentalItem,
  getHtmlImport,
  getRentalItemCreationOptions,
  listHtmlImportRows,
  listHtmlImports,
  listAssetRentalItemManualNotes,
  listAssetRentalItems,
  replaceCabinTypeDimensions,
  replaceHtmlImportMedia,
  retryHtmlImportMedia,
  saveHtmlImportPlan,
  skipHtmlImportMedia,
  updateAssetRentalItemPassport,
  updateAssetRentalItemGeneralComment,
  updateAssetRentalItemStatus,
  updateCabinCatalogItem,
} from "@/features/rental-items/api/asset-rental-items-api"

const RENTAL_ITEM_ID = "4b87e123-1f2a-4a38-ae57-c0d0e2a05c01"
const WAREHOUSE_ID = "69d4ca7e-d4d6-48d3-a5b4-0f60e43680f3"
const CANONICAL_SEEDED_WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const EQUIPMENT_ID = "f9d50892-25ae-4c08-a5a9-108e9a93dc1e"
const IDEMPOTENCY_KEY = "662e3540-4148-4c9f-a5a0-4e50f319a0e4"
const MANUAL_NOTE_ID = "73eaad90-e67d-4b59-b34e-0af48ce4f731"
const RENTAL_TYPE_ID = "83eaad90-e67d-4b59-b34e-0af48ce4f731"
const DIMENSION_ID = "93eaad90-e67d-4b59-b34e-0af48ce4f731"
const FINISHING_ID = "a3eaad90-e67d-4b59-b34e-0af48ce4f731"
const CHARACTERISTIC_ID = "b3eaad90-e67d-4b59-b34e-0af48ce4f731"

function rentalItemResponse(overrides: Record<string, unknown> = {}) {
  return {
    id: RENTAL_ITEM_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-042",
    status: "WAREHOUSE",
    rentalTypeId: RENTAL_TYPE_ID,
    rentalType: "БК-2",
    dimensionId: DIMENSION_ID,
    dimensions: "2.4x6",
    finishingId: FINISHING_ID,
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: [{ id: CHARACTERISTIC_ID, name: "Окно" }],
    linoleum: true,
    generalComment: "Проверить перед выдачей",
    passport: {},
    tags: ["ready"],
    contents: [
      {
        equipmentId: EQUIPMENT_ID,
        equipmentName: "Стул",
        quantity: 4,
        locationKind: "CABIN_NON_RENTED",
      },
    ],
    activeOrderReservation: null,
    createdAt: "2026-07-18T10:00:00Z",
    updatedAt: "2026-07-18T11:00:00Z",
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function emptyResponse(status = 204) {
  return new Response(null, { status })
}

function cabinCatalogItem(
  id: string,
  kind: "TYPE" | "DIMENSION" | "FINISHING" | "CHARACTERISTIC",
  name: string,
  overrides: Record<string, unknown> = {}
) {
  return {
    id,
    version: 3,
    kind,
    name,
    active: true,
    createdAt: "2026-07-18T10:00:00Z",
    updatedAt: "2026-07-18T11:00:00Z",
    ...overrides,
  }
}

function cabinSettingsResponse() {
  return {
    types: [cabinCatalogItem(RENTAL_TYPE_ID, "TYPE", "БК-2")],
    dimensions: [cabinCatalogItem(DIMENSION_ID, "DIMENSION", "2.4x6")],
    finishings: [cabinCatalogItem(FINISHING_ID, "FINISHING", "ЛДСП")],
    characteristics: [
      cabinCatalogItem(CHARACTERISTIC_ID, "CHARACTERISTIC", "Окно"),
    ],
    typeDimensions: [
      { typeId: RENTAL_TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 },
    ],
  }
}

const HTML_IMPORT_ID = "c3eaad90-e67d-4b59-b34e-0af48ce4f731"
const HTML_IMPORT_ROW_ID = "d3eaad90-e67d-4b59-b34e-0af48ce4f731"
const HTML_IMPORT_MEDIA_JOB_ID = "e3eaad90-e67d-4b59-b34e-0af48ce4f731"

function htmlImportResponse(overrides: Record<string, unknown> = {}) {
  return {
    id: HTML_IMPORT_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    state: "REVIEW_REQUIRED",
    rowCount: 2,
    selectedCount: 0,
    invalidCount: 0,
    unresolvedCount: 1,
    mediaLinkCount: 1,
    warningCount: 1,
    mediaJobId: null,
    failureCode: null,
    createdAt: "2026-07-29T08:00:00Z",
    updatedAt: "2026-07-29T08:01:00Z",
    catalogTargets: [
      { id: RENTAL_TYPE_ID, kind: "TYPE", name: "БК-2", active: true },
      { id: DIMENSION_ID, kind: "DIMENSION", name: "2.4x6", active: true },
      { id: FINISHING_ID, kind: "FINISHING", name: "ЛДСП", active: true },
    ],
    equipmentTargets: [{ id: EQUIPMENT_ID, name: "Стул", active: true }],
    sourceCandidates: [
      {
        kind: "TYPE",
        sourceValue: "БК-2",
        suggestedValue: "БК-2",
        occurrences: 2,
        suggestedTargetId: RENTAL_TYPE_ID,
        required: true,
      },
      {
        kind: "EQUIPMENT",
        sourceValue: "Стул",
        suggestedValue: "Стул",
        occurrences: 4,
        suggestedTargetId: EQUIPMENT_ID,
        required: false,
      },
      {
        kind: "STATUS",
        sourceValue: "Консервация",
        suggestedValue: null,
        occurrences: 1,
        suggestedTargetId: null,
        required: true,
      },
    ],
    plan: {
      catalogMappings: [
        {
          kind: "TYPE",
          sourceValue: "БК-2",
          action: "MAP",
          targetId: RENTAL_TYPE_ID,
        },
      ],
      equipmentMappings: [
        {
          sourceValue: "Стул",
          action: "MAP",
          targetId: EQUIPMENT_ID,
        },
      ],
      statusMappings: [
        {
          sourceValue: "Консервация",
          targetStatus: "WAREHOUSE",
        },
      ],
      rows: [],
    },
    ...overrides,
  }
}

function htmlImportRowsResponse() {
  return {
    content: [
      {
        id: HTML_IMPORT_ROW_ID,
        sourceRowId: "legacy-row-42",
        sourcePosition: 42,
        sourceNumber: "БЫТ-042",
        proposedNumber: "БЫТ-042",
        action: "MERGE",
        targetRentalItemId: RENTAL_ITEM_ID,
        rentalType: {
          sourceId: "legacy-type-42",
          sourceLabel: "БК-2",
          suggestedValue: "БК-2",
        },
        dimension: {
          sourceId: "legacy-dimension-42",
          sourceLabel: "2.4x6",
          suggestedValue: "2.4x6",
        },
        finishing: {
          sourceId: "legacy-finishing-42",
          sourceLabel: "ДВП",
          suggestedValue: "ДВП",
        },
        category: {
          sourceId: null,
          sourceLabel: "Обычная",
          suggestedValue: "Обычная",
        },
        characteristics: [
          {
            sourceId: "legacy-characteristic-window",
            sourceLabel: "Окно",
            suggestedValue: "Окно",
          },
        ],
        linoleum: true,
        storageState: "Стеллаж А-3",
        status: {
          sourceId: "legacy-status-conservation",
          sourceLabel: "Консервация",
          suggestedValue: null,
        },
        proposedStatus: null,
        comment: "Комментарий из HTML",
        hasPhotoLink: true,
        furniture: [
          {
            sourceId: "legacy-furniture-table",
            sourceLabel: "Стол",
            quantity: 2,
          },
        ],
        shipmentDate: "2026-07-20",
        tenant: "ООО Ромашка",
        price: 120000,
        diagnostics: [
          {
            severity: "ERROR",
            code: "STATUS_REQUIRES_MAPPING",
            field: "UF_STATUS_ID",
          },
        ],
        decision: {
          sourceRowId: "legacy-row-42",
          action: "MERGE",
          targetRentalItemId: RENTAL_ITEM_ID,
          targetExpectedVersion: 7,
          rentalTypeId: RENTAL_TYPE_ID,
          dimensionId: DIMENSION_ID,
          finishingId: FINISHING_ID,
          mergeChoices: {
            comment: "TARGET",
            furniture: "SOURCE",
            "passport.storageState": "SOURCE",
          },
        },
        existingRentalItem: {
          id: RENTAL_ITEM_ID,
          version: 7,
          number: "БЫТ-042",
          status: "WAREHOUSE",
          rentalTypeId: RENTAL_TYPE_ID,
          rentalType: "БК-2",
          dimensionId: DIMENSION_ID,
          dimension: "2.4x6",
          finishingId: FINISHING_ID,
          finishing: "ЛДСП",
          categoryId: null,
          category: "Обычная",
          linoleum: false,
          generalComment: "Текущий комментарий",
          passport: {
            storageState: "Стеллаж Б-1",
            shipmentDate: "2025-01-15",
            tenant: "ООО Старый арендатор",
            price: 90000,
          },
        },
      },
    ],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
  }
}

describe("asset rental-items HTTP adapter", () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal("fetch", fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("maps imported warehouse fields and catalog names from the public asset response", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        content: [
          rentalItemResponse({
            passport: {
              legacyId: "spb-42",
              legacyWarehouseId: "spb",
              legacyNumber: "БЫТ-042",
              shipmentDate: "2026-05-02",
              tenant: "ООО СтройПроект",
              price: 31_500,
            },
          }),
        ],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      })
    )

    const page = await listAssetRentalItems({
      accessToken: "access-token",
      warehouseId: WAREHOUSE_ID,
      excludeStatuses: ["WRITTEN_OFF", "WAITING_ESTIMATE_CONFIRMATION"],
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `https://gateway.example.test/api/asset/v1/rental-items?warehouseId=${WAREHOUSE_ID}&page=0&size=200&excludeStatus=WRITTEN_OFF&excludeStatus=WAITING_ESTIMATE_CONFIRMATION`,
      expect.objectContaining({
        headers: expect.any(Headers),
      })
    )
    expect(page.content[0]).toMatchObject({
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      type: "БК-2",
      rentalTypeId: RENTAL_TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      characteristics: [{ id: CHARACTERISTIC_ID, name: "Окно" }],
      comment: "Проверить перед выдачей",
      mediaAvailability: "AVAILABLE",
      shipmentDate: "2026-05-02",
      tenant: "ООО СтройПроект",
      price: 31_500,
      contentsItems: [
        {
          equipmentId: EQUIPMENT_ID,
          equipmentName: "Стул",
          name: "Стул",
          quantity: 4,
        },
      ],
    })
    expect(page.content[0]).toMatchObject({
      passport: expect.objectContaining({ legacyId: "spb-42" }),
      tags: ["ready"],
    })
  })

  it("accepts the canonical seeded warehouse UUID used by the services", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        content: [
          rentalItemResponse({ warehouseId: CANONICAL_SEEDED_WAREHOUSE_ID }),
        ],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      })
    )

    await expect(
      listAssetRentalItems({
        accessToken: "access-token",
        warehouseId: CANONICAL_SEEDED_WAREHOUSE_ID,
      })
    ).resolves.toMatchObject({
      content: [{ warehouseId: CANONICAL_SEEDED_WAREHOUSE_ID }],
    })
  })

  it("renders imported cabins with missing composition without inventing IDs", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        content: [
          rentalItemResponse({
            rentalTypeId: null,
            rentalType: null,
            dimensionId: null,
            dimensions: null,
            finishingId: null,
            finishing: null,
            category: null,
            linoleum: null,
          }),
        ],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      })
    )

    await expect(
      listAssetRentalItems({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
      })
    ).resolves.toMatchObject({
      content: [
        {
          rentalTypeId: "",
          type: "—",
          dimensionId: "",
          dimensions: null,
          finishingId: "",
          finishing: null,
          category: null,
          linoleum: null,
        },
      ],
    })
  })

  it("maps the active order reservation as the canonical tenant", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        rentalItemResponse({
          status: "BOOKED",
          passport: { tenant: "Устаревший клиент" },
          activeOrderReservation: {
            reservationId: "11111111-1111-1111-1111-111111111111",
            orderId: "22222222-2222-2222-2222-222222222222",
            clientId: "33333333-3333-3333-3333-333333333333",
            tenantSnapshot: "ООО Новый клиент",
            reservedAt: "2026-07-22T08:00:00Z",
          },
        })
      )
    )

    await expect(
      getAssetRentalItem("access-token", RENTAL_ITEM_ID)
    ).resolves.toMatchObject({
      status: "BOOKED",
      tenant: "ООО Новый клиент",
      activeOrderReservation: {
        orderId: "22222222-2222-2222-2222-222222222222",
      },
    })
  })

  it("uses the public asset API for detail, create, status, comment and manual notes", async () => {
    const note = {
      id: MANUAL_NOTE_ID,
      rentalItemId: RENTAL_ITEM_ID,
      text: "Проверить дверь",
      createdAt: "2026-07-18T12:00:00Z",
    }
    fetchMock
      .mockResolvedValueOnce(jsonResponse(rentalItemResponse()))
      .mockResolvedValueOnce(jsonResponse(rentalItemResponse(), 201))
      .mockResolvedValueOnce(jsonResponse(rentalItemResponse({ version: 5 })))
      .mockResolvedValueOnce(
        jsonResponse(rentalItemResponse({ version: 6, status: "FREE" }))
      )
      .mockResolvedValueOnce(
        jsonResponse(
          rentalItemResponse({
            version: 7,
            generalComment: "Готова к выдаче",
          })
        )
      )
      .mockResolvedValueOnce(jsonResponse([note]))
      .mockResolvedValueOnce(jsonResponse(note, 201))

    await expect(
      getAssetRentalItem("access-token", RENTAL_ITEM_ID)
    ).resolves.toMatchObject({ id: RENTAL_ITEM_ID, version: 4 })
    await expect(
      createAssetRentalItem({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input: {
          warehouseId: WAREHOUSE_ID,
          number: "БЫТ-042",
          rentalTypeId: RENTAL_TYPE_ID,
          dimensionId: DIMENSION_ID,
          finishingId: FINISHING_ID,
          category: "Обычная",
          characteristicIds: [CHARACTERISTIC_ID],
          linoleum: true,
        },
      })
    ).resolves.toMatchObject({ id: RENTAL_ITEM_ID })
    await expect(
      updateAssetRentalItemPassport({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 4,
          rentalTypeId: RENTAL_TYPE_ID,
          dimensionId: DIMENSION_ID,
          finishingId: FINISHING_ID,
          category: "Обычная",
          characteristicIds: [CHARACTERISTIC_ID],
          linoleum: true,
          passport: { tenant: "ООО СтройПроект" },
          tags: ["ready"],
        },
      })
    ).resolves.toMatchObject({ version: 5 })
    await expect(
      updateAssetRentalItemStatus({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 5,
          status: "FREE",
        },
      })
    ).resolves.toMatchObject({ version: 6, status: "FREE" })
    await expect(
      updateAssetRentalItemGeneralComment({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 6,
          comment: "Готова к выдаче",
        },
      })
    ).resolves.toMatchObject({
      version: 7,
      comment: "Готова к выдаче",
    })
    await expect(
      listAssetRentalItemManualNotes("access-token", RENTAL_ITEM_ID)
    ).resolves.toEqual([note])
    await expect(
      addAssetRentalItemManualNote({
        accessToken: "access-token",
        rentalItemId: RENTAL_ITEM_ID,
        expectedVersion: 7,
        text: "Проверить дверь",
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toEqual(note)

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      `https://gateway.example.test/api/asset/v1/rental-items/${RENTAL_ITEM_ID}`
    )

    const createRequest = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(createRequest.method).toBe("POST")
    expect(new Headers(createRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(createRequest.body))).toMatchObject({
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-042",
      rentalTypeId: RENTAL_TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      characteristicIds: [CHARACTERISTIC_ID],
    })

    const passportRequest = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[2]?.[0])).toMatch(/\/passport$/)
    expect(JSON.parse(String(passportRequest.body))).toEqual({
      expectedVersion: 4,
      rentalTypeId: RENTAL_TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      category: "Обычная",
      characteristicIds: [CHARACTERISTIC_ID],
      linoleum: true,
      passport: { tenant: "ООО СтройПроект" },
      tags: ["ready"],
    })

    const statusRequest = fetchMock.mock.calls[3]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[3]?.[0])).toMatch(/\/status$/)
    expect(JSON.parse(String(statusRequest.body))).toEqual({
      expectedVersion: 5,
      status: "FREE",
    })

    const commentRequest = fetchMock.mock.calls[4]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[4]?.[0])).toMatch(/\/general-comment$/)
    expect(JSON.parse(String(commentRequest.body))).toEqual({
      expectedVersion: 6,
      comment: "Готова к выдаче",
    })

    expect(String(fetchMock.mock.calls[5]?.[0])).toMatch(/\/manual-notes$/)
    const noteRequest = fetchMock.mock.calls[6]?.[1] as RequestInit
    expect(noteRequest.method).toBe("POST")
    expect(new Headers(noteRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(noteRequest.body))).toEqual({
      expectedVersion: 7,
      text: "Проверить дверь",
    })
  })

  it("reads catalog options and sends UUID-only cabin settings commands", async () => {
    const settings = cabinSettingsResponse()
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({
          newCategory: "Новая",
          usedCategories: ["Обычная"],
          rentalTypes: [{ id: RENTAL_TYPE_ID, name: "БК-2" }],
          dimensions: [{ id: DIMENSION_ID, name: "2.4x6" }],
          finishings: [{ id: FINISHING_ID, name: "ЛДСП" }],
          characteristics: [{ id: CHARACTERISTIC_ID, name: "Окно" }],
          typeDimensions: [
            {
              typeId: RENTAL_TYPE_ID,
              dimensionId: DIMENSION_ID,
              sortOrder: 0,
            },
          ],
        })
      )
      .mockResolvedValueOnce(jsonResponse(settings))
      .mockResolvedValueOnce(
        jsonResponse(cabinCatalogItem(RENTAL_TYPE_ID, "TYPE", "БК-3"), 201)
      )
      .mockResolvedValueOnce(
        jsonResponse(
          cabinCatalogItem(RENTAL_TYPE_ID, "TYPE", "БК-3", {
            version: 4,
            active: false,
          })
        )
      )
      .mockResolvedValueOnce(jsonResponse(settings))
      .mockResolvedValueOnce(emptyResponse())

    await expect(
      getRentalItemCreationOptions("access-token", WAREHOUSE_ID)
    ).resolves.toMatchObject({
      rentalTypes: [{ id: RENTAL_TYPE_ID, name: "БК-2" }],
      typeDimensions: [{ typeId: RENTAL_TYPE_ID, dimensionId: DIMENSION_ID }],
    })
    await expect(getCabinSettings("access-token")).resolves.toMatchObject({
      types: [{ id: RENTAL_TYPE_ID, name: "БК-2" }],
    })
    await createCabinCatalogItem({
      accessToken: "access-token",
      idempotencyKey: IDEMPOTENCY_KEY,
      input: { kind: "TYPE", name: "БК-3" },
    })
    await updateCabinCatalogItem({
      accessToken: "access-token",
      id: RENTAL_TYPE_ID,
      expectedVersion: 3,
      name: "БК-3",
      active: false,
    })
    await replaceCabinTypeDimensions({
      accessToken: "access-token",
      typeId: RENTAL_TYPE_ID,
      expectedVersion: 3,
      dimensionIds: [DIMENSION_ID],
    })
    await expect(
      deleteCabinCatalogItem({
        accessToken: "access-token",
        id: CHARACTERISTIC_ID,
        expectedVersion: 3,
      })
    ).resolves.toBeUndefined()

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      `https://gateway.example.test/api/asset/v1/rental-items/creation-options?warehouseId=${WAREHOUSE_ID}`
    )
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe(
      "https://gateway.example.test/api/asset/v1/cabin-settings"
    )
    const createRequest = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(new Headers(createRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(createRequest.body))).toEqual({
      kind: "TYPE",
      name: "БК-3",
    })
    expect(JSON.parse(String(fetchMock.mock.calls[3]?.[1]?.body))).toEqual({
      expectedVersion: 3,
      name: "БК-3",
      active: false,
    })
    expect(JSON.parse(String(fetchMock.mock.calls[4]?.[1]?.body))).toEqual({
      expectedVersion: 3,
      dimensionIds: [DIMENSION_ID],
    })
    expect(String(fetchMock.mock.calls[5]?.[0])).toBe(
      `https://gateway.example.test/api/asset/v1/cabin-settings/items/${CHARACTERISTIC_ID}?expectedVersion=3`
    )
    expect((fetchMock.mock.calls[5]?.[1] as RequestInit).method).toBe("DELETE")
  })

  it("preserves the backend reason when a catalog value cannot be deleted", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        { detail: "Характеристика используется в бытовке «БЫТ-042»." },
        409
      )
    )

    await expect(
      deleteCabinCatalogItem({
        accessToken: "access-token",
        id: CHARACTERISTIC_ID,
        expectedVersion: 3,
      })
    ).rejects.toMatchObject({
      status: 409,
      message: "Характеристика используется в бытовке «БЫТ-042».",
    })
  })

  it("fails closed for an unauthorized response", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ detail: "Требуется авторизация" }, 401)
    )

    await expect(
      listAssetRentalItems({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
      })
    ).rejects.toMatchObject({ status: 401 })
  })

  it("preserves a forbidden response instead of showing service data", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ detail: "Нет доступа к складу" }, 403)
    )

    await expect(
      listAssetRentalItems({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
      })
    ).rejects.toMatchObject({ status: 403, message: "Нет доступа к складу" })
  })

  it("preserves a not-found response for a missing canonical rental item", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ detail: "Бытовка не найдена" }, 404)
    )

    await expect(
      getAssetRentalItem("access-token", RENTAL_ITEM_ID)
    ).rejects.toMatchObject({ status: 404, message: "Бытовка не найдена" })
  })

  it("rejects a malformed canonical response instead of using a fixture", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        content: [rentalItemResponse({ id: "spb-42" })],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      })
    )

    await expect(
      listAssetRentalItems({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
      })
    ).rejects.toThrow("некорректный ответ")
  })

  it("surfaces a create conflict instead of retrying with a browser result", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        { detail: "Версия или ключ идемпотентности конфликтуют" },
        409
      )
    )

    await expect(
      createAssetRentalItem({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input: {
          warehouseId: WAREHOUSE_ID,
          number: "БЫТ-042",
          rentalTypeId: RENTAL_TYPE_ID,
          dimensionId: DIMENSION_ID,
          finishingId: FINISHING_ID,
          category: "Обычная",
          characteristicIds: [CHARACTERISTIC_ID],
          linoleum: true,
        },
      })
    ).rejects.toBeInstanceOf(AssetRentalItemConflictError)
  })

  it("does not send statuses forbidden by the public endpoint", async () => {
    await expect(
      updateAssetRentalItemStatus({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 4,
          status: "WRITTEN_OFF" as never,
        },
      })
    ).rejects.toThrow("не поддерживает IN_TRANSFER и WRITTEN_OFF")

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("uses the durable HTML-import API with multipart source and exact plan decisions", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(htmlImportResponse(), 201))
      .mockResolvedValueOnce(jsonResponse([htmlImportResponse()]))
      .mockResolvedValueOnce(jsonResponse(htmlImportResponse()))
      .mockResolvedValueOnce(jsonResponse(htmlImportRowsResponse()))
      .mockResolvedValueOnce(jsonResponse(htmlImportResponse({ version: 5 })))
      .mockResolvedValueOnce(
        jsonResponse(htmlImportResponse({ state: "COMMITTING" }))
      )
      .mockResolvedValueOnce(
        jsonResponse(
          htmlImportResponse({
            state: "ASSETS_COMMITTED",
            mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
          })
        )
      )
      .mockResolvedValueOnce(
        jsonResponse(
          htmlImportResponse({
            state: "ASSETS_COMMITTED",
            mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
          })
        )
      )
      .mockResolvedValueOnce(
        jsonResponse(
          htmlImportResponse({
            state: "COMPLETED_WITH_WARNINGS",
            mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
          })
        )
      )

    await expect(
      createHtmlImport({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
        html: new Blob(["<html><body>legacy</body></html>"], {
          type: "text/html",
        }),
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({
      id: HTML_IMPORT_ID,
      catalogTargets: [
        expect.objectContaining({
          id: RENTAL_TYPE_ID,
          kind: "TYPE",
          label: "БК-2",
        }),
        expect.objectContaining({
          id: DIMENSION_ID,
          kind: "DIMENSION",
          label: "2.4x6",
        }),
        expect.objectContaining({
          id: FINISHING_ID,
          kind: "FINISHING",
          label: "ЛДСП",
        }),
      ],
      catalogMappings: [
        expect.objectContaining({
          kind: "TYPE",
          sourceValue: "БК-2",
          plannedAction: "MAP",
          plannedTargetId: RENTAL_TYPE_ID,
        }),
      ],
      equipmentMappings: [
        expect.objectContaining({
          sourceValue: "Стул",
          plannedTargetId: EQUIPMENT_ID,
        }),
      ],
      statusCandidates: [
        expect.objectContaining({
          sourceValue: "Консервация",
          suggestedTargetStatus: null,
          plannedTargetStatus: "WAREHOUSE",
        }),
      ],
    })

    const createCall = fetchMock.mock.calls[0]
    expect(createCall[0]).toBe(
      "https://gateway.example.test/api/asset/v1/html-imports"
    )
    expect(createCall[1]).toMatchObject({
      method: "POST",
      headers: expect.objectContaining({
        Authorization: "Bearer access-token",
        "Idempotency-Key": IDEMPOTENCY_KEY,
      }),
    })
    expect((createCall[1] as RequestInit).headers).not.toHaveProperty(
      "Content-Type"
    )
    const formData = (createCall[1] as RequestInit).body as FormData
    expect(formData.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(await (formData.get("html") as Blob).text()).toContain("legacy")

    await expect(
      listHtmlImports("access-token", WAREHOUSE_ID)
    ).resolves.toHaveLength(1)
    await expect(
      getHtmlImport("access-token", HTML_IMPORT_ID)
    ).resolves.toMatchObject({
      version: 4,
      state: "REVIEW_REQUIRED",
    })
    await expect(
      listHtmlImportRows({
        accessToken: "access-token",
        importId: HTML_IMPORT_ID,
        page: 0,
        size: 100,
      })
    ).resolves.toMatchObject({
      content: [
        expect.objectContaining({
          sourceRowId: "legacy-row-42",
          proposedNumber: "БЫТ-042",
          plannedRentalTypeId: RENTAL_TYPE_ID,
          plannedDimensionId: DIMENSION_ID,
          plannedFinishingId: FINISHING_ID,
          source: expect.objectContaining({
            fields: expect.objectContaining({
              rentalType: "БК-2",
              dimension: "2.4x6",
              finishing: "ДВП",
              characteristics: "Окно",
              status: "Консервация",
              furniture: "Стол × 2",
              "passport.storageState": "Стеллаж А-3",
              "passport.shipmentDate": "2026-07-20",
              "passport.tenant": "ООО Ромашка",
              "passport.price": "120000",
            }),
          }),
          existing: expect.objectContaining({
            id: RENTAL_ITEM_ID,
            version: 7,
            fields: expect.objectContaining({
              comment: "Текущий комментарий",
              "passport.storageState": "Стеллаж Б-1",
              "passport.shipmentDate": "2025-01-15",
              "passport.tenant": "ООО Старый арендатор",
              "passport.price": "90000",
            }),
          }),
          diagnostics: [
            {
              severity: "ERROR",
              code: "STATUS_REQUIRES_MAPPING",
              field: "UF_STATUS_ID",
            },
          ],
          fieldDecisions: expect.objectContaining({
            furniture: "SOURCE",
            "passport.storageState": "SOURCE",
          }),
        }),
      ],
    })

    await saveHtmlImportPlan({
      accessToken: "access-token",
      importId: HTML_IMPORT_ID,
      expectedVersion: 4,
      catalogMappings: [
        {
          kind: "TYPE",
          sourceValue: "БК-2",
          action: "MAP",
          targetId: RENTAL_TYPE_ID,
        },
      ],
      equipmentMappings: [{ sourceValue: "Стул", action: "IGNORE" }],
      statusMappings: [{ sourceValue: "На складе", targetStatus: "WAREHOUSE" }],
      rows: [
        {
          sourceRowId: "legacy-row-42",
          action: "MERGE",
          targetRentalItemId: RENTAL_ITEM_ID,
          targetExpectedVersion: 7,
          mergeChoices: { comment: "TARGET" },
        },
      ],
    })

    expect(
      JSON.parse((fetchMock.mock.calls[4][1] as RequestInit).body as string)
    ).toEqual({
      expectedVersion: 4,
      catalogMappings: [
        {
          kind: "TYPE",
          sourceValue: "БК-2",
          action: "MAP",
          targetId: RENTAL_TYPE_ID,
        },
      ],
      equipmentMappings: [{ sourceValue: "Стул", action: "IGNORE" }],
      statusMappings: [{ sourceValue: "На складе", targetStatus: "WAREHOUSE" }],
      rows: [
        {
          sourceRowId: "legacy-row-42",
          action: "MERGE",
          targetRentalItemId: RENTAL_ITEM_ID,
          targetExpectedVersion: 7,
          mergeChoices: { comment: "TARGET" },
        },
      ],
    })

    await expect(
      commitHtmlImport({
        accessToken: "access-token",
        importId: HTML_IMPORT_ID,
        expectedVersion: 5,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ state: "COMMITTING" })
    expect(
      JSON.parse((fetchMock.mock.calls[5][1] as RequestInit).body as string)
    ).toEqual({
      expectedVersion: 5,
    })

    await expect(
      retryHtmlImportMedia({
        accessToken: "access-token",
        importId: HTML_IMPORT_ID,
        expectedVersion: 6,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({
      state: "ASSETS_COMMITTED",
      mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
    })
    expect(fetchMock.mock.calls[6][0]).toBe(
      `https://gateway.example.test/api/asset/v1/html-imports/${HTML_IMPORT_ID}/retry-media`
    )
    expect(
      ((fetchMock.mock.calls[6][1] as RequestInit).headers as Headers).get(
        "Idempotency-Key"
      )
    ).toBe(IDEMPOTENCY_KEY)
    expect(
      JSON.parse((fetchMock.mock.calls[6][1] as RequestInit).body as string)
    ).toEqual({ expectedVersion: 6 })

    await replaceHtmlImportMedia({
      accessToken: "access-token",
      importId: HTML_IMPORT_ID,
      expectedVersion: 7,
      idempotencyKey: IDEMPOTENCY_KEY,
      replacements: [
        {
          rowId: "e3eaad90-e67d-4b59-b34e-0af48ce4f731",
          publicUrl: "https://disk.yandex.ru/d/AbCdEfGhIjKlMn",
        },
      ],
    })
    expect(fetchMock.mock.calls[7][0]).toBe(
      `https://gateway.example.test/api/asset/v1/html-imports/${HTML_IMPORT_ID}/replace-media`
    )
    expect(
      JSON.parse((fetchMock.mock.calls[7][1] as RequestInit).body as string)
    ).toEqual({
      expectedVersion: 7,
      replacements: [
        {
          rowId: "e3eaad90-e67d-4b59-b34e-0af48ce4f731",
          publicUrl: "https://disk.yandex.ru/d/AbCdEfGhIjKlMn",
        },
      ],
    })

    await skipHtmlImportMedia({
      accessToken: "access-token",
      importId: HTML_IMPORT_ID,
      expectedVersion: 8,
      idempotencyKey: IDEMPOTENCY_KEY,
    })
    expect(fetchMock.mock.calls[8][0]).toBe(
      `https://gateway.example.test/api/asset/v1/html-imports/${HTML_IMPORT_ID}/skip-media`
    )
    expect(
      JSON.parse((fetchMock.mock.calls[8][1] as RequestInit).body as string)
    ).toEqual({ expectedVersion: 8 })
  })

  it("parses the frozen HTML-import lifecycle fields", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        htmlImportResponse({
          state: "FAILED",
          mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
          failureCode: "MEDIA_GATEWAY_FAILED",
        })
      )
    )

    await expect(
      getHtmlImport("access-token", HTML_IMPORT_ID)
    ).resolves.toMatchObject({
      state: "FAILED",
      mediaJobId: HTML_IMPORT_MEDIA_JOB_ID,
      failureCode: "MEDIA_GATEWAY_FAILED",
    })
  })

  it("cancels an HTML-import draft with optimistic concurrency", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(
      cancelHtmlImport({
        accessToken: "access-token",
        importId: HTML_IMPORT_ID,
        expectedVersion: 4,
      })
    ).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenCalledWith(
      `https://gateway.example.test/api/asset/v1/html-imports/${HTML_IMPORT_ID}?expectedVersion=4`,
      expect.objectContaining({
        method: "DELETE",
        headers: expect.any(Headers),
      })
    )
    const headers = fetchMock.mock.calls[0][1]?.headers as Headers
    expect(headers.get("Authorization")).toBe("Bearer access-token")
  })
})
