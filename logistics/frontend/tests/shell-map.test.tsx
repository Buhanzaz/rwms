import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MapCanvas } from '../src/map/MapCanvas';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture, workspaceFixture } from './fixtures';

const mapState = vi.hoisted(() => ({
  markers: [] as HTMLElement[],
  easeTo: vi.fn(),
}));

vi.mock('maplibre-gl', () => {
  class MapMock {
    private handlers = new globalThis.Map<string, Set<(...args: unknown[]) => void>>();
    private layers = new Set<string>();
    private sources = new globalThis.Map<string, { setData: ReturnType<typeof vi.fn> }>();

    constructor() {
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

    addControl() { return this; }
    addSource(id: string) { this.sources.set(id, { setData: vi.fn() }); }
    getSource(id: string) { return this.sources.get(id); }
    addLayer(layer: { id: string }) { this.layers.add(layer.id); }
    getLayer(id: string) { return this.layers.has(id) ? { id } : undefined; }
    getStyle() { return { layers: Array.from(this.layers, (id) => ({ id })) }; }
    setLayoutProperty() { return this; }
    setPaintProperty() { return this; }
    addImage() { return this; }
    hasImage() { return false; }
    getCanvas() { return { style: { cursor: '' } }; }
    getZoom() { return 10; }
    getBounds() { return { getWest: () => 29, getSouth: () => 58, getEast: () => 32, getNorth: () => 60 }; }
    easeTo(options: unknown) { mapState.easeTo(options); }
    fitBounds() { return this; }
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
    mapState.easeTo.mockReset();
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
    const view = render(<MapCanvas {...commonProps} selected={null} />);

    expect(screen.queryByRole('button', { name: 'Нарисовать зону' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Вырезать область внутри зоны' })).not.toBeInTheDocument();

    await waitFor(() => expect(mapState.markers.some((marker) => marker.getAttribute('aria-label') === 'Склад: Склад Великий Новгород')).toBe(true));
    const targetMarker = mapState.markers.find((marker) => marker.getAttribute('aria-label') === 'Склад: Склад Великий Новгород');
    const zoomCallsBeforeSelection = mapState.easeTo.mock.calls.length;
    fireEvent.click(targetMarker as HTMLElement);

    expect(onSelect).toHaveBeenCalledWith({ kind: 'warehouse', id: targetWarehouse.id });
    expect(onWarehouseActivate).not.toHaveBeenCalled();
    expect(mapState.easeTo).toHaveBeenCalledTimes(zoomCallsBeforeSelection);

    view.rerender(<MapCanvas {...commonProps} selected={{ kind: 'warehouse', id: targetWarehouse.id }} />);
    fireEvent.click(screen.getByRole('button', { name: `Перейти к складу ${targetWarehouse.name}` }));
    expect(onWarehouseActivate).toHaveBeenCalledWith(targetWarehouse.id);
  });
});
