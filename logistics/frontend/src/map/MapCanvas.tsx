import maplibregl, { type GeoJSONSource, type Map as MapLibreMap, type Marker, type Popup } from 'maplibre-gl';
import {
  ArrowRight,
  Layers3,
  MapPin,
  MousePointer2,
  Truck,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  Feature,
  FeatureCollection,
  LineString,
  MultiPolygon,
  Point,
  Polygon,
} from 'geojson';
import type {
  MapSelection,
  OptimizationTraceEvent,
  OptimizationRun,
  RoutePlan,
  WarehouseWorkspace,
  SimulationDerivedState,
  SimulationVehicleState,
  UUID,
} from '../domain/types';
import { api, type TruckRestrictionCategory, type TruckRestrictionMetadata } from '../api/client';
import { Button, CheckboxField } from '../components/ui';
import { isRequestVisibleOnDate } from '../domain/request-dates';
import { useUiStore, type LayerVisibility, type MapTool } from '../stores/ui-store';
import { formatTime } from '../utils/format';
import { userFacingErrorDetail } from '../utils/user-facing-error';
import { deriveSimulationRouteLayers } from '../simulation/route-layers';
import { RequestMapPopup } from './RequestMapCard';
import type { MapInsets } from '../app/map-layout';
import type { PlanMove } from '../features/planning/PlanPanel';
import type { SlotPlanningGeoJson, SlotPlanningMapPresentation } from '../features/slot-availability/types';
import {
  buildTruckRestrictionPopupContent,
  truckRestrictionKey,
  truckRestrictionLookup,
  truckRestrictionMapData,
  truckRestrictionPresentation,
  type TruckRestrictionLayerState,
} from './TruckRestrictions';
import { TruckRestrictionLayerMenuItem } from './TruckRestrictionsLayer';
import { ROUTE_COLORS, routeFeatures } from './route-features';
import {
  TRAVEL_TIME_CONTOUR_SOURCE_ID,
  travelTimeContourStyles,
  TASK_TRAVEL_TIME_CONTOUR_LAYER_IDS,
  WAREHOUSE_TRAVEL_TIME_CONTOUR_LAYER_IDS,
  deriveTravelTimeContourOrigins,
  travelTimeContourFeaturesForOrigin,
  travelTimeContourLayerSpecifications,
  type TravelTimeContourLayerState,
  type TravelTimeContourOrigin,
} from './TravelTimeContours';

const BLANK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  name: 'RWMS Offline Grid',
  sources: {},
  layers: [],
};

const EMPTY_COLLECTION: FeatureCollection = { type: 'FeatureCollection', features: [] };
const SOURCE_IDS = [
  TRAVEL_TIME_CONTOUR_SOURCE_ID,
  'rwms-corridor',
  'rwms-routes',
  'rwms-traveled',
  'rwms-active',
  'rwms-candidates',
  'rwms-selected',
  'rwms-requests',
  'rwms-truck-restrictions',
  'rwms-slot-route-before',
  'rwms-slot-route-after',
  'rwms-slot-pickup-candidates',
] as const;
const TRUCK_RESTRICTION_MIN_ZOOM = 8;
const TRAVEL_TIME_CONTOUR_MAX_CONCURRENCY = 4;
const MAP_VIEWPORT_KEY_PREFIX = 'rwms:logistics:map-viewport:';
const TRUCK_RESTRICTION_LAYER_IDS = [
  'rwms-truck-restrictions-lines',
  'rwms-truck-restrictions-points',
  'rwms-truck-restrictions-line-labels',
  'rwms-truck-restrictions-point-labels',
] as const;
const EMPTY_TRUCK_RESTRICTION_STATE: TruckRestrictionLayerState = {
  status: 'off',
  count: 0,
  truncated: false,
  error: null,
};

/** Per-warehouse browser view; it never participates in route feasibility or persistence. */
interface MapViewportPreference {
  longitude: number;
  latitude: number;
  zoom: number;
}

function readMapViewport(warehouseId: UUID): MapViewportPreference | null {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(`${MAP_VIEWPORT_KEY_PREFIX}${warehouseId}`) ?? 'null') as unknown;
    if (!parsed || typeof parsed !== 'object') return null;
    const value = parsed as Partial<MapViewportPreference>;
    if (
      !Number.isFinite(value.longitude)
      || !Number.isFinite(value.latitude)
      || !Number.isFinite(value.zoom)
      || (value.longitude as number) < -180
      || (value.longitude as number) > 180
      || (value.latitude as number) < -90
      || (value.latitude as number) > 90
      || (value.zoom as number) < 0
      || (value.zoom as number) > 24
    ) return null;
    return value as MapViewportPreference;
  } catch {
    return null;
  }
}

function writeMapViewport(warehouseId: UUID, viewport: MapViewportPreference): void {
  try {
    window.localStorage.setItem(`${MAP_VIEWPORT_KEY_PREFIX}${warehouseId}`, JSON.stringify(viewport));
  } catch {
    // The current in-memory map remains usable when browser storage is blocked.
  }
}

/** Load every contour origin without flooding the shared Valhalla worker pool. */
async function loadTravelTimeContourFeatures(
  origins: readonly TravelTimeContourOrigin[],
  signal: AbortSignal,
): Promise<Array<Feature<Polygon | MultiPolygon>>> {
  const featuresByOrigin = Array.from(
    { length: origins.length },
    (): Array<Feature<Polygon | MultiPolygon>> => [],
  );
  let nextIndex = 0;
  const worker = async () => {
    while (!signal.aborted) {
      const index = nextIndex;
      nextIndex += 1;
      const origin = origins[index];
      if (!origin) return;
      const collection = await api.getTravelTimeContours(
        origin.latitude,
        origin.longitude,
        signal,
      );
      featuresByOrigin[index] = travelTimeContourFeaturesForOrigin(collection, origin);
    }
  };
  const workerCount = Math.min(TRAVEL_TIME_CONTOUR_MAX_CONCURRENCY, origins.length);
  await Promise.all(Array.from({ length: workerCount }, worker));
  return featuresByOrigin.flat();
}

const layerLabels: Record<keyof LayerVisibility, string> = {
  base: 'Базовая карта / сетка',
  warehouse: 'Склады',
  warehouseIsochrones: 'Изохроны склада',
  taskIsochrones: 'Изохроны задания',
  deliveries: 'Доставки (Д)',
  pickups: 'Возвраты (В)',
  unassigned: 'Нераспределённые',
  candidates: 'Кандидатные связи',
  routes: 'Итоговые маршруты',
  traveled: 'Пройденный маршрут',
  activeLeg: 'Активный участок',
  trucks: 'Машины',
  corridor: 'Маршрутный коридор',
  selected: 'Выбранный объект',
  truckRestrictions: 'Ограничения грузового транспорта',
};

/** Optional unpersisted warehouse point retained for compatible map callers. */
export interface PendingWarehouseMapPoint {
  id: UUID;
  latitude: number;
  longitude: number;
  label: string;
}

