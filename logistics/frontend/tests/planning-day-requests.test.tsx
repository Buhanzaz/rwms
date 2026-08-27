import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';
import type { LogisticsRequest, ScenarioWorkspace } from '../src/domain/types';
import { NotificationLog } from '../src/features/planning/NotificationLog';
import { PlanningDayRequests } from '../src/features/planning/PlanningDayRequests';

const request: LogisticsRequest = {
  id: 'request-1',
  scenario_id: 'scenario-1',
  type: 'DELIVERY',
  name: 'Заказ 142',
  address_label: 'Москва, Тверская, 10',
  latitude: 55.75,
  longitude: 37.61,
  quantity: 3,
  service_minutes: 30,
  priority: 10,
  status: 'READY',
  zone_id: 'zone-1',
  zone_version: 1,
  split_allowed: true,
  notes: '',
  created_at: '2026-08-25T08:00:00Z',
  updated_at: '2026-08-25T08:00:00Z',
  scheduled_date: null,
  trailer_access_allowed: null,
  include_driver_passport_in_notification: false,
  contact_name: 'Иван Петров',
  contact_phone: '+7 900 000-00-00',
  date_options: [{ date: '2026-08-26', priority: 100, window_start: null, window_end: null, is_hard: false, travel_zone_hours: null }],
  tasks: [
    { id: 'task-1', request_id: 'request-1', part_number: 1, quantity: 2, type: 'DELIVERY', latitude: 55.75, longitude: 37.61, zone_id: 'zone-1', zone_version: 1, service_minutes: 30, priority: 10, status: 'READY' },
    { id: 'task-2', request_id: 'request-1', part_number: 2, quantity: 1, type: 'DELIVERY', latitude: 55.75, longitude: 37.61, zone_id: 'zone-1', zone_version: 1, service_minutes: 30, priority: 10, status: 'READY' },
  ],
};

const workspace: ScenarioWorkspace = {
  scenario: {
    id: 'scenario-1',
    name: 'Москва',
    description: '',
    timezone: 'Europe/Moscow',
    default_planning_date: '2026-08-26',
    seed: 42,
    settings: DEFAULT_PLANNING_SETTINGS,
    created_at: '2026-08-25T08:00:00Z',
    updated_at: '2026-08-25T08:00:00Z',
  },
  warehouses: [],
  zones: [],
  zone_relations: [],
  drivers: [],
  vehicles: [],
  shifts: [],
  requests: [request],
};

describe('planning day preparation', () => {
  it('requires a time window and explicit trailer agreement before saving', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(() => Promise.resolve());

    render(<PlanningDayRequests workspace={workspace} planningDate="2026-08-26" busy={false} onPlanningDateChange={() => undefined} onSave={onSave} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    expect(screen.getByText(/не задано окно времени/)).toBeVisible();
    expect(screen.getByText(/не согласован проезд с прицепом/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Сохранить условия' })).toBeDisabled();

    await user.type(screen.getByLabelText('Доставка/вывоз с'), '09:00');
    await user.type(screen.getByLabelText('До'), '15:00');
    await user.selectOptions(screen.getByLabelText('Машина с прицепом проедет к адресу'), 'false');
    await user.click(screen.getByLabelText('Оповещение с паспортными данными водителя'));
    await user.click(screen.getByRole('button', { name: 'Сохранить условия' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    expect(onSave).toHaveBeenCalledWith('request-1', {
      date: '2026-08-26',
      window_start: '09:00',
      window_end: '15:00',
      is_hard: false,
      trailer_access_allowed: false,
      include_driver_passport_in_notification: true,
      contact_name: 'Иван Петров',
      contact_phone: '+7 900 000-00-00',
    });
  });

  it('keeps an exact RWMS/CustomerApp window visible and immutable', () => {
    const sourceRequest: LogisticsRequest = {
      ...request,
      source_system: 'RWMS',
      date_options: [{ date: '2026-08-26', priority: 100, window_start: '09:00', window_end: '12:00', is_hard: true, travel_zone_hours: 3 }],
    };
    render(<PlanningDayRequests workspace={{ ...workspace, requests: [sourceRequest] }} planningDate="2026-08-26" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    expect(screen.getByText('зона 3 ч')).toBeVisible();
    expect(screen.getByLabelText('Доставка/вывоз с')).toHaveValue('09:00');
    expect(screen.getByLabelText('Доставка/вывоз с')).toBeDisabled();
    expect(screen.getByLabelText('До')).toHaveValue('12:00');
    expect(screen.getByLabelText('До')).toBeDisabled();
    expect(screen.getByLabelText('Жёсткое временное окно · зафиксировано RWMS/CustomerApp')).toBeChecked();
    expect(screen.getByLabelText('Жёсткое временное окно · зафиксировано RWMS/CustomerApp')).toBeDisabled();
  });

  it('resets unsaved fields when the operator switches to another planning date', async () => {
    const user = userEvent.setup();
    const twoDateRequest: LogisticsRequest = {
      ...request,
      date_options: [
        { date: '2026-08-26', priority: 100, window_start: null, window_end: null, is_hard: false, travel_zone_hours: null },
        { date: '2026-08-27', priority: 100, window_start: null, window_end: null, is_hard: false, travel_zone_hours: null },
      ],
    };
    const twoDateWorkspace = { ...workspace, requests: [twoDateRequest] };
    const view = render(<PlanningDayRequests workspace={twoDateWorkspace} planningDate="2026-08-26" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    await user.type(screen.getByLabelText('Доставка/вывоз с'), '09:00');
    await user.type(screen.getByLabelText('До'), '15:00');
    await user.selectOptions(screen.getByLabelText('Машина с прицепом проедет к адресу'), 'false');

    view.rerender(<PlanningDayRequests workspace={twoDateWorkspace} planningDate="2026-08-27" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    await waitFor(() => {
      expect(screen.getByLabelText('Доставка/вывоз с')).toHaveValue('');
      expect(screen.getByLabelText('До')).toHaveValue('');
      expect(screen.getByLabelText('Машина с прицепом проедет к адресу')).toHaveValue('');
    });
  });

  it('creates explicit one-cabin transport subtasks', async () => {
    const user = userEvent.setup();
    const onSplit = vi.fn(() => Promise.resolve());

    render(<PlanningDayRequests workspace={workspace} planningDate="2026-08-26" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={onSplit} onSelect={() => undefined} />);
    await user.click(screen.getByRole('button', { name: 'Создать части по 1 БК' }));

    expect(onSplit).toHaveBeenCalledWith('request-1', [1, 1, 1]);
  });
});

describe('simulated notification journal', () => {
  it('shows the recipient and exact message at the bottom-left log surface', () => {
    render(<NotificationLog sidebarsCollapsed={false} logs={[{
      id: 'log-1',
      plan_id: 'plan-1',
      request_id: 'request-1',
      recipient_name: 'Иван Петров',
      recipient_contact: '+7 900 000-00-00',
      message: 'Доставка 26 августа. Водитель Пётр Сидоров, JAC N120, А123БВ. Паспорт 00 00 123456.',
      includes_driver_passport: true,
      status: 'SIMULATED_DELIVERED',
      created_at: '2026-08-26T08:00:00Z',
    }]} />);

    expect(screen.getByLabelText('Журнал тестовых уведомлений')).toBeVisible();
    expect(screen.getByText('Иван Петров')).toBeVisible();
    expect(screen.getByText(/JAC N120/)).toBeVisible();
    expect(screen.getByText(/включены паспортные данные/)).toBeVisible();
  });
});
