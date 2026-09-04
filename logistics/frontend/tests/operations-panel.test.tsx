import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps, ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  type LogisticsAction,
  type LogisticsDecision,
  type LogisticsEventInput,
  type LogisticsNotice,
  type PlanningDayOperations,
  type RecoveryProposal,
} from '../src/api/client';
import type * as ApiClientModule from '../src/api/client';
import { OperationsPanel } from '../src/features/operations/OperationsPanel';
import { useUiStore } from '../src/stores/ui-store';
import { planFixture, requestFixture, warehouseFixture, workspaceFixture } from './fixtures';

const apiMocks = vi.hoisted(() => ({
  applyRecoveryProposal: vi.fn(),
  createLogisticsEvent: vi.fn(),
  decideLogisticsAction: vi.fn(),
  getPlanningDayOperations: vi.fn(),
  updatePlanningDayMode: vi.fn(),
}));

vi.mock('../src/api/client', async () => {
  const actual = await vi.importActual<typeof ApiClientModule>('../src/api/client');
  return {
    ...actual,
    api: {
      ...actual.api,
      ...apiMocks,
    },
  };
});

const DAY = '2026-08-30';

function eventFixture(overrides: Partial<PlanningDayOperations['events'][number]> = {}): PlanningDayOperations['events'][number] {
  return {
    id: 'event-1',
    warehouse_id: 'warehouse-1',
    day: DAY,
    plan_id: 'plan-1',
    cycle_id: null,
    request_id: 'request-1',
    task_id: 'task-1',
    event_type: 'PLANNING_MODE_CHANGED',
    source_event: null,
    occurred_at: '2026-08-30T08:00:00Z',
    actor: 'dispatcher',
    facts: {},
    created_at: '2026-08-30T08:00:00Z',
    ...overrides,
  };
}

function noticeFixture(overrides: Partial<LogisticsNotice> = {}): LogisticsNotice {
  return {
    id: 'notice-1',
    event_id: 'event-1',
    warehouse_id: 'warehouse-1',
    day: DAY,
    plan_id: 'plan-1',
    cycle_id: null,
    request_id: 'request-1',
    task_id: 'task-1',
    notice_type: 'MODE_CONFLICT',
    severity: 'WARNING',
    reason_codes: ['MODE_CONFLICT'],
    facts: {},
    message_ru: 'Вывоз не разрешён новым режимом дня.',
    recommended_action_ru: 'Согласуйте перенос с клиентом.',
    requires_action: true,
    status: 'REQUIRES_ACTION',
    created_at: '2026-08-30T08:00:00Z',
    resolved_at: null,
    ...overrides,
  };
}

function actionFixture(overrides: Partial<LogisticsAction> = {}): LogisticsAction {
  return {
    id: 'action-1',
    notice_id: 'notice-1',
    event_id: 'event-1',
    warehouse_id: 'warehouse-1',
    day: DAY,
    request_id: 'request-1',
    action_type: 'RESCHEDULE_REQUEST',
    status: 'PENDING',
    version: 2,
    customer_name: 'ООО Север',
    customer_type: 'LEGAL_ENTITY',
    customer_phone: '8 (999) 123-45-67',
    current_date: DAY,
    recommended_date: '2026-09-02',
    alternative_dates: ['2026-09-03'],
    context: {},
    created_at: '2026-08-30T08:00:00Z',
    updated_at: '2026-08-30T08:00:00Z',
    resolved_at: null,
    ...overrides,
  };
}

function proposalFixture(overrides: Partial<RecoveryProposal> = {}): RecoveryProposal {
  return {
    id: 'proposal-1',
    event_id: 'event-1',
    action_id: 'action-1',
    warehouse_id: 'warehouse-1',
    day: DAY,
    source_plan_id: 'plan-1',
    result_plan_id: null,
    proposal_type: 'RESCHEDULE_REQUEST',
    status: 'PROPOSED',
    version: 4,
    summary_ru: 'Перенести вывоз на согласованную дату.',
    affected_request_ids: ['request-1'],
    affected_task_ids: ['task-1'],
    changes: {},
    metrics: {},
    failure_code: null,
    created_at: '2026-08-30T08:00:00Z',
    updated_at: '2026-08-30T08:00:00Z',
    applied_at: null,
    ...overrides,
  };
}

function decisionFixture(overrides: Partial<LogisticsDecision> = {}): LogisticsDecision {
  return {
    id: 'decision-1',
    action_id: 'action-1',
    event_id: 'event-1',
    decision_type: 'ACCEPT_RECOMMENDATION',
    selected_date: '2026-09-02',
    actor: 'dispatcher',
    comment: null,
    constraint_data: {},
    created_at: '2026-08-30T08:10:00Z',
    ...overrides,
  };
}

function operationsFixture(overrides: Partial<PlanningDayOperations> = {}): PlanningDayOperations {
  return {
    warehouse_id: 'warehouse-1',
    day: DAY,
    mode: 'DELIVERIES_AND_PICKUPS',
    mode_version: 0,
    pending_action_count: 0,
    events: [],
    notices: [],
    actions: [],
    decisions: [],
    proposals: [],
    ...overrides,
  };
}

