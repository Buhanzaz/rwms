import {
  TerraDraw,
  TerraDrawPolygonMode,
  TerraDrawSelectMode,
  ValidateNotSelfIntersecting,
} from 'terra-draw';
import { TerraDrawMapLibreGLAdapter } from 'terra-draw-maplibre-gl-adapter';
import maplibregl, { type Map as MapLibreMap, type Marker } from 'maplibre-gl';
import { Crosshair, MousePointer2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { Polygon } from 'geojson';

const EMPTY_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  name: 'RWMS Empty Warehouse Map',
  sources: {},
  layers: [],
};

/** Canonical RWMS warehouse point used only to guide drawing on the bootstrap map. */
export interface WarehouseZoneFocus {
  latitude: number;
  longitude: number;
  label: string;
}

/** Inputs for drawing the mandatory first zone before a warehouse is persisted. */
interface BootstrapZoneMapProps {
  canDraw: boolean;
  focus: WarehouseZoneFocus | null;
  onZoneDrawn: (geometry: Polygon) => void;
  onError: (message: string) => void;
}

/** Common-map bootstrap that creates a warehouse and its first zone in one command. */
export function BootstrapZoneMap({ canDraw, focus, onZoneDrawn, onError }: BootstrapZoneMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const drawRef = useRef<TerraDraw | null>(null);
  const focusMarkerRef = useRef<Marker | null>(null);
  const onZoneDrawnRef = useRef(onZoneDrawn);
  const [mapReady, setMapReady] = useState(false);
  const [drawing, setDrawing] = useState(false);
  const styleUrl = import.meta.env.VITE_MAP_STYLE_URL;
  onZoneDrawnRef.current = onZoneDrawn;

  useEffect(() => {
    if (!canDraw) setDrawing(false);
  }, [canDraw]);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    let fellBack = false;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: styleUrl || EMPTY_STYLE,
      center: [37.6176, 55.7558],
      zoom: 7,
      attributionControl: false,
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right');
    const prepare = () => {
      setMapReady(true);
    };
    map.on('style.load', prepare);
    map.on('error', (event: unknown) => {
      const errorValue = event && typeof event === 'object' && 'error' in event ? event.error : null;
      const message = errorValue instanceof Error ? errorValue.message : 'неизвестная ошибка рендеринга';
      if (styleUrl && !map.isStyleLoaded() && !fellBack) {
        fellBack = true;
        setMapReady(false);
        onError('Стиль карты не загрузился — включён автономный фон для рисования зоны.');
        map.setStyle(EMPTY_STYLE);
      } else if (!message.includes('Failed to fetch')) {
        onError(`Ошибка карты: ${message}`);
      }
    });
    return () => {
      drawRef.current?.stop();
      drawRef.current = null;
      focusMarkerRef.current?.remove();
      focusMarkerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
  }, [onError, styleUrl]);

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
        new TerraDrawSelectMode(),
      ],
    });
    draw.start();
    draw.setMode('select');
    const onFinish: Parameters<typeof draw.on<'finish'>>[1] = (id, context) => {
      if (context.action !== 'draw') return;
      const feature = draw.getSnapshotFeature(id);
      if (!feature || feature.geometry.type !== 'Polygon') return;
      onZoneDrawnRef.current(feature.geometry);
      draw.removeFeatures([id]);
      draw.setMode('select');
      setDrawing(false);
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
    draw.clear();
    draw.setMode(drawing && canDraw ? 'polygon' : 'select');
  }, [canDraw, drawing]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    focusMarkerRef.current?.remove();
    focusMarkerRef.current = null;
    if (!focus) return;
    map.easeTo({ center: [focus.longitude, focus.latitude], zoom: 11, duration: 550 });
    focusMarkerRef.current = new maplibregl.Marker({ color: '#20C997' })
      .setLngLat([focus.longitude, focus.latitude])
      .setPopup(new maplibregl.Popup({ offset: 20 }).setText(focus.label))
      .addTo(map);
    return () => {
      focusMarkerRef.current?.remove();
      focusMarkerRef.current = null;
    };
  }, [focus, mapReady]);

  return (
    <main className="map-stage bootstrap-zone-map" data-testid="bootstrap-zone-map">
      <div className="map-container" ref={containerRef} aria-label="Карта первой зоны склада" />
      <div className="map-overlay map-toolbar" role="toolbar" aria-label="Создание первой зоны склада">
        <button type="button" aria-label="Перемещаться по карте" aria-pressed={!drawing} onClick={() => setDrawing(false)}>
          <MousePointer2 size={17} aria-hidden="true" />
        </button>
        <button type="button" aria-label="Нарисовать ценовую зону склада" aria-pressed={drawing} disabled={!canDraw} onClick={() => setDrawing(true)}>
          <Crosshair size={17} aria-hidden="true" />
        </button>
      </div>
      {!mapReady ? <div className="map-overlay progress-card"><span className="spinner">Карта запускается…</span></div> : null}
    </main>
  );
}
