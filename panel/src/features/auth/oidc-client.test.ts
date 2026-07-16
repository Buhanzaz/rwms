import { beforeEach, describe, expect, it } from "vitest"

import { getSafeReturnTo, getUserManager } from "@/features/auth/oidc-client"

describe("panel OIDC client", () => {
  beforeEach(() => window.sessionStorage.clear())

  it("uses gateway OIDC with authorization code and PKCE", () => {
    const settings = getUserManager().settings

    expect(settings.authority).toBe(`${window.location.origin}/auth`)
    expect(settings.redirect_uri).toBe(
      `${window.location.origin}/auth/callback`
    )
    expect(settings.post_logout_redirect_uri).toBe(`${window.location.origin}/`)
    expect(settings.response_type).toBe("code")
    expect(settings.disablePKCE).toBe(false)
  })

  it("keeps OIDC user and authorization state in sessionStorage", async () => {
    const settings = getUserManager().settings

    await settings.stateStore.set("pkce-state", "state")
    await settings.userStore.set("current-user", "user")

    expect(window.sessionStorage.getItem("rwms.oidc.state:pkce-state")).toBe(
      "state"
    )
    expect(window.sessionStorage.getItem("rwms.oidc.user:current-user")).toBe(
      "user"
    )
  })
})

describe("getSafeReturnTo", () => {
  it("preserves the original panel path, query and hash", () => {
    expect(getSafeReturnTo("/warehouse/cabin-1?tab=photos#photo-2")).toBe(
      "/warehouse/cabin-1?tab=photos#photo-2"
    )
  })

  it.each([
    undefined,
    "https://malicious.example.test",
    "//malicious.example.test",
    "/auth/callback",
    "/auth/callback?code=code",
  ])("rejects an unsafe return target", (returnTo) => {
    expect(getSafeReturnTo(returnTo)).toBe("/")
  })
})
