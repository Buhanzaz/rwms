const HTTP_PROTOCOLS = new Set(["http:", "https:"])

export type GatewayRuntimeConfig = {
  gatewayOrigin: string
  authAuthority: string
  assistantApiBaseUrl: string
  taskBoardApiBaseUrl: string
  warehouseApiBaseUrl: string
  assetApiBaseUrl: string
  maintenanceApiBaseUrl: string
  inventoryApiBaseUrl: string
  mediaApiBaseUrl: string
  logisticsApiBaseUrl: string
  dossierApiBaseUrl: string
}

function parseOrigin(value: string, settingName: string) {
  let url: URL

  try {
    url = new URL(value)
  } catch {
    throw new Error(`${settingName} must be an absolute HTTP(S) URL`)
  }

  if (
    !HTTP_PROTOCOLS.has(url.protocol) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error(`${settingName} must be an origin-only HTTP(S) URL`)
  }

  return url.origin
}

/**
 * Browser calls are intentionally pinned to the panel origin. In development
 * Vite forwards `/auth` and `/api` to the local gateway; deployed panels use
 * the same paths through their gateway/ingress. A service origin is never a
 * browser runtime setting.
 */
export function createGatewayRuntimeConfig(
  browserOrigin: string
): GatewayRuntimeConfig {
  const gatewayOrigin = parseOrigin(browserOrigin, "Browser origin")

  return {
    gatewayOrigin,
    authAuthority: `${gatewayOrigin}/auth`,
    assistantApiBaseUrl: `${gatewayOrigin}/api/assistant`,
    taskBoardApiBaseUrl: `${gatewayOrigin}/api/task-board`,
    warehouseApiBaseUrl: `${gatewayOrigin}/api/warehouse`,
    assetApiBaseUrl: `${gatewayOrigin}/api/asset`,
    maintenanceApiBaseUrl: `${gatewayOrigin}/api/maintenance`,
    inventoryApiBaseUrl: `${gatewayOrigin}/api/inventory`,
    mediaApiBaseUrl: `${gatewayOrigin}/api/media`,
    logisticsApiBaseUrl: `${gatewayOrigin}/api/logistics`,
    dossierApiBaseUrl: `${gatewayOrigin}/api/dossier`,
  }
}

let runtimeConfig: GatewayRuntimeConfig | undefined

export function getGatewayRuntimeConfig() {
  runtimeConfig ??= createGatewayRuntimeConfig(window.location.origin)
  return runtimeConfig
}
