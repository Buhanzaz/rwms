import { describe, expect, it } from 'vitest';
import {
  warehouseAttachedName,
  warehouseDisplayName,
  warehouseLocation,
  warehouseMapKind,
} from '../src/domain/warehouse-presentation';
import { warehouseFixture } from './fixtures';

describe('warehouse presentation', () => {
  it.each([
    [true, false, false, 'ПС'],
    [false, false, true, 'С'],
    [false, true, false, 'П'],
    [false, true, true, 'Ц'],
  ] as const)('labels canonical flags %s/%s/%s as %s', (representative, production, mainWarehouse, glyph) => {
    const warehouse = warehouseFixture({ representative });
    expect(warehouseMapKind(warehouse, { id: warehouse.external_warehouse_id, representative, production, mainWarehouse })).toMatchObject({ glyph, known: true });
  });

  it('does not infer a type from a name, unmatched identity or inconsistent projection', () => {
    const warehouse = warehouseFixture({ name: 'Центральный склад и производство' });
    expect(warehouseMapKind(warehouse).known).toBe(false);
    expect(warehouseMapKind(warehouse, { id: 'different', representative: false, production: true, mainWarehouse: true }).known).toBe(false);
    expect(warehouseMapKind(warehouse, { id: warehouse.external_warehouse_id, representative: true, production: false, mainWarehouse: false }).known).toBe(false);
    expect(warehouseMapKind(warehouse, { id: warehouse.external_warehouse_id, representative: false, production: false, mainWarehouse: false }).known).toBe(false);
    expect(warehouseMapKind(warehouseFixture({ representative: true }), { id: warehouse.external_warehouse_id, representative: true, production: true, mainWarehouse: true }).known).toBe(false);
    expect(warehouseMapKind(warehouse, { id: warehouse.external_warehouse_id, representative: false, production: false, mainWarehouse: false }).glyph).toBe('?');
  });
  it('uses one concise format for a main warehouse', () => {
    const warehouse = warehouseFixture({ name: 'Склад СПБ', city: 'СПБ' });

    expect(warehouseDisplayName(warehouse)).toBe('Склад СПБ');
    expect(warehouseAttachedName(warehouse)).toBe('Складу СПБ');
    expect(warehouseLocation(warehouse)).toBe('СПБ — Санкт-Петербург, Шоссе Революции, 1 — Europe/Moscow');
  });

  it('shows a representative warehouse together with its main warehouse', () => {
    const main = warehouseFixture({ id: 'main', name: 'Склад СПБ', city: 'СПБ' });
    const representative = warehouseFixture({
      id: 'rep',
      name: 'Склад В. Новгород',
      city: 'В. Новгород',
      representative: true,
    });

    expect(warehouseDisplayName(representative, main)).toBe('Представительский склад В. Новгород / СПБ');
    expect(warehouseAttachedName(representative, main)).toBe('Представительскому складу В. Новгород / СПБ');
  });
});
