import { simulatorApiUrl } from '../../api/client';

/** One selectable value from the canonical RWMS cabin catalog. */
export interface TransferCatalogValue {
  id: string;
  name: string;
}

/** Allowed cabin-size relation maintained by the RWMS catalog. */
export interface TransferTypeDimension {
  typeId: string;
  dimensionId: string;
  sortOrder: number;
}

/** Furniture catalog item with source-warehouse stock facts. */
export interface TransferFurnitureCatalogItem extends TransferCatalogValue {
  availableStock: number;
  reservedQuantity: number;
}

/** Catalog inputs needed to describe transfer cargo without hard-coded values. */
export interface TransferCargoCatalog {
  rentalTypes: TransferCatalogValue[];
  dimensions: TransferCatalogValue[];
  finishings: TransferCatalogValue[];
  characteristics: TransferCatalogValue[];
  typeDimensions: TransferTypeDimension[];
  furniture: TransferFurnitureCatalogItem[];
}

/** Furniture requirement applied to every cabin in one requirement group. */
export interface TransferFurniturePerCabinInput {
  furnitureCatalogItemId: string;
  quantityPerCabin: number;
}

/** Planned cabin configuration; physical cabin allocation remains optional in a draft. */
export interface TransferCabinGroupInput {
  rentalTypeId: string;
  dimensionId: string | null;
  finishingId: string | null;
  characteristicIds: string[];
  linoleum: boolean | null;
  quantity: number;
  furniturePerCabin: TransferFurniturePerCabinInput[];
  allocatedCabins: [];
}

/** Standalone vehicle identity used only to calculate the physical truck route. */
export interface TransferRouteVehicle {
  id: string;
  name: string;
  registrationNumber: string;
  capacity: number;
}

/** Side-effect-free exact road estimate for the planned warehouse leg. */
export interface TransferArrivalEstimate {
  departure_at: string;
  estimated_arrival_at: string;
  travel_seconds: number;
  distance_meters: number;
  vehicle_id: string;
  cabin_count: number;
  trailer_attached: boolean;
  routing_provider: string;
  osm_data_version: string | null;
}

/** Complete zero-or-more-cargo plan sent while the canonical transfer document is still a draft. */
export interface TransferPlanDraftInput {
  plannedDepartureAt: string | null;
  plannedArrivalAt: string | null;
  logisticsComment: string | null;
  tripDriverId: string | null;
  tripVehicleId: string | null;
  driverReposition: null;
  vehicleReposition: null;
  cabinGroups: TransferCabinGroupInput[];
  looseFurniture: [];
}

/** Authenticated, idempotent command for one exact canonical transfer-draft intention. */
export interface CreateTransferDraftInput {
  accessToken: string;
  idempotencyKey: string;
  warehouseId: string;
  destinationWarehouseId: string;
  scheduledDate: string;
  plan: TransferPlanDraftInput;
}

/** Minimum transfer-document identity needed by the standalone success feedback. */
export interface CreatedTransferDraft {
  id: string;
  version?: number;
  state?: string;
}

type JsonRecord = Record<string, unknown>;

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function canonicalProblemMessage(value: unknown, fallback: string) {
  if (!isRecord(value)) return fallback;
  if (typeof value.detail === 'string') return value.detail;
  if (typeof value.title === 'string') return value.title;
  return fallback;
}

async function readJson(response: Response, fallback: string): Promise<unknown> {
  if (!response.ok) {
    let problem: unknown = null;
    try {
      problem = await response.json();
    } catch {
      problem = null;
    }
    throw new Error(canonicalProblemMessage(problem, `${fallback} (HTTP ${response.status})`));
  }
  return response.json();
}

function catalogValue(value: unknown): TransferCatalogValue {
  if (!isRecord(value) || typeof value.id !== 'string' || typeof value.name !== 'string' || !value.name.trim()) {
    throw new Error('RWMS вернул некорректный справочник бытовок');
  }
  return { id: value.id, name: value.name };
}

function catalogValues(value: unknown): TransferCatalogValue[] {
  if (!Array.isArray(value)) throw new Error('RWMS вернул некорректный справочник бытовок');
  return value.map(catalogValue);
}

function typeDimension(value: unknown): TransferTypeDimension {
  if (
    !isRecord(value)
    || typeof value.typeId !== 'string'
    || typeof value.dimensionId !== 'string'
    || typeof value.sortOrder !== 'number'
  ) {
    throw new Error('RWMS вернул некорректные варианты исполнения бытовок');
  }
  return { typeId: value.typeId, dimensionId: value.dimensionId, sortOrder: value.sortOrder };
}

function nonNegativeInteger(value: unknown, message: string) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(message);
  return value;
}

function furnitureItem(value: unknown, warehouseId: string): TransferFurnitureCatalogItem | null {
  if (!isRecord(value) || !isRecord(value.equipment) || !isRecord(value.totals)) {
    throw new Error('RWMS вернул некорректные остатки мебели');
  }
  if (value.totals.warehouseId !== warehouseId) {
    throw new Error('RWMS вернул остатки мебели другого склада');
  }
  if (value.equipment.category !== 'FURNITURE' || value.equipment.active !== true) return null;
  const item = catalogValue(value.equipment);
  return {
    ...item,
    availableStock: nonNegativeInteger(value.totals.availableStock, 'RWMS вернул некорректный доступный остаток мебели'),
    reservedQuantity: nonNegativeInteger(value.totals.reservedQuantity, 'RWMS вернул некорректный резерв мебели'),
  };
}