function renderWithClient(node: ReactNode): QueryClient {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
  return queryClient;
}

function renderPanel(overrides: Partial<ComponentProps<typeof OperationsPanel>> = {}) {
  const workspace = workspaceFixture({
    planning_date: DAY,
    requests: [Object.assign(
      requestFixture({ name: 'Вывоз №42', type: 'PICKUP', scheduled_date: DAY }),
      { client_type: 'LEGAL_ENTITY' as const },
    )],
  });
  const props: ComponentProps<typeof OperationsPanel> = {
    workspace,
    plan: null,
    planningDate: DAY,
    busy: false,
    onSelectRequest: vi.fn(),
    ...overrides,
  };
  const queryClient = renderWithClient(<OperationsPanel {...props} />);
  return { props, queryClient };
}

function representativeGroupWorkspace() {
  const root = warehouseFixture({ id: 'warehouse-root', name: 'Корневой склад группы' });
  const selected = warehouseFixture({
    id: 'warehouse-selected',
    name: 'Выбранный представитель',
    representative: true,
  });
  const serviceWarehouse = warehouseFixture({
    id: 'warehouse-service',
    name: 'Сервисный склад Выборг',
    representative: true,
  });
  const task = requestFixture().tasks![0]!;
  return workspaceFixture({
    warehouse: selected,
    warehouses: [root, selected, serviceWarehouse],
    planning_root_warehouse_id: root.id,
    planning_group_warehouse_ids: [root.id, selected.id, serviceWarehouse.id],
    planning_date: DAY,
    requests: [requestFixture({
      id: 'request-group',
      warehouse_id: serviceWarehouse.id,
      name: 'Вывоз группы №17',
      type: 'PICKUP',
      scheduled_date: DAY,
      tasks: [
        { ...task, id: 'task-group', request_id: 'request-group', type: 'PICKUP', status: 'READY' },
        { ...task, id: 'task-completed', request_id: 'request-group', part_number: 2, type: 'PICKUP', status: 'COMPLETED' },
        { ...task, id: 'task-cancelled', request_id: 'request-group', part_number: 3, type: 'PICKUP', status: 'CANCELLED' },
      ],
    })],
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  useUiStore.setState({ notifications: [] });
  apiMocks.createLogisticsEvent.mockResolvedValue({ event: eventFixture(), notices: [], actions: [], proposals: [] });
  apiMocks.decideLogisticsAction.mockResolvedValue(decisionFixture());
});

afterEach(() => {
  useUiStore.setState({ notifications: [] });
});

describe('day mode and conflict projection', () => {
  it('changes an empty future day without fabricating a conflict or action', async () => {
    const initial = operationsFixture();
    const changed = operationsFixture({ mode: 'DELIVERIES_ONLY', mode_version: 1 });
    apiMocks.getPlanningDayOperations.mockResolvedValueOnce(initial).mockResolvedValue(changed);
    apiMocks.updatePlanningDayMode.mockResolvedValue({
      warehouse_id: 'warehouse-1', day: DAY, mode: 'DELIVERIES_ONLY', version: 1,
      event_id: 'mode-event', conflict_count: 0, pending_action_count: 0,
    });
    const user = userEvent.setup();
    renderPanel();

    expect(await screen.findByRole('heading', { name: /Операции на 30 августа 2026/u })).toBeVisible();
    expect(screen.queryByText('Склад СПб')).not.toBeInTheDocument();
    expect(screen.queryByText(/Ограничивает направления для оптимизатора/u)).not.toBeInTheDocument();
    expect(screen.queryByText(/Версия режима:/u)).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Только доставки' }));

    await waitFor(() => expect(apiMocks.updatePlanningDayMode).toHaveBeenCalledOnce());
    expect(apiMocks.updatePlanningDayMode.mock.calls[0]?.slice(0, 3)).toEqual([
      'warehouse-1', DAY, {
        expected_version: 0,
        plan_id: null,
        expected_plan_version: null,
        mode: 'DELIVERIES_ONLY',
      },
    ]);
    expect(await screen.findByText('Действий не требуется')).toBeVisible();
    expect(screen.getByText('Предложений нет')).toBeVisible();
    expect(useUiStore.getState().notifications.at(-1)).toMatchObject({
      title: 'Режим дня изменён',
      detail: 'Конфликтующих заданий нет. Очередь действий не создавалась.',
    });
  });

  it('uses the exact current plan fence and renders affected customer facts after a real conflict', async () => {
    const conflict = operationsFixture({
      mode: 'PICKUPS_ONLY',
      mode_version: 5,
      pending_action_count: 1,
      events: [eventFixture()],
      notices: [noticeFixture()],
      actions: [actionFixture()],
      proposals: [proposalFixture()],
    });
    apiMocks.getPlanningDayOperations.mockResolvedValueOnce(operationsFixture({ mode_version: 4 })).mockResolvedValue(conflict);
    apiMocks.updatePlanningDayMode.mockResolvedValue({
      warehouse_id: 'warehouse-1', day: DAY, mode: 'PICKUPS_ONLY', version: 5,
      event_id: 'event-1', conflict_count: 1, pending_action_count: 1,
    });
    const user = userEvent.setup();
    renderPanel({ plan: planFixture({ date: DAY, version: 9 }) });

    await user.click(await screen.findByRole('button', { name: 'Только вывозы' }));

    await waitFor(() => expect(apiMocks.updatePlanningDayMode).toHaveBeenCalledOnce());
    expect(apiMocks.updatePlanningDayMode.mock.calls[0]?.[2]).toEqual({
      expected_version: 4,
      plan_id: 'plan-1',
      expected_plan_version: 9,
      mode: 'PICKUPS_ONLY',
    });
    const card = await screen.findByTestId('operations-action-action-1');
    expect(within(card).getByRole('heading', { name: 'Вывоз №42' })).toBeVisible();
    expect(within(card).getByText('Юридическое лицо')).toBeVisible();
    const phone = within(card).getByText('+7 999 123-45-67');
    expect(phone).toBeVisible();
    expect(phone.tagName).toBe('STRONG');
    expect(card.querySelector('a[href^="tel:"]')).toBeNull();
    expect(within(card).getAllByLabelText('Клиент отказался')).toHaveLength(1);
    expect(within(card).getByText('Вывоз не разрешён новым режимом дня.')).toBeVisible();
    expect(within(card).getByText(/02 сентября 2026/)).toBeVisible();
    expect(within(card).getByText(/03 сентября 2026/)).toBeVisible();
  });

  it('uses the planning root projection and exact root plan fence for a selected representative', async () => {
    const initial = operationsFixture({ warehouse_id: 'warehouse-root' });
    const changed = operationsFixture({
      warehouse_id: 'warehouse-root',
      mode: 'DELIVERIES_ONLY',
      mode_version: 1,
    });
    apiMocks.getPlanningDayOperations.mockResolvedValueOnce(initial).mockResolvedValue(changed);
    apiMocks.updatePlanningDayMode.mockResolvedValue({
      warehouse_id: 'warehouse-root', day: DAY, mode: 'DELIVERIES_ONLY', version: 1,
      event_id: 'mode-event', conflict_count: 0, pending_action_count: 0,
    });
    const user = userEvent.setup();
    const { queryClient } = renderPanel({
      workspace: representativeGroupWorkspace(),
      plan: planFixture({
        id: 'plan-root',
        warehouse_id: 'warehouse-root',
        date: DAY,
        version: 12,
      }),
    });

    await screen.findByRole('heading', { name: /Операции на 30 августа 2026/u });
    expect(screen.queryByText('Выбранный представитель')).not.toBeInTheDocument();
    expect(apiMocks.getPlanningDayOperations).toHaveBeenCalledWith('warehouse-root', DAY);
    expect(queryClient.getQueryData(['planning-day-operations', 'warehouse-root', DAY])).toEqual(initial);
    await user.click(screen.getByRole('button', { name: 'Только доставки' }));

    await waitFor(() => expect(apiMocks.updatePlanningDayMode).toHaveBeenCalledOnce());
    expect(apiMocks.updatePlanningDayMode.mock.calls[0]?.slice(0, 3)).toEqual([
      'warehouse-root',
      DAY,
      {
        expected_version: 0,
        plan_id: 'plan-root',
        expected_plan_version: 12,
        mode: 'DELIVERIES_ONLY',
      },
    ]);
    expect(apiMocks.getPlanningDayOperations).not.toHaveBeenCalledWith('warehouse-selected', DAY);
  });

  it('uses the shared Russian conflict feedback and refetches stale mode data', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture());
    apiMocks.updatePlanningDayMode.mockRejectedValue(new ApiError(409, {
      code: 'PLANNING_DAY_MODE_VERSION_CONFLICT',
      detail: 'Traceback /api/internal mode fence mismatch',
    }, 'HTTP 409'));
    const user = userEvent.setup();
    const { queryClient } = renderPanel({ plan: planFixture({ date: DAY, version: 9 }) });
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries');

    await user.click(await screen.findByRole('button', { name: 'Только доставки' }));

    await waitFor(() => expect(apiMocks.getPlanningDayOperations).toHaveBeenCalledTimes(2));
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['plan'] });
    const notification = useUiStore.getState().notifications.at(-1);
    expect(notification).toMatchObject({
      tone: 'warning',
      title: 'Изменение конфликтует с текущими данными',
    });
    expect(JSON.stringify(notification)).not.toMatch(/Traceback|internal|PLANNING_DAY_MODE_VERSION_CONFLICT/u);
  });
});

