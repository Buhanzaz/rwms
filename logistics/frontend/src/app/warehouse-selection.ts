import type { UUID, Warehouse } from '../domain/types';

/** Selects the first warehouse only during initial bootstrap and never replaces a valid intent. */
export function initialWarehouseSelection(
  currentWarehouseId: UUID | null,
  warehouses: readonly Warehouse[] | undefined,
): UUID | null {
  if (currentWarehouseId !== null) return currentWarehouseId;
  return warehouses?.[0]?.id ?? null;
}
