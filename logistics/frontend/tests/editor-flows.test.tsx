import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { DriverInput, LogisticsRequestInput, ShiftInput, WarehouseUpdateInput } from '../src/api/client';
import {
  CatalogDialog,
  RequestDialog,
  ShiftDialog,
  WarehouseDialog,
} from '../src/components/EntityDialogs';
import { warehouseFixture, workspaceFixture } from './fixtures';
import { DateRangePicker } from '../src/components/DatePicker';

describe('hotel date range', () => {
  it('does not commit after the first click and commits the inclusive second endpoint', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<DateRangePicker from="" to="" label="Период смены" onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Период смены' }));
    const calendar = screen.getByRole('dialog', { name: 'Календарь: Период смены' });
    await user.click(within(calendar).getByRole('button', { name: /11 августа 2026/ }));
    expect(onChange).not.toHaveBeenCalled();
    expect(within(calendar).getByRole('button', { name: 'Готово' })).toBeDisabled();
    await user.click(within(calendar).getByRole('button', { name: /12 августа 2026/ }));
    expect(onChange).toHaveBeenCalledWith('2026-08-11', '2026-08-12');
    expect(within(calendar).getByRole('button', { name: 'Готово' })).toBeEnabled();
  });
});

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

describe('driver shift range', () => {
  it('submits an inclusive period selected in one calendar', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.click(screen.getByRole('button', { name: 'Период смены' }));
    const calendar = screen.getByRole('dialog', { name: 'Календарь: Период смены' });
    await user.click(within(calendar).getByRole('button', { name: /11 августа/ }));
    await user.click(within(calendar).getByRole('button', { name: /12 августа/ }));
    await user.click(within(calendar).getByRole('button', { name: 'Готово' }));
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ date_from: '2026-08-11', date_to: '2026-08-12', start_time: '08:00', end_time: '20:00' });
  });
});

describe('request editor', () => {
  it('retains every planning rule when opened for rescheduling an existing request', () => {
    const request = workspaceFixture().requests[0]!;
    render(<RequestDialog request={request} type={request.type} defaultDate="2026-08-30" busy={false} onClose={() => undefined} onSubmit={() => Promise.resolve()} />);

    expect(screen.getByLabelText('Дата')).toHaveValue('2026-08-30');
    expect(screen.getByLabelText('Жёсткое окно')).toBeChecked();
    expect(screen.getByLabelText('Машина с прицепом проедет к адресу')).toHaveValue('true');
    expect(screen.getByLabelText('Обязательная доставка')).not.toBeChecked();
    expect(screen.getByLabelText('Оповещение с паспортными данными водителя')).not.toBeChecked();
  });

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
