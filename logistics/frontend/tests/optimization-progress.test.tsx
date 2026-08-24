import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';

vi.mock('maplibre-gl', () => {
  class MapStub {
    addControl(): void {}
    on(): void {}
    off(): void {}
    remove(): void {}
  }

  return {
    default: {
      Map: MapStub,
      NavigationControl: class NavigationControlStub {},
      AttributionControl: class AttributionControlStub {},
    },
  };
});

class EventSourceStub {
  static instances: EventSourceStub[] = [];

  readonly url: string;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: (() => void) | null = null;
  private readonly listeners = new Map<string, Set<EventListener>>();

  constructor(url: string | URL) {
    this.url = String(url);
    EventSourceStub.instances.push(this);
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (typeof listener !== 'function') return;
    const listeners = this.listeners.get(type) ?? new Set<EventListener>();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (typeof listener === 'function') this.listeners.get(type)?.delete(listener);
  }

  close(): void {}

  emit(type: string, value: unknown): void {
    const event = new MessageEvent(type, { data: JSON.stringify(value) });
    this.listeners.get(type)?.forEach((listener) => listener(event));
    if (type === 'message') this.onmessage?.(event);
  }
}

const scenario = {
  id: 'scenario-id',
  name: 'Тестовый сценарий',
  description: '',
  timezone: 'Europe/Moscow',
  default_planning_date: '2026-08-25',
  created_at: '2026-08-20T08:00:00Z',
  updated_at: '2026-08-22T08:00:00Z',
  seed: 42,
  settings: { ...DEFAULT_PLANNING_SETTINGS, trace_enabled: true },
};

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function installFetchRouter() {
  const routeFetch = (input: RequestInfo | URL, init?: RequestInit): Response => {
    const url = typeof input === 'string'
      ? input
      : input instanceof URL
        ? input.href
        : input.url;
    const method = init?.method ?? 'GET';
    if (url === '/api/scenarios') return jsonResponse([scenario]);
    if (url === '/api/scenarios/scenario-id') return jsonResponse(scenario);
    if (url === '/api/scenarios/scenario-id/warehouses') {
      return jsonResponse([{
        id: 'warehouse-id', scenario_id: 'scenario-id', name: 'Основной склад', latitude: 55.75, longitude: 37.61,
        loading_minutes: 30, unloading_minutes: 20, turnaround_minutes: 20, working_day_start: '08:00:00', working_day_end: '20:00:00',
      }]);
    }
    if (url === '/api/scenarios/scenario-id/zones') return jsonResponse([]);
    if (url === '/api/scenarios/scenario-id/zone-relations') return jsonResponse([]);
    if (url === '/api/scenarios/scenario-id/drivers') {
      return jsonResponse([{ id: 'driver-id', scenario_id: 'scenario-id', name: 'Водитель 1', preferred_route_group: 'WEST', active: true, notes: '' }]);
    }
    if (url === '/api/scenarios/scenario-id/vehicles') {
      return jsonResponse([{
        id: 'vehicle-id', scenario_id: 'scenario-id', name: 'МАЗ', registration_number: 'А123БВ', capacity: 2,
        active: true, average_speed_city: 35, average_speed_region: 65, notes: '',
      }]);
    }
    if (url === '/api/scenarios/scenario-id/shifts') {
      return jsonResponse([{
        id: 'shift-id', scenario_id: 'scenario-id', driver_id: 'driver-id', vehicle_id: 'vehicle-id', date: '2026-08-25',
        start_at: '2026-08-25T05:00:00Z', end_at: '2026-08-25T17:00:00Z', break_minutes: 30,
        preferred_route_group: 'WEST', active: true,
      }]);
    }
    if (url === '/api/scenarios/scenario-id/requests') {
      return jsonResponse([{
        id: 'request-id', scenario_id: 'scenario-id', type: 'DELIVERY', name: 'Доставка 1', address_label: 'Адрес',
        latitude: 55.8, longitude: 37.7, quantity: 1, service_minutes: 30, priority: 1, status: 'READY',
        zone_id: null, zone_version: null, split_allowed: true, notes: '', created_at: '2026-08-20T08:00:00Z',
        updated_at: '2026-08-22T08:00:00Z', zone_classification_status: 'OUTSIDE_ZONES', zone_is_stale: false,
        date_options: [{ id: 'date-id', request_id: 'request-id', date: '2026-08-25', priority: 1, window_start: null, window_end: null, is_hard: false }],
        tasks: [],
      }]);
    }
    if (url === '/api/scenarios/scenario-id/plans/generate' && method === 'POST') {
      return jsonResponse({ run_id: 'run-id', plan_id: null, status: 'PENDING' });
    }
    if (url === '/api/optimization-runs/run-id') {
      return jsonResponse({
        id: 'run-id', scenario_id: 'scenario-id', plan_id: null, status: 'RUNNING', started_at: '2026-08-22T08:00:00Z',
        finished_at: null, seed: 42, settings_snapshot: {}, initial_score: null, final_score: null, error_message: null,
        stopped_by_limit: false, cancel_requested: false,
      });
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  };
  const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
    (input, init) => Promise.resolve(routeFetch(input, init)),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

beforeEach(() => {
  EventSourceStub.instances = [];
  vi.stubGlobal('EventSource', EventSourceStub);
});

describe('optimization progress', () => {
  it('renders a named SSE phase and normalized percentage after generation', async () => {
    const fetchMock = installFetchRouter();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={client}><App /></QueryClientProvider>);

    const generate = await screen.findByRole('button', { name: /Построить маршруты/ });
    await user.click(generate);

    const progress = await screen.findByTestId('optimization-progress');
    expect(progress).toHaveTextContent('VALIDATING_INPUT');
    await waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(0));
    const activeStream = EventSourceStub.instances.at(-1);
    expect(activeStream?.url).toBe('/api/optimization-runs/run-id/stream');

    act(() => {
      activeStream?.emit('phase_progress', {
        id: 'event-id',
        optimization_run_id: 'run-id',
        sequence: 3,
        event_type: 'phase_progress',
        payload: { phase: 'BUILDING_TRAVEL_MATRIX', progress: 42 },
        created_at: '2026-08-22T08:00:01Z',
      });
    });

    expect(progress).toHaveTextContent('BUILDING_TRAVEL_MATRIX');
    expect(progress).toHaveTextContent('42%');
    expect(progress).toHaveTextContent('seed 42 · события поиска 1');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/scenarios/scenario-id/plans/generate',
      expect.objectContaining({ method: 'POST' }),
    );
  });
});
