import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { DriverInput, LogisticsRequestInput, ShiftInput, WarehouseUpdateInput, ZoneInput } from '../src/api/client';
import {
  CatalogDialog,
  RequestDialog,
  ShiftDialog,
  WarehouseDialog,
  ZoneDialog,
} from '../src/components/EntityDialogs';
import { warehouseFixture, workspaceFixture } from './fixtures';

const polygon = {
  type: 'Polygon' as const,
  coordinates: [[[30, 59], [31, 59], [31, 60], [30, 59]]],
};

describe('warehouse editor', () => {
  it('edits planning settings without exposing canonical RWMS identity fields', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: WarehouseUpdateInput) => Promise<void>>(() => Promise.resolve());
    render(<WarehouseDialog
      warehouse={warehouseFixture()}
      busy={false}
      onClose={() => undefined}
      onSubmit={submit}
    />);

    expect(screen.getByText('Склад СПб')).toBeVisible();
    expect(screen.queryByLabelText('Название')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Адрес')).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Загрузка, мин'));
    await user.type(screen.getByLabelText('Загрузка, мин'), '45');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ loading_minutes: 45 });
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('external_warehouse_id');
  });
});

describe('zone editor', () => {
  it('submits a selected gradient color and no removed metadata', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ZoneInput) => Promise<void>>(() => Promise.resolve());
    render(<ZoneDialog geometry={polygon} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('Название'), 'Север');
    expect(screen.getByLabelText('Назначение зоны')).toHaveValue('SPECIAL_PRICE');
    fireEvent.change(screen.getByLabelText('Цвет зоны'), { target: { value: '#3366ff' } });
    fireEvent.change(screen.getByLabelText('Тариф доставки, ₽'), { target: { value: '20000' } });
    fireEvent.change(screen.getByLabelText('Тариф вывоза, ₽'), { target: { value: '15000' } });
    await user.click(screen.getByRole('button', { name: 'Сохранить зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toEqual({
      name: 'Север',
      kind: 'SPECIAL_PRICE',
      color: '#3366FF',
      delivery_price: 20000,
      pickup_price: 15000,
      locked: false,
      geometry: polygon,
    });
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('code');
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('priority');
    expect(screen.queryByText('Вершины')).not.toBeInTheDocument();
  });

  it('stores no UI-only price when the polygon forbids trailer access', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ZoneInput) => Promise<void>>(() => Promise.resolve());
    render(<ZoneDialog geometry={polygon} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('Название'), 'Без прицепа');
    await user.selectOptions(screen.getByLabelText('Назначение зоны'), 'NO_TRAILER');
    expect(screen.queryByLabelText('Тариф доставки, ₽')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Сохранить зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'NO_TRAILER',
      delivery_price: 0,
      pickup_price: 0,
    })));
  });
});

describe('driver assignment', () => {
  it('defaults to the qualified warehouse pool without fabricating a worker identity', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: DriverInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="driver" availableDrivers={[]} busy={false} onClose={() => undefined} onSubmit={(input) => submit(input as DriverInput)} />);

    expect(screen.getByLabelText('Назначение в RWMS')).toHaveValue('WAREHOUSE_DRIVERS');
    expect(screen.getByText(/всех активных работников склада/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ rwms_assignment_mode: 'WAREHOUSE_DRIVERS', external_worker_id: null });
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('name');
  });

  it('allows selecting one qualified RWMS driver explicitly', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: DriverInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="driver" availableDrivers={[{ worker_id: '22222222-2222-4222-8222-222222222222', display_name: 'Иван Петров' }]} busy={false} onClose={() => undefined} onSubmit={(input) => submit(input as DriverInput)} />);

    await user.selectOptions(screen.getByLabelText('Назначение в RWMS'), 'ASSIGNED_DRIVER');
    await user.selectOptions(screen.getByLabelText('Водитель RWMS'), '22222222-2222-4222-8222-222222222222');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ rwms_assignment_mode: 'ASSIGNED_DRIVER', external_worker_id: '22222222-2222-4222-8222-222222222222' });
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('name');
  });
});

describe('monthly driver shift', () => {
  it('submits a period inside one month instead of a single calendar day', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    fireEvent.change(screen.getByLabelText('Работает с'), { target: { value: '2026-08-01' } });
    fireEvent.change(screen.getByLabelText('Работает по'), { target: { value: '2026-08-15' } });
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ date_from: '2026-08-01', date_to: '2026-08-15', start_time: '08:00', end_time: '20:00' });
  });

  it('rejects a period crossing a month boundary', async () => {
    const user = userEvent.setup();
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={warehouseFixture()} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={() => Promise.resolve()} />);
    fireEvent.change(screen.getByLabelText('Работает с'), { target: { value: '2026-08-15' } });
    fireEvent.change(screen.getByLabelText('Работает по'), { target: { value: '2026-09-01' } });
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));
    expect(await screen.findByText('Период смены должен находиться внутри одного месяца')).toBeVisible();
  });
});

describe('request editor', () => {
  it('uses only delivery/pickup, preserves reverse-geocoded address and submits the mandatory flag', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="DELIVERY" point={{ latitude: 59.935, longitude: 30.325 }} initialAddress="СПб, Невский проспект, 1" defaultDate="2026-08-30" busy={false} onClose={() => undefined} onSubmit={submit} />);

    expect(screen.getByLabelText('Тип')).toHaveTextContent('ДоставкаВывоз');
    expect(screen.getByLabelText('Адрес (подпись)')).toHaveValue('СПб, Невский проспект, 1');
    expect(screen.queryByLabelText('Широта')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Долгота')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Координат/ })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Название / номер'), 'Доставка 142');
    await user.click(screen.getByLabelText('Обязательная доставка'));
    await user.click(screen.getByRole('button', { name: 'Сохранить доставку' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({
      type: 'DELIVERY',
      mandatory: true,
      address_label: 'СПб, Невский проспект, 1',
      latitude: 59.935,
      longitude: 30.325,
    });
  });
});
