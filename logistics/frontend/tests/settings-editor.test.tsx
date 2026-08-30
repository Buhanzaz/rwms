import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { WarehouseUpdateInput } from '../src/api/client';
import { SettingsEditor } from '../src/features/settings/SettingsEditor';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture } from './fixtures';

describe('planner resource settings', () => {
  it('saves only backend-supported planning settings after a single-field change', async () => {
    const user = userEvent.setup();
    const warehouse = warehouseFixture();
    const onSave = vi.fn<(input: WarehouseUpdateInput) => Promise<void>>().mockResolvedValue(undefined);
    render(<SettingsEditor warehouse={warehouse} busy={false} onSave={onSave} />);

    expect(screen.queryByLabelText('Показывать процесс поиска маршрута')).not.toBeInTheDocument();
    const citySpeed = screen.getByLabelText('Скорость в городе, км/ч');
    await user.clear(citySpeed);
    await user.type(citySpeed, '42');
    await user.click(screen.getByRole('button', { name: 'Сохранить настройки' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    const savedSettings = onSave.mock.calls[0]?.[0].settings;
    expect(savedSettings).toEqual({ ...warehouse.settings, city_speed_kmh: 42 });
    expect(savedSettings).not.toHaveProperty('trace_enabled');
  });

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
    expect(screen.getByRole('button', { name: 'Настройки изохронов' })).toBeVisible();
    expect(screen.getByText('Текущая конфигурация: 2 бытовки')).toBeVisible();
    expect(screen.queryByText(/backend/iu)).not.toBeInTheDocument();

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
    await user.click(screen.getByRole('button', { name: 'Сохранить настройки' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    expect(onSave.mock.calls[0]?.[0]).toMatchObject({
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

  it('saves a dynamic contiguous tariff ladder and exposes the delivery limit', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn<(input: WarehouseUpdateInput) => Promise<void>>().mockResolvedValue(undefined);
    render(<SettingsEditor warehouse={warehouseFixture()} busy={false} onSave={onSave} />);

    await user.click(screen.getByRole('button', { name: 'Настройки изохронов' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Заказы дальше 4 ч от склада недоступны');
    await user.click(screen.getByRole('button', { name: 'Добавить 5-й час' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Заказы дальше 5 ч от склада недоступны');
    const prices = screen.getAllByLabelText('Цена, ₽');
    await user.clear(prices[4]!);
    await user.type(prices[4]!, '30000');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ isochrone_tariffs: [
      { travel_minutes: 60, price_rubles: 10_000 },
      { travel_minutes: 120, price_rubles: 15_000 },
      { travel_minutes: 180, price_rubles: 20_000 },
      { travel_minutes: 240, price_rubles: 25_000 },
      { travel_minutes: 300, price_rubles: 30_000 },
    ] }));
  });
});
