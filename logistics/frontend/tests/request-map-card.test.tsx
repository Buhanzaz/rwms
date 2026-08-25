import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isRequestVisibleOnDate } from '../src/domain/request-dates';
import type { LogisticsRequest, Zone } from '../src/domain/types';
import { RequestMapCard, RequestMapPopup } from '../src/map/RequestMapCard';

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

const request: LogisticsRequest = {
  id: 'request-142',
  scenario_id: 'scenario-id',
  type: 'DELIVERY',
  name: 'Доставка №142',
  address_label: 'Москва, Тестовая улица, 25',
  latitude: 55.8,
  longitude: 37.6,
  quantity: 2,
  service_minutes: 35,
  priority: 10,
  status: 'READY',
  zone_id: 'zone-z1',
  zone_version: 3,
  split_allowed: true,
  notes: 'Позвонить за час',
  created_at: '2026-08-20T08:00:00Z',
  updated_at: '2026-08-22T08:00:00Z',
  scheduled_date: null,
  date_options: [
    { date: '2026-08-25', priority: 20, window_start: '09:00', window_end: '11:00', is_hard: true },
    { date: '2026-08-26', priority: 10, window_start: null, window_end: null, is_hard: false },
  ],
  zone_status: 'CURRENT',
};

const zone: Zone = {
  id: 'zone-z1',
  scenario_id: 'scenario-id',
  name: 'Запад',
  code: 'Z1',
  route_group: 'WEST',
  delivery_price: 125,
  pickup_price: 75,
  geometry: { type: 'Polygon', coordinates: [[[37, 55], [38, 55], [38, 56], [37, 55]]] },
  version: 3,
  priority: 1,
  locked: false,
  created_at: '2026-08-20T08:00:00Z',
  updated_at: '2026-08-22T08:00:00Z',
};

describe('request map card', () => {
  beforeEach(() => vi.clearAllMocks());

  it('anchors the card above the selected marker with a native map popup', async () => {
    const onClose = vi.fn();
    const map = { id: 'map-instance' };
    const view = render(
      <RequestMapPopup
        map={map as never}
        request={request}
        zone={zone}
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
        zone={zone}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={onClose}
      />,
    );
    await waitFor(() => expect(popupMock.setLngLat).toHaveBeenLastCalledWith([37.72, 55.91]));
    expect(popupMock.remove).toHaveBeenCalledOnce();

    await userEvent.click(screen.getByRole('button', { name: 'Закрыть карточку заявки' }));
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
        zone={zone}
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
    expect(screen.getByText('Зона Z1 · версия 3')).toBeVisible();
    expect(screen.getByText('Доставка · 125 ₽')).toBeVisible();
    expect(screen.getByText(/25 августа 2026.*09:00–11:00.*жёстко/)).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Выставить на 25 августа 2026 г.' }));
    expect(onSchedule).toHaveBeenCalledWith('request-142', '2026-08-25', false);
  });

  it('shows the pickup tariff from the current zone', () => {
    render(
      <RequestMapCard
        request={{ ...request, type: 'PICKUP', name: 'Вывоз №142' }}
        zone={zone}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText('Вывоз · 75 ₽')).toBeVisible();
  });

  it('marks a manually chosen date as an explicit agreement', async () => {
    const user = userEvent.setup();
    const onSchedule = vi.fn();
    render(
      <RequestMapCard
        request={request}
        zone={zone}
        planningDate="2026-08-25"
        busy={false}
        onSchedule={onSchedule}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    fireEvent.change(screen.getByLabelText('Дата для логистики'), { target: { value: '2026-08-29' } });
    expect(screen.getByText(/не входила в варианты клиента/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Добавить дату и выставить на 29 августа 2026 г.' }));
    expect(onSchedule).toHaveBeenCalledWith('request-142', '2026-08-29', true);
  });

  it('shows and removes an authoritative scheduled date', async () => {
    const user = userEvent.setup();
    const onUnschedule = vi.fn();
    render(
      <RequestMapCard
        request={{ ...request, scheduled_date: '2026-08-26' }}
        zone={zone}
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
});

describe('request planning date visibility', () => {
  it('uses all accepted dates until scheduling and only the selected date afterwards', () => {
    expect(isRequestVisibleOnDate(request, '2026-08-25')).toBe(true);
    expect(isRequestVisibleOnDate(request, '2026-08-26')).toBe(true);

    const scheduled = { ...request, scheduled_date: '2026-08-26' };
    expect(isRequestVisibleOnDate(scheduled, '2026-08-25')).toBe(false);
    expect(isRequestVisibleOnDate(scheduled, '2026-08-26')).toBe(true);
  });
});
