import { afterEach, describe, expect, it, vi } from "vitest"

import {
  getInventoryMediaOriginal,
  uploadInventoryMedia,
} from "@/features/inventory/adapters/http-inventory-media-adapter"

afterEach(() => vi.unstubAllGlobals())

describe("http inventory media adapter", () => {
  it("authorizes, directly uploads, pins the object version and finalizes", async () => {
    const digest = new Uint8Array(32).fill(1)
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-4000-8000-000000000001",
      subtle: { digest: vi.fn().mockResolvedValue(digest.buffer) },
    })
    const asset = {
      id: "00000000-0000-0000-0000-000000000050",
      fileName: "inspection.jpg",
      contentType: "image/jpeg",
      kind: "IMAGE",
      status: "PROCESSING",
      version: 1,
      generation: 1,
      rotationDegrees: 0,
      sortOrder: 0,
      sizeBytes: 3,
      createdAt: "2026-07-17T10:00:00Z",
      variants: [],
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            uploadSessionId: "00000000-0000-0000-0000-000000000060",
            mediaId: asset.id,
            expiresAt: "2026-07-17T10:05:00Z",
            uploadUrl: "https://objects.example/upload",
            formFields: { key: "safe-key", policy: "opaque" },
          }),
          { status: 201, headers: { "Content-Type": "application/json" } }
        )
      )
      .mockResolvedValueOnce(
        new Response(null, {
          status: 204,
          headers: { ETag: '"etag-1"', "x-amz-version-id": "version-1" },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(asset), {
          status: 202,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    const result = await uploadInventoryMedia({
      accessToken: "media-token",
      scope: {
        ownerId: "00000000-0000-0000-0000-000000000030",
        warehouseId: "00000000-0000-0000-0000-000000000020",
      },
      file: new File([new Uint8Array([1, 2, 3])], "inspection.jpg", {
        type: "image/jpeg",
      }),
      sortOrder: 0,
    })

    expect(result).toEqual(asset)
    expect(fetchMock.mock.calls[0][0]).toContain(
      "/api/media/v1/upload-sessions"
    )
    expect(
      new Headers(fetchMock.mock.calls[0][1].headers).get("Authorization")
    ).toBe("Bearer media-token")
    expect(fetchMock.mock.calls[1][0]).toBe("https://objects.example/upload")
    expect(
      new Headers(fetchMock.mock.calls[1][1].headers).has("Authorization")
    ).toBe(false)
    expect(fetchMock.mock.calls[2][0]).toContain("/complete")
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toMatchObject({
      objectVersionId: "version-1",
      etag: '"etag-1"',
    })
  })

  it("requests originals only with the proven inventory owner scope", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          url: "https://objects.example/original",
          expiresAt: "2026-07-17T10:01:00Z",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await getInventoryMediaOriginal(
      "token",
      {
        ownerId: "00000000-0000-0000-0000-000000000030",
        warehouseId: "00000000-0000-0000-0000-000000000020",
      },
      "00000000-0000-0000-0000-000000000050"
    )

    expect(fetchMock.mock.calls[0][0]).toContain(
      "ownerType=INVENTORY_FINDING&ownerId=00000000-0000-0000-0000-000000000030"
    )
    expect(fetchMock.mock.calls[0][0]).toContain("context=INSPECTION")
  })
})
