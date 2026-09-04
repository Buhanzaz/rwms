import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MapCanvas } from '../src/map/MapCanvas';
import { useUiStore } from '../src/stores/ui-store';
import { EMPTY_METRICS } from '../src/domain/defaults';
import { planFixture, requestFixture, warehouseFixture, workspaceFixture } from './fixtures';

const mapState = vi.hoisted(() => ({
  markers: [] as HTMLElement[],
  sourceData: new globalThis.Map<string, unknown>(),
  sourceOptions: new globalThis.Map<string, Record<string, unknown>>(),
  activeMap: null as null | {
    emit: (event: string, ...args: unknown[]) => void;
    emitLayer: (event: string, layer: string, payload: unknown) => void;
  },
  easeTo: vi.fn(),
  setPadding: vi.fn(),
  fitBounds: vi.fn(),
  constructorOptions: null as null | { center?: [number, number]; zoom?: number },
  center: [37.6176, 55.7558] as [number, number],
  zoom: 8.6,
}));

vi.mock('maplibre-gl', () => {
  class MapMock {
    private handlers = new globalThis.Map<string, Set<(...args: unknown[]) => void>>();
    private layers = new Set<string>();
    private sources = new globalThis.Map<string, { setData: ReturnType<typeof vi.fn> }>();

    constructor(options: { center?: [number, number]; zoom?: number }) {
      mapState.constructorOptions = options;
      if (options.center) mapState.center = options.center;
      if (options.zoom !== undefined) mapState.zoom = options.zoom;
      mapState.activeMap = this;
      queueMicrotask(() => this.emit('style.load'));
    }

    on(event: string, layerOrHandler: string | ((...args: unknown[]) => void), handler?: (...args: unknown[]) => void) {
      const key = typeof layerOrHandler === 'string' ? `${event}:${layerOrHandler}` : event;
      const listener = typeof layerOrHandler === 'string' ? handler : layerOrHandler;
      if (listener) this.handlers.set(key, new Set([...(this.handlers.get(key) ?? []), listener]));
      return this;
    }

    off(event: string, layerOrHandler: string | ((...args: unknown[]) => void), handler?: (...args: unknown[]) => void) {
      const key = typeof layerOrHandler === 'string' ? `${event}:${layerOrHandler}` : event;
      const listener = typeof layerOrHandler === 'string' ? handler : layerOrHandler;
      if (listener) this.handlers.get(key)?.delete(listener);
      return this;
    }

    emit(event: string, ...args: unknown[]) {
      this.handlers.get(event)?.forEach((handler) => handler(...args));
    }

    emitLayer(event: string, layer: string, payload: unknown) {
      this.handlers.get(`${event}:${layer}`)?.forEach((handler) => handler(payload));
    }

    addControl() { return this; }
    setPadding(value: unknown) { mapState.setPadding(value); return this; }
    addSource(id: string, options: Record<string, unknown>) {
      mapState.sourceOptions.set(id, options);
      this.sources.set(id, { setData: vi.fn((data: unknown) => mapState.sourceData.set(id, data)) });
    }
    getSource(id: string) { return this.sources.get(id); }
    addLayer(layer: { id: string }) { this.layers.add(layer.id); }
    getLayer(id: string) { return this.layers.has(id) ? { id } : undefined; }
    getStyle() { return { layers: Array.from(this.layers, (id) => ({ id })) }; }
    setLayoutProperty() { return this; }
    setPaintProperty() { return this; }
    addImage() { return this; }
    hasImage() { return false; }
    getCanvas() { return { style: { cursor: '' } }; }
    dragPan = { disable: vi.fn(), enable: vi.fn() };
    getZoom() { return mapState.zoom; }
    getCenter() { return { lng: mapState.center[0], lat: mapState.center[1] }; }
    getBounds() { return { getWest: () => 29, getSouth: () => 58, getEast: () => 32, getNorth: () => 60 }; }
    easeTo(options: { center?: [number, number]; zoom?: number }) {
      if (options.center) mapState.center = options.center;
      if (options.zoom !== undefined) mapState.zoom = options.zoom;
      mapState.easeTo(options);
    }
    fitBounds(...args: unknown[]) { mapState.fitBounds(...args); return this; }
    isStyleLoaded() { return true; }
    setStyle() { queueMicrotask(() => this.emit('style.load')); }
    remove() { return undefined; }
  }

  class MarkerMock {
    private coordinates = { lng: 0, lat: 0 };
    constructor(private options: { element?: HTMLElement } = {}) {}
    setLngLat(coordinates: [number, number]) {
      this.coordinates = { lng: coordinates[0], lat: coordinates[1] };
      return this;
    }
    getLngLat() { return this.coordinates; }
    setPopup() { return this; }
    addTo() {
      if (this.options.element) mapState.markers.push(this.options.element);
      return this;
    }
    on() { return this; }
    remove() { return undefined; }
  }

  class PopupMock {
    setText() { return this; }
    setLngLat() { return this; }
    setDOMContent() { return this; }
    addTo() { return this; }
    addClassName() { return this; }
    on() { return this; }
    remove() { return undefined; }
  }

  class LngLatBoundsMock {
    extend() { return this; }
  }

  const maplibre = {
    Map: MapMock,
    Marker: MarkerMock,
    Popup: PopupMock,
    LngLatBounds: LngLatBoundsMock,
    NavigationControl: class NavigationControlMock {},
  };
  return { default: maplibre, ...maplibre };
});

