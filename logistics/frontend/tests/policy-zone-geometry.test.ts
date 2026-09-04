import { describe, expect, it } from 'vitest';
import {
  mapRingGeometry,
  parsePolicyZoneGeometry,
  policyZoneGeometryText,
} from '../src/features/policy-zones/policy-zone-geometry';

describe('policy-zone exact geometry', () => {
  it('round-trips a MultiPolygon without repairing coordinates', () => {
    const input = {
      type: 'MultiPolygon' as const,
      coordinates: [[[[30.3, 59.9], [30.4, 59.9], [30.4, 60], [30.3, 59.9]]]],
    };

    expect(parsePolicyZoneGeometry(policyZoneGeometryText(input))).toEqual(input);
  });

  it('rejects ordinary polygons, open rings, and invalid WGS84 positions', () => {
    expect(() => parsePolicyZoneGeometry(JSON.stringify({
      type: 'Polygon',
      coordinates: [],
    }))).toThrow('MultiPolygon');
    expect(() => parsePolicyZoneGeometry(JSON.stringify({
      type: 'MultiPolygon',
      coordinates: [[[[30, 59], [31, 59], [31, 60], [30, 60]]]],
    }))).toThrow('замкнут');
    expect(() => parsePolicyZoneGeometry(JSON.stringify({
      type: 'MultiPolygon',
      coordinates: [[[[181, 59], [31, 59], [31, 60], [181, 59]]]],
    }))).toThrow('WGS84');
  });

  it('closes a map-drawn outer ring only after three points', () => {
    expect(mapRingGeometry([[30, 59], [31, 59]])).toBeNull();
    expect(mapRingGeometry([[30, 59], [31, 59], [31, 60]])).toEqual({
      type: 'MultiPolygon',
      coordinates: [[[[30, 59], [31, 59], [31, 60], [30, 59]]]],
    });
  });
});