describe('dispatcher decision queue', () => {
  it('removes the separate journal and hydrates notices and decisions into bounded notification history', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      events: [eventFixture({ event_type: 'VEHICLE_DELAY' })],
      notices: [noticeFixture({ requires_action: false, status: 'COMPLETED' })],
      actions: [actionFixture({ status: 'RESOLVED' })],
      decisions: [decisionFixture({ comment: 'Клиент предупреждён' })],
      truncated_collections: ['NOTICES', 'DECISIONS'],
    }));
    renderPanel();

    await screen.findByRole('heading', { name: /Операции на 30 августа 2026/u });
    expect(screen.queryByRole('region', { name: 'Системный журнал' })).not.toBeInTheDocument();
    await waitFor(() => expect(useUiStore.getState().notifications).toHaveLength(3));
    const initialNotice = useUiStore.getState().notifications.find((item) => (
      item.replacementKey === 'logistics-notice-notice-1'
    ));
    const initialDecision = useUiStore.getState().notifications.find((item) => (
      item.replacementKey === 'logistics-decision-decision-1'
    ));
    const truncatedWarning = useUiStore.getState().notifications.find((item) => (
      item.title === 'История операций показана частично'
    ));
    expect(initialNotice).toMatchObject({ visible: false, read: true });
    expect(initialDecision).toMatchObject({ title: 'Логист', visible: false, read: true });
    expect(initialDecision?.detail).toContain('Клиент предупреждён');
    expect(truncatedWarning).toMatchObject({ visible: true, read: false });
  });

  it.each([
    ['recommended', 'Согласована рекомендованная дата', 'ACCEPT_RECOMMENDATION', undefined],
    ['other', 'Согласована другая дата', 'ACCEPT_OTHER_DATE', '2026-09-03'],
    ['unreachable', 'Не удалось связаться', 'UNREACHABLE', undefined],
  ] as const)('submits the %s outcome with action and proposal fences', async (_name, label, decisionType, selectedDate) => {
    const projection = operationsFixture({
      pending_action_count: 1,
      events: [eventFixture()],
      notices: [noticeFixture()],
      actions: [actionFixture()],
      proposals: [proposalFixture()],
    });
    apiMocks.getPlanningDayOperations.mockResolvedValue(projection);
    const user = userEvent.setup();
    renderPanel();

    const card = await screen.findByTestId('operations-action-action-1');
    await user.click(within(card).getByLabelText(label));
    if (selectedDate) await user.selectOptions(within(card).getByLabelText('Согласованная дата'), selectedDate);
    await user.click(within(card).getByRole('button', { name: 'Сохранить решение' }));

    await waitFor(() => expect(apiMocks.decideLogisticsAction).toHaveBeenCalledOnce());
    expect(apiMocks.decideLogisticsAction.mock.calls[0]?.[0]).toBe('action-1');
    expect(apiMocks.decideLogisticsAction.mock.calls[0]?.[1]).toEqual({
      expected_version: 2,
      expected_proposal_version: 4,
      decision_type: decisionType,
      ...(selectedDate ? { selected_date: selectedDate } : {}),
      comment: null,
    });
    expect(apiMocks.decideLogisticsAction.mock.calls[0]?.[2]).toEqual(expect.any(String));
  });

  it('does not enable date agreement or rejection before the backend offers a feasible date', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      pending_action_count: 1,
      notices: [noticeFixture()],
      actions: [actionFixture({ recommended_date: null, alternative_dates: [] })],
      proposals: [proposalFixture()],
    }));
    renderPanel();

    const card = await screen.findByTestId('operations-action-action-1');
    expect(within(card).getByLabelText('Согласована рекомендованная дата')).toBeDisabled();
    expect(within(card).getByLabelText('Согласована другая дата')).toBeDisabled();
    expect(within(card).getByLabelText('Клиент отказался')).toBeDisabled();
    expect(within(card).getByLabelText('Не удалось связаться')).toBeChecked();
    expect(within(card).getByRole('button', { name: 'Сохранить решение' })).toBeEnabled();
  });

  it('turns a rejection into a constraint and displays the refreshed proposal', async () => {
    const initial = operationsFixture({
      pending_action_count: 1,
      events: [eventFixture()],
      notices: [noticeFixture()],
      actions: [actionFixture()],
      proposals: [proposalFixture()],
    });
    const refreshed = operationsFixture({
      pending_action_count: 1,
      events: [eventFixture()],
      notices: [
        noticeFixture({ status: 'REJECTED', requires_action: false }),
        noticeFixture({
          id: 'notice-2',
          created_at: '2026-08-30T08:05:00Z',
          message_ru: 'Отказ сохранён. Найден следующий вариант.',
        }),
      ],
      actions: [actionFixture({ notice_id: 'notice-2', version: 3, recommended_date: '2026-09-04', alternative_dates: [] })],
      decisions: [decisionFixture({
        decision_type: 'REJECT',
        selected_date: null,
        actor: 'dispatcher<00000000-0000-0000-0000-000000000001>',
        comment: 'Клиент отказался от переноса',
        created_at: '2026-08-30T08:03:00Z',
      })],
      proposals: [
        proposalFixture({ status: 'REJECTED', version: 5 }),
        proposalFixture({ id: 'proposal-2', version: 1, created_at: '2026-08-30T08:05:00Z', summary_ru: 'Повторно согласовать выполнимую дату с клиентом.' }),
      ],
    });
    apiMocks.getPlanningDayOperations.mockResolvedValueOnce(initial).mockResolvedValue(refreshed);
    apiMocks.decideLogisticsAction.mockResolvedValue(decisionFixture({
      decision_type: 'REJECT',
      selected_date: null,
      actor: 'dispatcher<00000000-0000-0000-0000-000000000001>',
      comment: 'Клиент отказался от переноса',
      created_at: '2026-08-30T08:03:00Z',
    }));
    const user = userEvent.setup();
    renderPanel();

    const card = await screen.findByTestId('operations-action-action-1');
    await user.click(within(card).getByLabelText('Клиент отказался'));
    expect(within(card).getByText(/новым ограничением/)).toBeVisible();
    await user.click(within(card).getByRole('button', { name: 'Сохранить решение' }));

    await waitFor(() => expect(apiMocks.decideLogisticsAction).toHaveBeenCalledOnce());
    expect(apiMocks.decideLogisticsAction.mock.calls[0]?.[1]).toMatchObject({ decision_type: 'REJECT' });
    expect(await screen.findByText(/04 сентября 2026/)).toBeVisible();
    expect(screen.getByText('Повторно согласовать выполнимую дату с клиентом.')).toBeVisible();
    expect(screen.queryByRole('region', { name: 'Системный журнал' })).not.toBeInTheDocument();
    const decisionNotification = useUiStore.getState().notifications.find((item) => item.title === 'Логист');
    const recalculationNotification = useUiStore.getState().notifications.find((item) => (
      item.title === 'Логистика требует внимания'
    ));
    expect(decisionNotification?.detail).toContain('Клиент отказался');
    expect(recalculationNotification?.detail).toContain('Отказ сохранён. Найден следующий вариант.');
  });

  it('keeps an in-slot delay informational and out of the requires-action queue', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      events: [eventFixture({ event_type: 'VEHICLE_DELAY' })],
      notices: [noticeFixture({
        notice_type: 'VEHICLE_DELAY_IMPACT',
        severity: 'INFO',
        reason_codes: ['ETA_IN_SLOT'],
        facts: { tasks: [{ task_id: 'task-1', classification: 'IN_SLOT' }] },
        message_ru: 'Машина задерживается на 10 мин. Клиентское окно не нарушено.',
        recommended_action_ru: 'Контролируйте ETA; клиентские окна пока не нарушены.',
        requires_action: false,
        status: 'COMPLETED',
      })],
    }));
    renderPanel();

    const queue = await screen.findByRole('region', { name: 'Очередь действий' });
    expect(within(queue).getByText('Действий не требуется')).toBeVisible();
    expect(screen.queryByRole('region', { name: 'Системный журнал' })).not.toBeInTheDocument();
    const delayNotification = useUiStore.getState().notifications.find((item) => (
      item.title === 'Логистика · Задержка машины'
    ));
    expect(delayNotification).toMatchObject({ visible: false, read: true });
    expect(delayNotification?.detail).toContain('Машина задерживается на 10 мин. Клиентское окно не нарушено.');
    expect(screen.queryByLabelText('Резервные внутренние задания')).not.toBeInTheDocument();
  });

  it('records a delay-contact outcome with the shared recovery proposal fence', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      events: [eventFixture({ event_type: 'VEHICLE_DELAY' })],
      notices: [noticeFixture({ notice_type: 'DELAY_CONTACT_REQUIRED' })],
      actions: [actionFixture({
        action_type: 'CONTACT_CUSTOMER_DELAY',
        recommended_date: null,
        alternative_dates: [],
      })],
      proposals: [proposalFixture({
        action_id: null,
        proposal_type: 'PARTIAL_REPLAN_DELAY',
        status: 'PROPOSED',
        version: 7,
      })],
      pending_action_count: 1,
    }));
    const user = userEvent.setup();
    renderPanel();

    const action = await screen.findByTestId('operations-action-action-1');
    await user.click(within(action).getByLabelText('Клиент не согласен на опоздание'));
    await user.click(within(action).getByRole('button', { name: 'Сохранить решение' }));

    await waitFor(() => expect(apiMocks.decideLogisticsAction).toHaveBeenCalledOnce());
    expect(apiMocks.decideLogisticsAction.mock.calls[0]?.[1]).toEqual({
      expected_version: 2,
      expected_proposal_version: 7,
      decision_type: 'REJECT_DELAY',
      comment: null,
    });
  });

  it.each([
    ['REQUIRES_ACTION', false, 'Требует действия'],
    ['ACCEPTED', true, 'Принято'],
    ['REJECTED', true, 'Отклонено'],
    ['COMPLETED', true, 'Выполнено'],
    ['OBSOLETE', true, 'Больше не актуально'],
  ] as const)('keeps persisted notice lifecycle %s in notification history', async (status, requiresAction, label) => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      notices: [noticeFixture({ status, requires_action: requiresAction })],
    }));
    renderPanel();

    await screen.findByRole('heading', { name: /Операции на 30 августа 2026/u });
    await waitFor(() => expect(useUiStore.getState().notifications[0]?.detail).toContain(`Статус: ${label}.`));
  });

  it('shows typed low-priority repair candidates under the notice and proposal without claim controls', async () => {
    const baseTaskGroups = [{
      warehouse_id: 'warehouse-1',
      external_warehouse_id: 'external-warehouse-1',
      warehouse_name: 'База Север',
      tasks: [
        {
          taskId: 'internal-task-1',
          externalTaskId: 'external-task-1',
          kind: 'DELIVER_TO_REPAIR',
          unitNumber: '61',
          summary: 'Отвезти бытовку в ремонтный цех',
          scheduledDate: DAY,
          priority: 1,
          state: 'SCHEDULED',
        },
        {
          taskId: 'internal-task-2',
          externalTaskId: 'external-task-2',
          kind: 'REMOVE_FROM_REPAIR',
          unitNumber: '62',
          summary: 'Забрать бытовку после ремонта',
          scheduledDate: DAY,
          priority: 1,
          state: 'SCHEDULED',
        },
      ],
    }];
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      events: [eventFixture({ event_type: 'DELIVERY_CANCELLED' })],
      notices: [noticeFixture({
        facts: { base_task_groups: baseTaskGroups },
        requires_action: false,
        status: 'COMPLETED',
      })],
      proposals: [proposalFixture({
        proposal_type: 'PARTIAL_REPLAN_CANCELLATION',
        status: 'READY_TO_APPLY',
        changes: { base_task_groups: baseTaskGroups },
      })],
    }));
    renderPanel();

    await waitFor(() => expect(useUiStore.getState().notifications.some((item) => (
      /База Север: бытовка №61 · Доставить в ремонт.*База Север: бытовка №62 · Забрать из ремонта/u.test(item.detail ?? '')
    ))).toBe(true));
    const proposal = screen.getByTestId('operations-proposal-proposal-1');
    expect(within(proposal).getByLabelText('Резервные внутренние задания')).toHaveTextContent('они не назначены автоматически');
    expect(screen.queryByText('internal-task-1')).not.toBeInTheDocument();
    expect(screen.queryByText('external-task-1')).not.toBeInTheDocument();
  });

  it('shows canonical customer type and resolves notice and proposal links to a readable route', async () => {
    const task = { ...requestFixture().tasks![0]!, id: 'task-readable', request_id: 'request-readable' };
    const request = Object.assign(requestFixture({
      id: 'request-readable',
      name: 'Заявка маршрута',
      tasks: [task],
    }), { client_type: 'LEGAL_ENTITY' as const });
    const plan = planFixture({
      driver_routes: [{
        driver_shift_id: 'shift-readable',
        shift_start_at: '2026-08-30T08:00:00+03:00',
        shift_end_at: '2026-08-30T20:00:00+03:00',
        driver_id: 'driver-readable',
        driver_name: 'Иван Петров',
        vehicle_id: 'vehicle-readable',
        vehicle_name: 'МАЗ 5551',
        registration_number: 'А123БВ',
        metrics: planFixture().metrics,
        cycles: [{
          id: 'cycle-readable',
          route_plan_id: 'plan-1',
          driver_shift_id: 'shift-readable',
          sequence: 3,
          planned_start: '2026-08-30T08:00:00+03:00',
          planned_finish: '2026-08-30T10:00:00+03:00',
          total_distance_meters: 10_000,
          total_travel_seconds: 1_800,
          total_service_seconds: 900,
          empty_distance_meters: 1_000,
          detour_seconds: 0,
          score: 10,
          locked: false,
          stops: [{
            id: 'stop-readable',
            route_cycle_id: 'cycle-readable',
            sequence: 1,
            task_id: task.id,
            stop_type: 'DELIVERY',
            planned_arrival: '2026-08-30T09:00:00+03:00',
            planned_departure: '2026-08-30T09:15:00+03:00',
            service_seconds: 900,
            quantity_delta: -2,
            load_before: 2,
            load_after: 0,
            latitude: request.latitude,
            longitude: request.longitude,
            label: request.name,
          }],
          legs: [],
          explanation: [],
          warnings: [],
        }],
      }],
    });
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      pending_action_count: 1,
      events: [eventFixture({ request_id: request.id, task_id: task.id, cycle_id: 'cycle-readable' })],
      notices: [noticeFixture({ request_id: request.id, task_id: task.id, cycle_id: 'cycle-readable' })],
      actions: [actionFixture({ request_id: request.id, customer_type: 'INDIVIDUAL' })],
      proposals: [proposalFixture({
        affected_request_ids: [request.id],
        affected_task_ids: [task.id],
        changes: { target: { cycle_id: 'cycle-readable' } },
      })],
    }));
    renderPanel({ workspace: workspaceFixture({ planning_date: DAY, requests: [request] }), plan });

    const action = await screen.findByTestId('operations-action-action-1');
    expect(within(action).getByText('Юридическое лицо')).toBeVisible();
    expect(action.querySelector('a[href^="tel:"]')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Системный журнал' })).not.toBeInTheDocument();
    const proposal = screen.getByTestId('operations-proposal-proposal-1');
    expect(within(proposal).getByRole('button', { name: /Заявка маршрута · Юридическое лицо/u })).toBeVisible();
    expect(within(proposal).getAllByText(/Маршрут:/u)[0]).toHaveTextContent('Иван Петров · МАЗ 5551 · А123БВ · рейс 3');
    expect(screen.queryByText('cycle-readable')).not.toBeInTheDocument();
  });
});