describe('warehouse selection on the shared map', () => {
  beforeEach(() => {
    mapState.markers.length = 0;
    mapState.sourceData.clear();
    mapState.sourceOptions.clear();
    mapState.activeMap = null;
    mapState.constructorOptions = null;
    mapState.center = [37.6176, 55.7558];
    mapState.zoom = 8.6;
    mapState.easeTo.mockReset();
    mapState.setPadding.mockReset();
    mapState.fitBounds.mockReset();
    window.localStorage.clear();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      fillStyle: '',
      strokeStyle: '',
      lineWidth: 1,
      font: '',
      textAlign: 'start',
      textBaseline: 'alphabetic',
      fillRect: vi.fn(),
      strokeRect: vi.fn(),
      fillText: vi.fn(),
      getImageData: vi.fn(() => ({} as ImageData)),
    } as unknown as CanvasRenderingContext2D);
    useUiStore.setState({
      mapTool: 'SELECT',
      layers: { ...useUiStore.getState().layers, warehouse: true, warehouseIsochrones: false, taskIsochrones: false, truckRestrictions: false },
    });
  });

  it('selects a marker without zooming or activating it, then navigates only from the contextual control', async () => {
    const currentWarehouse = warehouseFixture();
    const targetWarehouse = warehouseFixture({
      id: 'warehouse-2',
      name: 'Склад Великий Новгород',
      city: 'Великий Новгород',
      latitude: 58.5256,
      longitude: 31.2742,
    });
    const workspace = workspaceFixture({ warehouse: currentWarehouse, warehouses: [currentWarehouse, targetWarehouse] });
    const onSelect = vi.fn();
    const onWarehouseActivate = vi.fn();
    const commonProps = {
      workspace,
      plan: null,
      simulation: null,
      traceEvents: [],
      onSelect,
      onPlacePoint: vi.fn(),
      onWarehouseActivate,
      onMapError: vi.fn(),
      optimizationRun: null,
      onRequestMoveDraft: vi.fn(),
      planningDate: '2026-08-30',
      busy: false,
      onScheduleRequestDate: vi.fn(),
      onUnscheduleRequest: vi.fn(),
      onMoveTask: vi.fn(),
      planningCheck: null,
      onPlanningCheckPoint: vi.fn(),
      pendingWarehousePoint: null,
    };
    const padding = { top: 148, left: 256, right: 456, bottom: 36 };
    const warehouseKinds = new globalThis.Map([
      [currentWarehouse.external_warehouse_id, { id: currentWarehouse.external_warehouse_id, representative: false, mainWarehouse: true, production: true }],
      ['not-in-workspace', { id: 'not-in-workspace', representative: false, mainWarehouse: true, production: false }],
    ]);
    const view = render(<MapCanvas {...commonProps} cameraPadding={padding} warehouseKinds={warehouseKinds} selected={null} />);

    expect(screen.queryByRole('button', { name: 'Нарисовать зону' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Вырезать область внутри зоны' })).not.toBeInTheDocument();

    await waitFor(() => expect(mapState.markers.some((marker) => marker.getAttribute('aria-label') === 'Центральный склад и производство: Склад Великий Новгород')).toBe(true));
    expect(mapState.markers).toHaveLength(2);
    expect(mapState.markers.map((marker) => marker.textContent)).toEqual(['Ц', 'Ц']);
    expect(mapState.setPadding).toHaveBeenLastCalledWith(padding);
    const originalMap = mapState.activeMap;
    const targetMarker = mapState.markers.find((marker) => marker.getAttribute('aria-label') === 'Центральный склад и производство: Склад Великий Новгород');
    const zoomCallsBeforeSelection = mapState.easeTo.mock.calls.length;
    fireEvent.click(targetMarker as HTMLElement);

    expect(onSelect).toHaveBeenCalledWith({ kind: 'warehouse', id: targetWarehouse.id });
    expect(onWarehouseActivate).not.toHaveBeenCalled();
    expect(mapState.easeTo).toHaveBeenCalledTimes(zoomCallsBeforeSelection);

    view.rerender(<MapCanvas {...commonProps} cameraPadding={{ ...padding, right: 24 }} selected={{ kind: 'warehouse', id: targetWarehouse.id }} />);
    expect(mapState.activeMap).toBe(originalMap);
    expect(mapState.setPadding).toHaveBeenLastCalledWith({ ...padding, right: 24 });
    expect(mapState.markers.at(-1)?.textContent).toBe('?');
    view.rerender(<MapCanvas {...commonProps} warehouseKindsFailed selected={{ kind: 'warehouse', id: targetWarehouse.id }} />);
    expect(screen.getByRole('status')).toHaveTextContent('Типы складов временно недоступны');
    fireEvent.click(screen.getByRole('button', { name: `Перейти к складу ${targetWarehouse.name}` }));
    expect(onWarehouseActivate).toHaveBeenCalledWith(targetWarehouse.id);
  });

  it('restores and saves the viewport for the active warehouse', async () => {
    const warehouse = warehouseFixture();
    window.localStorage.setItem(`rwms:logistics:map-viewport:${warehouse.id}`, JSON.stringify({
      longitude: 31.271,
      latitude: 58.521,
      zoom: 13.25,
    }));
    render(<MapCanvas
      workspace={workspaceFixture({ warehouse, warehouses: [warehouse] })}
      plan={null}
      simulation={null}
      traceEvents={[]}
      selected={null}
      onSelect={vi.fn()}
      onPlacePoint={vi.fn()}
      onWarehouseActivate={vi.fn()}
      onMapError={vi.fn()}
      optimizationRun={null}
      onRequestMoveDraft={vi.fn()}
      planningDate="2026-08-30"
      busy={false}
      onScheduleRequestDate={vi.fn()}
      onUnscheduleRequest={vi.fn()}
      onMoveTask={vi.fn()}
      planningCheck={null}
      onPlanningCheckPoint={vi.fn()}
      pendingWarehousePoint={null}
    />);

    await waitFor(() => {
      expect(mapState.constructorOptions).toMatchObject({ center: [31.271, 58.521], zoom: 13.25 });
      expect(mapState.markers).toHaveLength(1);
    });
    mapState.center = [31.28, 58.53];
    mapState.zoom = 12.5;
    act(() => mapState.activeMap?.emit('moveend'));
    expect(JSON.parse(window.localStorage.getItem(`rwms:logistics:map-viewport:${warehouse.id}`) ?? '{}')).toEqual({
      longitude: 31.28,
      latitude: 58.53,
      zoom: 12.5,
    });
  });

  it('does not fit a shared root route over a newly activated representative warehouse', async () => {
    const root = warehouseFixture({ id: 'warehouse-root' });
    const representative = warehouseFixture({
      id: 'warehouse-representative',
      name: 'Склад Великий Новгород',
      representative: true,
      latitude: 58.5256,
      longitude: 31.2742,
    });
    const rootWorkspace = workspaceFixture({ warehouse: root, warehouses: [root, representative] });
    const representativeWorkspace = workspaceFixture({
      warehouse: representative,
      warehouses: [root, representative],
      planning_root_warehouse_id: root.id,
      planning_group_warehouse_ids: [root.id, representative.id],
    });
    const rootPlan = planFixture({
      warehouse_id: root.id,
      driver_routes: [{
        driver_shift_id: 'shift-1',
        shift_start_at: '2026-08-30T05:00:00Z',
        shift_end_at: '2026-08-30T17:00:00Z',
        driver_id: 'driver-1',
        driver_name: 'Водитель',
        vehicle_id: 'vehicle-1',
        vehicle_name: 'Машина',
        registration_number: 'А001АА',
        metrics: { ...EMPTY_METRICS },
        cycles: [{
          id: 'cycle-1',
          route_plan_id: 'plan-1',
          driver_shift_id: 'shift-1',
          sequence: 1,
          planned_start: '2026-08-30T05:00:00Z',
          planned_finish: '2026-08-30T06:00:00Z',
          total_distance_meters: 1000,
          total_travel_seconds: 600,
          total_service_seconds: 0,
          empty_distance_meters: 0,
          detour_seconds: 0,
          score: 1,
          locked: false,
          stops: [],
          legs: [{
            departure_at: '2026-08-30T05:00:00Z',
            arrival_at: '2026-08-30T05:10:00Z',
            distance_meters: 1000,
            travel_seconds: 600,
            geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[30.3, 59.9], [30.4, 59.8]] } },
          }],
          explanation: [],
          warnings: [],
        }],
      }],
    });
    const commonProps = {
      plan: null,
      simulation: null,
      traceEvents: [],
      selected: null,
      onSelect: vi.fn(),
      onPlacePoint: vi.fn(),
      onWarehouseActivate: vi.fn(),
      onMapError: vi.fn(),
      optimizationRun: null,
      onRequestMoveDraft: vi.fn(),
      planningDate: '2026-08-30',
      busy: false,
      onScheduleRequestDate: vi.fn(),
      onUnscheduleRequest: vi.fn(),
      onMoveTask: vi.fn(),
      planningCheck: null,
      onPlanningCheckPoint: vi.fn(),
      pendingWarehousePoint: null,
    };
    const view = render(<MapCanvas {...commonProps} workspace={rootWorkspace} />);
    await waitFor(() => expect(mapState.activeMap).not.toBeNull());
    mapState.fitBounds.mockClear();

    view.rerender(<MapCanvas {...commonProps} workspace={representativeWorkspace} plan={rootPlan} />);

    await waitFor(() => expect(mapState.easeTo).toHaveBeenLastCalledWith({
      center: [representative.longitude, representative.latitude],
      zoom: 11,
      duration: 550,
    }));
    expect(mapState.fitBounds).not.toHaveBeenCalled();

    view.rerender(<MapCanvas {...commonProps} workspace={representativeWorkspace} plan={{ ...rootPlan, id: 'root-plan-next' }} />);
    await waitFor(() => expect(mapState.fitBounds).not.toHaveBeenCalled());
  });

  it('centers a selected unassigned delivery while preserving the current zoom', async () => {
    const warehouse = warehouseFixture();
    const request = requestFixture({ id: 'request-unassigned', latitude: 58.5234, longitude: 31.2812, status: 'UNASSIGNED' });
    const workspace = workspaceFixture({ warehouse, warehouses: [warehouse], requests: [request] });
    const commonProps = {
      workspace,
      plan: null,
      simulation: null,
      traceEvents: [],
      onSelect: vi.fn(),
      onPlacePoint: vi.fn(),
      onWarehouseActivate: vi.fn(),
      onMapError: vi.fn(),
      optimizationRun: null,
      onRequestMoveDraft: vi.fn(),
      planningDate: '2026-08-30',
      busy: false,
      onScheduleRequestDate: vi.fn(),
      onUnscheduleRequest: vi.fn(),
      onMoveTask: vi.fn(),
      planningCheck: null,
      onPlanningCheckPoint: vi.fn(),
      pendingWarehousePoint: null,
    };
    const view = render(<MapCanvas {...commonProps} selected={null} />);
    await waitFor(() => expect(mapState.markers).toHaveLength(1));
    const fitCallsBefore = mapState.fitBounds.mock.calls.length;

    view.rerender(<MapCanvas {...commonProps} selected={{ kind: 'request', id: request.id }} />);

    await waitFor(() => expect(mapState.easeTo).toHaveBeenLastCalledWith({
      center: [request.longitude, request.latitude],
      zoom: 11,
      duration: 450,
    }));
    expect(mapState.fitBounds).toHaveBeenCalledTimes(fitCallsBefore);
  });

  it('renders request points through one clustered source and selects a point without DOM markers', async () => {
    const warehouse = warehouseFixture();
    const requests = Array.from({ length: 120 }, (_, index) => requestFixture({
      id: `request-${index}`,
      name: `Доставка ${index}`,
      latitude: 59.5 + index / 10_000,
      longitude: 30.2 + index / 10_000,
    }));
    const workspace = workspaceFixture({ warehouse, warehouses: [warehouse], requests });
    const onSelect = vi.fn();

    render(<MapCanvas
      workspace={workspace}
      plan={null}
      simulation={null}
      traceEvents={[]}
      selected={null}
      onSelect={onSelect}
      onPlacePoint={vi.fn()}
      onWarehouseActivate={vi.fn()}
      onMapError={vi.fn()}
      optimizationRun={null}
      onRequestMoveDraft={vi.fn()}
      planningDate="2026-08-30"
      busy={false}
      onScheduleRequestDate={vi.fn()}
      onUnscheduleRequest={vi.fn()}
      onMoveTask={vi.fn()}
      planningCheck={null}
      onPlanningCheckPoint={vi.fn()}
      pendingWarehousePoint={null}
    />);

    await waitFor(() => expect((mapState.sourceData.get('rwms-requests') as { features?: unknown[] })?.features).toHaveLength(120));
    expect(mapState.sourceOptions.get('rwms-requests')).toMatchObject({ cluster: true, clusterMaxZoom: 13, clusterRadius: 48 });
    expect(mapState.markers).toHaveLength(1);
    expect(mapState.markers[0]).toHaveAttribute('aria-label', `Тип склада не подтверждён: ${warehouse.name}`);

    const stopPropagation = vi.fn();
    const pointEvent = {
      features: [{ properties: { requestId: requests[7]?.id } }],
      originalEvent: { stopPropagation },
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
    };
    mapState.activeMap?.emitLayer('click', 'rwms-request-points', pointEvent);
    mapState.activeMap?.emit('click', pointEvent);
    expect(stopPropagation).toHaveBeenCalledOnce();
    expect(onSelect).toHaveBeenCalledOnce();
    expect(onSelect).toHaveBeenCalledWith({ kind: 'request', id: requests[7]?.id });
  });
});
