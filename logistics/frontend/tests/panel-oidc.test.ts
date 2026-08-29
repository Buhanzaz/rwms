import { beforeEach, describe, expect, it, vi } from 'vitest';

const oidc = vi.hoisted(() => ({
  manager: {
    getUser: vi.fn(),
    signinSilent: vi.fn(),
    signinRedirect: vi.fn(),
    removeUser: vi.fn(),
  },
}));

vi.mock('oidc-client-ts', () => ({
  UserManager: vi.fn(() => oidc.manager),
  WebStorageStateStore: vi.fn(),
}));

function panelUser(expired: boolean, accessToken: string) {
  return {
    expired,
    access_token: accessToken,
    refresh_token: 'refresh-token',
    scopes: ['openid', 'offline_access'],
    profile: { principal_type: 'USER' },
  };
}

describe('shared panel OIDC session', () => {
  beforeEach(() => {
    vi.resetModules();
    oidc.manager.getUser.mockReset();
    oidc.manager.signinSilent.mockReset();
    oidc.manager.signinRedirect.mockReset();
    oidc.manager.removeUser.mockReset();
  });

  it('silently renews an expired renewable USER session instead of logging out the panel', async () => {
    const expired = panelUser(true, 'expired-token');
    const renewed = panelUser(false, 'renewed-token');
    oidc.manager.getUser.mockResolvedValue(expired);
    oidc.manager.signinSilent.mockResolvedValue(renewed);
    const { restorePanelUser } = await import('../src/auth/panel-oidc');

    await expect(restorePanelUser()).resolves.toBe(renewed);
    expect(oidc.manager.signinSilent).toHaveBeenCalledOnce();
    expect(oidc.manager.removeUser).not.toHaveBeenCalled();
  });

  it('preserves the shared renewable session when silent renewal fails transiently', async () => {
    const expired = panelUser(true, 'expired-token');
    oidc.manager.getUser.mockResolvedValue(expired);
    oidc.manager.signinSilent.mockRejectedValue(new TypeError('Failed to fetch'));
    const { restorePanelUser } = await import('../src/auth/panel-oidc');

    await expect(restorePanelUser()).resolves.toBeNull();
    expect(oidc.manager.removeUser).not.toHaveBeenCalled();
  });
});