interface MapCanvasProps {
  cameraPadding?: MapInsets;
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  simulation: SimulationDerivedState | null;
  traceEvents: OptimizationTraceEvent[];
  selected: MapSelection;
  onSelect: (selection: MapSelection) => void;
  onPlacePoint: (kind: 'request', longitude: number, latitude: number) => void;
  onWarehouseActivate: (warehouseId: UUID) => void;
  onMapError: (message: string) => void;
  optimizationRun: OptimizationRun | null;
  onRequestMoveDraft: (requestId: UUID, longitude: number, latitude: number) => void;
  planningDate: string;
  busy: boolean;
  onScheduleRequestDate: (requestId: UUID, date: string, addIfMissing: boolean) => void;
  onUnscheduleRequest: (requestId: UUID) => void;
  onMoveTask: (move: PlanMove) => void;
  planningCheck: SlotPlanningMapPresentation | null;
  onPlanningCheckPoint: (point: { latitude: number; longitude: number }) => void;
  pendingWarehousePoint: PendingWarehouseMapPoint | null;
}

/** Explicit navigation control for a warehouse selected on the shared map. */
export function WarehouseActivationControl({ currentWarehouseId, warehouses, selected, onActivate }: {
  currentWarehouseId: UUID;
  warehouses: WarehouseWorkspace['warehouses'];
  selected: MapSelection;
  onActivate: (warehouseId: UUID) => void;
}) {
  if (selected?.kind !== 'warehouse' || selected.id === currentWarehouseId) return null;
  const target = warehouses.find((warehouse) => warehouse.id === selected.id);
  if (!target) return null;
  return (
    <Button
      size="sm"
      className="map-toolbar__warehouse-activate"
      aria-label={`Перейти к складу ${target.name}`}
      title={`Перейти к складу «${target.name}»`}
      onClick={() => onActivate(target.id)}
    >
      <span>Перейти к складу</span><ArrowRight size={15} aria-hidden="true" />
    </Button>
  );
}

function featureCollection(features: Feature[]): FeatureCollection {
  return { type: 'FeatureCollection', features };
}

function geoJsonFeatureCollection(value: SlotPlanningGeoJson | undefined): FeatureCollection {
  if (!value) return EMPTY_COLLECTION;
  if (value.type === 'FeatureCollection') return value;
  if (value.type === 'Feature') return featureCollection([value]);
  return featureCollection([{ type: 'Feature', properties: {}, geometry: value }]);
}

function candidateFeatures(events: OptimizationTraceEvent[]): Feature<LineString>[] {
  return events.flatMap((event) => {
    if (!['candidate_edge_considered', 'candidate_cycle_created', 'candidate_edge_rejected'].includes(event.event_type)) return [];
    const from = event.payload.from;
    const to = event.payload.to;
    if (!Array.isArray(from) || !Array.isArray(to)) return [];
    if (typeof from[0] !== 'number' || typeof from[1] !== 'number' || typeof to[0] !== 'number' || typeof to[1] !== 'number') return [];
    return [{
      type: 'Feature',
      properties: { rejected: event.event_type.includes('rejected') },
      geometry: { type: 'LineString', coordinates: [[from[0], from[1]], [to[0], to[1]]] },
    }];
  });
}

function selectedFeatures(selection: MapSelection, workspace: WarehouseWorkspace, plan: RoutePlan | null): Feature[] {
  if (!selection) return [];
  if (selection.kind === 'request') {
    const request = workspace.requests.find((candidate) => candidate.id === selection.id);
    if (!request) return [];
    return [{
      type: 'Feature',
      properties: { requestId: request.id },
      geometry: { type: 'Point', coordinates: [request.longitude, request.latitude] },
    }];
  }
  if (selection.kind === 'cycle' && plan) {
    const cycle = plan.driver_routes.flatMap((route) => route.cycles).find((candidate) => candidate.id === selection.id);
    if (!cycle) return [];
    return [
      ...(cycle.cross_warehouse_service?.positioning_outbound_geometry
        ? [cycle.cross_warehouse_service.positioning_outbound_geometry]
        : []),
      ...cycle.legs.map((leg) => leg.geometry),
      ...(cycle.cross_warehouse_service?.positioning_return_geometry
        ? [cycle.cross_warehouse_service.positioning_return_geometry]
        : []),
    ];
  }
  return [];
}

function requestPointFeatures(
  requests: WarehouseWorkspace['requests'],
  unassignedRequestIds: ReadonlySet<UUID>,
): Array<Feature<Point>> {
  return requests.map((request) => {
    const unassigned = request.status === 'UNASSIGNED' || unassignedRequestIds.has(request.id);
    return {
      type: 'Feature',
      properties: {
        requestId: request.id,
        requestType: request.type,
        glyph: `${request.type === 'DELIVERY' ? 'Д' : 'В'}${unassigned ? '!' : ''}`,
        unassigned,
      },
      geometry: { type: 'Point', coordinates: [request.longitude, request.latitude] },
    };
  });
}

function markerElement(kind: 'warehouse' | 'delivery' | 'pickup' | 'truck', label: string, selected: boolean): HTMLElement {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = `map-marker map-marker--${kind}${selected ? ' map-marker--selected' : ''}`;
  element.setAttribute('aria-label', label);
  element.title = label;
  const span = document.createElement('span');
  span.textContent = kind === 'warehouse' ? 'С' : kind === 'delivery' ? 'Д' : kind === 'pickup' ? 'В' : '🚚';
  element.append(span);
  return element;
}

function simulationVehicleMapLabel(vehicle: SimulationVehicleState, timeZone: string): string {
  const destination = vehicle.next_stop_label ?? (vehicle.status === 'FINISHED' ? 'маршрут завершён' : 'не определён');
  const eta = vehicle.eta ? formatTime(vehicle.eta, timeZone) : '—';
  return `${vehicle.driver_name} · ${vehicle.registration_number} · загрузка ${vehicle.load} · ${vehicle.status} · адрес назначения: ${destination} · ETA ${eta}`;
}

function setSource(map: MapLibreMap, id: typeof SOURCE_IDS[number], data: FeatureCollection): void {
  const source = map.getSource<GeoJSONSource>(id);
  source?.setData(data);
}

function featureProperty(feature: unknown, key: string): string | null {
  if (!feature || typeof feature !== 'object' || !('properties' in feature)) return null;
  const properties = feature.properties;
  if (!properties || typeof properties !== 'object' || !(key in properties)) return null;
  const value = (properties as Record<string, unknown>)[key];
  return typeof value === 'string' ? value : null;
}

