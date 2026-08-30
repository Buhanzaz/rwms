import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AvailableWarehouse } from '../src/domain/types';
import { TransferDraftDialog } from '../src/features/transfers/TransferDraftDialog';
import type {
  CreateTransferDraftInput,
  CreatedTransferDraft,
  TransferArrivalEstimate,
  TransferCargoCatalog,
  TransferDriver,
  TransferRouteVehicle,
} from '../src/features/transfers/transfer-client';

const auth = vi.hoisted(() => ({
  restorePanelUser: vi.fn(),
  beginPanelLogin: vi.fn(),
}));
const transfers = vi.hoisted(() => ({
  createTransferContractor: vi.fn(),
  createTransferDraft: vi.fn<(input: CreateTransferDraftInput) => Promise<CreatedTransferDraft>>(),
  loadTransferCargoCatalog: vi.fn<(accessToken: string, warehouseId: string) => Promise<TransferCargoCatalog>>(),
  loadTransferRouteVehicles: vi.fn<(warehouseId: string) => Promise<TransferRouteVehicle[]>>(),
  loadTransferDrivers: vi.fn<(warehouseId: string) => Promise<TransferDriver[]>>(),
  estimateTransferArrival: vi.fn<(input: {
    sourceWarehouseId: string;
    destinationWarehouseId: string;
    plannedDepartureAt: string;
    vehicleId: string;
    cabinCount: number;
  }) => Promise<TransferArrivalEstimate>>(),
  loadCapitalRepairCards: vi.fn().mockResolvedValue([]),
}));

vi.mock('../src/auth/panel-oidc', () => auth);
vi.mock('../src/features/transfers/transfer-client', () => transfers);

const SPB_ID = '11111111-1111-4111-8111-111111111111';
const NOVGOROD_ID = '22222222-2222-4222-8222-222222222222';
const MOSCOW_ID = '33333333-3333-4333-8333-333333333333';
const LOCAL_SPB_ID = '44444444-4444-4444-8444-444444444444';
const VEHICLE_ID = '55555555-5555-4555-8555-555555555555';
const DRIVER_ID = '12121212-1212-4121-8121-121212121212';
const TYPE_ID = '66666666-6666-4666-8666-666666666666';
const DIMENSION_ID = '77777777-7777-4777-8777-777777777777';
const FINISHING_ID = '88888888-8888-4888-8888-888888888888';
const CHARACTERISTIC_ID = '99999999-9999-4999-8999-999999999999';
const BED_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const TABLE_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const REPAIR_ONE_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const REPAIR_TWO_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const REPAIR_CABIN_ONE_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const REPAIR_CABIN_TWO_ID = 'ffffffff-ffff-4fff-8fff-ffffffffffff';

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
    local_warehouse_id: LOCAL_SPB_ID,
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

const catalog: TransferCargoCatalog = {
  rentalTypes: [{ id: TYPE_ID, name: 'BK2' }],
  dimensions: [{ id: DIMENSION_ID, name: '6 × 2,4 м' }],
  finishings: [{ id: FINISHING_ID, name: 'ЛДСП' }],
  characteristics: [{ id: CHARACTERISTIC_ID, name: 'ИТР' }],
  typeDimensions: [{ typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 }],
  furniture: [
    { id: BED_ID, name: 'Кровать', availableStock: 20, reservedQuantity: 3 },
    { id: TABLE_ID, name: 'Стол', availableStock: 10, reservedQuantity: 1 },
  ],
};

const estimate: TransferArrivalEstimate = {
  departure_at: '2026-08-30T05:30:00.000Z',
  estimated_arrival_at: '2026-08-30T09:20:00.000Z',
  travel_seconds: 13_800,
  distance_meters: 194_600,
  vehicle_id: VEHICLE_ID,
  cabin_count: 0,
  trailer_attached: false,
  routing_provider: 'valhalla',
  osm_data_version: '2026-08-29',
};

function renderDialog(onCreated = () => undefined) {
  return render(
    <TransferDraftDialog
      warehouses={warehouses}
      sourceWarehouseId={SPB_ID}
      destinationWarehouseId={NOVGOROD_ID}
      scheduledDate="2026-08-30"
      onClose={() => undefined}
      onCreated={onCreated}
    />,
  );
}

async function setDeparture(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(within(dialog).getByLabelText('Автомобиль рейса')).toHaveValue(VEHICLE_ID));
  fireEvent.change(within(dialog).getByLabelText('Плановое отправление'), { target: { value: '08:30' } });
  await user.click(within(dialog).getByText('Груз'));
  await within(dialog).findByText(/3 ч 50 мин/);
}