describe('operational event capture', () => {
  it.each([
    ['VEHICLE_BREAKDOWN', 'Поломка машины', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Машина'), 'vehicle-1'), { vehicle_id: 'vehicle-1' }, 'PICKUP'],
    ['VEHICLE_DELAY', 'Задержка машины', async (user: ReturnType<typeof userEvent.setup>) => {
      await user.selectOptions(screen.getByLabelText('Машина'), 'vehicle-1');
      await user.clear(screen.getByLabelText('Задержка, минут'));
      await user.type(screen.getByLabelText('Задержка, минут'), '45');
    }, { vehicle_id: 'vehicle-1', delay_minutes: 45 }, 'PICKUP'],
    ['DRIVER_UNAVAILABLE', 'Водитель недоступен', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Смена водителя'), 'shift-1'), { driver_shift_id: 'shift-1' }, 'PICKUP'],
    ['DELIVERY_CANCELLED', 'Доставка отменена', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Отменённая заявка'), 'request-1'), { request_id: 'request-1' }, 'DELIVERY'],
    ['PICKUP_CANCELLED', 'Вывоз отменён', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Отменённая заявка'), 'request-1'), { request_id: 'request-1' }, 'PICKUP'],
    ['ORDER_CANCELLED', 'Заказ отменён', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Отменённая заявка'), 'request-1'), { request_id: 'request-1' }, 'PICKUP'],
    ['TASK_BLOCKED', 'Задание заблокировано', async (user: ReturnType<typeof userEvent.setup>) => user.selectOptions(screen.getByLabelText('Заблокированное задание'), 'task-1'), { task_id: 'task-1' }, 'PICKUP'],
  ] as const)('submits %s as a fenced server fact', async (eventType, label, fillSpecific, expectedSpecific, requestType) => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture());
    const user = userEvent.setup();
    renderPanel({
      plan: planFixture({ date: DAY, version: 11 }),
      workspace: workspaceFixture({
        planning_date: DAY,
        requests: [requestFixture({ name: 'Заявка №42', type: requestType, scheduled_date: DAY })],
      }),
    });

    await user.click(await screen.findByRole('button', { name: 'Зафиксировать событие' }));
    await user.selectOptions(screen.getByLabelText('Событие'), eventType);
    expect(screen.getByLabelText('Событие')).toHaveDisplayValue(label);
    await fillSpecific(user);
    fireEvent.change(screen.getByLabelText('Время события (Europe/Moscow)'), { target: { value: '14:20' } });
    await user.type(screen.getByLabelText('Причина'), 'Подтверждено диспетчером');
    await user.click(screen.getByRole('button', { name: 'Зафиксировать и оценить влияние' }));

    await waitFor(() => expect(apiMocks.createLogisticsEvent).toHaveBeenCalledOnce());
    const input = apiMocks.createLogisticsEvent.mock.calls[0]?.[2] as LogisticsEventInput;
    expect(apiMocks.createLogisticsEvent.mock.calls[0]?.slice(0, 2)).toEqual(['warehouse-1', DAY]);
    expect(input).toMatchObject({
      event_type: eventType,
      plan_id: 'plan-1',
      expected_plan_version: 11,
      reason: 'Подтверждено диспетчером',
      ...expectedSpecific,
    });
    expect(input.occurred_at).toBe('2026-08-30T11:20:00.000Z');
    expect(input.effective_at).toBe(input.occurred_at);
    expect(apiMocks.createLogisticsEvent.mock.calls[0]?.[3]).toEqual(expect.any(String));
  });

  it('keeps service-owned plan-change events out of the dispatcher event picker', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture());
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Зафиксировать событие' }));
    const eventSelect = screen.getByLabelText('Событие');
    expect(eventSelect.querySelector('option[value="MANUAL_PLAN_CHANGE"]')).toBeNull();
    expect(eventSelect.querySelector('option[value="PLANNING_MODE_CHANGED"]')).toBeNull();
  });

  it('submits a group-member cancellation against the root day and keeps its service warehouse visible', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({ warehouse_id: 'warehouse-root' }));
    const user = userEvent.setup();
    renderPanel({
      workspace: representativeGroupWorkspace(),
      plan: planFixture({
        id: 'plan-root',
        warehouse_id: 'warehouse-root',
        date: DAY,
        version: 14,
      }),
    });

    await user.click(await screen.findByRole('button', { name: 'Зафиксировать событие' }));
    expect(within(screen.getByRole('dialog')).getByText(/Выбранный представитель/)).toBeVisible();
    await user.selectOptions(screen.getByLabelText('Событие'), 'PICKUP_CANCELLED');
    const requestSelect = screen.getByLabelText('Отменённая заявка');
    await user.selectOptions(requestSelect, 'request-group');
    expect(requestSelect).toHaveDisplayValue(/Вывоз группы №17 · Сервисный склад Выборг/u);
    fireEvent.change(screen.getByLabelText('Время события (Europe/Moscow)'), { target: { value: '15:05' } });
    await user.type(screen.getByLabelText('Причина'), 'Клиент отменил вывоз');
    await user.click(screen.getByRole('button', { name: 'Зафиксировать и оценить влияние' }));

    await waitFor(() => expect(apiMocks.createLogisticsEvent).toHaveBeenCalledOnce());
    expect(apiMocks.createLogisticsEvent.mock.calls[0]?.slice(0, 3)).toEqual([
      'warehouse-root',
      DAY,
      expect.objectContaining({
        event_type: 'PICKUP_CANCELLED',
        request_id: 'request-group',
        plan_id: 'plan-root',
        expected_plan_version: 14,
      }),
    ]);
  });

  it('submits an active group-member task against the root plan and hides terminal tasks', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({ warehouse_id: 'warehouse-root' }));
    const user = userEvent.setup();
    renderPanel({
      workspace: representativeGroupWorkspace(),
      plan: planFixture({
        id: 'plan-root',
        warehouse_id: 'warehouse-root',
        date: DAY,
        version: 15,
      }),
    });

    await user.click(await screen.findByRole('button', { name: 'Зафиксировать событие' }));
    await user.selectOptions(screen.getByLabelText('Событие'), 'TASK_BLOCKED');
    const taskSelect = screen.getByLabelText('Заблокированное задание');
    expect(within(taskSelect).getByRole('option', { name: /Вывоз группы №17 · часть 1.*Сервисный склад Выборг/u })).toBeVisible();
    expect(within(taskSelect).queryByRole('option', { name: /часть 2/u })).not.toBeInTheDocument();
    expect(within(taskSelect).queryByRole('option', { name: /часть 3/u })).not.toBeInTheDocument();
    await user.selectOptions(taskSelect, 'task-group');
    fireEvent.change(screen.getByLabelText('Время события (Europe/Moscow)'), { target: { value: '16:40' } });
    await user.type(screen.getByLabelText('Причина'), 'Адрес временно недоступен');
    await user.click(screen.getByRole('button', { name: 'Зафиксировать и оценить влияние' }));

    await waitFor(() => expect(apiMocks.createLogisticsEvent).toHaveBeenCalledOnce());
    expect(apiMocks.createLogisticsEvent.mock.calls[0]?.slice(0, 3)).toEqual([
      'warehouse-root',
      DAY,
      expect.objectContaining({
        event_type: 'TASK_BLOCKED',
        task_id: 'task-group',
        plan_id: 'plan-root',
        expected_plan_version: 15,
      }),
    ]);
  });
});

