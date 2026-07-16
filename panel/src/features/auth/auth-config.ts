import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { PANEL_AUTH_CALLBACK_PATH } from "@/lib/gateway-routes"

const gatewayConfig = getGatewayRuntimeConfig()

export const AUTHORITY = gatewayConfig.authAuthority

export const AUTH_CLIENT_ID = "rwms-panel"
export const AUTH_SCOPE = "openid profile rwms.read rwms.write warehouse.read"

/**
 * Local content-development switch. Vite only reads `.env.development` while
 * serving the app, so the default production build continues to use OIDC.
 */
export const DEV_AUTH_BYPASS_ENABLED =
  import.meta.env.DEV && import.meta.env.VITE_DEV_AUTH_BYPASS === "true"

/**
 * A local marker used only to keep existing authenticated API adapters typed
 * as `string`. `bearerRequest` recognizes it and deliberately omits the
 * Authorization header.
 */
export const DEV_AUTH_BYPASS_TOKEN = "__rwms_dev_auth_bypass__"

export function getPanelRedirectUri() {
  return `${gatewayConfig.gatewayOrigin}${PANEL_AUTH_CALLBACK_PATH}`
}

export function getPanelPostLogoutRedirectUri() {
  return `${gatewayConfig.gatewayOrigin}/`
}
