import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PlanningDayRequests } from '../src/features/planning/PlanningDayRequests';
import { requestFixture, workspaceFixture } from './fixtures';

describe('planning-day request preparation', () => {
  it('shows rental purpose, contact, agreed time and lifecycle separately from readiness', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const request = requestFixture({
      name: 'Заказ RENTAL-7',
      customer_delivery_purpose: 'RENTAL_DELIVERY',
      status: 'IN_PROGRESS',
      contact_name: 'Анна',
      contact_phone: '+7 (900) 123-45-67',
      notes: 'Позвонить перед въездом',
      date_options: [{ date: '2026-08-30', priority: 1, window_start: '09:00', window_end: '12:00', is_hard: true }],
    });
    render(<PlanningDayRequests workspace={workspaceFixture({ requests: [request] })} planningDate="2026-08-30" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={onSelect} />);

    const card = within(screen.getByTestId(`planning-request-${request.id}`));
    expect(card.getByText('Доставка в аренду')).toBeVisible();
    expect(card.getByText('В работе')).toBeVisible();
    expect(card.queryByText('IN_PROGRESS')).not.toBeInTheDocument();
    expect(card.getByText('09:00–12:00 · строго по времени')).toBeVisible();
    expect(card.getByText('Анна')).toBeVisible();
    expect(card.getByRole('link', { name: '+7 (900) 123-45-67' })).toHaveAttribute('href', 'tel:+79001234567');
    expect(card.getByText('Позвонить перед въездом')).toBeVisible();
    await user.click(card.getByLabelText('Контактное лицо'));
    expect(onSelect).not.toHaveBeenCalled();
    card.getByRole('button', { name: 'Открыть заявку №RENTAL-7 на карте' }).focus();
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(request.id);
  });

  it('navigates adjacent dates through the shared planning-date handler', async () => {
    const user = userEvent.setup();
    const onPlanningDateChange = vi.fn();
    render(<PlanningDayRequests workspace={workspaceFixture()} planningDate="2026-08-30" busy={false} onPlanningDateChange={onPlanningDateChange} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    expect(screen.queryByText('Планируемый день')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Предыдущая дата/ }));
    await user.click(screen.getByRole('button', { name: /Следующая дата/ }));
    expect(onPlanningDateChange.mock.calls).toEqual([['2026-08-29'], ['2026-08-31']]);
  });

  it('saves an explicit mandatory delivery together with its operational conditions', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(() => Promise.resolve());
    const request = requestFixture({
      mandatory: false,
      trailer_access_allowed: null,
      scheduled_date: null,
      date_options: [{ date: '2026-08-30', priority: 1, window_start: '09:00', window_end: '12:00', is_hard: false }],
    });

    render(<PlanningDayRequests
      workspace={workspaceFixture({ requests: [request] })}
      planningDate="2026-08-30"
      busy={false}
      onPlanningDateChange={() => undefined}
      onSave={onSave}
      onSplit={() => Promise.resolve()}
      onSelect={() => undefined}
    />);

    expect(screen.getByRole('button', { name: 'Сохранить условия' })).toBeDisabled();
    await user.clear(screen.getByLabelText('Доставка/вывоз с'));
    await user.type(screen.getByLabelText('Доставка/вывоз с'), '12:00');
    await user.clear(screen.getByLabelText('До'));
    await user.type(screen.getByLabelText('До'), '15:00');
    await user.selectOptions(screen.getByLabelText('Машина с прицепом проедет к адресу'), 'true');
    await user.click(screen.getByLabelText('Обязательная доставка'));
    await user.click(screen.getByRole('button', { name: 'Сохранить условия' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    expect(onSave).toHaveBeenCalledWith('request-1', expect.objectContaining({
      date: '2026-08-30',
      window_start: '12:00',
      window_end: '15:00',
      mandatory: true,
      trailer_access_allowed: true,
    }));
  });

  it('keeps a CustomerApp full-day delivery flexible when mandatory is changed', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(() => Promise.resolve());
    const request = requestFixture({
      source_system: 'RWMS',
      mandatory: false,
      trailer_access_allowed: true,
      date_options: [{ date: '2026-08-30', priority: 1, window_start: null, window_end: null, is_hard: false }],
    });

    render(<PlanningDayRequests
      workspace={workspaceFixture({ requests: [request] })}
      planningDate="2026-08-30"
      busy={false}
      onPlanningDateChange={() => undefined}
      onSave={onSave}
      onSplit={() => Promise.resolve()}
      onSelect={() => undefined}
    />);

    expect(screen.getByText('В течение дня')).toBeVisible();
    expect(screen.queryByLabelText('Доставка/вывоз с')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Гибкое окно «В течение дня» · выбрано клиентом')).toBeDisabled();
    await user.click(screen.getByLabelText('Обязательная доставка'));
    await user.click(screen.getByRole('button', { name: 'Сохранить условия' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith('request-1', expect.objectContaining({
      window_start: null,
      window_end: null,
      is_hard: false,
      mandatory: true,
    })));
  });

  it('uses the matching label for a mandatory pickup', () => {
    render(<PlanningDayRequests
      workspace={workspaceFixture({ requests: [requestFixture({ type: 'PICKUP', name: 'Вывоз 1', mandatory: true })] })}
      planningDate="2026-08-30"
      busy={false}
      onPlanningDateChange={() => undefined}
      onSave={() => Promise.resolve()}
      onSplit={() => Promise.resolve()}
      onSelect={() => undefined}
    />);

    expect(screen.getByLabelText('Обязательный вывоз')).toBeChecked();
    expect(screen.getByText('Обязательно')).toBeVisible();
  });

  it('shows one colored operation label and only the delivery number from a generated name', () => {
    render(<PlanningDayRequests
      workspace={workspaceFixture({ requests: [requestFixture({ name: 'Доставка 2026-08-29 №7', tasks: [] })] })}
      planningDate="2026-08-30"
      busy={false}
      onPlanningDateChange={() => undefined}
      onSave={() => Promise.resolve()}
      onSplit={() => Promise.resolve()}
      onSelect={() => undefined}
    />);

    expect(screen.getByText('Доставка')).toHaveClass('badge--accent');
    expect(screen.getByText('· №7')).toBeVisible();
    expect(screen.getByText('Готово')).toBeVisible();
    expect(screen.getByText('Подзадачи: будут созданы при сохранении')).toBeVisible();
    expect(screen.queryByText(/backend/iu)).not.toBeInTheDocument();
    expect(screen.queryByText('Доставка 2026-08-29 №7')).not.toBeInTheDocument();
  });

  it('normalizes a legacy RWMS order label to the same delivery number format', () => {
    render(<PlanningDayRequests
      workspace={workspaceFixture({ requests: [requestFixture({ name: 'Заказ 76E5AF71' })] })}
      planningDate="2026-08-30"
      busy={false}
      onPlanningDateChange={() => undefined}
      onSave={() => Promise.resolve()}
      onSplit={() => Promise.resolve()}
      onSelect={() => undefined}
    />);

    expect(screen.getByText('· №76E5AF71')).toBeVisible();
    expect(screen.queryByText('Заказ 76E5AF71')).not.toBeInTheDocument();
  });

  it('shows the persisted calculated price and does not invent a zero price', () => {
    const workspace = workspaceFixture({ requests: [
      requestFixture({ id: 'priced', name: 'Заказ PRICED', delivery_price_rubles: 28_500 }),
      requestFixture({ id: 'pending', name: 'Заказ PENDING', delivery_price_rubles: null }),
    ] });
    render(<PlanningDayRequests workspace={workspace} planningDate="2026-08-30" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    expect(screen.getByText(/28\s500 ₽/)).toBeVisible();
    expect(screen.getByText('Не рассчитана')).toBeVisible();
    expect(screen.queryByText('0 ₽')).not.toBeInTheDocument();
  });

  it('resets unsaved fields when the operator switches to another planning date', async () => {
    const user = userEvent.setup();
    const request = requestFixture({
      trailer_access_allowed: null,
      scheduled_date: null,
      date_options: [
        { date: '2026-08-30', priority: 1, window_start: '08:00', window_end: '10:00', is_hard: false },
        { date: '2026-08-31', priority: 2, window_start: '13:00', window_end: '16:00', is_hard: false },
      ],
    });
    const workspace = workspaceFixture({ requests: [request] });
    const view = render(<PlanningDayRequests workspace={workspace} planningDate="2026-08-30" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    await user.clear(screen.getByLabelText('Доставка/вывоз с'));
    await user.type(screen.getByLabelText('Доставка/вывоз с'), '09:00');
    await user.clear(screen.getByLabelText('До'));
    await user.type(screen.getByLabelText('До'), '12:00');
    view.rerender(<PlanningDayRequests workspace={workspace} planningDate="2026-08-31" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={() => Promise.resolve()} onSelect={() => undefined} />);

    await waitFor(() => {
      expect(screen.getByLabelText('Доставка/вывоз с')).toHaveValue('13:00');
      expect(screen.getByLabelText('До')).toHaveValue('16:00');
    });
  });

  it('splits a multi-cabin request into explicit one-cabin tasks', async () => {
    const user = userEvent.setup();
    const onSplit = vi.fn(() => Promise.resolve());
    render(<PlanningDayRequests workspace={workspaceFixture({ requests: [requestFixture({ quantity: 3 })] })} planningDate="2026-08-30" busy={false} onPlanningDateChange={() => undefined} onSave={() => Promise.resolve()} onSplit={onSplit} onSelect={() => undefined} />);

    await user.click(screen.getByRole('button', { name: 'Создать части по 1 БК' }));
    expect(onSplit).toHaveBeenCalledWith('request-1', [1, 1, 1]);
  });
});
