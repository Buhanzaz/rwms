import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isRequestVisibleOnDate, requestPlanningDates } from '../src/domain/request-dates';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { LogisticsRequest, RouteCycle } from '../src/domain/types';
import { RequestMapCard, RequestMapPopup } from '../src/map/RequestMapCard';
import { planFixture, requestFixture } from './fixtures';

const popupMock = vi.hoisted(() => ({
  addTo: vi.fn(),
  options: vi.fn(),
  remove: vi.fn(),
  setDOMContent: vi.fn(),
  setLngLat: vi.fn(),
}));

vi.mock('maplibre-gl', () => ({
  default: {
    Popup: class Popup {
      private content: HTMLElement | null = null;

      constructor(options: unknown) {
        popupMock.options(options);
      }

      setLngLat(coordinates: [number, number]) {
        popupMock.setLngLat(coordinates);
        return this;
      }

      setDOMContent(element: HTMLElement) {
        this.content = element;
        popupMock.setDOMContent(element);
        document.body.append(element);
        return this;
      }

      addTo(map: unknown) {
        popupMock.addTo(map);
        return this;
      }

      remove() {
        this.content?.remove();
        popupMock.remove();
      }
    },
  },
}));

const request: LogisticsRequest = requestFixture({
  id: 'request-142',
  name: 'Доставка №142',
  address_label: 'Москва, Тестовая улица, 25',
  latitude: 55.8,
  longitude: 37.6,
  service_minutes: 35,
  notes: 'Позвонить за час',
  scheduled_date: null,
  date_options: [
    { date: '2026-08-25', priority: 20, window_start: '09:00', window_end: '11:00', is_hard: true },
    { date: '2026-08-26', priority: 10, window_start: null, window_end: null, is_hard: false },
  ],
});

function cycle(id: string, shiftId: string, taskId: string | null): RouteCycle {
  return {
    id,
    route_plan_id: 'plan-1',
    driver_shift_id: shiftId,
    sequence: 1,
    planned_start: '2026-08-25T08:00:00+03:00',
    planned_finish: '2026-08-25T11:00:00+03:00',
    total_distance_meters: 10_000,
    total_travel_seconds: 1_800,
    total_service_seconds: 1_200,
    empty_distance_meters: 2_000,
    detour_seconds: 0,
    score: 10,
    locked: false,
    stops: taskId ? [{
      id: `${id}-stop`, route_cycle_id: id, sequence: 1, task_id: taskId, stop_type: 'DELIVERY',
      planned_arrival: '2026-08-25T09:00:00+03:00', planned_departure: '2026-08-25T09:30:00+03:00',
      service_seconds: 1_800, quantity_delta: -2, load_before: 2, load_after: 0,
      latitude: 55.8, longitude: 37.6, label: 'Доставка №142',
    }] : [],
    legs: [],
    explanation: [],
    warnings: [],
  };
}

