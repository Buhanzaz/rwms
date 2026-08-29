import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AvailableWarehouse } from '../src/domain/types';
import { TransferDraftDialog } from '../src/features/transfers/TransferDraftDialog';
import type { CreateTransferDraftInput, CreatedTransferDraft } from '../src/features/transfers/transfer-client';

const auth = vi.hoisted(() => ({
  restorePanelUser: vi.fn(),
  beginPanelLogin: vi.fn(),
}));
const transfers = vi.hoisted(() => ({
  createTransferDraft: vi.fn<(input: CreateTransferDraftInput) => Promise<CreatedTransferDraft>>(),
}));

vi.mock('../src/auth/panel-oidc', () => auth);
vi.mock('../src/features/transfers/transfer-client', () => ({
  createTransferDraft: transfers.createTransferDraft,
}));

const SPB_ID = '11111111-1111-4111-8111-111111111111';
const NOVGOROD_ID = '22222222-2222-4222-8222-222222222222';
const MOSCOW_ID = '33333333-3333-4333-8333-333333333333';

const warehouses: AvailableWarehouse[] = [
  {
    warehouse_id: SPB_ID,
    warehouse_version: 1,
    name: 'Склад СПб',
    city: 'Санкт-Петербург',
    address: 'Шоссе Революции, 1',
    latitude: 59.961,
    longitude: 30.49,
    timezone: 'Europe/Moscow',
    representative: false,
    routing_ready: true,
  },
  {
    warehouse_id: NOVGOROD_ID,
    warehouse_version: 1,
    name: 'Великий Новгород',
    city: 'Великий Новгород',
    address: 'Сырковское шоссе, 1',
    latitude: 58.572,
    longitude: 31.269,
    timezone: 'Europe/Moscow',
    representative: true,
    routing_ready: true,
  },
  {
    warehouse_id: MOSCOW_ID,
    warehouse_version: 1,
    name: 'Склад Москва',
    city: 'Москва',
    address: null,
    latitude: null,
    longitude: null,
    timezone: 'Europe/Moscow',
    representative: false,
    routing_ready: false,
    routing_unavailable_reason: 'Не заданы координаты для использования склада в логистике',
  },
];

function renderDialog() {
  return render(
    <TransferDraftDialog
      warehouses={warehouses}
      destinationWarehouseId={NOVGOROD_ID}
      scheduledDate="2026-08-30"
      onClose={() => undefined}
      onCreated={() => undefined}
    />,
  );
}

describe('standalone transfer draft dialog', () => {
  beforeEach(() => {
    auth.restorePanelUser.mockReset();
    auth.beginPanelLogin.mockReset();
    transfers.createTransferDraft.mockReset();
  });

  it('stays inside logistics and offers the shared RWMS login when no USER session exists', async () => {
    auth.restorePanelUser.mockResolvedValue(null);
    auth.beginPanelLogin.mockResolvedValue(undefined);
    window.history.replaceState(null, '', '/logistics-simulator/?day=2026-08-30');
    const user = userEvent.setup();
    renderDialog();

    expect(screen.getByRole('dialog', { name: 'Создать перемещение' })).toBeVisible();
    expect(await screen.findByText('Нужен вход в RWMS')).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Создать перемещение' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Войти в RWMS' }));

    expect(auth.beginPanelLogin).toHaveBeenCalledWith('/logistics-simulator/?day=2026-08-30');
  });

  it('creates the canonical zero-cargo draft and keeps unavailable warehouses visible', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft.mockResolvedValue({ id: 'transfer-1', state: 'DRAFT' });
    const onCreated = vi.fn();
    const user = userEvent.setup();
    render(
      <TransferDraftDialog
        warehouses={warehouses}
        sourceWarehouseId={SPB_ID}
        destinationWarehouseId={NOVGOROD_ID}
        scheduledDate="2026-08-30"
        onClose={() => undefined}
        onCreated={onCreated}
      />,
    );

    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });
    const source = within(dialog).getByLabelText('Склад отправления');
    const unavailable = within(source).getByRole('option', { name: 'Склад Москва · Нет координат в RWMS' });
    expect(unavailable).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Комментарий логиста'), 'Попутный рейс');
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledOnce());
    const submitted = transfers.createTransferDraft.mock.calls[0]![0];
    expect(submitted.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i);
    expect(submitted).toEqual({
      accessToken: 'panel-token',
      idempotencyKey: submitted.idempotencyKey,
      warehouseId: SPB_ID,
      destinationWarehouseId: NOVGOROD_ID,
      scheduledDate: '2026-08-30',
      plan: {
        plannedDepartureAt: null,
        plannedArrivalAt: null,
        logisticsComment: 'Попутный рейс',
        tripDriverId: null,
        tripVehicleId: null,
        driverReposition: null,
        vehicleReposition: null,
        cabinGroups: [],
        looseFurniture: [],
      },
    });
    expect(onCreated).toHaveBeenCalledWith({ id: 'transfer-1', state: 'DRAFT' });
  });

  it('blocks a same-warehouse transfer before calling logistics-service', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });
    fireEvent.change(within(dialog).getByLabelText('Склад отправления'), { target: { value: NOVGOROD_ID } });
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    expect(await within(dialog).findByText('Склад отправления и склад назначения должны отличаться')).toBeVisible();
    expect(transfers.createTransferDraft).not.toHaveBeenCalled();
  });

  it('reuses one idempotency key when a lost response is retried from the same dialog', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft
      .mockRejectedValueOnce(new Error('Ответ потерян'))
      .mockResolvedValueOnce({ id: 'transfer-1', state: 'DRAFT' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));
    expect(await within(dialog).findByText('Ответ потерян')).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledTimes(2));
    expect(transfers.createTransferDraft.mock.calls[0]?.[0].idempotencyKey).toBe(
      transfers.createTransferDraft.mock.calls[1]?.[0].idempotencyKey,
    );
  });

  it('starts a new idempotent intention when the payload changes after an error', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft
      .mockRejectedValueOnce(new Error('Ответ потерян'))
      .mockResolvedValueOnce({ id: 'transfer-2', state: 'DRAFT' });
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
      .mockReturnValueOnce('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));
    expect(await within(dialog).findByText('Ответ потерян')).toBeVisible();
    await user.type(within(dialog).getByLabelText('Комментарий логиста'), 'Изменённый состав намерения');
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledTimes(2));
    expect(transfers.createTransferDraft.mock.calls.map(([input]) => input.idempotencyKey)).toEqual([
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    ]);
  });
});
