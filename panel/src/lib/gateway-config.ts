const HTTP_PROTOCOLS = new Set(["http:", "https:"])

export type GatewayRuntimeConfig = {
  gatewayOrigin: string
  authAuthority: string
  taskBoardApiBaseUrl: string
  warehouseApiBaseUrl: string
  assetApiBaseUrl: string
}

function parseOriginOnlyUrl(value: string, settingName: string) {
  if (value.trim() === "") {
    throw new Error(`${settingName} must not be empty`)
  }

  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${settingName} must be an absolute HTTP(S) URL`)
  }

  if (!HTTP_PROTOCOLS.has(url.protocol)) {
    throw new Error(`${settingName} must use HTTP or HTTPS`)
  }

  if (
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error(`${settingName} must contain an origin only`)
  }

  return url.origin
}

export function createGatewayRuntimeConfig(
  configuredGatewayUrl: string | undefined,
  browserOrigin: string
): GatewayRuntimeConfig {
  const normalizedBrowserOrigin = parseOriginOnlyUrl(
    browserOrigin,
    "Browser origin"
  )
  const gatewayOrigin = parseOriginOnlyUrl(
    configuredGatewayUrl ?? normalizedBrowserOrigin,
    "VITE_GATEWAY_URL"
  )

  if (gatewayOrigin !== normalizedBrowserOrigin) {
    throw new Error(
      "VITE_GATEWAY_URL must use the panel origin; route the browser through the gateway or its trusted ingress"
    )
  }

  return {
    gatewayOrigin,
    authAuthority: `${gatewayOrigin}/auth`,
    taskBoardApiBaseUrl: `${gatewayOrigin}/api/task-board`,
    warehouseApiBaseUrl: `${gatewayOrigin}/api/warehouse`,
    assetApiBaseUrl: `${gatewayOrigin}/api/asset`,
  }
}

let runtimeConfig: GatewayRuntimeConfig | undefined

export function getGatewayRuntimeConfig() {
  runtimeConfig ??= createGatewayRuntimeConfig(
    import.meta.env.VITE_GATEWAY_URL,
    window.location.origin
  )
  return runtimeConfig
}
