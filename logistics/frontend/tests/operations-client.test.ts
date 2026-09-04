import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function initAt(fetchMock: ReturnType<typeof vi.fn>, index: number): RequestInit {
  return fetchMock.mock.calls[index]?.[1] as RequestInit;
}

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('dynamic logistics transport', () => {
  it('uses the selected warehouse and date for the complete operational projection', async () => {
    const projection = {
      warehouse_id: 'warehouse-selected',
      day: '2026-09-01',
      mode: 'DELIVERIES_AND_PICKUPS',
      mode_version: 0,
      pending_action_count: 0,
      events: [],
      notices: [],
      actions: [],
      decisions: [],
      proposals: [],
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(projection));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getPlanningDayOperations('warehouse-selected', '2026-09-01')).resolves.toEqual(projection);

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-selected/planning-days/2026-09-01/operations');
  });

  it('sends both day and current-plan fences when changing a filled day mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      warehouse_id: 'warehouse-1',
      day: '2026-09-01',
      mode: 'DELIVERIES_ONLY',
      version: 4,
      event_id: 'event-1',
      conflict_count: 2,
      pending_action_count: 2,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.updatePlanningDayMode('warehouse-1', '2026-09-01', {
      expected_version: 3,
      plan_id: 'plan-current',
      expected_plan_version: 8,
      mode: 'DELIVERIES_ONLY',
    }, 'mode-intent');

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-1/planning-days/2026-09-01/mode');
    expect(initAt(fetchMock, 0).method).toBe('PUT');
    expect(new Headers(initAt(fetchMock, 0).headers).get('Idempotency-Key')).toBe('mode-intent');
    expect(JSON.parse(initAt(fetchMock, 0).body as string)).toEqual({
      expected_version: 3,
      plan_id: 'plan-current',
      expected_plan_version: 8,
      mode: 'DELIVERIES_ONLY',
    });
  });

  it('posts fenced incidents, immutable decisions and confirmed proposal application', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ event: {}, notices: [], actions: [], proposals: [] }))
      .mockResolvedValueOnce(jsonResponse({ id: 'decision-1' }))
      .mockResolvedValueOnce(jsonResponse({ id: 'proposal-1', status: 'APPLIED' }));
    vi.stubGlobal('fetch', fetchMock);

    await api.createLogisticsEvent('warehouse-1', '2026-09-01', {
      event_type: 'VEHICLE_DELAY',
      plan_id: 'plan-current',
      expected_plan_version: 8,
      vehicle_id: 'vehicle-1',
      occurred_at: '2026-09-01T09:00:00Z',
      effective_at: '2026-09-01T09:00:00Z',
      delay_minutes: 45,
      reason: 'Пробка на КАД',
      facts: {},
    }, 'event-intent');
    await api.decideLogisticsAction('action-1', {
      expected_version: 2,
      expected_proposal_version: 4,
      decision_type: 'ACCEPT_OTHER_DATE',
      selected_date: '2026-09-03',
      comment: 'Согласовано по телефону',
    }, 'decision-intent');
    await api.applyRecoveryProposal('proposal-1', 5, 'apply-intent');

    expect(fetchMock.mock.calls.map(([url]) => String(url))).toEqual([
      '/api/warehouses/warehouse-1/planning-days/2026-09-01/events',
      '/api/logistics-actions/action-1/decisions',
      '/api/recovery-proposals/proposal-1/apply',
    ]);
    expect(fetchMock.mock.calls.map(([, init]) => (init as RequestInit).method)).toEqual(['POST', 'POST', 'POST']);
    expect(fetchMock.mock.calls.map(([, init]) => new Headers((init as RequestInit).headers).get('Idempotency-Key'))).toEqual([
      'event-intent',
      'decision-intent',
      'apply-intent',
    ]);
    expect(JSON.parse(initAt(fetchMock, 0).body as string)).toMatchObject({
      plan_id: 'plan-current',
      expected_plan_version: 8,
      delay_minutes: 45,
    });
    expect(JSON.parse(initAt(fetchMock, 1).body as string)).toEqual({
      expected_version: 2,
      expected_proposal_version: 4,
      decision_type: 'ACCEPT_OTHER_DATE',
      selected_date: '2026-09-03',
      comment: 'Согласовано по телефону',
    });
    expect(JSON.parse(initAt(fetchMock, 2).body as string)).toEqual({ expected_version: 5 });
  });
});
