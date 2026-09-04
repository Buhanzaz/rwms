import { describe, expect, it } from 'vitest';
import {
  warehouseAttachedName,
  warehouseDisplayName,
  warehouseLocation,
} from '../src/domain/warehouse-presentation';
import { warehouseFixture } from './fixtures';

describe('warehouse presentation', () => {
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
