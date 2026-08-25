import type { Trailer, VehicleLoadProfile } from '../../domain/types';

export interface PreviewCargoInput {
  length_mm: number | null;
  width_mm: number | null;
  height_mm: number | null;
  weight_kg: number | null;
}

export interface PreviewVehicleInput {
  length_mm: number | null;
  width_mm: number | null;
  height_mm: number | null;
  tare_weight_kg: number | null;
  platform_length_mm: number | null;
  platform_height_from_ground_mm: number | null;
  can_use_trailer: boolean;
  combined_length_with_trailer_mm: number | null;
  coupling_length_mm: number | null;
  height_safety_margin_mm: number;
  width_safety_margin_mm: number;
  weight_safety_margin_kg: number;
}

export interface TruckPreviewMetrics {
  length_meters: number;
  width_meters: number;
  height_meters: number;
  weight_tons: number;
  max_axle_load_tons: number;
}

export interface TruckPreviewResult {
  title: string;
  trailer_attached: boolean;
  metrics: TruckPreviewMetrics | null;
  missing: string[];
}

function positive(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function missingValues(values: Array<[string, number | null | undefined]>): string[] {
  return values.filter(([, value]) => !positive(value)).map(([label]) => label);
}

function axleLoad(profiles: VehicleLoadProfile[], configurationType: VehicleLoadProfile['configuration_type']): number | null {
  return profiles.find((profile) => profile.configuration_type === configurationType)?.max_actual_axle_load_kg ?? null;
}

/** Derives display-only effective configurations from entered physical values without inventing defaults. */
export function calculateTruckConfigurationPreviews(
  vehicle: PreviewVehicleInput,
  trailer: Trailer | null,
  cargo: PreviewCargoInput,
  profiles: VehicleLoadProfile[],
): { one: TruckPreviewResult; two: TruckPreviewResult; retained_trailer_length_meters: number | null } {
  const oneAxleLoad = axleLoad(profiles, 'CARGO_ON_TRUCK');
  const oneMissing = missingValues([
    ['длина машины', vehicle.length_mm],
    ['ширина машины', vehicle.width_mm],
    ['высота машины', vehicle.height_mm],
    ['собственная масса машины', vehicle.tare_weight_kg],
    ['длина платформы машины', vehicle.platform_length_mm],
    ['высота платформы машины', vehicle.platform_height_from_ground_mm],
    ['длина бытовки для предпросмотра', cargo.length_mm],
    ['ширина бытовки для предпросмотра', cargo.width_mm],
    ['высота бытовки для предпросмотра', cargo.height_mm],
    ['масса бытовки для предпросмотра', cargo.weight_kg],
    ['фактическая осевая нагрузка «одна бытовка»', oneAxleLoad],
  ]);
  const oneMetrics = oneMissing.length === 0 ? {
    length_meters: (vehicle.length_mm! + Math.max(0, cargo.length_mm! - vehicle.platform_length_mm!)) / 1000,
    width_meters: (Math.max(vehicle.width_mm!, cargo.width_mm!) + vehicle.width_safety_margin_mm) / 1000,
    height_meters: (
      Math.max(vehicle.height_mm!, vehicle.platform_height_from_ground_mm! + cargo.height_mm!)
      + vehicle.height_safety_margin_mm
    ) / 1000,
    weight_tons: (vehicle.tare_weight_kg! + cargo.weight_kg! + vehicle.weight_safety_margin_kg) / 1000,
    max_axle_load_tons: oneAxleLoad! / 1000,
  } : null;

  const twoAxleLoad = axleLoad(profiles, 'TWO_CARGO_SPLIT');
  const exactOrCalculatedLength = positive(vehicle.combined_length_with_trailer_mm)
    ? vehicle.combined_length_with_trailer_mm
    : positive(vehicle.length_mm) && positive(vehicle.coupling_length_mm) && positive(trailer?.length_mm)
      ? vehicle.length_mm + vehicle.coupling_length_mm + trailer.length_mm
      : null;
  const twoMissing = [
    ...(!vehicle.can_use_trailer ? ['разрешение машины на использование прицепа'] : []),
    ...(!trailer ? ['назначенный совместимый прицеп'] : []),
    ...missingValues([
      ['полная длина автопоезда или параметры сцепки', exactOrCalculatedLength],
      ['ширина машины', vehicle.width_mm],
      ['высота машины', vehicle.height_mm],
      ['собственная масса машины', vehicle.tare_weight_kg],
      ['длина платформы машины', vehicle.platform_length_mm],
      ['высота платформы машины', vehicle.platform_height_from_ground_mm],
      ['длина прицепа', trailer?.length_mm],
      ['ширина прицепа', trailer?.width_mm],
      ['высота прицепа', trailer?.height_mm],
      ['собственная масса прицепа', trailer?.tare_weight_kg],
      ['длина платформы прицепа', trailer?.platform_length_mm],
      ['высота платформы прицепа', trailer?.platform_height_from_ground_mm],
      ['длина бытовки для предпросмотра', cargo.length_mm],
      ['ширина бытовки для предпросмотра', cargo.width_mm],
      ['высота бытовки для предпросмотра', cargo.height_mm],
      ['масса бытовки для предпросмотра', cargo.weight_kg],
      ['фактическая осевая нагрузка «две бытовки»', twoAxleLoad],
    ]),
  ];
  const twoMetrics = twoMissing.length === 0 && trailer ? {
    length_meters: (
      exactOrCalculatedLength!
      + Math.max(0, cargo.length_mm! - vehicle.platform_length_mm!)
      + Math.max(0, cargo.length_mm! - trailer.platform_length_mm!)
    ) / 1000,
    width_meters: (Math.max(vehicle.width_mm!, trailer.width_mm!, cargo.width_mm!) + vehicle.width_safety_margin_mm) / 1000,
    height_meters: (
      Math.max(
        vehicle.height_mm!,
        vehicle.platform_height_from_ground_mm! + cargo.height_mm!,
        trailer.height_mm!,
        trailer.platform_height_from_ground_mm! + cargo.height_mm!,
      )
      + vehicle.height_safety_margin_mm
    ) / 1000,
    weight_tons: (
      vehicle.tare_weight_kg!
      + trailer.tare_weight_kg!
      + cargo.weight_kg! * 2
      + vehicle.weight_safety_margin_kg
    ) / 1000,
    max_axle_load_tons: twoAxleLoad! / 1000,
  } : null;

  return {
    one: { title: 'Одна бытовка · без прицепа', trailer_attached: false, metrics: oneMetrics, missing: oneMissing },
    two: { title: 'Две бытовки · машина + прицеп', trailer_attached: true, metrics: twoMetrics, missing: twoMissing },
    retained_trailer_length_meters: twoMetrics?.length_meters ?? null,
  };
}
