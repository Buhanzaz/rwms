import { describe, expect, it } from "vitest"

import { createGatewayRuntimeConfig } from "@/lib/gateway-config"
import { isPanelAuthCallbackRequest } from "@/lib/gateway-routes"

describe("gateway runtime boundary", () => {
  it("derives every browser API path from the panel origin", () => {
    expect(createGatewayRuntimeConfig("https://panel.example.test")).toEqual({
      gatewayOrigin: "https://panel.example.test",
      authAuthority: "https://panel.example.test/auth",
      taskBoardApiBaseUrl: "https://panel.example.test/api/task-board",
      warehouseApiBaseUrl: "https://panel.example.test/api/warehouse",
      assetApiBaseUrl: "https://panel.example.test/api/asset",
      maintenanceApiBaseUrl: "https://panel.example.test/api/maintenance",
      inventoryApiBaseUrl: "https://panel.example.test/api/inventory",
      mediaApiBaseUrl: "https://panel.example.test/api/media",
      logisticsApiBaseUrl: "https://panel.example.test/api/logistics",
      dossierApiBaseUrl: "https://panel.example.test/api/dossier",
    })
  })

  it.each([
    "",
    "/gateway",
    "ftp://panel.example.test",
    "https://panel.example.test/gateway",
    "https://panel.example.test?tenant=rwms",
  ])("rejects a non-origin browser URL: %s", (origin) => {
    expect(() => createGatewayRuntimeConfig(origin)).toThrow()
  })

  it.each([
    "/auth/callback",
    "/auth/callback?code=code&state=state",
    "https://panel.example.test/auth/callback?code=code",
  ])("keeps %s in the SPA instead of proxying it", (requestUrl) => {
    expect(
      isPanelAuthCallbackRequest(requestUrl, "https://panel.example.test")
    ).toBe(true)
  })
})
