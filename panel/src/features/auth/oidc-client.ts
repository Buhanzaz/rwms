import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts"

import {
  AUTHORITY,
  PANEL_AUTH_CONFIG,
  getPostLogoutRedirectUri,
  getRedirectUri,
  type AuthApplicationConfig,
} from "@/features/auth/auth-config"

const userManagers = new Map<string, UserManager>()

function createSessionStore(prefix: string) {
  return new WebStorageStateStore({ prefix, store: window.sessionStorage })
}

function managerKey(config: AuthApplicationConfig) {
  return [
    config.clientId,
    config.scope,
    config.callbackPath,
    config.postLogoutPath,
    config.sessionStoragePrefix,
  ].join("|")
}

export function getUserManager(config = PANEL_AUTH_CONFIG) {
  const key = managerKey(config)
  let userManager = userManagers.get(key)

  if (userManager === undefined) {
    userManager = new UserManager({
      authority: AUTHORITY,
      client_id: config.clientId,
      redirect_uri: getRedirectUri(config),
      post_logout_redirect_uri: getPostLogoutRedirectUri(config),
      response_type: "code",
      scope: config.scope,
      // With offline_access the OIDC client renews through the rotating refresh
      // token, without navigating the panel away from the current page.
      automaticSilentRenew: true,
      monitorSession: false,
      loadUserInfo: false,
      userStore: createSessionStore(`${config.sessionStoragePrefix}user:`),
      stateStore: createSessionStore(`${config.sessionStoragePrefix}state:`),
    })
    userManagers.set(key, userManager)
  }

  return userManager
}

export function isPanelUser(user: User) {
  return user.profile.principal_type === "USER"
}

export function hasRenewablePanelSession(user: User) {
  return Boolean(user.refresh_token) && user.scopes.includes("offline_access")
}

export function getSafeReturnTo(value: unknown, config = PANEL_AUTH_CONFIG) {
  if (typeof value !== "string" || !value.startsWith("/")) {
    return config.postLogoutPath
  }

  if (value.startsWith("//") || value.startsWith(config.callbackPath)) {
    return config.postLogoutPath
  }

  if (
    config.routePrefix !== "/" &&
    value !== config.routePrefix.slice(0, -1) &&
    !value.startsWith(config.routePrefix)
  ) {
    return config.postLogoutPath
  }

  return value
}
