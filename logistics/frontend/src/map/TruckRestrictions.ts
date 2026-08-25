import type { FeatureCollection, Geometry } from 'geojson';
import type {
  TruckRestrictionCategory,
  TruckRestrictionFeature,
  TruckRestrictionMetadata,
  TruckRestrictionProperties,
} from '../api/client';

export const TRUCK_RESTRICTIONS_LAYER_LABEL = 'Ограничения грузового транспорта';

export type TruckRestrictionLayerStatus = 'off' | 'loading' | 'loaded' | 'zoom' | 'error';

export interface TruckRestrictionLayerState {
  status: TruckRestrictionLayerStatus;
  count: number;
  truncated: boolean;
  error: string | null;
}

export interface TruckRestrictionMapProperties {
  osm_type: 'node' | 'way';
  osm_id: number;
  category: TruckRestrictionCategory;
  marker: string;
  color: string;
}

interface TruckRestrictionCategoryPresentation {
  title: string;
  marker: string;
  color: string;
}

const CATEGORY_PRESENTATION: Record<TruckRestrictionCategory, TruckRestrictionCategoryPresentation> = {
  HGV_ACCESS: { title: 'Ограничение движения грузовиков', marker: 'HGV', color: '#ef4444' },
  MAX_HEIGHT: { title: 'Ограничение высоты', marker: 'H', color: '#f97316' },
  MAX_WIDTH: { title: 'Ограничение ширины', marker: 'W', color: '#eab308' },
  MAX_LENGTH: { title: 'Ограничение длины', marker: 'L', color: '#8b5cf6' },
  MAX_WEIGHT: { title: 'Ограничение массы', marker: 't', color: '#ec4899' },
  MAX_AXLE_LOAD: { title: 'Ограничение нагрузки на ось', marker: 'AX', color: '#14b8a6' },
  CONDITIONAL: { title: 'Условное ограничение', marker: '?', color: '#60a5fa' },
  TRAILER_ACCESS: { title: 'Ограничение для прицепа', marker: 'TR', color: '#a855f7' },
};

const SUPPORT_LABELS = {
  SUPPORTED: 'учитывается',
  PARTIAL: 'частично',
  UNSUPPORTED: 'не поддерживается движком',
} as const;

export function truckRestrictionPresentation(category: TruckRestrictionCategory): TruckRestrictionCategoryPresentation {
  return CATEGORY_PRESENTATION[category];
}

export function truckRestrictionMapData(
  collection: FeatureCollection<Geometry, TruckRestrictionProperties>,
): FeatureCollection<Geometry, TruckRestrictionMapProperties> {
  return {
    type: 'FeatureCollection',
    features: collection.features.map((feature) => {
      const presentation = truckRestrictionPresentation(feature.properties.category);
      return {
        ...feature,
        properties: {
          osm_type: feature.properties.osm_type,
          osm_id: feature.properties.osm_id,
          category: feature.properties.category,
          marker: presentation.marker,
          color: presentation.color,
        },
      };
    }),
  };
}

export function truckRestrictionKey(osmType: string, osmId: number): string {
  return `${osmType}:${osmId}`;
}

export function truckRestrictionLookup(features: TruckRestrictionFeature[]): Map<string, TruckRestrictionFeature> {
  return new Map(features.map((feature) => [
    truckRestrictionKey(feature.properties.osm_type, feature.properties.osm_id),
    feature,
  ]));
}

function popupRow(label: string, value: string): HTMLDivElement {
  const row = document.createElement('div');
  const term = document.createElement('dt');
  const description = document.createElement('dd');
  term.textContent = label;
  description.textContent = value;
  row.append(term, description);
  return row;
}

/** Builds restriction diagnostics without interpreting backend strings as HTML. */
export function buildTruckRestrictionPopupContent(
  feature: TruckRestrictionFeature,
  metadata: TruckRestrictionMetadata,
): HTMLDivElement {
  const { properties } = feature;
  const presentation = truckRestrictionPresentation(properties.category);
  const root = document.createElement('div');
  root.className = 'truck-restriction-popup';

  const eyebrow = document.createElement('span');
  eyebrow.className = 'truck-restriction-popup__eyebrow';
  eyebrow.textContent = `Грузовое ограничение · ${presentation.marker}`;
  const title = document.createElement('h3');
  title.textContent = presentation.title;
  const details = document.createElement('dl');
  details.append(
    popupRow('Тег', properties.primary_tag),
    popupRow('Значение', properties.value),
    popupRow('Поддержка', SUPPORT_LABELS[properties.support_status]),
    popupRow('Объект OSM', `${properties.osm_type} ${properties.osm_id}`),
    popupRow('Версия OSM', metadata.osm_data_version),
  );

  const tagsTitle = document.createElement('strong');
  tagsTitle.textContent = 'Грузовые теги OSM';
  const tags = document.createElement('dl');
  const entries = Object.entries(properties.tags).sort(([left], [right]) => left.localeCompare(right));
  if (entries.length === 0) {
    tags.append(popupRow('Теги', 'нет дополнительных тегов'));
  } else {
    entries.forEach(([key, value]) => tags.append(popupRow(key, value)));
  }
  root.append(eyebrow, title, details, tagsTitle, tags);
  return root;
}