function featureNumberProperty(feature: unknown, key: string): number | null {
  if (!feature || typeof feature !== 'object' || !('properties' in feature)) return null;
  const properties = feature.properties;
  if (!properties || typeof properties !== 'object' || !(key in properties)) return null;
  const value = (properties as Record<string, unknown>)[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

const TRUCK_RESTRICTION_CATEGORIES: TruckRestrictionCategory[] = [
  'HGV_ACCESS',
  'MAX_HEIGHT',
  'MAX_WIDTH',
  'MAX_LENGTH',
  'MAX_WEIGHT',
  'MAX_AXLE_LOAD',
  'CONDITIONAL',
  'TRAILER_ACCESS',
];

function addTruckRestrictionIcons(map: MapLibreMap): void {
  TRUCK_RESTRICTION_CATEGORIES.forEach((category) => {
    const imageName = `rwms-truck-restriction-${category}`;
    if (map.hasImage(imageName)) return;
    const presentation = truckRestrictionPresentation(category);
    const canvas = document.createElement('canvas');
    canvas.width = 72;
    canvas.height = 36;
    const context = canvas.getContext('2d');
    if (!context) return;
    context.fillStyle = '#07101d';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.strokeStyle = presentation.color;
    context.lineWidth = 5;
    context.strokeRect(2.5, 2.5, canvas.width - 5, canvas.height - 5);
    context.fillStyle = '#f8fafc';
    context.font = 'bold 20px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText(presentation.marker, canvas.width / 2, canvas.height / 2 + 1);
    map.addImage(imageName, context.getImageData(0, 0, canvas.width, canvas.height), { pixelRatio: 2 });
  });
}

function addOverlaySources(map: MapLibreMap): void {
  for (const id of SOURCE_IDS) {
    if (!map.getSource(id)) {
      map.addSource(id, {
        type: 'geojson',
        data: EMPTY_COLLECTION,
        ...(id === 'rwms-requests'
          ? { cluster: true, clusterMaxZoom: 13, clusterRadius: 48 }
          : {}),
        ...(id === 'rwms-truck-restrictions'
          ? { attribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank">© OpenStreetMap contributors</a>' }
          : {}),
      });
    }
  }
  const addLayer = (layer: maplibregl.LayerSpecification) => {
    if (!map.getLayer(layer.id)) map.addLayer(layer);
  };
  addTruckRestrictionIcons(map);
  travelTimeContourLayerSpecifications().forEach(addLayer);
  addLayer({ id: 'rwms-candidates-line', type: 'line', source: 'rwms-candidates', paint: { 'line-color': ['case', ['get', 'rejected'], '#fb7185', '#fbbf24'], 'line-width': 2, 'line-opacity': 0.48, 'line-dasharray': [2, 2] } });
  addLayer({ id: 'rwms-corridor-line', type: 'line', source: 'rwms-corridor', paint: { 'line-color': '#38bdf8', 'line-width': 18, 'line-opacity': 0.1 } });
  addLayer({ id: 'rwms-selected-fill', type: 'fill', source: 'rwms-selected', filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': '#fbbf24', 'fill-opacity': 0.22 } });
  addLayer({ id: 'rwms-request-clusters', type: 'circle', source: 'rwms-requests', filter: ['has', 'point_count'], paint: { 'circle-color': '#2563eb', 'circle-radius': ['step', ['get', 'point_count'], 18, 25, 22, 100, 27], 'circle-stroke-color': '#dbeafe', 'circle-stroke-width': 2, 'circle-opacity': 0.92 } });
  addLayer({ id: 'rwms-request-cluster-count', type: 'symbol', source: 'rwms-requests', filter: ['has', 'point_count'], layout: { 'text-field': '{point_count_abbreviated}', 'text-size': 12 }, paint: { 'text-color': '#f8fafc' } });
  addLayer({ id: 'rwms-request-points', type: 'circle', source: 'rwms-requests', filter: ['!', ['has', 'point_count']], paint: { 'circle-color': ['case', ['==', ['get', 'requestType'], 'DELIVERY'], '#38bdf8', '#fb923c'], 'circle-radius': ['case', ['get', 'unassigned'], 10, 8], 'circle-stroke-color': ['case', ['get', 'unassigned'], '#fb7185', '#07101d'], 'circle-stroke-width': ['case', ['get', 'unassigned'], 4, 2], 'circle-opacity': 0.94 } });
  addLayer({ id: 'rwms-request-labels', type: 'symbol', source: 'rwms-requests', filter: ['!', ['has', 'point_count']], layout: { 'text-field': ['get', 'glyph'], 'text-size': 10, 'text-allow-overlap': true }, paint: { 'text-color': '#07101d' } });
  addLayer({ id: 'rwms-selected-request', type: 'circle', source: 'rwms-selected', filter: ['==', ['geometry-type'], 'Point'], paint: { 'circle-color': '#fbbf24', 'circle-radius': 12, 'circle-stroke-color': '#f8fafc', 'circle-stroke-width': 3, 'circle-opacity': 0.96 } });
  addLayer({ id: 'rwms-slot-pickup-candidates-points', type: 'circle', source: 'rwms-slot-pickup-candidates', filter: ['==', ['geometry-type'], 'Point'], layout: { visibility: 'none' }, paint: { 'circle-color': ['case', ['==', ['get', 'selected'], true], '#5ee2b2', '#fb923c'], 'circle-radius': 7, 'circle-stroke-color': '#07101d', 'circle-stroke-width': 2 } });
  addLayer({ id: 'rwms-slot-pickup-candidates-lines', type: 'line', source: 'rwms-slot-pickup-candidates', filter: ['!=', ['geometry-type'], 'Point'], layout: { visibility: 'none' }, paint: { 'line-color': '#fb923c', 'line-width': 3, 'line-opacity': 0.8, 'line-dasharray': [2, 2] } });
  addLayer({
    id: 'rwms-truck-restrictions-lines',
    type: 'line',
    source: 'rwms-truck-restrictions',
    filter: ['!=', ['geometry-type'], 'Point'],
    layout: { visibility: 'none' },
    paint: {
      'line-color': ['get', 'color'],
      'line-width': ['interpolate', ['linear'], ['zoom'], 8, 3, 14, 7],
      'line-opacity': 0.82,
    },
  });
  addLayer({
    id: 'rwms-truck-restrictions-points',
    type: 'circle',
    source: 'rwms-truck-restrictions',
    filter: ['==', ['geometry-type'], 'Point'],
    layout: { visibility: 'none' },
    paint: {
      'circle-color': ['get', 'color'],
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 8, 6, 14, 10],
      'circle-stroke-color': '#07101d',
      'circle-stroke-width': 2,
      'circle-opacity': 0.9,
    },
  });
  addLayer({
    id: 'rwms-truck-restrictions-line-labels',
    type: 'symbol',
    source: 'rwms-truck-restrictions',
    filter: ['!=', ['geometry-type'], 'Point'],
    layout: {
      visibility: 'none',
      'symbol-placement': 'line',
      'symbol-spacing': 180,
      'icon-image': ['concat', 'rwms-truck-restriction-', ['get', 'category']],
      'icon-keep-upright': true,
      'icon-rotation-alignment': 'map',
    },
  });
  addLayer({
    id: 'rwms-truck-restrictions-point-labels',
    type: 'symbol',
    source: 'rwms-truck-restrictions',
    filter: ['==', ['geometry-type'], 'Point'],
    layout: {
      visibility: 'none',
      'icon-image': ['concat', 'rwms-truck-restriction-', ['get', 'category']],
      'icon-offset': [0, -14],
      'icon-allow-overlap': false,
    },
  });
  addLayer({ id: 'rwms-slot-route-before-line', type: 'line', source: 'rwms-slot-route-before', layout: { visibility: 'none' }, paint: { 'line-color': '#94a3b8', 'line-width': 5, 'line-opacity': 0.72, 'line-dasharray': [2, 1.5] } });
  addLayer({ id: 'rwms-routes-halo', type: 'line', source: 'rwms-routes', paint: { 'line-color': ['case', ['get', 'isSelected'], '#f8fafc', '#06111d'], 'line-width': ['case', ['get', 'isSelected'], 10, 8], 'line-opacity': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], 0.9, 0.28], 0.58] } });
  addLayer({ id: 'rwms-routes-line', type: 'line', source: 'rwms-routes', paint: { 'line-color': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], ['get', 'color'], '#94a3b8'], ['get', 'color']], 'line-width': ['case', ['get', 'isSelected'], 6, 4.5], 'line-opacity': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], 1, 0.28], 0.9] } });
  addLayer({ id: 'rwms-slot-route-after-line', type: 'line', source: 'rwms-slot-route-after', layout: { visibility: 'none' }, paint: { 'line-color': '#5ee2b2', 'line-width': 7, 'line-opacity': 0.96 } });
  addLayer({ id: 'rwms-traveled-line', type: 'line', source: 'rwms-traveled', paint: { 'line-color': '#cbd5e1', 'line-width': 5, 'line-opacity': 0.82 } });
  addLayer({ id: 'rwms-active-line', type: 'line', source: 'rwms-active', paint: { 'line-color': '#fbbf24', 'line-width': 7, 'line-opacity': 0.95 } });
  addLayer({ id: 'rwms-selected-line', type: 'line', source: 'rwms-selected', paint: { 'line-color': '#fbbf24', 'line-width': 6, 'line-opacity': 0.9 } });
}

