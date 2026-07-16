import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts"

import {
  AUTHORITY,
  AUTH_CLIENT_ID,
  AUTH_SCOPE,
  getPanelPostLogoutRedirectUri,
  getPanelRedirectUri,
} from "@/features/auth/auth-config"

let userManager: UserManager | null = null

function createSessionStore(prefix: string) {
  return new WebStorageStateStore({ prefix, store: window.sessionStorage })
}

export function getUserManager() {
  if (userManager === null) {
    userManager = new UserManager({
      authority: AUTHORITY,
      client_id: AUTH_CLIENT_ID,
      redirect_uri: getPanelRedirectUri(),
      post_logout_redirect_uri: getPanelPostLogoutRedirectUri(),
      response_type: "code",
      scope: AUTH_SCOPE,
      automaticSilentRenew: false,
      monitorSession: false,
      loadUserInfo: false,
      userStore: createSessionStore("rwms.oidc.user:"),
      stateStore: createSessionStore("rwms.oidc.state:"),
    })
  }

  return userManager
}

export function isPanelUser(user: User) {
  return user.profile.principal_type === "USER"
}

export function getSafeReturnTo(value: unknown) {
  if (typeof value !== "string" || !value.startsWith("/")) {
    return "/"
  }

  if (value.startsWith("//") || value.startsWith("/auth/callback")) {
    return "/"
  }

  return value
}
