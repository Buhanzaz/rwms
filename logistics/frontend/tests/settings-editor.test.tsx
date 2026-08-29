import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { WarehouseUpdateInput } from '../src/api/client';
import { SettingsEditor } from '../src/features/settings/SettingsEditor';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture } from './fixtures';

describe('planner resource settings', () => {
  it('shows and saves fleet activation and driver workload controls', async () => {
    useUiStore.setState({ notificationDurationSeconds: 8 });
    const user = userEvent.setup();
    const onSave = vi.fn<(input: WarehouseUpdateInput) => Promise<void>>();
    onSave.mockResolvedValue(undefined);
    render(
      <SettingsEditor
        warehouse={warehouseFixture()}
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
    expect(screen.getByLabelText('Стандартная длина бытовки, мм')).toHaveValue(6000);
    expect(screen.getByLabelText('Стандартная ширина бытовки, мм')).toHaveValue(2400);
    expect(screen.getByLabelText('Стандартная высота бытовки, мм')).toHaveValue(2400);
    expect(screen.getByLabelText('Стандартная масса бытовки, кг')).toHaveValue(1200);
    expect(screen.getByLabelText('В каждом цикле доставки раньше вывозов')).toBeChecked();
    expect(screen.getByLabelText('В каждом цикле доставки раньше вывозов')).toBeDisabled();
    expect(screen.getByLabelText('Доставок в цикле')).toHaveAttribute('min', '1');
    expect(screen.getByLabelText('Доставок в цикле')).toHaveAttribute('max', '2');
    expect(screen.getByLabelText('Вывозов в цикле')).toHaveAttribute('min', '1');
    expect(screen.getByLabelText('Вывозов в цикле')).toHaveAttribute('max', '2');
    expect(screen.getByLabelText('Показывать уведомление, секунд')).toHaveValue(8);
    expect(screen.getByRole('switch', { name: 'Разрешить переработку' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByLabelText('Максимальная переработка, ч')).toBeDisabled();
    expect(screen.getByLabelText('До 1 часа, ₽')).toHaveValue(10_000);
    expect(screen.getByLabelText('До 4 часов, ₽')).toHaveValue(25_000);

    await user.clear(input);
    await user.type(input, '240');
    const utilization = screen.getByLabelText('Целевая загрузка смены, %');
    await user.clear(utilization);
    await user.type(utilization, '75');
    const notificationDuration = screen.getByLabelText('Показывать уведомление, секунд');
    fireEvent.change(notificationDuration, { target: { value: '12' } });
    expect(useUiStore.getState().notificationDurationSeconds).toBe(12);
    await user.click(screen.getByRole('switch', { name: 'Разрешить переработку' }));
    const overtime = screen.getByLabelText('Максимальная переработка, ч');
    expect(overtime).toBeEnabled();
    await user.clear(overtime);
    await user.type(overtime, '2.5');
    const oneHourPrice = screen.getByLabelText('До 1 часа, ₽');
    await user.clear(oneHourPrice);
    await user.type(oneHourPrice, '11000');
    await user.click(screen.getByRole('button', { name: 'Сохранить настройки' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    expect(onSave.mock.calls[0]?.[0]).toMatchObject({
      isochrone_price_60_minutes: 11_000,
      isochrone_price_120_minutes: 15_000,
      isochrone_price_180_minutes: 20_000,
      isochrone_price_240_minutes: 25_000,
      settings: {
        additional_resource_activation_penalty: 240,
        preferred_shift_utilization_percent: 75,
        driver_workload_weight: 3,
        max_customer_wait_minutes: 120,
        default_cargo_length_mm: 6000,
        default_cargo_width_mm: 2400,
        default_cargo_height_mm: 2400,
        default_cargo_weight_kg: 1200,
        allow_soft_overtime: true,
        soft_overtime_limit_minutes: 150,
      },
    });
  });
});
