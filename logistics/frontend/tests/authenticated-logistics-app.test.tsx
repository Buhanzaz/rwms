import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthenticatedLogisticsApp } from '../src/auth/AuthenticatedLogisticsApp';

const auth = vi.hoisted(() => ({
  restorePanelUser: vi.fn(),
  beginPanelLogin: vi.fn(),
}));

vi.mock('../src/auth/panel-oidc', () => auth);

describe('authenticated logistics application boundary', () => {
  beforeEach(() => {
    auth.restorePanelUser.mockReset();
    auth.beginPanelLogin.mockReset();
    window.history.replaceState({}, '', '/logistics-panel/?day=2026-08-31');
  });

  it('renders the operational application only after restoring a panel USER session', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });

    render(<AuthenticatedLogisticsApp><div>Рабочая область логистики</div></AuthenticatedLogisticsApp>);

    expect(screen.getByRole('status')).toHaveTextContent('Проверяем вход в RWMS');
    expect(await screen.findByText('Рабочая область логистики')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Войти в RWMS' })).not.toBeInTheDocument();
  });

  it('starts panel SSO automatically with the safe current return path when no tab session exists', async () => {
    auth.restorePanelUser.mockResolvedValue(null);
    auth.beginPanelLogin.mockReturnValue(new Promise(() => {}));

    render(<AuthenticatedLogisticsApp><div>Секретная логистика</div></AuthenticatedLogisticsApp>);

    expect(await screen.findByRole('status')).toHaveTextContent('Открываем вход в RWMS');
    expect(screen.queryByText('Секретная логистика')).not.toBeInTheDocument();
    await waitFor(() => expect(auth.beginPanelLogin).toHaveBeenCalledWith(
      '/logistics-panel/?day=2026-08-31',
    ));
  });

  it('offers a manual retry without a redirect loop when automatic SSO cannot start', async () => {
    const user = userEvent.setup();
    auth.restorePanelUser.mockResolvedValue(null);
    auth.beginPanelLogin
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockReturnValueOnce(new Promise(() => {}));

    render(<AuthenticatedLogisticsApp><div>Секретная логистика</div></AuthenticatedLogisticsApp>);

    const retry = await screen.findByRole('button', { name: 'Войти в RWMS' });
    expect(screen.getByText('Не удалось открыть вход. Проверьте соединение и повторите попытку.')).toBeVisible();
    await user.click(retry);

    expect(await screen.findByRole('status')).toHaveTextContent('Открываем вход в RWMS');
    expect(auth.beginPanelLogin).toHaveBeenCalledTimes(2);
  });
});
