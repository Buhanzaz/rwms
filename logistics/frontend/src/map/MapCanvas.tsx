import {
  TerraDraw,
  TerraDrawPolygonMode,
  TerraDrawSelectMode,
  ValidateNotSelfIntersecting,
  type GeoJSONStoreFeatures,
} from 'terra-draw';
import { TerraDrawMapLibreGLAdapter } from 'terra-draw-maplibre-gl-adapter';
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap, type Marker } from 'maplibre-gl';
import {
  Box,
  Crosshair,
  GitFork,
  Layers3,
  MapPin,
  MousePointer2,
  Pencil,
  Truck,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  Feature,
  FeatureCollection,
  LineString,
  MultiPolygon,
  Polygon,
} from 'geojson';
import type {
  MapSelection,
  OptimizationTraceEvent,
  OptimizationRun,
  RoutePlan,
  ScenarioWorkspace,
  SimulationDerivedState,
  UUID,
  Zone,
} from '../domain/types';
import { CheckboxField } from '../components/ui';
import { useUiStore, type LayerVisibility, type MapTool } from '../stores/ui-store';
import { formatDate, nextDate } from '../utils/format';

const BLANK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  name: 'RWMS Offline Grid',
  sources: {},
  layers: [],
};

const EMPTY_COLLECTION: FeatureCollection = { type: 'FeatureCollection', features: [] };
const SOURCE_IDS = ['rwms-zones', 'rwms-corridor', 'rwms-routes', 'rwms-traveled', 'rwms-active', 'rwms-candidates', 'rwms-selected'] as const;
const ROUTE_COLORS = ['#5ee2b2', '#60a5fa', '#fb923c', '#c084fc', '#facc15', '#22d3ee'];

const layerLabels: Record<keyof LayerVisibility, string> = {
  base: 'Базовая карта / сетка',
  zones: 'Заливка зон',
  zoneBorders: 'Границы зон',
  warehouse: 'Склад',
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
};

interface MapCanvasProps {
  workspace: ScenarioWorkspace;
  plan: RoutePlan | null;
  simulation: SimulationDerivedState | null;
  traceEvents: OptimizationTraceEvent[];
  selected: MapSelection;
  onSelect: (selection: MapSelection) => void;
  onPlacePoint: (kind: 'warehouse' | 'request', longitude: number, latitude: number) => void;
  onZoneDrawn: (geometry: Polygon) => void;
  onZoneGeometryChanged: (zoneId: UUID, geometry: Polygon | MultiPolygon) => void;
  onMapError: (message: string) => void;
  optimizationRun: OptimizationRun | null;
  onRequestMoveDraft: (requestId: UUID, longitude: number, latitude: number) => void;
  onZoneRelation: (fromId: UUID, toId: UUID) => void;
  planningDate: string;
  onAgreeRequestDate: (requestId: UUID, date: string) => void;
}

function featureCollection(features: Feature[]): FeatureCollection {
  return { type: 'FeatureCollection', features };
}

function zoneFeatures(zones: Zone[]): Feature[] {
  return zones.map((zone) => ({
    type: 'Feature',
    id: zone.id,
    properties: {
      id: zone.id,
      name: zone.name,
      code: zone.code,
      group: zone.route_group,
      locked: zone.locked,
      priority: zone.priority,
    },
    geometry: zone.geometry,
  }));
}