export function MapCanvas({
  workspace,
  plan,
  simulation,
  traceEvents,
  selected,
  onSelect,
  onPlacePoint,
  onWarehouseActivate,
  onMapError,
  optimizationRun,
  onRequestMoveDraft,
  planningDate,
  busy,
  onScheduleRequestDate,
  onUnscheduleRequest,
  onMoveTask,
  planningCheck,
  onPlanningCheckPoint,
  pendingWarehousePoint,
  cameraPadding,
}: MapCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const cameraPaddingRef = useRef(cameraPadding);
  cameraPaddingRef.current = cameraPadding;
  const markersRef = useRef<Marker[]>([]);
  const truckRestrictionPopupRef = useRef<Popup | null>(null);
  const truckRestrictionAbortRef = useRef<AbortController | null>(null);
  const travelTimeContourAbortRef = useRef<AbortController | null>(null);
  const truckRestrictionLookupRef = useRef<ReturnType<typeof truckRestrictionLookup>>(new Map());
  const truckRestrictionMetadataRef = useRef<TruckRestrictionMetadata | null>(null);
  const initialViewportRef = useRef(readMapViewport(workspace.warehouse.id));
  const initialMapPositionRef = useRef(initialViewportRef.current ?? {
    longitude: workspace.warehouse.longitude,
    latitude: workspace.warehouse.latitude,
    zoom: 11,
  });
  const fittedWarehouseIdRef = useRef<UUID | null>(workspace.warehouse.id);
  const fittedPendingWarehouseIdRef = useRef<UUID | null>(null);
  const fittedPlanIdRef = useRef<UUID | null>(null);
  const suppressCurrentPlanFitRef = useRef(initialViewportRef.current !== null);
  const pannedRequestIdRef = useRef<UUID | null>(null);
  const [mapReady, setMapReady] = useState(false);
  const [layersOpen, setLayersOpen] = useState(false);
  const [truckRestrictionState, setTruckRestrictionState] = useState<TruckRestrictionLayerState>(EMPTY_TRUCK_RESTRICTION_STATE);
  const [travelTimeContourState, setTravelTimeContourState] = useState<TravelTimeContourLayerState>({ status: 'idle' });
  const mapTool = useUiStore((state) => state.mapTool);
  const setMapTool = useUiStore((state) => state.setMapTool);
  const layers = useUiStore((state) => state.layers);
  const toggleLayer = useUiStore((state) => state.toggleLayer);
  const styleUrl = import.meta.env.VITE_MAP_STYLE_URL;
  const selectedTaskContourOrigin = useMemo<TravelTimeContourOrigin | null>(() => {
    if (planningCheck?.active && planningCheck.point) {
      return {
        kind: 'TASK',
        id: 'slot-check-point',
        name: 'Проверяемое задание',
        ...planningCheck.point,
      };
    }
    if (selected?.kind !== 'request') return null;
    const request = workspace.requests.find((candidate) => candidate.id === selected.id);
    return request ? {
      kind: 'TASK',
      id: request.id,
      name: request.name,
      latitude: request.latitude,
      longitude: request.longitude,
    } : null;
  }, [planningCheck?.active, planningCheck?.point, selected, workspace.requests]);
  const travelTimeContourOrigins = useMemo(
    () => deriveTravelTimeContourOrigins(
      workspace.warehouses,
      selectedTaskContourOrigin,
      layers.warehouseIsochrones,
      layers.taskIsochrones,
    ),
    [layers.taskIsochrones, layers.warehouseIsochrones, selectedTaskContourOrigin, workspace.warehouses],
  );

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    let fellBack = false;
    const initialViewport = initialMapPositionRef.current;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: styleUrl || BLANK_STYLE,
      center: [initialViewport.longitude, initialViewport.latitude],
      zoom: initialViewport.zoom,
      attributionControl: false,
      canvasContextAttributes: { preserveDrawingBuffer: true },
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right');

    const prepare = () => {
      addOverlaySources(map);
      setMapReady(true);
    };
    map.on('style.load', prepare);
    map.on('error', (event: unknown) => {
      const errorValue = event && typeof event === 'object' && 'error' in event ? event.error : null;
      const message = userFacingErrorDetail(errorValue, 'Не удалось отобразить карту.');
      if (styleUrl && !map.isStyleLoaded() && !fellBack) {
        fellBack = true;
        setMapReady(false);
        onMapError('Стиль карты не загрузился — включён автономный координатный фон. Рисование и маршруты доступны.');
        map.setStyle(BLANK_STYLE);
      } else if (!message.includes('Failed to fetch')) {
        onMapError(`Ошибка карты: ${message}`);
      }
    });

    return () => {
      markersRef.current.forEach((marker) => marker.remove());
      markersRef.current = [];
      truckRestrictionAbortRef.current?.abort();
      truckRestrictionAbortRef.current = null;
      travelTimeContourAbortRef.current?.abort();
      travelTimeContourAbortRef.current = null;
      truckRestrictionPopupRef.current?.remove();
      truckRestrictionPopupRef.current = null;
      map.remove();
      mapRef.current = null;
    };
  }, [onMapError, styleUrl]);

  useEffect(() => {
    if (mapReady && cameraPadding) mapRef.current?.setPadding(cameraPadding);
  }, [mapReady, cameraPadding]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const persistViewport = () => {
      const center = map.getCenter();
      writeMapViewport(workspace.warehouse.id, {
        longitude: center.lng,
        latitude: center.lat,
        zoom: map.getZoom(),
      });
    };
    map.on('moveend', persistViewport);
    return () => {
      map.off('moveend', persistViewport);
    };
  }, [mapReady, workspace.warehouse.id]);

  const loadTruckRestrictions = useCallback(async () => {
    const map = mapRef.current;
    if (!map || !mapReady || !layers.truckRestrictions) return;
    if (map.getZoom() < TRUCK_RESTRICTION_MIN_ZOOM) {
      truckRestrictionAbortRef.current?.abort();
      truckRestrictionAbortRef.current = null;
      truckRestrictionLookupRef.current.clear();
      truckRestrictionMetadataRef.current = null;
      setSource(map, 'rwms-truck-restrictions', EMPTY_COLLECTION);
      setTruckRestrictionState({ status: 'zoom', count: 0, truncated: false, error: null });
      return;
    }

    truckRestrictionAbortRef.current?.abort();
    const controller = new AbortController();
    truckRestrictionAbortRef.current = controller;
    setTruckRestrictionState((current) => ({ ...current, status: 'loading', error: null }));
    const bounds = map.getBounds();
    try {
      const collection = await api.getTruckRestrictions({
        west: bounds.getWest(),
        south: bounds.getSouth(),
        east: bounds.getEast(),
        north: bounds.getNorth(),
      }, controller.signal, 2000);
      if (controller.signal.aborted || truckRestrictionAbortRef.current !== controller) return;
      truckRestrictionLookupRef.current = truckRestrictionLookup(collection.features);
      truckRestrictionMetadataRef.current = collection.metadata;
      setSource(map, 'rwms-truck-restrictions', truckRestrictionMapData(collection));
      setTruckRestrictionState({
        status: 'loaded',
        count: collection.metadata.count,
        truncated: collection.metadata.truncated,
        error: null,
      });
    } catch (error: unknown) {
      if (controller.signal.aborted || truckRestrictionAbortRef.current !== controller) return;
      truckRestrictionLookupRef.current.clear();
      truckRestrictionMetadataRef.current = null;
      setSource(map, 'rwms-truck-restrictions', EMPTY_COLLECTION);
      setTruckRestrictionState({
        status: 'error',
        count: 0,
        truncated: false,
        error: userFacingErrorDetail(error, 'Не удалось загрузить ограничения грузового транспорта.'),
      });
    } finally {
      if (truckRestrictionAbortRef.current === controller) truckRestrictionAbortRef.current = null;
    }
  }, [layers.truckRestrictions, mapReady]);

  useEffect(() => {
    const map = mapRef.current;
    if (!layers.truckRestrictions) {
      truckRestrictionAbortRef.current?.abort();
      truckRestrictionAbortRef.current = null;
      truckRestrictionPopupRef.current?.remove();
      truckRestrictionPopupRef.current = null;
      truckRestrictionLookupRef.current.clear();
      truckRestrictionMetadataRef.current = null;
      if (map && mapReady) setSource(map, 'rwms-truck-restrictions', EMPTY_COLLECTION);
      setTruckRestrictionState(EMPTY_TRUCK_RESTRICTION_STATE);
      return;
    }
    if (!map || !mapReady) return;

    void loadTruckRestrictions();
    let debounceTimer: ReturnType<typeof setTimeout> | null = null;
    const onMoveEnd = () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => { void loadTruckRestrictions(); }, 250);
    };
    map.on('moveend', onMoveEnd);
    return () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      map.off('moveend', onMoveEnd);
      truckRestrictionAbortRef.current?.abort();
      truckRestrictionAbortRef.current = null;
    };
  }, [layers.truckRestrictions, loadTruckRestrictions, mapReady]);

  useEffect(() => {
    const map = mapRef.current;
    travelTimeContourAbortRef.current?.abort();
    travelTimeContourAbortRef.current = null;
    if (!layers.warehouseIsochrones && !layers.taskIsochrones) {
      if (map && mapReady) setSource(map, TRAVEL_TIME_CONTOUR_SOURCE_ID, EMPTY_COLLECTION);
      setTravelTimeContourState({ status: 'idle' });
      return;
    }
    if (!map || !mapReady) return;
    if (!travelTimeContourOrigins.length) {
      setSource(map, TRAVEL_TIME_CONTOUR_SOURCE_ID, EMPTY_COLLECTION);
      setTravelTimeContourState({ status: 'idle' });
      return;
    }

    const controller = new AbortController();
    travelTimeContourAbortRef.current = controller;
    setTravelTimeContourState({ status: 'loading' });
    void loadTravelTimeContourFeatures(travelTimeContourOrigins, controller.signal).then((features) => {
      if (controller.signal.aborted || travelTimeContourAbortRef.current !== controller) return;
      setSource(map, TRAVEL_TIME_CONTOUR_SOURCE_ID, featureCollection(features));
      setTravelTimeContourState({
        status: 'loaded',
        warehouseCount: travelTimeContourOrigins.filter((origin) => origin.kind === 'DEPOT').length,
        taskCount: travelTimeContourOrigins.filter((origin) => origin.kind === 'TASK').length,
      });
    }).catch((error: unknown) => {
      if (controller.signal.aborted || travelTimeContourAbortRef.current !== controller) return;
      setSource(map, TRAVEL_TIME_CONTOUR_SOURCE_ID, EMPTY_COLLECTION);
      setTravelTimeContourState({
        status: 'unavailable',
        error: userFacingErrorDetail(error, 'Не удалось загрузить изохроны.'),
      });
      controller.abort();
    }).finally(() => {
      if (travelTimeContourAbortRef.current === controller) travelTimeContourAbortRef.current = null;
    });
    return () => {
      controller.abort();
      if (travelTimeContourAbortRef.current === controller) travelTimeContourAbortRef.current = null;
    };
  }, [layers.taskIsochrones, layers.warehouseIsochrones, mapReady, travelTimeContourOrigins]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const onRestrictionClick = (event: maplibregl.MapLayerMouseEvent) => {
      const renderedFeature = event.features?.[0];
      const osmType = featureProperty(renderedFeature, 'osm_type');
      const osmId = featureNumberProperty(renderedFeature, 'osm_id');
      const metadata = truckRestrictionMetadataRef.current;
      if (!osmType || osmId === null || !metadata) return;
      const restriction = truckRestrictionLookupRef.current.get(truckRestrictionKey(osmType, osmId));
      if (!restriction) return;
      event.originalEvent.stopPropagation();
      truckRestrictionPopupRef.current?.remove();
      const popup = new maplibregl.Popup({ closeButton: true, closeOnClick: true, maxWidth: '360px', offset: 12 })
        .setLngLat(event.lngLat)
        .setDOMContent(buildTruckRestrictionPopupContent(restriction, metadata))
        .addTo(map);
      popup.addClassName('truck-restriction-map-popup');
      popup.on('close', () => {
        if (truckRestrictionPopupRef.current === popup) truckRestrictionPopupRef.current = null;
      });
      truckRestrictionPopupRef.current = popup;
    };
    const onPointerEnter = () => { map.getCanvas().style.cursor = 'pointer'; };
    const onPointerLeave = () => { map.getCanvas().style.cursor = ''; };
    TRUCK_RESTRICTION_LAYER_IDS.forEach((layerId) => {
      map.on('mouseenter', layerId, onPointerEnter);
      map.on('mouseleave', layerId, onPointerLeave);
    });
    map.on('click', 'rwms-truck-restrictions-lines', onRestrictionClick);
    map.on('click', 'rwms-truck-restrictions-points', onRestrictionClick);
    return () => {
      TRUCK_RESTRICTION_LAYER_IDS.forEach((layerId) => {
        map.off('mouseenter', layerId, onPointerEnter);
        map.off('mouseleave', layerId, onPointerLeave);
      });
      map.off('click', 'rwms-truck-restrictions-lines', onRestrictionClick);
      map.off('click', 'rwms-truck-restrictions-points', onRestrictionClick);
      map.getCanvas().style.cursor = '';
    };
  }, [mapReady]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const onRequestClick = (event: maplibregl.MapLayerMouseEvent) => {
      const requestId = featureProperty(event.features?.[0], 'requestId');
      if (!requestId) return;
      event.preventDefault();
      event.originalEvent.stopPropagation();
      onSelect({ kind: 'request', id: requestId });
    };
    const onClusterClick = (event: maplibregl.MapLayerMouseEvent) => {
      const renderedFeature = event.features?.[0];
      const clusterId = featureNumberProperty(renderedFeature, 'cluster_id');
      const geometry = renderedFeature?.geometry;
      if (clusterId === null || geometry?.type !== 'Point') return;
      event.preventDefault();
      event.originalEvent.stopPropagation();
      const source = map.getSource<GeoJSONSource>('rwms-requests');
      if (!source) return;
      const center = geometry.coordinates as [number, number];
      void source.getClusterExpansionZoom(clusterId).then((zoom) => {
        map.easeTo({ center, zoom, duration: 350 });
      });
    };
    const onPointerEnter = () => { map.getCanvas().style.cursor = 'pointer'; };
    const onPointerLeave = () => { map.getCanvas().style.cursor = ''; };
    map.on('click', 'rwms-request-points', onRequestClick);
    map.on('click', 'rwms-request-clusters', onClusterClick);
    map.on('mouseenter', 'rwms-request-points', onPointerEnter);
    map.on('mouseleave', 'rwms-request-points', onPointerLeave);
    map.on('mouseenter', 'rwms-request-clusters', onPointerEnter);
    map.on('mouseleave', 'rwms-request-clusters', onPointerLeave);
    return () => {
      map.off('click', 'rwms-request-points', onRequestClick);
      map.off('click', 'rwms-request-clusters', onClusterClick);
      map.off('mouseenter', 'rwms-request-points', onPointerEnter);
      map.off('mouseleave', 'rwms-request-points', onPointerLeave);
      map.off('mouseenter', 'rwms-request-clusters', onPointerEnter);
      map.off('mouseleave', 'rwms-request-clusters', onPointerLeave);
      map.getCanvas().style.cursor = '';
    };
  }, [mapReady, onSelect]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const onClick = (event: maplibregl.MapMouseEvent) => {
      if (event.defaultPrevented) return;
      if (planningCheck?.active) onPlanningCheckPoint({ longitude: event.lngLat.lng, latitude: event.lngLat.lat });
      else if (mapTool === 'ADD_DELIVERY' || mapTool === 'ADD_PICKUP') onPlacePoint('request', event.lngLat.lng, event.lngLat.lat);
      else if (mapTool === 'SELECT') onSelect(null);
    };
    map.on('click', onClick);
    const onRouteClick = (event: maplibregl.MapLayerMouseEvent) => {
      const id = featureProperty(event.features?.[0], 'cycleId');
      if (id) onSelect({ kind: 'cycle', id });
    };
    map.on('click', 'rwms-routes-line', onRouteClick);
    return () => {
      map.off('click', onClick);
      map.off('click', 'rwms-routes-line', onRouteClick);
    };
  }, [mapReady, mapTool, onPlacePoint, onPlanningCheckPoint, onSelect, planningCheck?.active]);

  const selectedCycleId = selected?.kind === 'cycle' ? selected.id : null;
  const selectedDriverShiftId = selected?.kind === 'driver' ? selected.id : null;
  const requestsForPlanningDate = useMemo(
    () => workspace.requests.filter((request) => isRequestVisibleOnDate(request, planningDate)),
    [planningDate, workspace.requests],
  );
  const selectedRequest = useMemo(
    () => selected?.kind === 'request' ? requestsForPlanningDate.find((request) => request.id === selected.id) ?? null : null,
    [requestsForPlanningDate, selected],
  );
  useEffect(() => {
    const map = mapRef.current;
    if (!selectedRequest) {
      pannedRequestIdRef.current = null;
      return;
    }
    if (!map || !mapReady || pannedRequestIdRef.current === selectedRequest.id) return;
    pannedRequestIdRef.current = selectedRequest.id;
    map.easeTo({
      center: [selectedRequest.longitude, selectedRequest.latitude],
      zoom: map.getZoom(),
      duration: 450,
    });
  }, [mapReady, selectedRequest]);
  const allRoutes = useMemo(
    () => routeFeatures(plan, selectedCycleId, selectedDriverShiftId),
    [plan, selectedCycleId, selectedDriverShiftId],
  );
  const routeLegend = useMemo(() => plan?.driver_routes.flatMap((route, driverIndex) => route.cycles.map((cycle, cycleIndex) => ({
    id: cycle.id,
    label: `${route.driver_name} · рейс ${cycle.sequence}`,
    detail: `${cycle.legs.length + (cycle.cross_warehouse_service?.positioning_outbound_geometry ? 1 : 0) + (cycle.cross_warehouse_service?.positioning_return_geometry ? 1 : 0)} участк. · ${cycle.stops.length} точек`,
    color: ROUTE_COLORS[(driverIndex * 3 + cycleIndex) % ROUTE_COLORS.length],
  }))) ?? [], [plan]);
  const unassignedRequestIds = useMemo(
    () => new Set(plan?.unassigned.map((item) => item.request?.id ?? item.task.request_id) ?? []),
    [plan],
  );
  const mappedRequests = useMemo(
    () => requestsForPlanningDate.filter((request) => {
      const visibleByKind = request.type === 'DELIVERY' ? layers.deliveries : layers.pickups;
      const unassigned = request.status === 'UNASSIGNED' || unassignedRequestIds.has(request.id);
      return visibleByKind && (!unassigned || layers.unassigned);
    }),
    [layers.deliveries, layers.pickups, layers.unassigned, requestsForPlanningDate, unassignedRequestIds],
  );
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    setSource(map, 'rwms-requests', featureCollection(requestPointFeatures(mappedRequests, unassignedRequestIds)));
  }, [mapReady, mappedRequests, unassignedRequestIds]);
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    setSource(map, 'rwms-corridor', featureCollection(allRoutes));
    setSource(map, 'rwms-routes', featureCollection(allRoutes));
    setSource(map, 'rwms-candidates', featureCollection(candidateFeatures(traceEvents)));
    setSource(map, 'rwms-selected', featureCollection(
      selected?.kind === 'request' && !selectedRequest
        ? []
        : selectedFeatures(selected, workspace, plan),
    ));

    const { traveled, active } = simulation && plan
      ? deriveSimulationRouteLayers(plan, simulation)
      : { traveled: [], active: [] };
    setSource(map, 'rwms-traveled', featureCollection(traveled));
    setSource(map, 'rwms-active', featureCollection(active));
  }, [allRoutes, mapReady, plan, selected, selectedRequest, simulation, traceEvents, workspace]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || !selectedRequest) return;
    let dragging = false;
    const onDragStart = (event: maplibregl.MapLayerMouseEvent) => {
      if (featureProperty(event.features?.[0], 'requestId') !== selectedRequest.id) return;
      event.originalEvent.preventDefault();
      event.originalEvent.stopPropagation();
      dragging = true;
      map.dragPan.disable();
      map.getCanvas().style.cursor = 'grabbing';
    };
    const onDrag = (event: maplibregl.MapMouseEvent) => {
      if (!dragging) return;
      setSource(map, 'rwms-selected', featureCollection([{
        type: 'Feature',
        properties: { requestId: selectedRequest.id },
        geometry: { type: 'Point', coordinates: [event.lngLat.lng, event.lngLat.lat] },
      }]));
    };
    const onDragEnd = (event: maplibregl.MapMouseEvent) => {
      if (!dragging) return;
      dragging = false;
      map.dragPan.enable();
      map.getCanvas().style.cursor = '';
      onRequestMoveDraft(selectedRequest.id, event.lngLat.lng, event.lngLat.lat);
    };
    map.on('mousedown', 'rwms-selected-request', onDragStart);
    map.on('mousemove', onDrag);
    map.on('mouseup', onDragEnd);
    return () => {
      map.off('mousedown', 'rwms-selected-request', onDragStart);
      map.off('mousemove', onDrag);
      map.off('mouseup', onDragEnd);
      if (dragging) map.dragPan.enable();
      map.getCanvas().style.cursor = '';
    };
  }, [mapReady, onRequestMoveDraft, selectedRequest]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const candidate = planningCheck?.selectedSlot?.best_candidate;
    setSource(map, 'rwms-slot-route-before', geoJsonFeatureCollection(candidate?.route_before_geojson));
    setSource(map, 'rwms-slot-route-after', geoJsonFeatureCollection(candidate?.route_after_geojson));
    setSource(map, 'rwms-slot-pickup-candidates', geoJsonFeatureCollection(candidate?.pickup_candidates_geojson));
  }, [mapReady, planningCheck?.selectedSlot]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || fittedWarehouseIdRef.current === workspace.warehouse.id) return;
    fittedWarehouseIdRef.current = workspace.warehouse.id;
    fittedPlanIdRef.current = null;
    suppressCurrentPlanFitRef.current = true;
    const savedViewport = readMapViewport(workspace.warehouse.id);
    map.easeTo({
      center: savedViewport
        ? [savedViewport.longitude, savedViewport.latitude]
        : [workspace.warehouse.longitude, workspace.warehouse.latitude],
      zoom: savedViewport?.zoom ?? 11,
      duration: 550,
    });
  }, [mapReady, workspace.warehouse.id, workspace.warehouse.latitude, workspace.warehouse.longitude]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    if (!pendingWarehousePoint) {
      fittedPendingWarehouseIdRef.current = null;
      return;
    }
    if (fittedPendingWarehouseIdRef.current === pendingWarehousePoint.id) return;
    fittedPendingWarehouseIdRef.current = pendingWarehousePoint.id;
    map.easeTo({
      center: [pendingWarehousePoint.longitude, pendingWarehousePoint.latitude],
      zoom: 11,
      duration: 550,
    });
  }, [mapReady, pendingWarehousePoint]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || !plan || fittedPlanIdRef.current === plan.id) return;
    if (plan.warehouse_id !== workspace.warehouse.id) {
      fittedPlanIdRef.current = plan.id;
      return;
    }
    if (suppressCurrentPlanFitRef.current) {
      suppressCurrentPlanFitRef.current = false;
      fittedPlanIdRef.current = plan.id;
      return;
    }
    const bounds = new maplibregl.LngLatBounds();
    let pointCount = 0;
    allRoutes.forEach((feature) => feature.geometry.coordinates.forEach((coordinates) => {
      bounds.extend(coordinates as [number, number]);
      pointCount += 1;
    }));
    if (pointCount >= 2) {
      fittedPlanIdRef.current = plan.id;
      map.fitBounds(bounds, { padding: cameraPaddingRef.current ?? { top: 74, bottom: 74, left: 74, right: 290 }, maxZoom: 12, duration: 450 });
    }
  }, [allRoutes, mapReady, plan, workspace.warehouse.id]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    markersRef.current.forEach((marker) => marker.remove());
    const markers: Marker[] = [];
    if (layers.warehouse || planningCheck?.active) {
      workspace.warehouses.forEach((warehouse) => {
        const element = markerElement('warehouse', `Склад: ${warehouse.name}`, selected?.kind === 'warehouse' && selected.id === warehouse.id);
        element.addEventListener('click', (event) => {
          event.stopPropagation();
          onSelect({ kind: 'warehouse', id: warehouse.id });
        });
        markers.push(new maplibregl.Marker({ element, anchor: 'center' }).setLngLat([warehouse.longitude, warehouse.latitude]).addTo(map));
      });
    }
    if (pendingWarehousePoint) {
      const element = markerElement('warehouse', pendingWarehousePoint.label, false);
      element.classList.add('map-marker--planning-check');
      const markerLabel = element.querySelector('span');
      if (markerLabel) markerLabel.textContent = 'Н';
      markers.push(new maplibregl.Marker({ element, anchor: 'bottom' })
        .setLngLat([pendingWarehousePoint.longitude, pendingWarehousePoint.latitude])
        .setPopup(new maplibregl.Popup({ offset: 18 }).setText(pendingWarehousePoint.label))
        .addTo(map));
    }
    if (planningCheck?.active && planningCheck.point) {
      const element = markerElement('delivery', 'Новый адрес для проверки слотов', false);
      element.classList.add('map-marker--planning-check');
      const markerLabel = element.querySelector('span');
      if (markerLabel) markerLabel.textContent = 'Н';
      markers.push(new maplibregl.Marker({ element, anchor: 'bottom' })
        .setLngLat([planningCheck.point.longitude, planningCheck.point.latitude])
        .setPopup(new maplibregl.Popup({ offset: 18 }).setText('Новый адрес для проверки слотов'))
        .addTo(map));
    }
    if (layers.trucks && simulation) {
      simulation.vehicles.forEach((vehicle) => {
        const coordinates = vehicle.position.geometry.coordinates;
        const vehicleLabel = simulationVehicleMapLabel(vehicle, workspace.warehouse.timezone);
        const element = markerElement('truck', vehicleLabel, selected?.kind === 'driver' && selected.id === vehicle.driver_shift_id);
        element.addEventListener('click', (event) => { event.stopPropagation(); onSelect({ kind: 'driver', id: vehicle.driver_shift_id }); });
        const popup = new maplibregl.Popup({ offset: 18 }).setText(vehicleLabel);
        markers.push(new maplibregl.Marker({ element, anchor: 'center' }).setLngLat(coordinates as [number, number]).setPopup(popup).addTo(map));
      });
    }
    markersRef.current = markers;
    return () => markers.forEach((marker) => marker.remove());
  }, [layers.trucks, layers.warehouse, mapReady, onSelect, pendingWarehousePoint, planningCheck?.active, planningCheck?.point, selected, simulation, workspace.warehouse.id, workspace.warehouse.timezone, workspace.warehouses]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const visibility: Array<[string, boolean]> = [
      ['rwms-candidates-line', layers.candidates],
      ['rwms-corridor-line', layers.corridor],
      ['rwms-routes-halo', layers.routes],
      ['rwms-routes-line', layers.routes],
      ['rwms-traveled-line', layers.traveled],
      ['rwms-active-line', layers.activeLeg],
      ['rwms-request-clusters', layers.deliveries || layers.pickups],
      ['rwms-request-cluster-count', layers.deliveries || layers.pickups],
      ['rwms-request-points', layers.deliveries || layers.pickups],
      ['rwms-request-labels', layers.deliveries || layers.pickups],
      ['rwms-selected-fill', layers.selected],
      ['rwms-selected-line', layers.selected],
      ['rwms-selected-request', layers.selected],
      ['rwms-truck-restrictions-lines', layers.truckRestrictions],
      ['rwms-truck-restrictions-points', layers.truckRestrictions],
      ['rwms-truck-restrictions-line-labels', layers.truckRestrictions],
      ['rwms-truck-restrictions-point-labels', layers.truckRestrictions],
      ...WAREHOUSE_TRAVEL_TIME_CONTOUR_LAYER_IDS.map((id): [string, boolean] => [id, layers.warehouseIsochrones]),
      ...TASK_TRAVEL_TIME_CONTOUR_LAYER_IDS.map((id): [string, boolean] => [id, layers.taskIsochrones && selectedTaskContourOrigin !== null]),
      ['rwms-slot-route-before-line', Boolean(planningCheck?.active && planningCheck.layers.routeBefore)],
      ['rwms-slot-route-after-line', Boolean(planningCheck?.active && planningCheck.layers.routeAfter)],
      ['rwms-slot-pickup-candidates-points', Boolean(planningCheck?.active && planningCheck.layers.pickupCandidates)],
      ['rwms-slot-pickup-candidates-lines', Boolean(planningCheck?.active && planningCheck.layers.pickupCandidates)],
    ];
    visibility.forEach(([id, visible]) => map.getLayer(id) && map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none'));
    const overlayIds = new Set(visibility.map(([id]) => id));
    map.getStyle().layers.forEach((layer) => {
      if (!overlayIds.has(layer.id) && !layer.id.startsWith('td-')) map.setLayoutProperty(layer.id, 'visibility', layers.base ? 'visible' : 'none');
    });
  }, [layers, mapReady, planningCheck?.active, planningCheck?.layers, selectedTaskContourOrigin]);

  const toolButton = useCallback((tool: MapTool, label: string, icon: React.ReactNode) => (
    <button type="button" aria-label={label} title={label} aria-pressed={mapTool === tool} onClick={() => setMapTool(tool)}>{icon}</button>
  ), [mapTool, setMapTool]);
  return (
    <main className="map-stage" data-testid="map-stage">
      <div className="map-container" ref={containerRef} aria-label="Интерактивная логистическая карта" />
      <div className="map-overlay map-toolbar" role="toolbar" aria-label="Инструменты карты">
        {toolButton('SELECT', 'Выбрать объект', <MousePointer2 size={17} aria-hidden="true" />)}
        {toolButton('ADD_DELIVERY', 'Добавить доставку', <MapPin size={17} aria-hidden="true" />)}
        {toolButton('ADD_PICKUP', 'Добавить вывоз', <Truck size={17} aria-hidden="true" />)}
        <button type="button" aria-label="Слои карты" title="Слои карты" aria-pressed={layersOpen} onClick={() => setLayersOpen((open) => !open)}><Layers3 size={17} aria-hidden="true" /></button>
        <WarehouseActivationControl currentWarehouseId={workspace.warehouse.id} warehouses={workspace.warehouses} selected={selected} onActivate={onWarehouseActivate} />
      </div>
      {(layersOpen || (layers.routes && routeLegend.length > 0)) ? (
        <div className="map-overlay map-overlay-stack">
          {layers.routes && routeLegend.length ? (
            <div className="route-legend" aria-label="Все участки построенного плана">
              <strong>Полные маршруты</strong>
              <small>Каждая строка — весь цикл, включая возврат на склад.</small>
              {routeLegend.map((route) => <button type="button" key={route.id} onClick={() => onSelect({ kind: 'cycle', id: route.id })}>
                <i style={{ background: route.color }} />
                <span>{route.label}<small>{route.detail}</small></span>
              </button>)}
            </div>
          ) : null}
          {layersOpen ? (
            <div className="layer-menu" aria-label="Видимость слоёв">
              <h3>Слои карты</h3>
              {(Object.keys(layerLabels) as Array<keyof LayerVisibility>).map((layer) => layer === 'truckRestrictions' ? (
                <TruckRestrictionLayerMenuItem
                  key={layer}
                  checked={layers.truckRestrictions}
                  state={truckRestrictionState}
                  onChange={() => toggleLayer('truckRestrictions')}
                />
              ) : (
                <CheckboxField key={layer} label={layerLabels[layer]} checked={layers[layer]} onChange={() => toggleLayer(layer)} />
              ))}
              {(layers.warehouseIsochrones || layers.taskIsochrones) ? (
                <div className="contour-layer-status" role="status" aria-live="polite">
                  {travelTimeContourState.status === 'loading' ? <small>Загружаем изохроны…</small> : null}
                  {travelTimeContourState.status === 'unavailable' ? <small>Изохроны недоступны: {travelTimeContourState.error}</small> : null}
                  {layers.taskIsochrones && !selectedTaskContourOrigin ? <small>Для изохрона задания нажмите на задание или поставьте точку проверки.</small> : null}
                  {travelTimeContourState.status === 'loaded' ? <small>Складов: {travelTimeContourState.warehouseCount} · заданий: {travelTimeContourState.taskCount}</small> : null}
                  {travelTimeContourState.status === 'loaded' ? <span>{travelTimeContourStyles(workspace.warehouse.isochrone_tariffs.map((tariff) => tariff.travel_minutes)).map((style) => <i key={style.minutes} title={style.label} style={{ background: style.color }} />)}</span> : null}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}
      {selectedRequest && mapReady && mapRef.current ? (
        <RequestMapPopup
          map={mapRef.current}
          request={selectedRequest}
          plan={plan}
          planningDate={planningDate}
          busy={busy}
          onSchedule={onScheduleRequestDate}
          onUnschedule={onUnscheduleRequest}
          onMoveTask={onMoveTask}
          onClose={() => onSelect(null)}
          warehouses={workspace.warehouses}
        />
      ) : null}
      {!mapReady ? <div className="map-overlay progress-card"><span className="spinner">Карта запускается…</span></div> : null}
      {optimizationRun && ['PENDING', 'RUNNING'].includes(optimizationRun.status) ? (
        <div className="progress-card" role="status" data-testid="optimization-progress">
          <div className="progress-card__line"><strong>{optimizationRun.phase ?? 'VALIDATING_INPUT'}</strong><span>{Math.round((optimizationRun.progress ?? 0) * 100)}%</span></div>
          <div className="progress-track"><i style={{ width: `${Math.max(3, (optimizationRun.progress ?? 0) * 100)}%` }} /></div>
          <small>seed {optimizationRun.seed} · события поиска {traceEvents.length}</small>
        </div>
      ) : null}
    </main>
  );
}
