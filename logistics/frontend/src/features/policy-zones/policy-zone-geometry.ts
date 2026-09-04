import type { MultiPolygon, Position } from 'geojson';

/** Parse an exact WGS84 MultiPolygon without repairing or simplifying its rings. */
export function parsePolicyZoneGeometry(value: string): MultiPolygon {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error('GeoJSON должен быть корректным JSON.');
  }
  if (!isRecord(parsed) || parsed.type !== 'MultiPolygon' || !Array.isArray(parsed.coordinates)) {
    throw new Error('Геометрия должна иметь тип MultiPolygon.');
  }
  if (!parsed.coordinates.length) throw new Error('Добавьте хотя бы один полигон.');
  const coordinates = parsed.coordinates.map((polygon, polygonIndex) => {
    if (!Array.isArray(polygon) || !polygon.length) {
      throw new Error(`Полигон ${polygonIndex + 1} не содержит контур.`);
    }
    return polygon.map((ring, ringIndex) => {
      if (!Array.isArray(ring) || ring.length < 4) {
        throw new Error(`Контур ${ringIndex + 1} полигона ${polygonIndex + 1} должен содержать минимум четыре позиции.`);
      }
      const positions = ring.map((position) => parsePosition(position));
      const first = positions[0]!;
      const last = positions.at(-1)!;
      if (first[0] !== last[0] || first[1] !== last[1]) {
        throw new Error(`Контур ${ringIndex + 1} полигона ${polygonIndex + 1} должен быть замкнут.`);
      }
      return positions;
    });
  });
  return { type: 'MultiPolygon', coordinates };
}

/** Serialize the server representation for exact operator editing. */
export function policyZoneGeometryText(geometry: MultiPolygon): string {
  return JSON.stringify(geometry, null, 2);
}

/** Convert map clicks into one closed outer ring without inventing holes or extra parts. */
export function mapRingGeometry(points: Position[]): MultiPolygon | null {
  if (points.length < 3) return null;
  const ring = points.map((point) => parsePosition(point));
  const first = ring[0];
  if (!first) return null;
  ring.push([first[0], first[1]]);
  return { type: 'MultiPolygon', coordinates: [[ring]] };
}

function parsePosition(value: unknown): [number, number] {
  if (
    !Array.isArray(value)
    || value.length !== 2
    || typeof value[0] !== 'number'
    || typeof value[1] !== 'number'
    || !Number.isFinite(value[0])
    || !Number.isFinite(value[1])
    || value[0] < -180
    || value[0] > 180
    || value[1] < -90
    || value[1] > 90
  ) {
    throw new Error('Каждая позиция должна быть парой WGS84 [долгота, широта].');
  }
  return [value[0], value[1]];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
