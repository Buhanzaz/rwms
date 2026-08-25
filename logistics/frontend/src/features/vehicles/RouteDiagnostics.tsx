import type { RouteCycle, RouteLeg, RoutingProfileSnapshot } from '../../domain/types';

const configurationLabels: Record<RoutingProfileSnapshot['configurationType'], string> = {
  EMPTY_TRUCK: 'пустая машина',
  CARGO_ON_TRUCK: 'груз на машине',
  EMPTY_COMBINATION: 'машина + пустой прицеп',
  CARGO_ON_TRUCK_WITH_TRAILER: 'груз на машине + прицеп',
  CARGO_ON_TRAILER_WITH_TRAILER: 'груз на прицепе',
  TWO_CARGO_SPLIT: 'две бытовки: машина + прицеп',
};

function segmentTitle(cycle: RouteCycle, leg: RouteLeg, index: number): string {
  const from = cycle.stops.find((stop) => stop.id === leg.from_stop_id)?.label ?? 'начало';
  const to = cycle.stops.find((stop) => stop.id === leg.to_stop_id)?.label ?? 'конец';
  return `Участок ${index + 1}: ${from} → ${to}`;
}

function timestamp(value: string | null | undefined, timeZone: string): string {
  if (!value) return 'не указано';
  return new Date(value).toLocaleString('ru-RU', { timeZone });
}

function ProfileDetails({ profile, leg, timeZone }: {
  profile: RoutingProfileSnapshot;
  leg: RouteLeg;
  timeZone: string;
}) {
  return <>
    <div className="detail-grid">
      <div className="detail-item"><small>Конфигурация</small><strong>{configurationLabels[profile.configurationType]}</strong></div>
      <div className="detail-item"><small>Грузовой автомобиль</small><strong>{profile.isHgv ? 'да' : 'нет'}</strong></div>
      <div className="detail-item"><small>Бытовок на участке</small><strong>{profile.cargoCount}</strong></div>
      <div className="detail-item"><small>Прицеп</small><strong>{profile.trailerAttached ? `присоединён${profile.trailerId ? ` · ${profile.trailerId}` : ''}` : 'не присоединён'}</strong></div>
      <div className="detail-item"><small>Высота</small><strong>{profile.effectiveHeightMeters.toFixed(2)} м</strong></div>
      <div className="detail-item"><small>Ширина</small><strong>{profile.effectiveWidthMeters.toFixed(2)} м</strong></div>
      <div className="detail-item"><small>Полная длина</small><strong>{profile.effectiveLengthMeters.toFixed(2)} м</strong></div>
      <div className="detail-item"><small>Фактическая масса</small><strong>{profile.actualWeightTons.toFixed(2)} т</strong></div>
      <div className="detail-item"><small>Фактическая нагрузка на ось</small><strong>{profile.maxAxleLoadTons.toFixed(2)} т</strong></div>
      <div className="detail-item"><small>Осей</small><strong>{profile.axleCount}</strong></div>
      <div className="detail-item"><small>Провайдер</small><strong>{leg.routing_provider ?? profile.routingProvider ?? 'не указан'}</strong></div>
      <div className="detail-item"><small>Данные дорог</small><strong>{leg.osm_data_version ?? profile.osmDataVersion ?? 'версия не указана'}</strong></div>
      <div className="detail-item"><small>Рассчитан</small><strong>{timestamp(leg.routed_at ?? profile.calculatedAt, timeZone)}</strong></div>
    </div>
    {profile.cargoPlacements.length ? <ul>
      {profile.cargoPlacements.map((placement) => <li key={`${placement.cargoId}-${placement.position}`}>
        {placement.cargoId}: {placement.position === 'TRUCK_PLATFORM' ? 'на платформе машины' : 'на платформе прицепа'} · {placement.lengthMm}×{placement.widthMm}×{placement.heightMm} мм · {placement.weightKg} кг
      </li>)}
    </ul> : <p className="field__hint">На этом участке груз отсутствует.</p>}
  </>;
}

export function RouteDiagnostics({ cycles, timeZone }: { cycles: RouteCycle[]; timeZone: string }) {
  const legCount = cycles.reduce((count, cycle) => count + cycle.legs.length, 0);
  if (legCount === 0) return null;
  return (
    <section aria-label="Диагностика грузовых маршрутов">
      <h2 className="section-title">Диагностика грузовых маршрутов</h2>
      <p className="section-subtitle">Технический снимок фактической конфигурации машины хранится отдельно для каждого участка.</p>
      <div className="entity-list">
        {cycles.flatMap((cycle) => cycle.legs.map((leg, index) => {
          const profile = leg.routing_profile_snapshot;
          return <details className="entity-card" key={leg.id ?? `${cycle.id}-${index}`} open={Boolean(profile)}>
            <summary><strong>{segmentTitle(cycle, leg, index)}</strong></summary>
            <div style={{ marginTop: 10 }}>
              {profile
                ? <ProfileDetails profile={profile} leg={leg} timeZone={timeZone} />
                : <div className="error-panel" role="status"><strong>Грузовой профиль не сохранён</strong><p>Маршрут не подтверждён как truck-safe.</p></div>}
            </div>
          </details>;
        }))}
      </div>
    </section>
  );
}
