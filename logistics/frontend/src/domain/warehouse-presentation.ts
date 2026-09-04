import type { Warehouse } from './types';

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
