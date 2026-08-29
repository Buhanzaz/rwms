import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

const AUTH_CLIENT_ID = 'rwms-panel';
const AUTH_SCOPE = 'openid profile offline_access rwms.read rwms.write warehouse.read';

let userManager: UserManager | null = null;

function sessionStore(prefix: string) {
  return new WebStorageStateStore({ prefix, store: window.sessionStorage });
}

/** Shares the primary RWMS panel OIDC session without creating a second browser identity. */
export function getPanelUserManager() {
  if (userManager === null) {
    userManager = new UserManager({
      authority: `${window.location.origin}/auth`,
      client_id: AUTH_CLIENT_ID,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      response_type: 'code',
      scope: AUTH_SCOPE,
      automaticSilentRenew: true,
      monitorSession: false,
      loadUserInfo: false,
      userStore: sessionStore('rwms.oidc.user:'),
      stateStore: sessionStore('rwms.oidc.state:'),
    });
  }
  return userManager;
}

function isRenewablePanelUser(user: User | null): user is User {
  return Boolean(
    user
    && user.profile.principal_type === 'USER'
    && user.refresh_token
    && user.scopes.includes('offline_access'),
  );
}

function isUsablePanelUser(user: User | null): user is User {
  return isRenewablePanelUser(user) && !user.expired;
}

/** Returns only the same renewable USER session accepted by the primary panel. */
export async function restorePanelUser(): Promise<User | null> {
  const manager = getPanelUserManager();
  const user = await manager.getUser();
  if (isUsablePanelUser(user)) return user;
  if (isRenewablePanelUser(user)) {
    try {
      const renewed = await manager.signinSilent();
      if (isUsablePanelUser(renewed)) return renewed;
    } catch {
      // A transient refresh failure must not destroy the primary panel's renewable session.
      return null;
    }
    return null;
  }
  if (user !== null) await manager.removeUser();
  return null;
}

function safeReturnTo(value: string) {
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/auth/callback')) return '/logistics-simulator/';
  return value;
}

/** Starts the panel login and asks its shared callback to return to this logistics workspace. */
export async function beginPanelLogin(returnTo: string) {
  await getPanelUserManager().signinRedirect({
    state: { returnTo: safeReturnTo(returnTo) },
  });
}
