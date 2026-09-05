import maplibregl, {
  type GeoJSONSource,
  type Map as MapLibreMap,
} from "maplibre-gl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Cursor01Icon,
  Undo02Icon,
  Cancel01Icon,
} from "@hugeicons/core-free-icons"
import "maplibre-gl/dist/maplibre-gl.css"
import { useEffect, useMemo, useRef, useState } from "react"
import type {
  Feature,
  FeatureCollection,
  Geometry,
  LineString,
  MultiPolygon,
  Point,
  Position,
} from "geojson"
import type {
  PlannerWarehouseSettings,
  WarehousePolicyZone,
} from "./planning-types"
import { Button } from "@/components/ui/button"
import { mapRingGeometry } from "./policy-zone-geometry"

const BLANK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  name: "RWMS Policy Grid",
  sources: {},
  layers: [],
}
const PERSISTED_SOURCE_ID = "rwms-policy-zones"
const CURRENT_SOURCE_ID = "rwms-policy-zone-current"
const DRAFT_SOURCE_ID = "rwms-policy-zone-draft"

interface PolicyZoneMapEditorProps {
  warehouse: PlannerWarehouseSettings
  zones: WarehousePolicyZone[]
  selectedZoneId: string | null
  geometry: MultiPolygon | null
  color: string
  disabled?: boolean
  onGeometryChange: (geometry: MultiPolygon) => void
}

/** MapLibre editor for one exact exceptional polygon over the warehouse context. */
export function PolicyZoneMapEditor({
  warehouse,
  zones,
  selectedZoneId,
  geometry,
  color,
  disabled = false,
  onGeometryChange,
}: PolicyZoneMapEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const [ready, setReady] = useState(false)
  const [drawing, setDrawing] = useState(false)
  const [draftPoints, setDraftPoints] = useState<Position[]>([])
  const [mapNotice, setMapNotice] = useState<string | null>(null)
  const drawingRef = useRef(drawing)
  const disabledRef = useRef(disabled)
  const styleUrl =
    import.meta.env.VITE_MAP_STYLE_URL ||
    "https://tiles.openfreemap.org/styles/liberty"

  useEffect(() => {
    drawingRef.current = drawing
    disabledRef.current = disabled
  }, [drawing, disabled])

  const persisted = useMemo(
    () => policyZoneCollection(zones, selectedZoneId),
    [selectedZoneId, zones]
  )
  const current = useMemo(() => geometryCollection(geometry), [geometry])
  const draft = useMemo(() => draftCollection(draftPoints), [draftPoints])

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    let fellBack = false
    let map: MapLibreMap
    try {
      map = new maplibregl.Map({
        container: containerRef.current,
        style: styleUrl || BLANK_STYLE,
        center: [warehouse.longitude, warehouse.latitude],
        zoom: 9.5,
        attributionControl: { compact: true },
      })
    } catch {
      // MapLibre reports asynchronous failures through its error event; WebGL
      // initialization can also throw before that listener can be installed.
      const notification = window.setTimeout(
        () =>
          setMapNotice(
            "Карта недоступна. Готовый контур можно указать в точных координатах ниже."
          ),
        0
      )
      return () => window.clearTimeout(notification)
    }
    mapRef.current = map
    map.addControl(
      new maplibregl.NavigationControl({ showCompass: false }),
      "top-right"
    )

    const prepare = () => {
      addPolicyLayers(map)
      setReady(true)
    }
    const onClick = (event: maplibregl.MapMouseEvent) => {
      if (!drawingRef.current || disabledRef.current) return
      setDraftPoints((points) => [
        ...points,
        [event.lngLat.lng, event.lngLat.lat],
      ])
    }
    map.on("style.load", prepare)
    map.on("click", onClick)
    map.on("error", () => {
      if (styleUrl && !map.isStyleLoaded() && !fellBack) {
        fellBack = true
        setReady(false)
        setMapNotice(
          "Стиль карты недоступен — включён автономный координатный фон."
        )
        map.setStyle(BLANK_STYLE)
      }
    })

    return () => {
      map.off("click", onClick)
      map.remove()
      mapRef.current = null
    }
  }, [styleUrl, warehouse.latitude, warehouse.longitude])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !ready) return
    setSource(map, PERSISTED_SOURCE_ID, persisted)
    setSource(map, CURRENT_SOURCE_ID, current)
    setSource(map, DRAFT_SOURCE_ID, draft)
    map.setPaintProperty("rwms-policy-zone-current-fill", "fill-color", color)
    map.setPaintProperty("rwms-policy-zone-current-line", "line-color", color)
  }, [color, current, draft, persisted, ready])

  useEffect(() => {
    const canvas = mapRef.current?.getCanvas()
    if (canvas) canvas.style.cursor = drawing && !disabled ? "crosshair" : ""
  }, [disabled, drawing])

  const startDrawing = () => {
    setDraftPoints([])
    setDrawing(true)
  }
  const applyDraft = () => {
    const next = mapRingGeometry(draftPoints)
    if (!next) return
    onGeometryChange(next)
    setDraftPoints([])
    setDrawing(false)
  }

  return (
    <section
      className="relative overflow-hidden rounded-xl border bg-muted"
      aria-label="Редактор контура исключения"
    >
      <div className="h-80" ref={containerRef} />
      <div className="flex flex-wrap gap-2 border-t bg-background p-3">
        <Button
          type="button"
          size="sm"
          disabled={disabled || !ready}
          onClick={startDrawing}
        >
          <HugeiconsIcon
            icon={Cursor01Icon}
            data-icon="inline-start"
            aria-hidden="true"
          />
          {drawing ? "Начать заново" : "Нарисовать контур"}
        </Button>
        {drawing ? (
          <>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={!draftPoints.length || disabled}
              onClick={() => setDraftPoints((points) => points.slice(0, -1))}
            >
              <HugeiconsIcon
                icon={Undo02Icon}
                data-icon="inline-start"
                aria-hidden="true"
              />
              Убрать точку
            </Button>
            <Button
              type="button"
              size="sm"
              variant="default"
              disabled={draftPoints.length < 3 || disabled}
              onClick={applyDraft}
            >
              Применить контур
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={disabled}
              onClick={() => {
                setDraftPoints([])
                setDrawing(false)
              }}
            >
              <HugeiconsIcon
                icon={Cancel01Icon}
                data-icon="inline-start"
                aria-hidden="true"
              />
              Отмена
            </Button>
          </>
        ) : null}
      </div>
      <p className="border-t p-3 text-sm text-muted-foreground" role="status">
        {mapNotice ??
          (drawing
            ? `Поставьте минимум три точки по границе. Сейчас: ${draftPoints.length}.`
            : `Склад «${warehouse.name}». Цветные области — только исключения из обычных правил.`)}
      </p>
    </section>
  )
}

