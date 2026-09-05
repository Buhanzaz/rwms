import { z } from 'zod';
import { ApiError, requireSimulatorAccessToken } from './client';

const warehouseKindSchema = z.object({
  // Canonical warehouse IDs include UUIDs without RFC version/variant bits.
  id: z.guid(),
  representative: z.boolean(),
  production: z.boolean(),
  mainWarehouse: z.boolean(),
}).refine(
  ({ representative, production, mainWarehouse }) => (
    representative ? !production && !mainWarehouse : production || mainWarehouse
  ),
  { message: 'Warehouse classification is inconsistent' },
);

export type WarehouseKindMetadata = z.infer<typeof warehouseKindSchema>;

/** Canonical labels only: directory membership never grants planner access. */
export async function loadWarehouseKinds(signal?: AbortSignal): Promise<ReadonlyMap<string, WarehouseKindMetadata>> {
  const token = await requireSimulatorAccessToken();
  const response = await fetch('/api/warehouse/v1/warehouses', {
    signal: signal ?? null,
    cache: 'no-store',
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new ApiError(response.status, null, 'Не удалось загрузить типы складов. Повторите обновление.');
  const body: unknown = await response.json();
  const parsed = z.array(warehouseKindSchema).safeParse(body);
  if (!parsed.success || new Set(parsed.data.map((item) => item.id)).size !== parsed.data.length) {
    throw new Error('Справочник вернул неподтверждённые типы складов. Обновите данные.');
  }
  return new Map(parsed.data.map((item) => [item.id, item]));
}
