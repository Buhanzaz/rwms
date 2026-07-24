import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    assetApiBaseUrl: "https://gateway.example.test/api/asset",
  }),
}))

import {
  addAssetRentalItemManualNote,
  AssetRentalItemConflictError,
  createAssetRentalItem,
  getAssetRentalItem,
  listAssetRentalItemManualNotes,
  listAssetRentalItems,
  updateAssetRentalItemGeneralComment,
  updateAssetRentalItemStatus,
} from "@/features/rental-items/api/asset-rental-items-api"

const RENTAL_ITEM_ID = "4b87e123-1f2a-4a38-ae57-c0d0e2a05c01"
const WAREHOUSE_ID = "69d4ca7e-d4d6-48d3-a5b4-0f60e43680f3"
const CANONICAL_SEEDED_WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const EQUIPMENT_ID = "f9d50892-25ae-4c08-a5a9-108e9a93dc1e"
const IDEMPOTENCY_KEY = "662e3540-4148-4c9f-a5a0-4e50f319a0e4"
const MANUAL_NOTE_ID = "73eaad90-e67d-4b59-b34e-0af48ce4f731"

function rentalItemResponse(overrides: Record<string, unknown> = {}) {
  return {
    id: RENTAL_ITEM_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-042",
    status: "WAREHOUSE",
    rentalType: "БК-2",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: "Окно",
    linoleum: true,
    generalComment: "Проверить перед выдачей",
    passport: {},
    tags: ["ready"],
    contents: [
      {
        equipmentId: EQUIPMENT_ID,
        equipmentCode: "CHAIR-01",
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
              shipmentDate: "2026-05-02",
              tenant: "ООО СтройПроект",
              price: 31_500,
              photoCount: 1,
              mainPhotoUrl: "https://images.example.test/cabin.jpg",
              previewPhotoUrls: ["https://images.example.test/cabin-small.jpg"],
              legacyPhotos: [
                {
                  id: "spb-42-photo-1",
                  url: "https://images.example.test/cabin.jpg",
                  variants: {
                    small: {
                      url: "https://images.example.test/cabin-small.jpg",
                    },
                  },
                },
              ],
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
      comment: "Проверить перед выдачей",
      mediaAvailability: "AVAILABLE",
      hasPhotos: true,
      photoCount: 1,
      mainPhotoUrl: "https://images.example.test/cabin.jpg",
      shipmentDate: "2026-05-02",
      tenant: "ООО СтройПроект",
      price: 31_500,
      contentsItems: [
        {
          equipmentId: EQUIPMENT_ID,
          equipmentCode: "CHAIR-01",
          equipmentName: "Стул",
          name: "Стул",
          quantity: 4,
        },
      ],
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
      .mockResolvedValueOnce(
        jsonResponse(rentalItemResponse({ version: 5, status: "FREE" }))
      )
      .mockResolvedValueOnce(
        jsonResponse(
          rentalItemResponse({
            version: 6,
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
          rentalType: "БК-2",
          dimensions: "2.4x6",
          finishing: "ЛДСП",
          category: "Обычная",
          characteristics: "Окно",
          linoleum: true,
        },
      })
    ).resolves.toMatchObject({ id: RENTAL_ITEM_ID })
    await expect(
      updateAssetRentalItemStatus({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 4,
          status: "FREE",
        },
      })
    ).resolves.toMatchObject({ version: 5, status: "FREE" })
    await expect(
      updateAssetRentalItemGeneralComment({
        accessToken: "access-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 5,
          comment: "Готова к выдаче",
        },
      })
    ).resolves.toMatchObject({
      version: 6,
      comment: "Готова к выдаче",
    })
    await expect(
      listAssetRentalItemManualNotes("access-token", RENTAL_ITEM_ID)
    ).resolves.toEqual([note])
    await expect(
      addAssetRentalItemManualNote({
        accessToken: "access-token",
        rentalItemId: RENTAL_ITEM_ID,
        expectedVersion: 6,
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
    })

    const statusRequest = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[2]?.[0])).toMatch(/\/status$/)
    expect(JSON.parse(String(statusRequest.body))).toEqual({
      expectedVersion: 4,
      status: "FREE",
    })

    const commentRequest = fetchMock.mock.calls[3]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[3]?.[0])).toMatch(/\/general-comment$/)
    expect(JSON.parse(String(commentRequest.body))).toEqual({
      expectedVersion: 5,
      comment: "Готова к выдаче",
    })

    expect(String(fetchMock.mock.calls[4]?.[0])).toMatch(/\/manual-notes$/)
    const noteRequest = fetchMock.mock.calls[5]?.[1] as RequestInit
    expect(noteRequest.method).toBe("POST")
    expect(new Headers(noteRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(noteRequest.body))).toEqual({
      expectedVersion: 6,
      text: "Проверить дверь",
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
          rentalType: "БК-2",
          dimensions: "2.4x6",
          finishing: "ЛДСП",
          category: "Обычная",
          characteristics: "Окно",
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
})