describe('request map card', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the rental purpose and a callable contact without presenting delivery cost as rent', () => {
    render(<RequestMapCard request={{ ...request, customer_delivery_purpose: 'RENTAL_DELIVERY', contact_name: 'Анна', contact_phone: '+7 900 123-45-67', delivery_price_rubles: 28500 }} planningDate="2026-08-25" busy={false} onSchedule={() => undefined} onUnschedule={() => undefined} onClose={() => undefined} />);
    expect(screen.getByText('Доставка в аренду')).toBeVisible();
    expect(screen.getByText('Анна')).toBeVisible();
    expect(screen.getByRole('link', { name: '+7 900 123-45-67' })).toHaveAttribute('href', 'tel:+79001234567');
    expect(screen.getByText('Стоимость доставки')).toBeVisible();
    expect(screen.getByText(/28\s500 ₽/)).toBeVisible();
    expect(screen.queryByText(/Стоимость аренды/)).not.toBeInTheDocument();
  });

  it('anchors the card above the selected marker with a native map popup', async () => {
    const onClose = vi.fn();
    const map = { id: 'map-instance' };
    const view = render(
      <RequestMapPopup
        map={map as never}
        request={request}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={onClose}
      />,
    );

    expect(await screen.findByText('Доставка №142')).toBeVisible();
    expect(screen.getByTestId('request-map-menu')).not.toHaveClass('map-overlay');
    expect(popupMock.options).toHaveBeenCalledWith(expect.objectContaining({
      anchor: 'bottom',
      className: 'request-map-popup',
      closeButton: false,
      closeOnClick: false,
      offset: [0, -26],
    }));
    expect(popupMock.setLngLat).toHaveBeenCalledWith([37.6, 55.8]);
    expect(popupMock.addTo).toHaveBeenCalledWith(map);

    view.rerender(
      <RequestMapPopup
        map={map as never}
        request={{ ...request, longitude: 37.72, latitude: 55.91 }}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={onClose}
      />,
    );
    await waitFor(() => expect(popupMock.setLngLat).toHaveBeenLastCalledWith([37.72, 55.91]));
    expect(popupMock.remove).toHaveBeenCalledOnce();

    await userEvent.click(screen.getByRole('button', { name: 'Закрыть карточку доставки или вывоза' }));
    expect(onClose).toHaveBeenCalledOnce();

    view.unmount();
    await waitFor(() => expect(popupMock.remove).toHaveBeenCalledTimes(2));
  });

  it('shows logistics details and assigns a customer-approved date', async () => {
    const user = userEvent.setup();
    const onSchedule = vi.fn();
    render(
      <RequestMapCard
        request={request}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={onSchedule}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText('Доставка №142')).toBeVisible();
    expect(screen.getByText('Москва, Тестовая улица, 25')).toBeVisible();
    expect(screen.getByText('2 бытов. · обслуживание 35 мин')).toBeVisible();
    expect(screen.getByText('Не рассчитана')).toBeVisible();
    expect(screen.getByText('Контактное лицо')).toBeVisible();
    expect(screen.getByText(/25 августа 2026.*09:00–11:00.*жёстко/)).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Выставить на 25 августа 2026 г.' }));
    expect(onSchedule).toHaveBeenCalledWith('request-142', '2026-08-25', false);
  });

  it('shows an explicit missing pickup price', () => {
    render(
      <RequestMapCard
        request={{ ...request, type: 'PICKUP', name: 'Вывоз №142' }}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText('Не рассчитана')).toBeVisible();
  });

  it('shows the persisted delivery price and contractor handoff', () => {
    render(
      <RequestMapCard
        request={{
          ...request,
          delivery_price_rubles: 28_500,
          price_isochrone_minutes: 180,
          assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_name: 'Иван Петров',
        }}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText(/28\s500 ₽ · до 3 ч в пути от склада/)).toBeVisible();
    expect(screen.getByText(/Передано наёмному водителю:/)).toHaveTextContent('Иван Петров');
  });

  it('marks a manually chosen date as an explicit agreement', async () => {
    const user = userEvent.setup();
    const onSchedule = vi.fn();
    render(
      <RequestMapCard
        request={request}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={onSchedule}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Дата для логистики' }));
    await user.click(screen.getByRole('button', { name: /29 августа 2026/ }));
    expect(screen.getByText(/не входила в варианты клиента/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Добавить дату и выставить на 29 августа 2026 г.' }));
    expect(onSchedule).toHaveBeenCalledWith('request-142', '2026-08-29', true);
  }, 10_000);

  it('shows and removes an authoritative scheduled date', async () => {
    const user = userEvent.setup();
    const onUnschedule = vi.fn();
    render(
      <RequestMapCard
        request={{ ...request, scheduled_date: '2026-08-26' }}
        planningDate="2026-08-26"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={onUnschedule}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText(/Выставлено на/)).toHaveTextContent('Выставлено на 26 августа 2026 г.');
    await user.click(screen.getByRole('button', { name: 'Снять с назначенной даты' }));
    expect(onUnschedule).toHaveBeenCalledWith('request-142');
  });

  it('shows the assigned driver and moves the delivery task to another cycle', async () => {
    const user = userEvent.setup();
    const onMoveTask = vi.fn();
    const requestWithTask = {
      ...request,
      tasks: [{
        id: 'task-142', request_id: request.id, part_number: 1, quantity: 2, type: 'DELIVERY' as const,
        latitude: request.latitude, longitude: request.longitude,
        service_minutes: request.service_minutes, priority: 10, mandatory: false, status: 'READY',
      }],
    };
    const source = cycle('cycle-1', 'shift-1', 'task-142');
    const target = cycle('cycle-2', 'shift-2', 'pickup-task');
    target.stops[0]!.stop_type = 'PICKUP';
    const plan = planFixture({
      date: '2026-08-25',
      driver_routes: [
        {
          driver_shift_id: 'shift-1', shift_start_at: '2026-08-25T08:00:00+03:00', shift_end_at: '2026-08-25T20:00:00+03:00',
          driver_id: 'driver-1', driver_name: 'Антон', vehicle_id: 'vehicle-1', vehicle_name: 'МАЗ', registration_number: 'А123БВ',
          cycles: [source], metrics: { ...EMPTY_METRICS },
        },
        {
          driver_shift_id: 'shift-2', shift_start_at: '2026-08-25T08:00:00+03:00', shift_end_at: '2026-08-25T20:00:00+03:00',
          driver_id: 'driver-2', driver_name: 'Борис', vehicle_id: 'vehicle-2', vehicle_name: 'КАМАЗ', registration_number: 'В456ГД',
          cycles: [target], metrics: { ...EMPTY_METRICS },
        },
      ],
    });

    render(
      <RequestMapCard
        request={requestWithTask}
        plan={plan}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onMoveTask={onMoveTask}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText('Водитель и рейс')).toBeVisible();
    expect(screen.getByLabelText('Рейс для Доставка №142')).toHaveDisplayValue(/Антон · цикл 1/);
    await user.selectOptions(screen.getByLabelText('Рейс для Доставка №142'), 'cycle-2');
    expect(onMoveTask).toHaveBeenCalledWith({
      taskId: 'task-142', sourceCycleId: 'cycle-1', targetCycleId: 'cycle-2', targetSequence: 1, kind: 'MOVE_TASK',
    });
  });
});

describe('request planning date visibility', () => {
  it('uses all accepted dates until scheduling and only the selected date afterwards', () => {
    expect(isRequestVisibleOnDate(request, '2026-08-25')).toBe(true);
    expect(isRequestVisibleOnDate(request, '2026-08-26')).toBe(true);

    const scheduled = { ...request, scheduled_date: '2026-08-26' };
    expect(isRequestVisibleOnDate(scheduled, '2026-08-25')).toBe(false);
    expect(isRequestVisibleOnDate(scheduled, '2026-08-26')).toBe(true);
    expect(requestPlanningDates(scheduled)).toEqual(['2026-08-26']);
    expect(requestPlanningDates(request)).toEqual(['2026-08-25', '2026-08-26']);
  });
});
