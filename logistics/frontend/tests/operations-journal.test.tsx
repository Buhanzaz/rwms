import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, type PlanningDayOperations } from '../src/api/client';
import { SettingsEditor } from '../src/features/settings/SettingsEditor';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture } from './fixtures';

const DAY = '2026-09-03';

function operationsFixture(): PlanningDayOperations {
  return {
    warehouse_id: 'warehouse-root',
    planning_date: DAY,
    mode: 'DELIVERIES_AND_PICKUPS',
    mode_version: 2,
    events: [{
      id: 'event-1',
      event_type: 'VEHICLE_DELAY',
    }],
    notices: [{
      id: 'notice-1',
      event_id: 'event-1',
      notice_type: 'VEHICLE_DELAY',
      severity: 'WARNING',
      status: 'COMPLETED',
      requires_action: false,
      message_ru: 'Машина задерживается на 30 минут.',
      recommended_action_ru: 'Клиент предупреждён.',
      request_id: 'request-1',
      task_id: null,
      cycle_id: null,
      facts: {},
      resolved_at: '2026-09-03T09:20:00Z',
      created_at: '2026-09-03T09:00:00Z',
    }],
    actions: [{
      id: 'action-1',
      notice_id: 'notice-1',
      request_id: 'request-1',
    }],
    decisions: [{
      id: 'decision-1',
      action_id: 'action-1',
      actor: 'dispatcher',
      decision_type: 'ACCEPT_DELAY',
      selected_date: null,
      comment: 'Клиент предупреждён',
      created_at: '2026-09-03T09:15:00Z',
    }],
    proposals: [],
    pending_action_count: 0,
    truncated_collections: ['NOTICES'],
  } as unknown as PlanningDayOperations;
}

afterEach(() => {
  vi.restoreAllMocks();
  useUiStore.setState({ settingsView: 'ALGORITHM' });
});

describe('settings operations journal', () => {
  it('loads the server history under Settings and keeps it off the day workspace', async () => {
    const user = userEvent.setup();
    const getOperations = vi.spyOn(api, 'getPlanningDayOperations').mockResolvedValue(operationsFixture());
    const onSelectRequest = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <SettingsEditor
          warehouse={warehouseFixture({ timezone: 'Europe/Moscow' })}
          busy={false}
          onSave={() => Promise.resolve()}
          journal={{
            warehouseId: 'warehouse-root',
            planningDate: DAY,
            timeZone: 'Europe/Moscow',
            onSelectRequest,
          }}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Настройки алгоритма' })).toBeVisible();
    await user.click(screen.getByRole('tab', { name: 'Журнал' }));

    await waitFor(() => expect(getOperations).toHaveBeenCalledWith('warehouse-root', DAY));
    expect(await screen.findByRole('heading', { name: 'Журнал за 03 сентября 2026 г.' })).toBeVisible();
    expect(screen.getByText('Машина задерживается на 30 минут.', { exact: false })).toBeVisible();
    expect(screen.getByText('Клиент согласен на опоздание', { exact: false })).toBeVisible();
    expect(screen.getByText('Показано только последнее доступное окно истории за выбранный день.')).toBeVisible();
    expect(screen.queryByText('Системный журнал')).not.toBeInTheDocument();
    expect(useUiStore.getState().settingsView).toBe('JOURNAL');

    await user.click(screen.getAllByRole('button', { name: 'Показать заявку' })[0]!);
    expect(onSelectRequest).toHaveBeenCalledWith('request-1');
  });
});
