import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DatePicker, DateRangePicker } from '../src/components/DatePicker';
import { Modal } from '../src/components/ui';

describe('calendar interaction', () => {
  it('closes only the calendar on Escape and returns focus inside the parent dialog', async () => {
    const user = userEvent.setup();
    const closeDialog = vi.fn();
    render(<Modal title="Условия рейса" onClose={closeDialog}>
      <DatePicker label="Дата рейса" value="2026-09-12" onChange={vi.fn()} />
    </Modal>);
    const trigger = screen.getByRole('button', { name: 'Дата рейса' });
    await user.click(trigger);
    expect(screen.getByRole('button', { name: 'Следующий месяц' })).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Календарь: Дата рейса' })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(closeDialog).not.toHaveBeenCalled();
  });

  it('preserves closed dates and commits an available ISO day', async () => {
    const user = userEvent.setup();
    const change = vi.fn();
    render(<DatePicker label="Дата рейса" value="2026-09-12" disabledDates={{ before: new Date(2026, 8, 12) }} onChange={change} />);
    const trigger = screen.getByRole('button', { name: 'Дата рейса' });
    await user.click(trigger);
    const closed = screen.getByRole('button', { name: /11 сентября 2026/ });
    expect(closed).toBeDisabled();
    await user.click(closed);
    expect(change).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: /15 сентября 2026/ }));
    expect(change).toHaveBeenCalledWith('2026-09-15');
    expect(trigger).toHaveFocus();
  });

  it('discards an unfinished range when dismissed outside', async () => {
    const user = userEvent.setup();
    const change = vi.fn();
    render(<><DateRangePicker label="Период смены" from="2026-09-12" to="2026-09-15" onChange={change} /><button>Вне календаря</button></>);
    const trigger = screen.getByRole('button', { name: 'Период смены' });
    await user.click(trigger);
    await user.click(screen.getByRole('button', { name: /20 сентября 2026/ }));
    expect(screen.getByRole('button', { name: 'Готово' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Вне календаря' }));
    expect(change).not.toHaveBeenCalled();
    await user.click(trigger);
    expect(screen.getByRole('button', { name: 'Готово' })).toBeEnabled();
  });
});
