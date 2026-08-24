import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { CatalogDialog, RequestDialog } from '../src/components/EntityDialogs';
import type { LogisticsRequestInput, VehicleInput } from '../src/api/client';

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
}

describe('application states', () => {
  it('shows an actionable backend-unavailable state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('network down'))));
    renderApp();
    expect(await screen.findByRole('alert')).toHaveTextContent('Backend недоступен');
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeEnabled();
  });

  it('opens scenario creation from an empty backend', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))));
    const user = userEvent.setup();
    renderApp();
    await user.click(await screen.findByRole('button', { name: /Создать сценарий/ }));
    expect(screen.getByRole('dialog', { name: 'Новый сценарий' })).toBeVisible();
    expect(screen.getByLabelText('Часовой пояс')).toHaveValue('Europe/Moscow');
  });
});

describe('request editor', () => {
  it('keeps the server zone read-only and supports multiple date windows', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="DELIVERY" point={{ latitude: 55.7, longitude: 37.6 }} defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    expect(screen.getByText(/Зону определит backend/)).toBeVisible();
    expect(screen.queryByLabelText(/zone_id/i)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Название / номер'), 'Заявка 142');
    await user.click(screen.getByRole('button', { name: 'Дата' }));
    expect(screen.getAllByLabelText('Дата')).toHaveLength(2);
    fireEvent.change(screen.getAllByLabelText('Дата')[1]!, { target: { value: '2026-08-26' } });
    await user.click(screen.getByRole('button', { name: 'Сохранить заявку' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    const submitted = submit.mock.calls[0]?.[0];
    expect(submitted).not.toHaveProperty('zone_id');
    expect(submitted?.date_options).toHaveLength(2);
  });
});

describe('vehicle editor', () => {
  it('rejects capacity above the backend maximum with an inline error', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="vehicle" busy={false} onClose={() => undefined} onSubmit={(input) => submit(input as VehicleInput)} />);
    await user.type(screen.getByLabelText('Название'), 'Машина 1');
    await user.type(screen.getByLabelText('Госномер'), 'А123БВ');
    const capacity = screen.getByLabelText('Вместимость');
    await user.clear(capacity);
    await user.type(capacity, '3');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Для MVP вместимость не может превышать 2')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
});
