import { afterEach, describe, expect, it, vi } from "vitest"

import {
  ADMIN_AUTH_CONFIG,
  AUTHORITY,
  AUTH_SCOPE,
  RENTAL_MANAGER_AUTH_CONFIG,
} from "@/features/auth/auth-config"
import {
  getSafeReturnTo,
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

  it("isolates the administration client, callback, scopes and session storage", () => {
    const manager = getUserManager(ADMIN_AUTH_CONFIG)

    expect(manager.settings.client_id).toBe("rwms-admin-web")
    expect(manager.settings.scope).toBe(
      "openid profile offline_access admin.manage"
    )
    expect(manager.settings.redirect_uri).toBe(
      `${window.location.origin}/admin/auth/callback`
    )
    expect(manager).not.toBe(getUserManager())
    expect(getSafeReturnTo("/admin/users", ADMIN_AUTH_CONFIG)).toBe(
      "/admin/users"
    )
    expect(getSafeReturnTo("/orders", ADMIN_AUTH_CONFIG)).toBe("/admin/")
    expect(getSafeReturnTo("/admin/auth/callback", ADMIN_AUTH_CONFIG)).toBe(
      "/admin/"
    )
  })

  it("isolates the rental-manager client and keeps returns inside /manager", () => {
    const manager = getUserManager(RENTAL_MANAGER_AUTH_CONFIG)

    expect(manager.settings.client_id).toBe("rwms-rental-manager-web")
    expect(manager.settings.scope).toBe(
      "openid profile offline_access rental.manage"
    )
    expect(manager.settings.redirect_uri).toBe(
      `${window.location.origin}/manager/auth/callback`
    )
    expect(manager).not.toBe(getUserManager())
    expect(getSafeReturnTo("/manager/orders", RENTAL_MANAGER_AUTH_CONFIG)).toBe(
      "/manager/orders"
    )
    expect(getSafeReturnTo("/orders", RENTAL_MANAGER_AUTH_CONFIG)).toBe(
      "/manager/"
    )
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
