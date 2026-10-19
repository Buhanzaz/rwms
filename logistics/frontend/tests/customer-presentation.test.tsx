import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { LogisticsRequest, RouteCycle } from '../src/domain/types';
import { PlanPanel } from '../src/features/planning/PlanPanel';
import { PlanningDayRequests } from '../src/features/planning/PlanningDayRequests';
import { RequestMapCard } from '../src/map/RequestMapCard';
import {
  customerDeliveryPurposeLabel,
  customerLegalTypeLabel,
  normalizeCustomerLegalType,
  type CustomerLegalType,
} from '../src/utils/customer-presentation';
import { planFixture, requestFixture, workspaceFixture } from './fixtures';

vi.mock('maplibre-gl', () => ({ default: {} }));

function requestWithLegalType(
  clientType: CustomerLegalType | null,
  overrides: Partial<LogisticsRequest> = {},
): LogisticsRequest & { client_type: CustomerLegalType | null } {
  return { ...requestFixture(overrides), client_type: clientType };
}

function routeCycle(taskId: string, label: string): RouteCycle {
  return {
    id: 'cycle-customer-type',
    route_plan_id: 'plan-1',
    driver_shift_id: 'shift-1',
    sequence: 2,
    planned_start: '2026-08-30T08:00:00+03:00',
    planned_finish: '2026-08-30T10:00:00+03:00',
    total_distance_meters: 12_000,
    total_travel_seconds: 1_800,
    total_service_seconds: 1_200,
    empty_distance_meters: 2_000,
    detour_seconds: 0,
    score: 12,
    locked: false,
    stops: [{
      id: 'stop-customer-type',
      route_cycle_id: 'cycle-customer-type',
      sequence: 1,
      task_id: taskId,
      stop_type: 'DELIVERY',
      planned_arrival: '2026-08-30T09:00:00+03:00',
      planned_departure: '2026-08-30T09:30:00+03:00',
      service_seconds: 1_800,
      quantity_delta: -1,
      load_before: 1,
      load_after: 0,
      latitude: 59.9,
      longitude: 30.3,
      label,
    }],
    legs: [],
    explanation: [],
    warnings: [],
  };
}

describe('canonical customer legal type presentation', () => {
  it.each([
    ['RENTAL_DELIVERY', 'Доставка в аренду'],
    ['SALE_DELIVERY', 'Доставка продажи'],
    ['CUSTOMER_RELOCATION', 'Перемещение клиента'],
    [null, 'Доставка'],
  ] as const)('labels the explicit customer delivery purpose %s', (value, label) => {
    expect(customerDeliveryPurposeLabel(value)).toBe(label);
  });

  it.each([
    ['INDIVIDUAL', 'Физическое лицо'],
    ['SOLE_PROPRIETOR', 'Индивидуальный предприниматель'],
    ['LEGAL_ENTITY', 'Юридическое лицо'],
    [null, 'Тип клиента не указан'],
  ] as const)('normalizes %s without using customer names', (value, label) => {
    expect(normalizeCustomerLegalType(value)).toBe(value);
    expect(customerLegalTypeLabel(value)).toBe(label);
  });

  it('shows the explicit type in the planning request card', () => {
    const request = requestWithLegalType('LEGAL_ENTITY', {
      name: 'Доставка №77',
      customer_delivery_purpose: 'SALE_DELIVERY',
    });
    render(
      <PlanningDayRequests
        workspace={workspaceFixture({ requests: [request] })}
        planningDate="2026-08-30"
        busy={false}
        onPlanningDateChange={() => undefined}
        onSave={() => Promise.resolve()}
        onSplit={() => Promise.resolve()}
        onSelect={() => undefined}
      />,
    );

    expect(screen.getByTestId(`planning-request-${request.id}`)).toHaveTextContent('Тип клиента: Юридическое лицо');
    expect(screen.getByTestId(`planning-request-${request.id}`)).toHaveTextContent('Доставка продажи');
  });

  it('shows the explicit type in the delivery or pickup map card', () => {
    const request = requestWithLegalType('SOLE_PROPRIETOR', { name: 'Вывоз №81', type: 'PICKUP' });
    render(
      <RequestMapCard
        request={request}
        planningDate="2026-08-30"
        busy={false}
        onSchedule={() => undefined}
        onUnschedule={() => undefined}
        onClose={() => undefined}
      />,
    );

    const details = screen.getByText('Тип клиента').closest('div');
    expect(details).not.toBeNull();
    expect(within(details as HTMLElement).getByText('Индивидуальный предприниматель')).toBeVisible();
  });

  it('resolves an assigned route stop through its task id and never infers from an ООО name', () => {
    const request = requestWithLegalType('INDIVIDUAL', { id: 'request-route', name: 'ООО Ложная подсказка' });
    const task = { ...request.tasks![0]!, id: 'task-route', request_id: request.id, quantity: 1 };
    request.tasks = [task];
    const cycle = routeCycle(task.id, request.name);
    const plan = planFixture({
      driver_routes: [{
        driver_shift_id: 'shift-1',
        shift_start_at: '2026-08-30T08:00:00+03:00',
        shift_end_at: '2026-08-30T20:00:00+03:00',
        driver_id: 'driver-1',
        driver_name: 'Иван Петров',
        vehicle_id: 'vehicle-1',
        vehicle_name: 'МАЗ',
        registration_number: 'А123БВ',
        cycles: [cycle],
        metrics: { ...EMPTY_METRICS },
      }],
    });

    render(
      <PlanPanel
        plan={plan}
        requests={[request]}
        timeZone="Europe/Moscow"
        onSelectCycle={() => undefined}
        onSelectDriverRoute={() => undefined}
        onMove={() => undefined}
        onToggleLock={() => undefined}
      />,
    );

    const stop = screen.getByTestId('stop-stop-customer-type');
    expect(stop).toHaveTextContent('Тип клиента: Физическое лицо');
    expect(stop).not.toHaveTextContent('Юридическое лицо');
  });

  it('shows the missing-type label in an unassigned request card', () => {
    const request = requestWithLegalType(null, { id: 'request-unassigned', name: 'ИП тоже не подсказка' });
    const task = { ...request.tasks![0]!, id: 'task-unassigned', request_id: request.id };
    request.tasks = [task];
    render(
      <PlanPanel
        plan={planFixture({
          unassigned: [{
            task,
            request,
            reason_codes: ['UNKNOWN'],
            reasons: ['Причина уточняется'],
            recommendations: [],
          }],
        })}
        requests={[request]}
        timeZone="Europe/Moscow"
        showUnassignedOnly
        onSelectCycle={() => undefined}
        onSelectDriverRoute={() => undefined}
        onMove={() => undefined}
        onToggleLock={() => undefined}
      />,
    );

    expect(screen.getByText('Тип клиента не указан')).toBeVisible();
  });
});
