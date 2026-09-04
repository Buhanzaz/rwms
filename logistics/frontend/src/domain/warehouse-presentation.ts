import type { Warehouse } from './types';
import type { WarehouseKindMetadata } from '../api/warehouse-directory';

/** Canonical admin flags, joined only to warehouses already admitted to this workspace. */
export function warehouseMapKind(warehouse: Pick<Warehouse, 'external_warehouse_id' | 'representative'>, metadata?: WarehouseKindMetadata) {
  if (!metadata || metadata.id !== warehouse.external_warehouse_id || metadata.representative !== warehouse.representative) {
    return { glyph: '?', label: 'Тип склада не подтверждён', known: false };
  }
  if (metadata.representative) return { glyph: 'ПС', label: 'Представительский склад', known: true };
  if (metadata.production && metadata.mainWarehouse) return { glyph: 'Ц', label: 'Центральный склад и производство', known: true };
  if (metadata.mainWarehouse) return { glyph: 'С', label: 'Основной склад', known: true };
  if (metadata.production) return { glyph: 'П', label: 'Производство', known: true };
  return { glyph: '?', label: 'Тип склада не задан', known: false };
}

/**
 * Produces the operator-facing warehouse name without exposing planning-group
 * implementation details. The source record remains authoritative for the
 * actual warehouse identity.
 */
export function warehouseShortName(warehouse: Pick<Warehouse, 'name' | 'city'>): string {
  const name = warehouse.name.trim();
  const withoutPrefix = name.replace(/^(?:представительский\s+)?склад(?:\s+|$)/iu, '').trim();
  const city = warehouse.city?.trim();
  // Older source records sometimes use a role as their name (for example,
  // "Опорный склад"). The operator-facing format always needs the city.
  if (/(?:опорный|основной)\s+склад$/iu.test(name) && city) return city;
  return withoutPrefix || city || name;
}

/** Displays a warehouse in the single format used throughout logistics. */
export function warehouseDisplayName(
  warehouse: Pick<Warehouse, 'id' | 'name' | 'city' | 'representative'>,
  mainWarehouse?: Pick<Warehouse, 'id' | 'name' | 'city'> | null,
): string {
  const name = warehouseShortName(warehouse);
  if (!warehouse.representative) return `Склад ${name}`;

  const mainName = mainWarehouse && mainWarehouse.id !== warehouse.id
    ? warehouseShortName(mainWarehouse)
    : null;
  return `Представительский склад ${name}${mainName ? ` / ${mainName}` : ''}`;
}

/** Uses the dative form needed by resource lists. */
export function warehouseAttachedName(
  warehouse: Pick<Warehouse, 'id' | 'name' | 'city' | 'representative'>,
  mainWarehouse?: Pick<Warehouse, 'id' | 'name' | 'city'> | null,
): string {
  const name = warehouseShortName(warehouse);
  if (!warehouse.representative) return `Складу ${name}`;

  const mainName = mainWarehouse && mainWarehouse.id !== warehouse.id
    ? warehouseShortName(mainWarehouse)
    : null;
  return `Представительскому складу ${name}${mainName ? ` / ${mainName}` : ''}`;
}

/** One compact location line for the warehouse settings and summary views. */
export function warehouseLocation(warehouse: Pick<Warehouse, 'name' | 'city' | 'address' | 'timezone'>): string {
  return [warehouseShortName(warehouse), warehouse.address, warehouse.timezone]
    .filter((value): value is string => Boolean(value?.trim()))
    .join(' — ');
}
