import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const AUTHORITY = getGatewayRuntimeConfig().authAuthority

export const AUTH_CLIENT_ID = "rwms-panel"
export const AUTH_SCOPE =
  "openid profile offline_access rwms.read rwms.write warehouse.read"

export function getPanelRedirectUri() {
  return `${window.location.origin}/auth/callback`
}

export function getPanelPostLogoutRedirectUri() {
  return `${window.location.origin}/`
}
