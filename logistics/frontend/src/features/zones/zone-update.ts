import type { ZoneInput } from '../../api/client';
import type { Zone } from '../../domain/types';

/** Builds a zone patch while keeping unchanged geometry out of metadata-only edits. */
export function buildZoneUpdatePayload(
  zone: Zone,
  input: ZoneInput,
): Partial<Omit<ZoneInput, 'locked'>> | null {
  const geometryChanged = JSON.stringify(zone.geometry) !== JSON.stringify(input.geometry);
  const metadataChanged = zone.name !== input.name
    || zone.code !== input.code
    || zone.route_group !== input.route_group
    || zone.delivery_price !== input.delivery_price
    || zone.pickup_price !== input.pickup_price
    || zone.priority !== input.priority;

  if (!geometryChanged && !metadataChanged) return null;

  const metadata = {
    name: input.name,
    code: input.code,
    route_group: input.route_group,
    delivery_price: input.delivery_price,
    pickup_price: input.pickup_price,
    priority: input.priority,
  };
  return geometryChanged ? { ...metadata, geometry: input.geometry } : metadata;
}

export interface ZoneUpdateActions {
  setLocked: (locked: boolean) => Promise<unknown>;
  update: (payload: Partial<Omit<ZoneInput, 'locked'>>) => Promise<unknown>;
}

/** Applies one zone form while preserving its requested final lock state. */
export async function saveZoneUpdate(
  zone: Zone,
  input: ZoneInput,
  actions: ZoneUpdateActions,
): Promise<void> {
  const payload = buildZoneUpdatePayload(zone, input);
  const mustUnlock = zone.locked && (!input.locked || payload !== null);

  if (mustUnlock) await actions.setLocked(false);

  try {
    if (payload) await actions.update(payload);
  } catch (error: unknown) {
    if (mustUnlock) {
      try {
        await actions.setLocked(true);
      } catch {
        // Preserve the patch failure: it explains which requested edit was rejected.
      }
    }
    throw error;
  }

  if (input.locked && (!zone.locked || mustUnlock)) await actions.setLocked(true);
}
