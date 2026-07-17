import { describe, expect, it } from "vitest"

import { createGatewayRuntimeConfig } from "@/lib/gateway-config"
import { isPanelAuthCallbackRequest } from "@/lib/gateway-routes"

describe("createGatewayRuntimeConfig", () => {
  it("builds the OIDC and task-board URLs from the browser origin", () => {
    expect(
      createGatewayRuntimeConfig(undefined, "https://panel.example.test")
    ).toEqual({
      gatewayOrigin: "https://panel.example.test",
      authAuthority: "https://panel.example.test/auth",
      taskBoardApiBaseUrl: "https://panel.example.test/api/task-board",
      warehouseApiBaseUrl: "https://panel.example.test/api/warehouse",
      assetApiBaseUrl: "https://panel.example.test/api/asset",
      maintenanceApiBaseUrl: "https://panel.example.test/api/maintenance",
      inventoryApiBaseUrl: "https://panel.example.test/api/inventory",
      mediaApiBaseUrl: "https://panel.example.test/api/media",
    })
  })

  it("normalizes an explicitly configured same-origin gateway", () => {
    expect(
      createGatewayRuntimeConfig(
        "https://panel.example.test/",
        "https://panel.example.test"
      ).gatewayOrigin
    ).toBe("https://panel.example.test")
  })

  it.each([
    ["", "empty URL"],
    ["/gateway", "relative URL"],
    ["ftp://panel.example.test", "unsupported protocol"],
    ["https://panel.example.test/gateway", "path-bearing URL"],
    ["https://panel.example.test?tenant=rwms", "query-bearing URL"],
    ["https://gateway.example.test", "cross-origin URL"],
  ])("rejects an invalid %s configuration (%s)", (gatewayUrl) => {
    expect(() =>
      createGatewayRuntimeConfig(gatewayUrl, "https://panel.example.test")
    ).toThrow()
  })
})

describe("isPanelAuthCallbackRequest", () => {
  it.each([
    "/auth/callback",
    "/auth/callback?code=code&state=state",
    "https://panel.example.test/auth/callback?code=code",
  ])("keeps %s in the panel", (requestUrl) => {
    expect(
      isPanelAuthCallbackRequest(requestUrl, "https://panel.example.test")
    ).toBe(true)
  })

  it.each([
    "/auth/callback/extra",
    "/auth/authorize",
    "/auth/.well-known/openid-configuration",
    "/api/task-board/warehouses",
  ])("does not classify %s as the panel callback", (requestUrl) => {
    expect(
      isPanelAuthCallbackRequest(requestUrl, "https://panel.example.test")
    ).toBe(false)
  })
})