function addPolicyLayers(map: MapLibreMap): void {
  if (!map.getSource(PERSISTED_SOURCE_ID)) {
    map.addSource(PERSISTED_SOURCE_ID, {
      type: "geojson",
      data: emptyCollection(),
    })
    map.addLayer({
      id: "rwms-policy-zones-fill",
      type: "fill",
      source: PERSISTED_SOURCE_ID,
      paint: {
        "fill-color": ["get", "color"],
        "fill-opacity": [
          "case",
          ["boolean", ["get", "selected"], false],
          0.08,
          0.2,
        ],
      },
    })
    map.addLayer({
      id: "rwms-policy-zones-line",
      type: "line",
      source: PERSISTED_SOURCE_ID,
      paint: { "line-color": ["get", "color"], "line-width": 2 },
    })
  }
  if (!map.getSource(CURRENT_SOURCE_ID)) {
    map.addSource(CURRENT_SOURCE_ID, {
      type: "geojson",
      data: emptyCollection(),
    })
    map.addLayer({
      id: "rwms-policy-zone-current-fill",
      type: "fill",
      source: CURRENT_SOURCE_ID,
      paint: { "fill-color": "#3B82F6", "fill-opacity": 0.34 },
    })
    map.addLayer({
      id: "rwms-policy-zone-current-line",
      type: "line",
      source: CURRENT_SOURCE_ID,
      paint: { "line-color": "#3B82F6", "line-width": 3 },
    })
  }
  if (!map.getSource(DRAFT_SOURCE_ID)) {
    map.addSource(DRAFT_SOURCE_ID, { type: "geojson", data: emptyCollection() })
    map.addLayer({
      id: "rwms-policy-zone-draft-line",
      type: "line",
      source: DRAFT_SOURCE_ID,
      paint: {
        "line-color": "#F8FAFC",
        "line-width": 2,
        "line-dasharray": [2, 2],
      },
    })
    map.addLayer({
      id: "rwms-policy-zone-draft-points",
      type: "circle",
      source: DRAFT_SOURCE_ID,
      paint: {
        "circle-radius": 5,
        "circle-color": "#F8FAFC",
        "circle-stroke-color": "#111827",
        "circle-stroke-width": 1,
      },
    })
  }
}

function setSource(
  map: MapLibreMap,
  id: string,
  data: FeatureCollection
): void {
  map.getSource<GeoJSONSource>(id)?.setData(data)
}

function policyZoneCollection(
  zones: WarehousePolicyZone[],
  selectedZoneId: string | null
): FeatureCollection<MultiPolygon> {
  return {
    type: "FeatureCollection",
    features: zones.map((zone) => ({
      type: "Feature",
      properties: {
        color: zone.color,
        kind: zone.kind,
        name: zone.name,
        selected: zone.id === selectedZoneId,
      },
      geometry: zone.geometry,
    })),
  }
}

function geometryCollection(
  geometry: MultiPolygon | null
): FeatureCollection<MultiPolygon> {
  return {
    type: "FeatureCollection",
    features: geometry ? [{ type: "Feature", properties: {}, geometry }] : [],
  }
}

function draftCollection(points: Position[]): FeatureCollection<Geometry> {
  const features: Array<Feature<Point | LineString>> = points.map(
    (coordinates) => ({
      type: "Feature",
      properties: {},
      geometry: { type: "Point", coordinates },
    })
  )
  if (points.length > 1) {
    features.unshift({
      type: "Feature",
      properties: {},
      geometry: { type: "LineString", coordinates: points },
    })
  }
  return { type: "FeatureCollection", features }
}

function emptyCollection(): FeatureCollection {
  return { type: "FeatureCollection", features: [] }
}
