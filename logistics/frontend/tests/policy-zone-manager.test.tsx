import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { WarehousePolicyZone } from '../src/domain/types';
import { SettingsEditor } from '../src/features/settings/SettingsEditor';
import { warehouseFixture } from './fixtures';

const geometry = {
  type: 'MultiPolygon' as const,
  coordinates: [[[[30.3, 59.9], [30.4, 59.9], [30.4, 60], [30.3, 59.9]]]],
};

vi.mock('../src/map/PolicyZoneMapEditor', () => ({
  PolicyZoneMapEditor: ({ onGeometryChange }: {
    onGeometryChange: (value: typeof geometry) => void;
  }) => (
    <button type="button" onClick={() => onGeometryChange(geometry)}>
      Нарисовать тестовый контур
    </button>
  ),
}));

function zoneFixture(overrides: Partial<WarehousePolicyZone> = {}): WarehousePolicyZone {
  return {
    id: '22222222-2222-4222-8222-222222222222',
    warehouse_id: 'warehouse-1',
    name: 'Закрытый квартал',
    kind: 'FORBIDDEN',
    color: '#EF4444',
    geometry,
    version: 1,
    delivery_price_rubles: null,
    pickup_price_rubles: null,
    created_at: '2026-08-31T10:00:00Z',
    updated_at: '2026-08-31T10:00:00Z',
    ...overrides,
  };
}

describe('settings policy-zone manager', () => {
  it('loads lazily and creates only an exceptional restriction with server geometry', async () => {
    const user = userEvent.setup();
    const saved = zoneFixture();
    const list = vi.spyOn(api, 'listPolicyZones')
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([saved]);
    const create = vi.spyOn(api, 'createPolicyZone').mockResolvedValue(saved);
    render(
      <SettingsEditor
        warehouse={warehouseFixture()}
        busy={false}
        onSave={() => Promise.resolve()}
      />,
    );

    expect(list).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Управлять исключениями' }));
    expect(await screen.findByText('Обычная доставка остаётся изохронной')).toBeVisible();
    expect(screen.getByText(/последняя изохрона — жёсткий предел дальности/iu)).toBeVisible();
    await waitFor(() => expect(list).toHaveBeenCalledWith('warehouse-1'));

    await user.type(screen.getByLabelText('Название'), 'Закрытый квартал');
    await user.selectOptions(screen.getByLabelText('Правило'), 'FORBIDDEN');
    await user.click(screen.getByRole('button', { name: 'Нарисовать тестовый контур' }));
    await user.click(screen.getByRole('button', { name: 'Создать исключение' }));

    await waitFor(() => expect(create).toHaveBeenCalledOnce());
    expect(create.mock.calls[0]?.[0]).toBe('warehouse-1');
    expect(create.mock.calls[0]?.[1]).toEqual({
      name: 'Закрытый квартал',
      kind: 'FORBIDDEN',
      color: '#EF4444',
      geometry,
      delivery_price_rubles: null,
      pickup_price_rubles: null,
    });
    expect(create.mock.calls[0]?.[2]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu,
    );
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    expect(screen.getByText('v1')).toBeVisible();
  });

  it('submits both prices and the exact optimistic fence for an existing special price', async () => {
    const user = userEvent.setup();
    const original = zoneFixture({
      id: '33333333-3333-4333-8333-333333333333',
      name: 'Удалённый район',
      kind: 'SPECIAL_PRICE',
      color: '#3B82F6',
      delivery_price_rubles: 20_000,
      pickup_price_rubles: 12_000,
      version: 7,
    });
    const updated = { ...original, version: 8, delivery_price_rubles: 21_000 };
    vi.spyOn(api, 'listPolicyZones')
      .mockResolvedValueOnce([original])
      .mockResolvedValueOnce([updated]);
    const update = vi.spyOn(api, 'updatePolicyZone').mockResolvedValue(updated);
    render(
      <SettingsEditor
        warehouse={warehouseFixture()}
        busy={false}
        onSave={() => Promise.resolve()}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Управлять исключениями' }));
    await user.click(await screen.findByRole('button', { name: /Удалённый район/iu }));
    await user.clear(screen.getByLabelText('Доставка, ₽'));
    await user.type(screen.getByLabelText('Доставка, ₽'), '21000');
    await user.click(screen.getByRole('button', { name: 'Сохранить изменения' }));

    await waitFor(() => expect(update).toHaveBeenCalledOnce());
    expect(update).toHaveBeenCalledWith(
      'warehouse-1',
      original.id,
      expect.objectContaining({
        kind: 'SPECIAL_PRICE',
        delivery_price_rubles: 21_000,
        pickup_price_rubles: 12_000,
        geometry,
      }),
      7,
    );
    expect(await screen.findByText('v8')).toBeVisible();
  });
});
