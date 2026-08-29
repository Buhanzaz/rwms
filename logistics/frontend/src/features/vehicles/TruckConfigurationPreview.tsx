import type { Trailer, VehicleLoadProfile } from '../../domain/types';
import {
  calculateTruckConfigurationPreviews,
  type PreviewCargoInput,
  type PreviewVehicleInput,
  type TruckPreviewResult,
} from './truck-preview';

function formatMeters(value: number): string {
  return `${value.toFixed(2)} м`;
}

function formatTons(value: number): string {
  return `${value.toFixed(2)} т`;
}

function PreviewCard({ result }: { result: TruckPreviewResult }) {
  return (
    <article className="entity-card" data-testid={result.trailer_attached ? 'two-cargo-preview' : 'one-cargo-preview'}>
      <div className="entity-card__row"><strong>{result.title}</strong><span>{result.trailer_attached ? 'прицеп присоединён' : 'без прицепа'}</span></div>
      {result.metrics ? <div className="detail-grid">
        <div className="detail-item"><small>Длина</small><strong>{formatMeters(result.metrics.length_meters)}</strong></div>
        <div className="detail-item"><small>Ширина</small><strong>{formatMeters(result.metrics.width_meters)}</strong></div>
        <div className="detail-item"><small>Высота</small><strong>{formatMeters(result.metrics.height_meters)}</strong></div>
        <div className="detail-item"><small>Фактическая масса</small><strong>{formatTons(result.metrics.weight_tons)}</strong></div>
        <div className="detail-item"><small>Фактическая нагрузка на ось</small><strong>{formatTons(result.metrics.max_axle_load_tons)}</strong></div>
      </div> : <p className="field__hint">Не хватает данных: {result.missing.join(', ')}.</p>}
    </article>
  );
}

export function TruckConfigurationPreview({ vehicle, trailer, cargo, profiles }: {
  vehicle: PreviewVehicleInput;
  trailer: Trailer | null;
  cargo: PreviewCargoInput;
  profiles: VehicleLoadProfile[];
}) {
  const preview = calculateTruckConfigurationPreviews(vehicle, trailer, cargo, profiles);
  return (
    <section className="span-2" aria-label="Предпросмотр конфигураций">
      <h3 className="section-title">Предпросмотр конфигураций</h3>
      <p className="section-subtitle">Расчёт только для проверки введённых данных. Фактический маршрут использует параметры бытовок из доставки или вывоза.</p>
      <div className="entity-list"><PreviewCard result={preview.one} /><PreviewCard result={preview.two} /></div>
      {preview.retained_trailer_length_meters !== null ? <p className="explanation" data-testid="retained-trailer-preview">
        После выгрузки бытовки с прицепа прицеп остаётся присоединён. Длина автопоезда для следующего участка остаётся {formatMeters(preview.retained_trailer_length_meters)} до отдельного действия отсоединения.
      </p> : null}
    </section>
  );
}
