import { describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import {
  HttpMediaClient,
  type HttpMediaClientOptions,
} from "@/features/media/api/http-media-client"
import {
  cabinMediaOwner,
  inventoryFindingMediaOwner,
  logisticsTransferMediaOwner,
  maintenanceEstimateMediaOwner,
  readyMediaReference,
  type MediaAsset,
} from "@/features/media/model/service-media"

const ORIGIN = window.location.origin
const BASE_URL = `${ORIGIN}/api/media`
const OWNER_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const SESSION_ID = "33333333-3333-4333-8333-333333333333"
const MEDIA_ID = "44444444-4444-4444-8444-444444444444"
const FOLDER_ID = "77777777-7777-4777-8777-777777777777"
const CREATE_KEY = "55555555-5555-4555-8555-555555555555"
const FINALIZE_KEY = "66666666-6666-4666-8666-666666666666"
const CHECKSUM = "a".repeat(64)
const owner = inventoryFindingMediaOwner(OWNER_ID, WAREHOUSE_ID)

type FetchCall = Readonly<{
  url: URL
  init: RequestInit
}>

function jsonResponse(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function mediaAsset(overrides: Partial<MediaAsset> = {}): MediaAsset {
  return {
    id: MEDIA_ID,
    folderId: FOLDER_ID,
    fileName: "finding.jpg",
    contentType: "image/jpeg",
    kind: "IMAGE",
    status: "PROCESSING",
    version: 2,
    generation: 0,
    rotationDegrees: 0,
    sortOrder: 0,
    sizeBytes: 16,
    createdAt: "2026-07-18T10:00:00Z",
    variants: [],
    ...overrides,
  }
}

function createClient(
  responses: Response[],
  overrides: Partial<HttpMediaClientOptions> = {}
) {
  const calls: FetchCall[] = []
  const fetch = vi.fn(async (input: string | URL | Request, init = {}) => {
    calls.push({
      url: new URL(input instanceof Request ? input.url : input),
      init,
    })
    const response = responses.shift()
    if (!response) throw new Error("Unexpected fetch")
    return response
  })
  const client = new HttpMediaClient({ baseUrl: BASE_URL, fetch, ...overrides })
  return { client, calls, fetch }
}

describe("HttpMediaClient", () => {
  it("orchestrates create, same-origin content, and idempotent finalize", async () => {
    const session = {
      uploadSessionId: SESSION_ID,
      mediaId: MEDIA_ID,
      expiresAt: "2026-07-18T10:05:00Z",
      contentUploadUrl: `/api/media/v1/upload-sessions/${SESSION_ID}/content`,
    }
    const uploadedObject = {
      objectVersionId: "opaque-version-1",
      etag: "opaque-etag-1",
      checksumSha256: CHECKSUM,
    }
    const asset = mediaAsset()
    const keys = [CREATE_KEY, FINALIZE_KEY]
    const { client, calls } = createClient(
      [
        jsonResponse(session, 201),
        jsonResponse(uploadedObject, 201),
        jsonResponse(asset),
      ],
      {
        randomUUID: () => keys.shift() ?? "unexpected-key",
        sha256: async () => CHECKSUM,
      }
    )
    const file = new File([new Uint8Array(16)], "finding.jpg", {
      type: "image/jpeg",
    })

    const result = await client.uploadFile(
      "access-token",
      owner,
      file,
      3,
      FOLDER_ID
    )

    expect(result).toEqual({ session, uploadedObject, asset })
    expect(calls).toHaveLength(3)
    expect(calls.map(({ url }) => url.origin)).toEqual([ORIGIN, ORIGIN, ORIGIN])
    expect(
      calls.map(({ url }) => url.searchParams.has("access_token"))
    ).toEqual([false, false, false])
    expect(calls[0]?.url.pathname).toBe("/api/media/v1/upload-sessions")
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      ...owner,
      folderId: FOLDER_ID,
      fileName: "finding.jpg",
      contentType: "image/jpeg",
      contentLength: 16,
      checksumSha256: CHECKSUM,
      sortOrder: 3,
    })
    expect(new Headers(calls[0]?.init.headers).get("Idempotency-Key")).toBe(
      CREATE_KEY
    )
    expect(calls[1]?.url.pathname).toBe(
      `/api/media/v1/upload-sessions/${SESSION_ID}/content`
    )
    expect(calls[1]?.init.body).toBe(file)
    expect(new Headers(calls[1]?.init.headers).get("Content-Type")).toBe(
      "image/jpeg"
    )
    expect(new Headers(calls[1]?.init.headers).get("Idempotency-Key")).toBe(
      FINALIZE_KEY
    )
    expect(calls[2]?.url.pathname).toBe(
      `/api/media/v1/upload-sessions/${SESSION_ID}/complete`
    )
    expect(new Headers(calls[2]?.init.headers).get("Idempotency-Key")).toBe(
      FINALIZE_KEY
    )
    for (const call of calls) {
      expect(new Headers(call.init.headers).get("Authorization")).toBe(
        "Bearer access-token"
      )
      expect(call.url.toString()).not.toContain("access-token")
      expect(call.url.hostname).not.toContain("minio")
    }
  })

  it("creates disposable Bearer-fetched object URLs and revokes them once", async () => {
    const contentPath =
      `/api/media/v1/assets/${MEDIA_ID}/variants/SMALL/content?` +
      new URLSearchParams({
        ownerType: owner.ownerType,
        ownerId: owner.ownerId,
        warehouseId: owner.warehouseId,
        context: owner.context,
        generation: "4",
      })
    const readyAsset = mediaAsset({
      status: "READY",
      generation: 4,
      variants: [
        {
          kind: "SMALL",
          contentType: "image/webp",
          contentPath,
          width: 96,
          height: 64,
        },
      ],
    })
    const create = vi.fn(() => "blob:ephemeral-media")
    const revoke = vi.fn()
    const { client, calls } = createClient(
      [
        jsonResponse({ items: [readyAsset], next: null }),
        new Response("webp-body", {
          headers: { "Content-Type": "image/webp" },
        }),
      ],
      { objectUrls: { create, revoke } }
    )

    const page = await client.listOwnerMedia("read-token", owner)
    const objectUrl = await client.createVariantObjectUrl(
      "read-token",
      owner,
      page.items[0]!.variants[0]!
    )

    expect(readyMediaReference(page.items[0]!)).toEqual({
      mediaId: MEDIA_ID,
      generation: 4,
    })
    expect(objectUrl).toMatchObject({
      url: "blob:ephemeral-media",
      contentType: "image/webp",
      size: 9,
    })
    expect(create).toHaveBeenCalledOnce()
    expect(calls[1]?.url.origin).toBe(ORIGIN)
    expect(calls[1]?.url.searchParams.get("ownerId")).toBe(OWNER_ID)
    expect(calls[1]?.url.searchParams.get("generation")).toBe("4")
    expect(calls[1]?.url.searchParams.has("access_token")).toBe(false)
    expect(new Headers(calls[1]?.init.headers).get("Authorization")).toBe(
      "Bearer read-token"
    )
    expect(calls[1]?.init.cache).toBe("no-store")
    objectUrl.dispose()
    objectUrl.dispose()
    expect(revoke).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith("blob:ephemeral-media")
  })

  it("uses the CABIN and WAREHOUSE owner scope for warehouse photos", async () => {
    const cabinOwner = cabinMediaOwner(OWNER_ID, WAREHOUSE_ID)
    const { client, calls } = createClient([
      jsonResponse({ items: [], next: null }),
    ])

    await expect(
      client.listOwnerMedia("cabin-token", cabinOwner, { limit: 100 })
    ).resolves.toEqual({ items: [], next: null })

    expect(calls[0]?.url.searchParams.get("ownerType")).toBe("CABIN")
    expect(calls[0]?.url.searchParams.get("context")).toBe("WAREHOUSE")
    expect(calls[0]?.url.searchParams.get("ownerId")).toBe(OWNER_ID)
    expect(calls[0]?.url.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(new Headers(calls[0]?.init.headers).get("Authorization")).toBe(
      "Bearer cabin-token"
    )
  })

  it("serializes maintenance and structured logistics owner scopes", async () => {
    const documentId = "88888888-8888-4888-8888-888888888888"
    const lineId = "99999999-9999-4999-8999-999999999999"
    const estimateOwner = maintenanceEstimateMediaOwner(OWNER_ID, WAREHOUSE_ID)
    const transferOwner = logisticsTransferMediaOwner(
      documentId,
      lineId,
      WAREHOUSE_ID
    )
    const { client, calls } = createClient([
      jsonResponse({ items: [], next: null }),
      jsonResponse({ items: [], next: null }),
    ])

    await client.listOwnerMedia("token", estimateOwner)
    await client.listOwnerMedia("token", transferOwner)

    expect(calls[0]?.url.searchParams.get("ownerType")).toBe(
      "MAINTENANCE_ESTIMATE"
    )
    expect(calls[0]?.url.searchParams.get("ownerId")).toBe(OWNER_ID)
    expect(calls[0]?.url.searchParams.has("documentId")).toBe(false)
    expect(calls[1]?.url.searchParams.get("ownerType")).toBe(
      "LOGISTICS_TRANSFER"
    )
    expect(calls[1]?.url.searchParams.get("documentId")).toBe(documentId)
    expect(calls[1]?.url.searchParams.get("lineId")).toBe(lineId)
    expect(calls[1]?.url.searchParams.has("ownerId")).toBe(false)
  })

  it("soft-deletes one logical asset with CAS and idempotency", async () => {
    const deleted = mediaAsset({ status: "DELETED", version: 3 })
    const { client, calls } = createClient([jsonResponse(deleted)])

    await expect(
      client.deleteAsset("write-token", owner, MEDIA_ID, 2, FINALIZE_KEY)
    ).resolves.toEqual(deleted)

    expect(calls[0]?.url.pathname).toBe(
      `/api/media/v1/assets/${MEDIA_ID}/deletion`
    )
    expect(calls[0]?.init.method).toBe("POST")
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      expectedVersion: 2,
    })
    expect(new Headers(calls[0]?.init.headers).get("Idempotency-Key")).toBe(
      FINALIZE_KEY
    )
  })

  it("reads one bounded CABIN cover batch and preserves logical asset counts", async () => {
    const secondCabinId = "88888888-8888-4888-8888-888888888888"
    const secondMediaId = "99999999-9999-4999-8999-999999999999"
    const coverPath =
      `/api/media/v1/assets/${MEDIA_ID}/variants/SMALL/content?` +
      new URLSearchParams({
        ownerType: "CABIN",
        ownerId: OWNER_ID,
        warehouseId: WAREHOUSE_ID,
        context: "WAREHOUSE",
        generation: "2",
      })
    const secondPath =
      `/api/media/v1/assets/${secondMediaId}/variants/SMALL/content?` +
      new URLSearchParams({
        ownerType: "CABIN",
        ownerId: OWNER_ID,
        warehouseId: WAREHOUSE_ID,
        context: "WAREHOUSE",
        generation: "1",
      })
    const cover = {
      mediaId: MEDIA_ID,
      generation: 2,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath: coverPath,
      width: 360,
      height: 240,
    }
    const secondPreview = {
      mediaId: secondMediaId,
      generation: 1,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath: secondPath,
      width: 360,
      height: 240,
    }
    const { client, calls } = createClient([
      jsonResponse({
        items: [
          {
            cabinId: OWNER_ID,
            photoCount: 2,
            cover,
            previews: [cover, secondPreview],
          },
        ],
      }),
    ])

    await expect(
      client.listCabinCovers("cover-token", WAREHOUSE_ID, [
        OWNER_ID,
        secondCabinId,
      ])
    ).resolves.toEqual({
      items: [
        {
          cabinId: OWNER_ID,
          photoCount: 2,
          cover,
          previews: [cover, secondPreview],
        },
      ],
    })
    expect(calls).toHaveLength(1)
    expect(calls[0]?.url.pathname).toBe("/api/media/v1/cabin-covers")
    expect(calls[0]?.init.method).toBe("POST")
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      cabinIds: [OWNER_ID, secondCabinId],
    })
    expect(new Headers(calls[0]?.init.headers).get("Authorization")).toBe(
      "Bearer cover-token"
    )
  })

  it("rejects a CABIN cover that is not exactly the first SMALL preview", async () => {
    const contentPath =
      `/api/media/v1/assets/${MEDIA_ID}/variants/SMALL/content?` +
      new URLSearchParams({
        ownerType: "CABIN",
        ownerId: OWNER_ID,
        warehouseId: WAREHOUSE_ID,
        context: "WAREHOUSE",
        generation: "2",
      })
    const cover = {
      mediaId: MEDIA_ID,
      generation: 2,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath,
      width: 360,
      height: 240,
    }
    const { client } = createClient([
      jsonResponse({
        items: [
          {
            cabinId: OWNER_ID,
            photoCount: 1,
            cover,
            previews: [{ ...cover, width: 361 }],
          },
        ],
      }),
    ])

    await expect(
      client.listCabinCovers("cover-token", WAREHOUSE_ID, [OWNER_ID])
    ).rejects.toThrow("Cabin cover does not match previews")
  })

  it("rejects malformed enums and URL or query injection before content fetch", async () => {
    const maliciousSessions = [
      `https://minio.internal/private/${SESSION_ID}`,
      `/api/media/v1/upload-sessions/${SESSION_ID}/content?access_token=stolen`,
    ]
    for (const contentUploadUrl of maliciousSessions) {
      const { client, fetch } = createClient([
        jsonResponse({
          uploadSessionId: SESSION_ID,
          mediaId: MEDIA_ID,
          expiresAt: "2026-07-18T10:05:00Z",
          contentUploadUrl,
        }),
      ])
      await expect(
        client.createUploadSession(
          "access-token",
          owner,
          {
            fileName: "finding.jpg",
            contentType: "image/jpeg",
            contentLength: 16,
            checksumSha256: CHECKSUM,
          },
          CREATE_KEY
        )
      ).rejects.toThrow(/path|public media API/i)
      expect(fetch).toHaveBeenCalledOnce()
    }

    const malformed = mediaAsset({
      status: "READY",
      generation: 4,
      rotationDegrees: 45 as 0,
      variants: [],
    })
    const { client, fetch } = createClient([
      jsonResponse({ items: [malformed], next: null }),
    ])
    await expect(client.listOwnerMedia("access-token", owner)).rejects.toThrow(
      "rotationDegrees"
    )
    expect(fetch).toHaveBeenCalledOnce()

    const injectedVariant = mediaAsset({
      status: "READY",
      generation: 4,
      variants: [
        {
          kind: "SMALL",
          contentType: "image/webp",
          contentPath:
            `/api/media/v1/assets/${MEDIA_ID}/variants/SMALL/content?` +
            new URLSearchParams({
              ownerType: owner.ownerType,
              ownerId: owner.ownerId,
              warehouseId: owner.warehouseId,
              context: owner.context,
              generation: "4",
              access_token: "stolen",
            }),
          width: 96,
          height: 64,
        },
      ],
    })
    const injected = createClient([
      jsonResponse({ items: [injectedVariant], next: null }),
    ])
    await expect(
      injected.client.listOwnerMedia("access-token", owner)
    ).rejects.toThrow("unexpected query parameter")
    expect(injected.fetch).toHaveBeenCalledOnce()
  })

  it("maps Problem Details and never falls back to a mock", async () => {
    const { client, fetch } = createClient([
      jsonResponse(
        {
          status: 409,
          detail: "Версия медиа уже изменилась",
          code: "MEDIA_CONFLICT",
        },
        409
      ),
    ])

    await expect(
      client.rotate("access-token", owner, MEDIA_ID, 90, 2, FINALIZE_KEY)
    ).rejects.toEqual(new ApiError("Версия медиа уже изменилась", 409))
    expect(fetch).toHaveBeenCalledOnce()
  })
})
