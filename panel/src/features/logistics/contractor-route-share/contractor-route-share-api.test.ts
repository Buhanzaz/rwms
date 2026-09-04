import { afterEach, describe, expect, it, vi } from "vitest"

import {
  applyPublicContractorRouteAction,
  getPublicContractorRouteShare,
  uploadPublicContractorEvidence,
} from "@/features/logistics/contractor-route-share/contractor-route-share-api"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("public contractor route API", () => {
  it("loads one scoped route without a bearer token or browser cache", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "10000000-0000-0000-0000-000000000001",
          expiresAt: "2026-09-01T18:00:00Z",
          tasks: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await getPublicContractorRouteShare("token/with spaces")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/public/v1/contractor-route-shares/token%2Fwith%20spaces"
    )
    expect(init.cache).toBe("no-store")
    expect(new Headers(init.headers).get("Accept")).toBe("application/json")
    expect(new Headers(init.headers).has("Authorization")).toBe(false)
  })

  it("starts an exact route entry behind version and idempotency fences", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ currentVersion: 8, task: {} }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await applyPublicContractorRouteAction({
      token: "route-token",
      externalTaskId: "20000000-0000-0000-0000-000000000001",
      entryId: "30000000-0000-0000-0000-000000000001",
      idempotencyKey: "40000000-0000-0000-0000-000000000001",
      action: "START",
      expectedVersion: 7,
      evidenceId: null,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/public/v1/contractor-route-shares/route-token/tasks/20000000-0000-0000-0000-000000000001/entries/30000000-0000-0000-0000-000000000001/actions"
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "40000000-0000-0000-0000-000000000001"
    )
    expect(new Headers(init.headers).has("Authorization")).toBe(false)
    expect(JSON.parse(String(init.body))).toEqual({
      action: "START",
      expectedVersion: 7,
      evidenceId: null,
    })
  })

  it("uploads immutable evidence with the exact media metadata headers", async () => {
    const digest = Uint8Array.from({ length: 32 }, (_, index) => index).buffer
    vi.stubGlobal("crypto", {
      subtle: { digest: vi.fn().mockResolvedValue(digest) },
    })
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          evidenceId: "50000000-0000-0000-0000-000000000001",
          version: 1,
          state: "UPLOADING",
          mediaId: "60000000-0000-0000-0000-000000000001",
          mediaGeneration: null,
          contentType: "image/jpeg",
          contentPath: null,
          thumbnailPath: null,
        }),
        { status: 202, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)
    const file = new File(["photo"], "result.jpg", { type: "image/jpeg" })

    await uploadPublicContractorEvidence({
      token: "route-token",
      externalTaskId: "20000000-0000-0000-0000-000000000001",
      entryId: "30000000-0000-0000-0000-000000000001",
      evidenceId: "50000000-0000-0000-0000-000000000001",
      capturedAt: "2026-09-01T09:00:00Z",
      file,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(new URL(url).pathname).toBe(
      "/api/logistics/public/v1/contractor-route-shares/route-token/tasks/20000000-0000-0000-0000-000000000001/entries/30000000-0000-0000-0000-000000000001/evidence/50000000-0000-0000-0000-000000000001"
    )
    expect(init.method).toBe("POST")
    expect(init.body).toBe(file)
    expect(headers.get("Content-Type")).toBe("image/jpeg")
    expect(headers.get("Idempotency-Key")).toBe(
      "50000000-0000-0000-0000-000000000001"
    )
    expect(headers.get("X-Captured-At")).toBe("2026-09-01T09:00:00Z")
    expect(headers.get("X-Content-SHA256")).toBe(
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    )
    expect(headers.has("Authorization")).toBe(false)
  })

  it("rejects unsupported evidence before making a network request", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      uploadPublicContractorEvidence({
        token: "route-token",
        externalTaskId: "task-id",
        entryId: "entry-id",
        evidenceId: "evidence-id",
        capturedAt: "2026-09-01T09:00:00Z",
        file: new File(["photo"], "result.png", { type: "image/png" }),
      })
    ).rejects.toThrow("Выберите фотографию JPEG или WebP.")
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
