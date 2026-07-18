import { afterEach, describe, expect, it, vi } from "vitest"

import { AUTHORITY, AUTH_SCOPE } from "@/features/auth/auth-config"
import { bearerRequest } from "@/lib/api-client"

const ORDINARY_DEVELOPMENT_TOKEN = "removed-development-bypass-token"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("panel authentication runtime", () => {
  it("uses gateway OIDC scopes", () => {
    expect(AUTHORITY).toBe(`${window.location.origin}/auth`)
    expect(AUTH_SCOPE.split(" ")).toEqual([
      "openid",
      "profile",
      "rwms.read",
      "rwms.write",
      "warehouse.read",
    ])
  })

  it("does not suppress ordinary bearer values in development", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "content-type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await bearerRequest(
      ORDINARY_DEVELOPMENT_TOKEN,
      "/api/warehouse/v1/warehouses"
    )

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      `Bearer ${ORDINARY_DEVELOPMENT_TOKEN}`
    )
  })
})