/** Loads cabin characteristics and furniture balances from canonical asset-service APIs. */
export async function loadTransferCargoCatalog(accessToken: string, warehouseId: string): Promise<TransferCargoCatalog> {
  const headers = { Accept: 'application/json, application/problem+json', Authorization: `Bearer ${accessToken}` };
  const query = `warehouseId=${encodeURIComponent(warehouseId)}`;
  const [optionsResponse, equipmentResponse] = await Promise.all([
    fetch(`/api/asset/v1/rental-items/creation-options?${query}`, { headers }),
    fetch(`/api/asset/v1/equipment?${query}`, { headers }),
  ]);
  const [optionsValue, equipmentValue] = await Promise.all([
    readJson(optionsResponse, 'Не удалось получить справочники бытовок'),
    readJson(equipmentResponse, 'Не удалось получить каталог мебели'),
  ]);
  if (!isRecord(optionsValue) || !Array.isArray(optionsValue.typeDimensions) || !Array.isArray(equipmentValue)) {
    throw new Error('RWMS вернул некорректные данные для состава перемещения');
  }
  return {
    rentalTypes: catalogValues(optionsValue.rentalTypes),
    dimensions: catalogValues(optionsValue.dimensions),
    finishings: catalogValues(optionsValue.finishings),
    characteristics: catalogValues(optionsValue.characteristics),
    typeDimensions: optionsValue.typeDimensions.map(typeDimension),
    furniture: equipmentValue
      .map((item) => furnitureItem(item, warehouseId))
      .filter((item): item is TransferFurnitureCatalogItem => item !== null)
      .sort((left, right) => left.name.localeCompare(right.name, 'ru')),
  };
}

/** Loads active source-warehouse vehicles without refreshing canonical RWMS identities. */
export async function loadTransferRouteVehicles(localWarehouseId: string): Promise<TransferRouteVehicle[]> {
  const response = await fetch(simulatorApiUrl(`/warehouses/${encodeURIComponent(localWarehouseId)}/workspace?refresh_rwms=false`), {
    headers: { Accept: 'application/json, application/problem+json' },
  });
  const value = await readJson(response, 'Не удалось получить автомобили склада');
  if (!isRecord(value) || !Array.isArray(value.vehicles)) throw new Error('Логистика вернула некорректный список автомобилей');
  return value.vehicles.flatMap((candidate): TransferRouteVehicle[] => {
    if (isRecord(candidate) && candidate.warehouse_id !== localWarehouseId) {
      throw new Error('Логистика вернула автомобиль другого склада');
    }
    if (
      !isRecord(candidate)
      || candidate.active !== true
      || typeof candidate.id !== 'string'
      || typeof candidate.name !== 'string'
      || typeof candidate.registration_number !== 'string'
      || typeof candidate.capacity !== 'number'
    ) return [];
    return [{
      id: candidate.id,
      name: candidate.name,
      registrationNumber: candidate.registration_number,
      capacity: nonNegativeInteger(candidate.capacity, 'Логистика вернула некорректную вместимость автомобиля'),
    }];
  });
}

/** Calculates exact warehouse-to-warehouse arrival using the selected physical vehicle profile. */
export async function estimateTransferArrival(input: {
  sourceWarehouseId: string;
  destinationWarehouseId: string;
  plannedDepartureAt: string;
  vehicleId: string;
  cabinCount: number;
}): Promise<TransferArrivalEstimate> {
  const response = await fetch(simulatorApiUrl('/routing/transfer-arrival-estimate'), {
    method: 'POST',
    headers: { Accept: 'application/json, application/problem+json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      source_warehouse_id: input.sourceWarehouseId,
      destination_warehouse_id: input.destinationWarehouseId,
      planned_departure_at: input.plannedDepartureAt,
      vehicle_id: input.vehicleId,
      cabin_count: input.cabinCount,
    }),
  });
  const value = await readJson(response, 'Не удалось рассчитать время прибытия');
  if (
    !isRecord(value)
    || typeof value.estimated_arrival_at !== 'string'
    || typeof value.departure_at !== 'string'
    || typeof value.travel_seconds !== 'number'
    || typeof value.distance_meters !== 'number'
    || typeof value.vehicle_id !== 'string'
    || typeof value.cabin_count !== 'number'
    || typeof value.trailer_attached !== 'boolean'
    || typeof value.routing_provider !== 'string'
    || !(value.osm_data_version === null || typeof value.osm_data_version === 'string')
  ) {
    throw new Error('Логистика вернула некорректный расчёт прибытия');
  }
  return value as unknown as TransferArrivalEstimate;
}

/** Creates the authoritative logistics-service draft through the public same-origin gateway. */
export async function createTransferDraft(input: CreateTransferDraftInput): Promise<CreatedTransferDraft> {
  const response = await fetch('/api/logistics/v1/transfers', {
    method: 'POST',
    headers: {
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${input.accessToken}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': input.idempotencyKey,
    },
    body: JSON.stringify({
      warehouseId: input.warehouseId,
      destinationWarehouseId: input.destinationWarehouseId,
      scheduledDate: input.scheduledDate,
      lines: [],
      furnitureReplacements: [],
      plan: input.plan,
    }),
  });
  const value = await readJson(response, 'Не удалось создать перемещение');
  if (!isRecord(value) || typeof value.id !== 'string') {
    throw new Error('Логистика вернула некорректный черновик перемещения');
  }
  return value as unknown as CreatedTransferDraft;
}