function routeFeatures(
  plan: RoutePlan | null,
  selectedCycleId: UUID | null,
  selectedDriverShiftId: UUID | null,
): Feature<LineString>[] {
  if (!plan) return [];
  return plan.driver_routes.flatMap((driverRoute, driverIndex) =>
    driverRoute.cycles.flatMap((cycle, cycleIndex) =>
      cycle.legs.map((leg, legIndex) => ({
        ...leg.geometry,
        properties: {
          cycleId: cycle.id,
          driver: driverRoute.driver_name,
          driverIndex,
          color: ROUTE_COLORS[(driverIndex * 3 + cycleIndex) % ROUTE_COLORS.length],
          isSelected: selectedCycleId === cycle.id || selectedDriverShiftId === driverRoute.driver_shift_id,
          hasSelectedCycle: selectedCycleId !== null || selectedDriverShiftId !== null,
          label: `${driverRoute.driver_name} · рейс ${cycle.sequence} · участок ${legIndex + 1}/${cycle.legs.length}`,
          legIndex,
        },
      })),
    ),
  );
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

function selectedFeatures(selection: MapSelection, workspace: ScenarioWorkspace, plan: RoutePlan | null): Feature[] {
  if (!selection) return [];
  if (selection.kind === 'zone') {
    const zone = workspace.zones.find((candidate) => candidate.id === selection.id);
    return zone ? [{ type: 'Feature', properties: {}, geometry: zone.geometry }] : [];
  }
  if (selection.kind === 'cycle' && plan) {
    const cycle = plan.driver_routes.flatMap((route) => route.cycles).find((candidate) => candidate.id === selection.id);
    return cycle?.legs.map((leg) => leg.geometry) ?? [];
  }
  return [];
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

function addOverlaySources(map: MapLibreMap): void {
  for (const id of SOURCE_IDS) {
    if (!map.getSource(id)) map.addSource(id, { type: 'geojson', data: EMPTY_COLLECTION });
  }
  const addLayer = (layer: maplibregl.LayerSpecification) => {
    if (!map.getLayer(layer.id)) map.addLayer(layer);
  };
  addLayer({
    id: 'rwms-zones-fill', type: 'fill', source: 'rwms-zones',
    paint: { 'fill-color': ['case', ['==', ['get', 'group'], 'WEST'], '#60a5fa', ['==', ['get', 'group'], 'EAST'], '#fb923c', ['==', ['get', 'group'], 'REGION'], '#a78bfa', '#5ee2b2'], 'fill-opacity': 0.16 },
  });
  addLayer({ id: 'rwms-zones-line', type: 'line', source: 'rwms-zones', paint: { 'line-color': '#8ba8c7', 'line-width': 1.5, 'line-opacity': 0.75 } });
  addLayer({ id: 'rwms-candidates-line', type: 'line', source: 'rwms-candidates', paint: { 'line-color': ['case', ['get', 'rejected'], '#fb7185', '#fbbf24'], 'line-width': 2, 'line-opacity': 0.48, 'line-dasharray': [2, 2] } });
  addLayer({ id: 'rwms-corridor-line', type: 'line', source: 'rwms-corridor', paint: { 'line-color': '#38bdf8', 'line-width': 18, 'line-opacity': 0.1 } });
  addLayer({ id: 'rwms-routes-halo', type: 'line', source: 'rwms-routes', paint: { 'line-color': ['case', ['get', 'isSelected'], '#f8fafc', '#06111d'], 'line-width': ['case', ['get', 'isSelected'], 10, 8], 'line-opacity': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], 0.9, 0.28], 0.58] } });
  addLayer({ id: 'rwms-routes-line', type: 'line', source: 'rwms-routes', paint: { 'line-color': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], ['get', 'color'], '#94a3b8'], ['get', 'color']], 'line-width': ['case', ['get', 'isSelected'], 6, 4.5], 'line-opacity': ['case', ['get', 'hasSelectedCycle'], ['case', ['get', 'isSelected'], 1, 0.28], 0.9] } });
  addLayer({ id: 'rwms-traveled-line', type: 'line', source: 'rwms-traveled', paint: { 'line-color': '#cbd5e1', 'line-width': 5, 'line-opacity': 0.82 } });
  addLayer({ id: 'rwms-active-line', type: 'line', source: 'rwms-active', paint: { 'line-color': '#fbbf24', 'line-width': 7, 'line-opacity': 0.95 } });
  addLayer({ id: 'rwms-selected-fill', type: 'fill', source: 'rwms-selected', filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': '#fbbf24', 'fill-opacity': 0.22 } });
  addLayer({ id: 'rwms-selected-line', type: 'line', source: 'rwms-selected', paint: { 'line-color': '#fbbf24', 'line-width': 6, 'line-opacity': 0.9 } });
}

function splitZoneForDraw(zone: Zone): GeoJSONStoreFeatures<Polygon>[] {
  if (zone.geometry.type === 'Polygon') {
    return [{ type: 'Feature', id: `${zone.id}:0`, properties: { mode: 'polygon', zoneId: zone.id, part: 0 }, geometry: zone.geometry }];
  }
  return zone.geometry.coordinates.map((coordinates, part) => ({
    type: 'Feature',
    id: `${zone.id}:${part}`,
    properties: { mode: 'polygon', zoneId: zone.id, part },
    geometry: { type: 'Polygon', coordinates },
  }));
}

function recombineZone(zone: Zone, part: number, polygon: Polygon): Polygon | MultiPolygon {
  if (zone.geometry.type === 'Polygon') return polygon;
  const coordinates = zone.geometry.coordinates.map((value, index) => index === part ? polygon.coordinates : value);
  return { type: 'MultiPolygon', coordinates };
}

export function MapCanvas({
  workspace,
  plan,
  simulation,
  traceEvents,
  selected,
  onSelect,
  onPlacePoint,
  onZoneDrawn,
  onZoneGeometryChanged,
  onMapError,
  optimizationRun,
  onRequestMoveDraft,
  onZoneRelation,
  planningDate,
  onAgreeRequestDate,
}: MapCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const drawRef = useRef<TerraDraw | null>(null);
  const markersRef = useRef<Marker[]>([]);
  const hydratedRef = useRef(false);
  const fittedPlanIdRef = useRef<UUID | null>(null);
  const zonesRef = useRef(workspace.zones);
  const [mapReady, setMapReady] = useState(false);
  const [offlineMode, setOfflineMode] = useState(!import.meta.env.VITE_MAP_STYLE_URL);
  const [layersOpen, setLayersOpen] = useState(false);
  const mapTool = useUiStore((state) => state.mapTool);
  const setMapTool = useUiStore((state) => state.setMapTool);
  const relationSourceZoneId = useUiStore((state) => state.relationSourceZoneId);
  const setRelationSourceZoneId = useUiStore((state) => state.setRelationSourceZoneId);
  const layers = useUiStore((state) => state.layers);
  const toggleLayer = useUiStore((state) => state.toggleLayer);
  const styleUrl = import.meta.env.VITE_MAP_STYLE_URL;
  zonesRef.current = workspace.zones;

  const onZoneDrawnRef = useRef(onZoneDrawn);
  const onZoneGeometryChangedRef = useRef(onZoneGeometryChanged);
  onZoneDrawnRef.current = onZoneDrawn;
  onZoneGeometryChangedRef.current = onZoneGeometryChanged;

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    let fellBack = false;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: styleUrl || BLANK_STYLE,
      center: [37.6176, 55.7558],
      zoom: 8.6,
      attributionControl: false,
      canvasContextAttributes: { preserveDrawingBuffer: true },
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right');
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-left');

    const prepare = () => {
      addOverlaySources(map);
      setMapReady(true);
    };
    map.on('style.load', prepare);
    map.on('error', (event: unknown) => {
      const errorValue = event && typeof event === 'object' && 'error' in event ? event.error : null;
      const message = errorValue instanceof Error ? errorValue.message : 'неизвестная ошибка рендеринга';
      if (styleUrl && !map.isStyleLoaded() && !fellBack) {
        fellBack = true;
        setOfflineMode(true);
        setMapReady(false);
        onMapError('Стиль карты не загрузился — включён автономный координатный фон. Рисование и маршруты доступны.');
        map.setStyle(BLANK_STYLE);
      } else if (!message.includes('Failed to fetch')) {
        onMapError(`Ошибка карты: ${message}`);
      }
    });

    return () => {
      drawRef.current?.stop();
      drawRef.current = null;
      markersRef.current.forEach((marker) => marker.remove());
      markersRef.current = [];
      map.remove();
      mapRef.current = null;
    };
  }, [onMapError, styleUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || drawRef.current) return;
    const adapterOptions: ConstructorParameters<typeof TerraDrawMapLibreGLAdapter<MapLibreMap>>[0] & { lib: typeof maplibregl } = {
      map,
      lib: maplibregl,
    };
    const draw = new TerraDraw({
      adapter: new TerraDrawMapLibreGLAdapter(adapterOptions),
      modes: [
        new TerraDrawPolygonMode({ showCoordinatePoints: true, validation: ValidateNotSelfIntersecting }),
        new TerraDrawSelectMode({
          flags: {
            polygon: {
              feature: {
                draggable: true,
                coordinates: { midpoints: true, draggable: true, deletable: true },
              },
            },
          },
        }),
      ],
    });
    draw.start();
    draw.setMode('select');
    const onFinish: Parameters<typeof draw.on<'finish'>>[1] = (id, context) => {
      if (hydratedRef.current) return;
      const feature = draw.getSnapshotFeature(id);
      if (!feature || feature.geometry.type !== 'Polygon') return;
      if (context.action === 'draw') {
        onZoneDrawnRef.current(feature.geometry);
        draw.removeFeatures([id]);
        draw.setMode('polygon');
        return;
      }
      const zoneId = feature.properties.zoneId;
      const part = feature.properties.part;
      if (typeof zoneId !== 'string' || typeof part !== 'number') return;
      const zone = zonesRef.current.find((candidate) => candidate.id === zoneId);
      if (zone && !zone.locked) onZoneGeometryChangedRef.current(zone.id, recombineZone(zone, part, feature.geometry));
    };
    draw.on('finish', onFinish);
    drawRef.current = draw;
    return () => {
      draw.off('finish', onFinish);
      draw.stop();
      drawRef.current = null;
    };
  }, [mapReady]);

  useEffect(() => {
    const draw = drawRef.current;
    if (!draw) return;
    hydratedRef.current = true;
    draw.clear();
    if (mapTool === 'EDIT_ZONE') {
      const features = workspace.zones.filter((zone) => !zone.locked).flatMap(splitZoneForDraw);
      if (features.length) draw.addFeatures(features);
      draw.setMode('select');
    } else if (mapTool === 'DRAW_ZONE') {
      draw.setMode('polygon');
    } else {
      draw.setMode('select');
    }
    queueMicrotask(() => { hydratedRef.current = false; });
  }, [mapTool, workspace.zones]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const onClick = (event: maplibregl.MapMouseEvent) => {
      if (mapTool === 'PLACE_WAREHOUSE') onPlacePoint('warehouse', event.lngLat.lng, event.lngLat.lat);
      else if (mapTool === 'ADD_DELIVERY' || mapTool === 'ADD_PICKUP') onPlacePoint('request', event.lngLat.lng, event.lngLat.lat);
      else if (mapTool === 'SELECT') onSelect(null);
    };
    map.on('click', onClick);
    const onZoneClick = (event: maplibregl.MapLayerMouseEvent) => {
      const id = featureProperty(event.features?.[0], 'id');
      if (!id) return;
      if (mapTool === 'RELATE_ZONES') {
        if (!relationSourceZoneId) {
          setRelationSourceZoneId(id);
          onSelect({ kind: 'zone', id });
        } else if (relationSourceZoneId !== id) {
          onZoneRelation(relationSourceZoneId, id);
          setRelationSourceZoneId(null);
          setMapTool('SELECT');
        }
        return;
      }
      onSelect({ kind: 'zone', id });
    };
    const onRouteClick = (event: maplibregl.MapLayerMouseEvent) => {
      const id = featureProperty(event.features?.[0], 'cycleId');
      if (id) onSelect({ kind: 'cycle', id });
    };
    map.on('click', 'rwms-zones-fill', onZoneClick);
    map.on('click', 'rwms-routes-line', onRouteClick);
    return () => {
      map.off('click', onClick);
      map.off('click', 'rwms-zones-fill', onZoneClick);
      map.off('click', 'rwms-routes-line', onRouteClick);
    };
  }, [mapReady, mapTool, onPlacePoint, onSelect, onZoneRelation, relationSourceZoneId, setMapTool, setRelationSourceZoneId]);

  useEffect(() => {
    if (mapTool !== 'RELATE_ZONES' && relationSourceZoneId) setRelationSourceZoneId(null);
  }, [mapTool, relationSourceZoneId, setRelationSourceZoneId]);

  const selectedCycleId = selected?.kind === 'cycle' ? selected.id : null;
  const selectedDriverShiftId = selected?.kind === 'driver' ? selected.id : null;
  const requestsForPlanningDate = useMemo(
    () => workspace.requests.filter((request) => request.date_options.some((option) => option.date === planningDate)),
    [planningDate, workspace.requests],
  );
  const selectedRequest = useMemo(
    () => selected?.kind === 'request' ? requestsForPlanningDate.find((request) => request.id === selected.id) ?? null : null,
    [requestsForPlanningDate, selected],
  );
  const allRoutes = useMemo(
    () => routeFeatures(plan, selectedCycleId, selectedDriverShiftId),
    [plan, selectedCycleId, selectedDriverShiftId],
  );
  const routeLegend = useMemo(() => plan?.driver_routes.flatMap((route, driverIndex) => route.cycles.map((cycle, cycleIndex) => ({
    id: cycle.id,
    label: `${route.driver_name} · рейс ${cycle.sequence}`,
    detail: `${cycle.legs.length} участк. · ${cycle.stops.length} точек`,
    color: ROUTE_COLORS[(driverIndex * 3 + cycleIndex) % ROUTE_COLORS.length],
  }))) ?? [], [plan]);
  const unassignedRequestIds = useMemo(
    () => new Set(plan?.unassigned.map((item) => item.request?.id ?? item.task.request_id) ?? []),
    [plan],
  );
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    setSource(map, 'rwms-zones', featureCollection(zoneFeatures(workspace.zones)));
    setSource(map, 'rwms-corridor', featureCollection(allRoutes));
    setSource(map, 'rwms-routes', featureCollection(allRoutes));
    setSource(map, 'rwms-candidates', featureCollection(candidateFeatures(traceEvents)));
    setSource(map, 'rwms-selected', featureCollection(selectedFeatures(selected, workspace, plan)));

    const traveled: Feature<LineString>[] = [];
    const active: Feature<LineString>[] = [];
    if (simulation && plan) {
      for (const vehicle of simulation.vehicles) {
        const route = plan.driver_routes.find((candidate) => candidate.driver_shift_id === vehicle.driver_shift_id);
        const cycle = route?.cycles.find((candidate) => candidate.id === vehicle.active_cycle_id);
        if (!cycle) continue;
        cycle.legs.forEach((leg, index) => {
          if (vehicle.active_leg_index !== null && index < vehicle.active_leg_index) traveled.push(leg.geometry);
          if (index === vehicle.active_leg_index) active.push(leg.geometry);
        });
      }
    }
    setSource(map, 'rwms-traveled', featureCollection(traveled));
    setSource(map, 'rwms-active', featureCollection(active));
  }, [allRoutes, mapReady, plan, selected, simulation, traceEvents, workspace]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || !plan || fittedPlanIdRef.current === plan.id) return;
    const bounds = new maplibregl.LngLatBounds();
    let pointCount = 0;
    allRoutes.forEach((feature) => feature.geometry.coordinates.forEach((coordinates) => {
      bounds.extend(coordinates as [number, number]);
      pointCount += 1;
    }));
    if (pointCount >= 2) {
      fittedPlanIdRef.current = plan.id;
      map.fitBounds(bounds, { padding: { top: 74, bottom: 74, left: 74, right: 290 }, maxZoom: 12, duration: 450 });
    }
  }, [allRoutes, mapReady, plan]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    markersRef.current.forEach((marker) => marker.remove());
    const markers: Marker[] = [];
    if (layers.warehouse) {
      workspace.warehouses.forEach((warehouse) => {
        const element = markerElement('warehouse', `Склад: ${warehouse.name}`, selected?.kind === 'warehouse' && selected.id === warehouse.id);
        element.addEventListener('click', (event) => { event.stopPropagation(); onSelect({ kind: 'warehouse', id: warehouse.id }); });
        markers.push(new maplibregl.Marker({ element, anchor: 'center' }).setLngLat([warehouse.longitude, warehouse.latitude]).addTo(map));
      });
    }
    requestsForPlanningDate.forEach((request) => {
      const visible = request.type === 'DELIVERY' ? layers.deliveries : layers.pickups;
      const isUnassigned = request.status === 'UNASSIGNED' || unassignedRequestIds.has(request.id);
      if (!visible || (isUnassigned && !layers.unassigned)) return;
      const element = markerElement(request.type === 'DELIVERY' ? 'delivery' : 'pickup', `${request.type === 'DELIVERY' ? 'Доставка' : 'Вывоз'}: ${request.name}`, selected?.kind === 'request' && selected.id === request.id);
      if (isUnassigned) {
        element.classList.add('map-marker--unassigned');
        element.style.borderColor = '#fb7185';
        element.setAttribute('aria-label', `Нераспределённая заявка: ${request.name}`);
        const markerLabel = element.querySelector('span');
        if (markerLabel) markerLabel.textContent = `${request.type === 'DELIVERY' ? 'Д' : 'В'}!`;
      }
      element.addEventListener('click', (event) => { event.stopPropagation(); onSelect({ kind: 'request', id: request.id }); });
      const marker = new maplibregl.Marker({ element, anchor: 'bottom', draggable: selected?.kind === 'request' && selected.id === request.id }).setLngLat([request.longitude, request.latitude]).addTo(map);
      marker.on('dragend', () => {
        const point = marker.getLngLat();
        onRequestMoveDraft(request.id, point.lng, point.lat);
      });
      markers.push(marker);
    });
    if (layers.trucks && simulation) {
      simulation.vehicles.forEach((vehicle) => {
        const coordinates = vehicle.position.geometry.coordinates;
        const element = markerElement('truck', `${vehicle.driver_name}, загрузка ${vehicle.load}, ${vehicle.status}`, selected?.kind === 'driver' && selected.id === vehicle.driver_shift_id);
        element.addEventListener('click', (event) => { event.stopPropagation(); onSelect({ kind: 'driver', id: vehicle.driver_shift_id }); });
        const popup = new maplibregl.Popup({ offset: 18 }).setText(`${vehicle.driver_name} · ${vehicle.registration_number} · загрузка ${vehicle.load} · ${vehicle.status}`);
        markers.push(new maplibregl.Marker({ element, anchor: 'center' }).setLngLat(coordinates as [number, number]).setPopup(popup).addTo(map));
      });
    }
    markersRef.current = markers;
    return () => markers.forEach((marker) => marker.remove());
  }, [layers.deliveries, layers.pickups, layers.trucks, layers.unassigned, layers.warehouse, mapReady, onRequestMoveDraft, onSelect, requestsForPlanningDate, selected, simulation, unassignedRequestIds, workspace.warehouses]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    const visibility: Array<[string, boolean]> = [
      ['rwms-zones-fill', layers.zones],
      ['rwms-zones-line', layers.zoneBorders],
      ['rwms-candidates-line', layers.candidates],
      ['rwms-corridor-line', layers.corridor],
      ['rwms-routes-halo', layers.routes],
      ['rwms-routes-line', layers.routes],
      ['rwms-traveled-line', layers.traveled],
      ['rwms-active-line', layers.activeLeg],
      ['rwms-selected-fill', layers.selected],
      ['rwms-selected-line', layers.selected],
    ];
    visibility.forEach(([id, visible]) => map.getLayer(id) && map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none'));
    const overlayIds = new Set(visibility.map(([id]) => id));
    map.getStyle().layers.forEach((layer) => {
      if (!overlayIds.has(layer.id) && !layer.id.startsWith('td-')) map.setLayoutProperty(layer.id, 'visibility', layers.base ? 'visible' : 'none');
    });
  }, [layers, mapReady]);

  const toolButton = useCallback((tool: MapTool, label: string, icon: React.ReactNode) => (
    <button type="button" aria-label={label} title={label} aria-pressed={mapTool === tool} onClick={() => setMapTool(tool)}>{icon}</button>
  ), [mapTool, setMapTool]);

  return (
    <main className="map-stage" data-testid="map-stage">
      <div className="map-container" ref={containerRef} aria-label="Интерактивная логистическая карта" />
      <div className="map-overlay map-toolbar" role="toolbar" aria-label="Инструменты карты">
        {toolButton('SELECT', 'Выбрать объект', <MousePointer2 size={17} />)}
        {toolButton('PLACE_WAREHOUSE', 'Поставить склад', <Box size={17} />)}
        {toolButton('ADD_DELIVERY', 'Добавить доставку', <MapPin size={17} />)}
        {toolButton('ADD_PICKUP', 'Добавить вывоз', <Truck size={17} />)}
        {toolButton('DRAW_ZONE', 'Нарисовать зону', <Crosshair size={17} />)}
        {toolButton('EDIT_ZONE', 'Редактировать вершины зон', <Pencil size={17} />)}
        {toolButton('RELATE_ZONES', 'Связать две зоны', <GitFork size={17} />)}
        <button type="button" aria-label="Слои карты" title="Слои карты" aria-pressed={layersOpen} onClick={() => setLayersOpen((open) => !open)}><Layers3 size={17} /></button>
      </div>
      {layersOpen ? (
        <div className="map-overlay layer-menu" aria-label="Видимость слоёв">
          <h3>Слои карты</h3>
          {(Object.keys(layerLabels) as Array<keyof LayerVisibility>).map((layer) => (
            <CheckboxField key={layer} label={layerLabels[layer]} checked={layers[layer]} onChange={() => toggleLayer(layer)} />
          ))}
        </div>
      ) : null}
      {layers.routes && routeLegend.length ? (
        <div className="map-overlay route-legend" aria-label="Все участки построенного плана">
          <strong>Полные маршруты</strong>
          <small>Каждая строка — весь цикл, включая возврат на склад.</small>
          {routeLegend.map((route) => <button type="button" key={route.id} onClick={() => onSelect({ kind: 'cycle', id: route.id })}>
            <i style={{ background: route.color }} />
            <span>{route.label}<small>{route.detail}</small></span>
          </button>)}
        </div>
      ) : null}
      {selectedRequest ? (
        <section className="map-overlay request-map-menu" aria-label="Заявка на карте" data-testid="request-map-menu">
          <div className="request-map-menu__head">
            <strong>{selectedRequest.type === 'DELIVERY' ? 'Д · доставка' : 'В · возврат'}</strong>
            <button type="button" aria-label="Закрыть карточку заявки" onClick={() => onSelect(null)}>×</button>
          </div>
          <p>{selectedRequest.name}</p>
          <small>{selectedRequest.quantity} бытов. · {selectedRequest.address_label}</small>
          <div className="request-map-menu__dates" aria-label="Допустимые даты заявки">
            {selectedRequest.date_options.map((option) => (
              <span key={option.date} className={option.date === planningDate ? 'request-map-menu__date--active' : undefined}>
                {formatDate(option.date)}{option.window_start && option.window_end ? ` · ${option.window_start.slice(0, 5)}–${option.window_end.slice(0, 5)}` : ''}
              </span>
            ))}
          </div>
          <div className="request-map-menu__actions">
            <button type="button" onClick={() => onAgreeRequestDate(selectedRequest.id, planningDate)}>
              Согласовать {formatDate(planningDate)}
            </button>
            <button type="button" onClick={() => onAgreeRequestDate(selectedRequest.id, nextDate(planningDate, 1))}>
              {selectedRequest.date_options.some((option) => option.date === nextDate(planningDate, 1)) ? 'Согласовать' : 'Перенести на'} {formatDate(nextDate(planningDate, 1))}
            </button>
          </div>
        </section>
      ) : null}
      <div className={`map-overlay map-status ${offlineMode ? 'map-status--error' : ''}`}>
        <i />{mapTool === 'RELATE_ZONES'
          ? relationSourceZoneId ? 'Связи зон · выберите вторую зону' : 'Связи зон · выберите первую зону'
          : offlineMode ? 'Grid mode · без внешней карты' : 'Map mode · MapLibre'}
      </div>
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
