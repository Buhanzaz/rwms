import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const AUTHORITY = getGatewayRuntimeConfig().authAuthority

export type AuthApplicationConfig = Readonly<{
  clientId: string
  scope: string
  callbackPath: string
  postLogoutPath: string
  routePrefix: string
  sessionStoragePrefix: string
}>

export const PANEL_AUTH_CONFIG: AuthApplicationConfig = {
  clientId: "rwms-panel",
  scope: "openid profile offline_access rwms.read rwms.write warehouse.read",
  callbackPath: "/auth/callback",
  postLogoutPath: "/",
  routePrefix: "/",
  sessionStoragePrefix: "rwms.panel.oidc.",
}

export const ADMIN_AUTH_CONFIG: AuthApplicationConfig = {
  clientId: "rwms-admin-web",
  scope: "openid profile offline_access admin.manage",
  callbackPath: "/admin/auth/callback",
  postLogoutPath: "/admin/",
  routePrefix: "/admin/",
  sessionStoragePrefix: "rwms.admin.oidc.",
}

export const RENTAL_MANAGER_AUTH_CONFIG: AuthApplicationConfig = {
  clientId: "rwms-rental-manager-web",
  scope: "openid profile offline_access rental.manage",
  callbackPath: "/manager/auth/callback",
  postLogoutPath: "/manager/",
  routePrefix: "/manager/",
  sessionStoragePrefix: "rwms.rental-manager.oidc.",
}
export const AUTH_SCOPE = PANEL_AUTH_CONFIG.scope

export function getRedirectUri(config: AuthApplicationConfig) {
  return `${window.location.origin}${config.callbackPath}`
}

export function getPostLogoutRedirectUri(config: AuthApplicationConfig) {
  return `${window.location.origin}${config.postLogoutPath}`
}