describe('standalone transfer draft dialog', () => {
  beforeEach(() => {
    auth.restorePanelUser.mockReset();
    auth.beginPanelLogin.mockReset();
    transfers.createTransferDraft.mockReset();
    transfers.createTransferContractor.mockReset();
    transfers.loadTransferCargoCatalog.mockReset();
    transfers.loadTransferRouteVehicles.mockReset();
    transfers.loadTransferDrivers.mockReset();
    transfers.estimateTransferArrival.mockReset();
    transfers.loadCapitalRepairCards.mockReset();
    transfers.loadCapitalRepairCards.mockResolvedValue([]);
    transfers.loadTransferCargoCatalog.mockResolvedValue(catalog);
    transfers.loadTransferRouteVehicles.mockResolvedValue([{
      id: VEHICLE_ID,
      name: 'SPB-04',
      registrationNumber: 'А123АА 178',
      capacity: 2,
    }]);
    transfers.loadTransferDrivers.mockResolvedValue([{ workerId: DRIVER_ID, displayName: 'Петров Алексей' }]);
    transfers.estimateTransferArrival.mockImplementation((input) => Promise.resolve({
      ...estimate,
      departure_at: input.plannedDepartureAt,
      cabin_count: input.cabinCount,
      trailer_attached: input.cabinCount === 2,
    }));
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

  it('creates an empty transfer with exact calculated arrival and keeps unavailable warehouses visible', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft.mockResolvedValue({ id: 'transfer-1', state: 'DRAFT' });
    const onCreated = vi.fn();
    const user = userEvent.setup();
    renderDialog(onCreated);

    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });
    const source = within(dialog).getByLabelText('Склад отправления');
    expect(within(source).getByRole('option', { name: 'Склад Москва · Нет координат в RWMS' })).toBeDisabled();
    await setDeparture(dialog, user);
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
        plannedDepartureAt: '2026-08-30T05:30:00.000Z',
        plannedArrivalAt: '2026-08-30T09:20:00.000Z',
        logisticsComment: 'Попутный рейс',
        tripDriverId: null,
        tripVehicleId: VEHICLE_ID,
        driverReposition: null,
        vehicleReposition: null,
        cabinGroups: [],
        looseFurniture: [],
      },
    });
    expect(transfers.estimateTransferArrival).toHaveBeenLastCalledWith(expect.objectContaining({ cabinCount: 0 }));
    expect(onCreated).toHaveBeenCalledWith({ id: 'transfer-1', state: 'DRAFT' });
  });

  it('reveals RWMS cabin fields behind the checkbox, recalculates furniture and submits the requirement', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft.mockResolvedValue({ id: 'transfer-cargo', state: 'DRAFT' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    expect(within(dialog).queryByLabelText('Бытовка 1')).not.toBeInTheDocument();
    await user.click(within(dialog).getByLabelText('Перевозить бытовки'));
    const cabin = await within(dialog).findByLabelText('Бытовка 1');
    expect(transfers.loadTransferCargoCatalog).toHaveBeenCalledWith('panel-token', SPB_ID);
    await user.selectOptions(within(cabin).getByLabelText('Тип бытовки 1'), TYPE_ID);
    await user.selectOptions(within(cabin).getByLabelText('Исполнение бытовки 1'), DIMENSION_ID);
    await user.selectOptions(within(cabin).getByLabelText('Отделка бытовки 1'), FINISHING_ID);
    fireEvent.change(within(cabin).getByLabelText('Количество бытовок 1'), { target: { value: '2' } });
    await user.click(within(cabin).getByLabelText('Линолеум'));
    await user.click(within(cabin).getByLabelText('ИТР'));
    await user.click(within(cabin).getByRole('button', { name: 'Добавить мебель' }));
    await user.selectOptions(within(cabin).getByLabelText('Мебель 1'), BED_ID);
    fireEvent.change(within(cabin).getByLabelText('Количество мебели 1 для бытовки 1'), { target: { value: '4' } });
    await user.click(within(cabin).getByRole('button', { name: 'Добавить мебель' }));
    await user.selectOptions(within(cabin).getByLabelText('Мебель 2'), TABLE_ID);

    expect(within(dialog).getByText('Кровать — 8')).toBeVisible();
    expect(within(dialog).getByText('Стол — 2')).toBeVisible();
    await setDeparture(dialog, user);
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledOnce());
    expect(transfers.createTransferDraft.mock.calls[0]![0].plan.cabinGroups).toEqual([{
      rentalTypeId: TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      characteristicIds: [CHARACTERISTIC_ID],
      linoleum: true,
      quantity: 2,
      furniturePerCabin: [
        { furnitureCatalogItemId: BED_ID, quantityPerCabin: 4 },
        { furnitureCatalogItemId: TABLE_ID, quantityPerCabin: 1 },
      ],
      allocatedCabins: [],
    }]);
    expect(transfers.estimateTransferArrival).toHaveBeenLastCalledWith(expect.objectContaining({ cabinCount: 2 }));
  });

  it('adds another independent cabin configuration from the bottom action', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });
    await user.click(within(dialog).getByLabelText('Перевозить бытовки'));
    await within(dialog).findByLabelText('Бытовка 1');
    await user.click(within(dialog).getByRole('button', { name: 'Добавить ещё бытовку' }));

    expect(within(dialog).getByLabelText('Бытовка 1')).toBeVisible();
    expect(within(dialog).getByLabelText('Бытовка 2')).toBeVisible();
  });

  it('keeps trip execution separate from a temporary operational warehouse assignment', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft.mockResolvedValue({ id: 'driver-transfer', state: 'DRAFT' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    await waitFor(() => expect(within(dialog).getByLabelText('Водитель рейса')).toBeEnabled());
    await user.selectOptions(within(dialog).getByLabelText('Водитель рейса'), DRIVER_ID);
    await user.click(within(dialog).getByLabelText('Переместить водителя на склад назначения'));
    fireEvent.change(within(dialog).getByLabelText('Назначение действует по'), { target: { value: '2026-09-03' } });
    await setDeparture(dialog, user);
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));

    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledOnce());
    const submitted = transfers.createTransferDraft.mock.calls[0]![0].plan;
    expect(submitted.tripDriverId).toBe(DRIVER_ID);
    expect(submitted.driverReposition).toEqual({
      resourceId: DRIVER_ID,
      mode: 'TEMPORARY',
      until: '2026-09-03T20:59:00.000Z',
    });
  });

  it('creates a time-bounded contractor explicitly and selects that driver for the trip', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferContractor.mockResolvedValue({ workerId: '34343434-3434-4343-8343-343434343434', displayName: 'Иванов Илья' });
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    await user.click(within(dialog).getByRole('button', { name: 'Добавить наёмного водителя' }));
    await user.type(within(dialog).getByLabelText('Имя наёмного водителя'), 'Иванов Илья');
    await user.type(within(dialog).getByLabelText('Телефон наёмного водителя'), '+79990001122');
    await user.click(within(dialog).getByRole('button', { name: 'Подтвердить доступность' }));

    await waitFor(() => expect(transfers.createTransferContractor).toHaveBeenCalledOnce());
    expect(transfers.createTransferContractor.mock.calls[0]![0]).toEqual(expect.objectContaining({
      accessToken: 'panel-token',
      warehouseId: SPB_ID,
      displayName: 'Иванов Илья',
      phone: '+79990001122',
      availableFrom: '2026-08-30T05:00:00.000Z',
      availableUntil: '2026-08-30T17:00:00.000Z',
    }));
    expect(within(dialog).getByLabelText('Водитель рейса')).toHaveValue('34343434-3434-4343-8343-343434343434');
  });

  it('selects exact capital-repair cabins after outbound cargo is unloaded', async () => {
    auth.restorePanelUser.mockResolvedValue({ access_token: 'panel-token' });
    transfers.createTransferDraft.mockResolvedValue({ id: 'round-trip-transfer', state: 'DRAFT' });
    transfers.loadCapitalRepairCards.mockResolvedValue([
      { repairId: REPAIR_ONE_ID, assetId: REPAIR_CABIN_ONE_ID, assetVersion: 4, assetNumber: 'ВН-172', priority: 1, complexity: 'Капитальный ремонт' },
      { repairId: REPAIR_TWO_ID, assetId: REPAIR_CABIN_TWO_ID, assetVersion: 7, assetNumber: 'ВН-311', priority: 2, complexity: 'Капитальный ремонт' },
    ]);
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole('dialog', { name: 'Создать перемещение' });

    await user.click(within(dialog).getByLabelText('Перевозить бытовки'));
    const outbound = await within(dialog).findByLabelText('Бытовка 1');
    await user.selectOptions(within(outbound).getByLabelText('Тип бытовки 1'), TYPE_ID);
    await user.selectOptions(within(outbound).getByLabelText('Исполнение бытовки 1'), DIMENSION_ID);
    await user.selectOptions(within(outbound).getByLabelText('Отделка бытовки 1'), FINISHING_ID);
    fireEvent.change(within(outbound).getByLabelText('Количество бытовок 1'), { target: { value: '2' } });
    await waitFor(() => expect(transfers.loadCapitalRepairCards).toHaveBeenCalledWith('panel-token', NOVGOROD_ID));
    await user.click(within(dialog).getByLabelText('Забрать бытовки на капремонт обратным рейсом'));
    await user.click(within(dialog).getByText(/ВН-172/).closest('label')!.querySelector('input')!);
    await user.click(within(dialog).getByText(/ВН-311/).closest('label')!.querySelector('input')!);
    expect(within(dialog).getByText('Выбрано: 2 из 2 доступных мест')).toBeVisible();

    await setDeparture(dialog, user);
    await user.click(within(dialog).getByRole('button', { name: 'Создать черновик' }));
    await waitFor(() => expect(transfers.createTransferDraft).toHaveBeenCalledOnce());
    expect(transfers.createTransferDraft.mock.calls[0]![0].returnCapitalRepairLines).toEqual([
      { repairId: REPAIR_ONE_ID, assetId: REPAIR_CABIN_ONE_ID, assetVersion: 4 },
      { repairId: REPAIR_TWO_ID, assetId: REPAIR_CABIN_TWO_ID, assetVersion: 7 },
    ]);
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
    await setDeparture(dialog, user);

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
    await setDeparture(dialog, user);

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
