import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { LogisticsRequestInput, ShiftInput, WarehouseUpdateInput } from '../src/api/client';
import {
  RequestDialog,
  ShiftDialog,
  WarehouseDialog,
} from '../src/components/EntityDialogs';
import { warehouseFixture, workspaceFixture } from './fixtures';
import { DateRangePicker } from '../src/components/DatePicker';
import { formatIsoDate } from '../src/components/date-value';

function currentMonthDay(day: number): Date {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), day, 12);
}

function calendarDateName(date: Date): RegExp {
  const label = new Intl.DateTimeFormat('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(date).replace(/\s*г\.$/u, '');
  return new RegExp(label.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'), 'u');
}

describe('hotel date range', () => {
  it('does not commit after the first click and commits the inclusive second endpoint', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<DateRangePicker from="" to="" label="Период смены" onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Период смены' }));
    const calendar = screen.getByRole('dialog', { name: 'Календарь: Период смены' });
    const from = currentMonthDay(11);
    const to = currentMonthDay(12);
    await user.click(within(calendar).getByRole('button', { name: calendarDateName(from) }));
    expect(onChange).not.toHaveBeenCalled();
    expect(within(calendar).getByRole('button', { name: 'Готово' })).toBeDisabled();
    await user.click(within(calendar).getByRole('button', { name: calendarDateName(to) }));
    expect(onChange).toHaveBeenCalledWith(formatIsoDate(from), formatIsoDate(to));
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

    expect(screen.getByText(/СПб — Санкт-Петербург, Шоссе Революции, 1 — Europe\/Moscow/u)).toBeVisible();
    expect(screen.queryByLabelText('Название')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Адрес')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Дата.*по умолчанию/iu)).not.toBeInTheDocument();
    expect(screen.queryByText(/Изохроны и цены/iu)).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Среднее время загрузки, мин'));
    await user.type(screen.getByLabelText('Среднее время загрузки, мин'), '45');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ loading_minutes: 45 });
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('external_warehouse_id');
  });
});

describe('driver shift range', () => {
  it('explains an existing driver assignment before sending a conflicting shift', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} shifts={workspace.shifts} busy={false} onClose={() => undefined} onSubmit={submit} />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Смены водителя пересекаются');
    expect(alert).toHaveTextContent(/уже есть активная смена/u);
    expect(screen.getByRole('button', { name: 'Сохранить смену' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));
    expect(submit).not.toHaveBeenCalled();
  });

  it('submits an inclusive period selected in one calendar', async () => {
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    fireEvent.click(screen.getByRole('button', { name: 'Период смены' }));
    const calendar = screen.getByRole('dialog', { name: 'Календарь: Период смены' });
    fireEvent.click(within(calendar).getByRole('button', { name: /11 августа/ }));
    fireEvent.click(within(calendar).getByRole('button', { name: /12 августа/ }));
    fireEvent.click(within(calendar).getByRole('button', { name: 'Готово' }));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ date_from: '2026-08-11', date_to: '2026-08-12', start_time: '08:00', end_time: '20:00' });
  });

  it('uses next-day duration for an overnight shift and rejects a break that consumes it', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    fireEvent.change(screen.getByLabelText('Начало'), { target: { value: '22:00' } });
    fireEvent.change(screen.getByLabelText('Окончание'), { target: { value: '06:00' } });
    fireEvent.change(screen.getByLabelText('Перерыв, мин'), { target: { value: '480' } });
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    expect(await screen.findByText('Перерыв должен быть короче смены')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });

  it('rejects a zero-duration shift instead of treating equal times as overnight', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.clear(screen.getByLabelText('Начало'));
    await user.type(screen.getByLabelText('Начало'), '00:00');
    await user.clear(screen.getByLabelText('Окончание'));
    await user.type(screen.getByLabelText('Окончание'), '00:00');
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    expect(await screen.findByText('Окончание смены должно отличаться от начала')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });

  it('submits an overnight inclusive range across a month boundary and shows its actual endpoint', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    const shift = {
      ...workspace.shifts[0]!,
      date_from: '2026-08-25',
      date_to: '2026-09-05',
      start_time: '22:00',
      end_time: '06:00',
      break_minutes: 60,
      active: false,
    };
    render(<ShiftDialog shift={shift} warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    expect(screen.getByRole('status')).toHaveTextContent(/25 августа 2026.*22:00.*06 сентября 2026.*06:00/u);
    expect(screen.getByRole('status')).toHaveTextContent('Смена неактивна');
    expect(screen.getByRole('status')).toHaveTextContent('12 календарных дней');
    expect(screen.getByRole('status')).toHaveTextContent('окончание на следующий день');
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({
      date_from: '2026-08-25',
      date_to: '2026-09-05',
      start_time: '22:00',
      end_time: '06:00',
      break_minutes: 60,
      active: false,
    })));
  });

  it('rejects an inclusive range longer than 31 consecutive days', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(<ShiftDialog shift={{ ...workspace.shifts[0]!, date_from: '2026-08-01', date_to: '2026-09-01' }} warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    expect(await screen.findByText('Период смены не может быть длиннее 31 дня')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
});

describe('request editor', () => {
  it('retains every planning rule when opened for rescheduling an existing request', () => {
    const request = workspaceFixture().requests[0]!;
    render(<RequestDialog request={request} type={request.type} defaultDate="2026-08-30" busy={false} onClose={() => undefined} onSubmit={() => Promise.resolve()} />);

    expect(screen.getByText('Стоимость доставки рассчитывается по изохроне склада')).toBeVisible();
    expect(screen.queryByText(/backend/iu)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Дата', expanded: false })).toHaveTextContent('30 августа 2026');
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
    expect(screen.queryByLabelText('Статус')).not.toBeInTheDocument();
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
    expect(submit.mock.calls[0]?.[0]).not.toHaveProperty('status');
  });
});
