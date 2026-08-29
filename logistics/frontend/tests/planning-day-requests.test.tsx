import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PlanningDayRequests } from '../src/features/planning/PlanningDayRequests';
import { requestFixture, workspaceFixture } from './fixtures';

describe('planning-day request preparation', () => {
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
      workspace={workspaceFixture({ requests: [requestFixture({ name: 'Доставка 2026-08-29 №7' })] })}
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
