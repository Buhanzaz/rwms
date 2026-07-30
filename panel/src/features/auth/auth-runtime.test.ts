import { afterEach, describe, expect, it, vi } from "vitest"

import { AUTHORITY, AUTH_SCOPE } from "@/features/auth/auth-config"
import {
  getUserManager,
  hasRenewablePanelSession,
} from "@/features/auth/oidc-client"
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
      "offline_access",
      "rwms.read",
      "rwms.write",
      "warehouse.read",
    ])
  })

  it("renews the panel session from its refresh token without a redirect", () => {
    expect(getUserManager().settings.automaticSilentRenew).toBe(true)
  })

  it("recognizes only offline panel sessions with a refresh token as renewable", () => {
    expect(
      hasRenewablePanelSession({
        refresh_token: "refresh-token",
        scopes: ["openid", "profile", "offline_access"],
      } as never)
    ).toBe(true)
    expect(
      hasRenewablePanelSession({
        scopes: ["openid", "profile"],
      } as never)
    ).toBe(false)
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
