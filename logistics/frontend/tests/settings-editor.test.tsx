import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';
import { SettingsEditor } from '../src/features/settings/SettingsEditor';

describe('planner resource settings', () => {
  it('shows and saves fleet activation and driver workload controls', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(() => Promise.resolve());
    render(
      <SettingsEditor
        settings={DEFAULT_PLANNING_SETTINGS}
        busy={false}
        onSave={onSave}
      />,
    );

    const input = screen.getByLabelText('Штраф дополнительной машины/водителя');
    expect(input).toHaveValue(180);
    expect(screen.getByLabelText('Целевая загрузка смены, %')).toHaveValue(80);
    expect(screen.getByLabelText('Штраф нагрузки сверх цели')).toHaveValue(3);
    expect(screen.getByLabelText('Порог большого крюка, мин')).toHaveValue(35);
    expect(screen.getByLabelText('Порог доли крюка')).toHaveValue(1.5);
    expect(screen.getByLabelText('Максимум ожидания между клиентами, мин')).toHaveValue(120);
    expect(screen.getByLabelText('Максимум ожидания между клиентами, мин')).toHaveAttribute('min', '0');
    expect(screen.getByLabelText('В каждом цикле доставки раньше вывозов')).toBeChecked();
    expect(screen.getByLabelText('В каждом цикле доставки раньше вывозов')).toBeDisabled();
    expect(screen.getByLabelText('Доставок в цикле')).toHaveAttribute('min', '1');
    expect(screen.getByLabelText('Доставок в цикле')).toHaveAttribute('max', '2');
    expect(screen.getByLabelText('Вывозов в цикле')).toHaveAttribute('min', '1');
    expect(screen.getByLabelText('Вывозов в цикле')).toHaveAttribute('max', '2');

    await user.clear(input);
    await user.type(input, '240');
    const utilization = screen.getByLabelText('Целевая загрузка смены, %');
    await user.clear(utilization);
    await user.type(utilization, '75');
    await user.click(screen.getByRole('button', { name: 'Сохранить настройки' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      additional_resource_activation_penalty: 240,
      preferred_shift_utilization_percent: 75,
      driver_workload_weight: 3,
      max_customer_wait_minutes: 120,
    })));
  });
});