describe('recovery proposal application', () => {
  it('keeps customer agreement separate and applies only after explicit confirmation', async () => {
    const agreed = proposalFixture({ status: 'CUSTOMER_AGREED', version: 5 });
    const initial = operationsFixture({ proposals: [agreed], decisions: [decisionFixture()] });
    const applied = proposalFixture({ status: 'APPLIED', version: 7, result_plan_id: 'plan-2', applied_at: '2026-08-30T09:00:00Z' });
    apiMocks.getPlanningDayOperations.mockResolvedValueOnce(initial).mockResolvedValue(operationsFixture({
      proposals: [applied],
      decisions: [decisionFixture()],
    }));
    apiMocks.applyRecoveryProposal.mockResolvedValue(applied);
    const user = userEvent.setup();
    renderPanel();

    const card = await screen.findByTestId('operations-proposal-proposal-1');
    expect(within(card).getByText('Предложение рассчитано')).toBeVisible();
    expect(within(card).getByText('Согласовано с клиентом')).toBeVisible();
    expect(within(card).getByText('Изменение не применено')).toBeVisible();
    await user.click(within(card).getByRole('button', { name: 'Проверить и применить' }));
    expect(apiMocks.applyRecoveryProposal).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: 'Применить предложение?' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Да, применить предложение' }));

    await waitFor(() => expect(apiMocks.applyRecoveryProposal).toHaveBeenCalledOnce());
    expect(apiMocks.applyRecoveryProposal.mock.calls[0]?.slice(0, 2)).toEqual(['proposal-1', 5]);
    expect(apiMocks.applyRecoveryProposal.mock.calls[0]?.[2]).toEqual(expect.any(String));
    expect(await screen.findByText('Изменение применено')).toBeVisible();
  });

  it('keeps immutable customer agreement visible when applying the proposal failed', async () => {
    apiMocks.getPlanningDayOperations.mockResolvedValue(operationsFixture({
      proposals: [proposalFixture({ status: 'FAILED', version: 7, failure_code: 'PLAN_VERSION_CONFLICT' })],
      decisions: [decisionFixture()],
    }));
    renderPanel();

    const card = await screen.findByTestId('operations-proposal-proposal-1');
    expect(within(card).getByText('Согласовано с клиентом')).toBeVisible();
    expect(within(card).getByText('Применение завершилось ошибкой')).toBeVisible();
    expect(within(card).getByRole('button', { name: 'Повторить применение' })).toBeEnabled();
  });
});
