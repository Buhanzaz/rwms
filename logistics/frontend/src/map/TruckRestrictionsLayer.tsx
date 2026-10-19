import type { TruckRestrictionCategory } from '../api/client';
import { CheckboxField } from '../components/ui';
import {
  TRUCK_RESTRICTIONS_LAYER_LABEL,
  truckRestrictionPresentation,
  type TruckRestrictionLayerState,
} from './TruckRestrictions';

const LEGEND_CATEGORIES: TruckRestrictionCategory[] = [
  'HGV_ACCESS',
  'MAX_HEIGHT',
  'MAX_WIDTH',
  'MAX_LENGTH',
  'MAX_WEIGHT',
  'MAX_AXLE_LOAD',
  'CONDITIONAL',
  'TRAILER_ACCESS',
];

export function TruckRestrictionLayerMenuItem({
  checked,
  state,
  onChange,
}: {
  checked: boolean;
  state: TruckRestrictionLayerState;
  onChange: () => void;
}) {
  return (
    <section className="truck-restriction-layer-menu" data-testid="truck-restriction-layer-menu">
      <CheckboxField label={TRUCK_RESTRICTIONS_LAYER_LABEL} checked={checked} onChange={onChange} />
      {checked ? (
        <div className="truck-restriction-layer-menu__details">
          {state.status === 'loading' ? <p role="status">Загружаем ограничения в текущей области…</p> : null}
          {state.status === 'zoom' ? <p role="status">Приблизьте карту до масштаба 8 или крупнее.</p> : null}
          {state.status === 'loaded' ? (
            <p role="status">
              Показано ограничений: <strong>{state.count}</strong>
              {state.truncated ? <span> · показаны первые 2000, приблизьте карту</span> : null}
            </p>
          ) : null}
          {state.status === 'error' ? (
            <div className="truck-restriction-layer-menu__error" role="alert">
              <strong>Ограничения не загрузились</strong>
              <span>{state.error ?? 'Неизвестная ошибка'}</span>
            </div>
          ) : null}
          <div className="truck-restriction-legend" aria-label="Легенда грузовых ограничений">
            {LEGEND_CATEGORIES.map((category) => {
              const item = truckRestrictionPresentation(category);
              return (
                <span key={category}>
                  <i style={{ backgroundColor: item.color }}>{item.marker}</i>
                  {item.title}
                </span>
              );
            })}
          </div>
        </div>
      ) : null}
    </section>
  );
}
