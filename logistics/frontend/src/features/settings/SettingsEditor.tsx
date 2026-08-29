import { useEffect, useState } from 'react';
import type { PlanningSettings, Warehouse } from '../../domain/types';
import type { WarehouseUpdateInput } from '../../api/client';
import { Button, CheckboxField, Field, SwitchField } from '../../components/ui';
import { useUiStore } from '../../stores/ui-store';

interface NumericSetting {
  key: Exclude<keyof PlanningSettings, 'deliveries_before_pickups' | 'allow_soft_overtime' | 'trace_enabled'>;
  label: string;
  step?: string;
  min?: number;
  max?: number;
  disabled?: boolean;
  hint?: string;
}

/** Editable warehouse tariff values keyed by their inclusive travel-time boundary. */
interface IsochroneTariffs {
  isochrone_price_60_minutes: number;
  isochrone_price_120_minutes: number;
  isochrone_price_180_minutes: number;
  isochrone_price_240_minutes: number;
}

const routingFields: NumericSetting[] = [
  { key: 'city_speed_kmh', label: 'Скорость в городе, км/ч', min: 1 },
  { key: 'region_speed_kmh', label: 'Скорость в области, км/ч', min: 1 },
  { key: 'road_factor', label: 'Коэффициент дорожного пути', step: '0.01', min: 1 },
  { key: 'morning_traffic_multiplier', label: 'Утренний трафик', step: '0.01', min: 1 },
  { key: 'evening_traffic_multiplier', label: 'Вечерний трафик', step: '0.01', min: 1 },
  {
    key: 'max_detour_minutes',
    label: 'Порог большого крюка, мин',
    min: 0,
    hint: 'Превышение даёт предупреждение и штраф, но не создаёт лишний возврат на склад',
  },
  {
    key: 'max_detour_ratio',
    label: 'Порог доли крюка',
    step: '0.01',
    min: 0,
    hint: 'Жёсткими остаются вместимость, окна, смена и запрещённая связь зон',
  },
  { key: 'max_candidate_neighbors', label: 'Соседей-кандидатов', min: 1 },
];

const operationFields: NumericSetting[] = [
  { key: 'vehicle_capacity', label: 'Вместимость машины', min: 2, max: 2, disabled: true, hint: 'Правило MVP зафиксировано backend: 2 бытовки' },
  { key: 'max_delivery_stops', label: 'Доставок в цикле', min: 1, max: 2 },
  { key: 'max_pickup_stops', label: 'Вывозов в цикле', min: 1, max: 2 },
  { key: 'default_load_minutes', label: 'Загрузка, мин', min: 0 },
  { key: 'default_unload_minutes', label: 'Выгрузка, мин', min: 0 },
  { key: 'default_pickup_minutes', label: 'Вывоз, мин', min: 0 },
  { key: 'default_depot_turnaround_minutes', label: 'Оборот на складе, мин', min: 0 },
  { key: 'default_route_buffer_minutes', label: 'Резерв цикла, мин', min: 0 },
  {
    key: 'max_customer_wait_minutes',
    label: 'Максимум ожидания между клиентами, мин',
    min: 0,
    hint: 'Более длинный разрыв разделяет задания на разные рейсы со стартом со склада',
  },
  { key: 'default_service_minutes', label: 'Обслуживание по умолчанию, мин', min: 0 },
  { key: 'default_buffer_minutes', label: 'Общий резерв по умолчанию, мин', min: 0 },
  { key: 'default_cargo_length_mm', label: 'Стандартная длина бытовки, мм', min: 1, max: 30_000 },
  { key: 'default_cargo_width_mm', label: 'Стандартная ширина бытовки, мм', min: 1, max: 10_000 },
  { key: 'default_cargo_height_mm', label: 'Стандартная высота бытовки, мм', min: 1, max: 10_000 },
  {
    key: 'default_cargo_weight_kg',
    label: 'Стандартная масса бытовки, кг',
    min: 1,
    max: 100_000,
    hint: 'Автоматически применяется к доставкам из RWMS, если источник не передал физические параметры груза',
  },
];

const optimizationFields: NumericSetting[] = [
  { key: 'max_optimization_seconds', label: 'Лимит оптимизации, сек', min: 1 },
  { key: 'max_local_search_iterations', label: 'Итераций локального поиска', min: 0 },
  { key: 'empty_travel_weight', label: 'Вес пустого пробега', step: '0.1', min: 0 },
  { key: 'detour_weight', label: 'Вес крюка', step: '0.1', min: 0 },
  {
    key: 'additional_resource_activation_penalty',
    label: 'Штраф дополнительной машины/водителя',
    step: '1',
    min: 0,
    hint: 'Эквивалент минут пути за подключение ещё одной смены; hard-окна и лимиты остаются важнее',
  },
  {
    key: 'preferred_shift_utilization_percent',
    label: 'Целевая загрузка смены, %',
    step: '1',
    min: 1,
    max: 100,
    hint: 'До этой загрузки алгоритм предпочитает повторно использовать уже задействованного водителя',
  },
  {
    key: 'driver_workload_weight',
    label: 'Штраф нагрузки сверх цели',
    step: '0.1',
    min: 0,
    hint: 'Стоимость каждой минуты работы сверх целевой загрузки смены',
  },
  { key: 'paired_delivery_bonus', label: 'Бонус пары доставок', step: '0.1', min: 0 },
  { key: 'paired_pickup_bonus', label: 'Бонус пары вывозов', step: '0.1', min: 0 },
  { key: 'unassigned_hard_task_penalty', label: 'Штраф обязательной задачи', step: '1', min: 0 },
  { key: 'last_available_date_penalty', label: 'Штраф последней даты', step: '1', min: 0 },
  { key: 'max_trace_events', label: 'Максимум trace events', min: 0 },
  { key: 'trace_sample_rate', label: 'Шаг sampling trace', min: 1 },
];

function SettingGroup({ title, fields, settings, onNumber }: {
  title: string; fields: NumericSetting[]; settings: PlanningSettings; onNumber: (key: NumericSetting['key'], value: number) => void;
}) {
  return (
    <section style={{ marginBottom: 16 }}>
      <h3>{title}</h3>
      <div className="form-grid">
        {fields.map((field) => <Field key={field.key} label={field.label} name={field.key} autoComplete="off" type="number" step={field.step ?? '1'} min={field.min} max={field.max} disabled={field.disabled} hint={field.hint} value={settings[field.key]} onChange={(event) => onNumber(field.key, Number(event.target.value))} />)}
      </div>
    </section>
  );
}

export function SettingsEditor({ warehouse, busy, onSave }: {
  warehouse: Warehouse;
  busy: boolean;
  onSave: (input: WarehouseUpdateInput) => Promise<void>;
}) {
  const [draft, setDraft] = useState(warehouse.settings);
  const [tariffs, setTariffs] = useState<IsochroneTariffs>({
    isochrone_price_60_minutes: warehouse.isochrone_price_60_minutes,
    isochrone_price_120_minutes: warehouse.isochrone_price_120_minutes,
    isochrone_price_180_minutes: warehouse.isochrone_price_180_minutes,
    isochrone_price_240_minutes: warehouse.isochrone_price_240_minutes,
  });
  const notificationDurationSeconds = useUiStore((state) => state.notificationDurationSeconds);
  const setNotificationDurationSeconds = useUiStore((state) => state.setNotificationDurationSeconds);
  useEffect(() => {
    setDraft(warehouse.settings);
    setTariffs({
      isochrone_price_60_minutes: warehouse.isochrone_price_60_minutes,
      isochrone_price_120_minutes: warehouse.isochrone_price_120_minutes,
      isochrone_price_180_minutes: warehouse.isochrone_price_180_minutes,
      isochrone_price_240_minutes: warehouse.isochrone_price_240_minutes,
    });
  }, [warehouse]);
  const onNumber = (key: NumericSetting['key'], value: number) => setDraft((current) => ({ ...current, [key]: value }));
  return (
    <div>
      <h2 className="section-title">Настройки алгоритма</h2>
      <p className="section-subtitle">Снимок настроек сохраняется в каждом запуске оптимизации.</p>
      <section style={{ marginBottom: 16 }}>
        <h3>Интерфейс</h3>
        <div className="form-grid">
          <Field
            label="Показывать уведомление, секунд"
            type="number"
            min="1"
            max="60"
            value={notificationDurationSeconds}
            onChange={(event) => setNotificationDurationSeconds(Number(event.target.value))}
            hint="После этого уведомление остаётся в истории под колокольчиком"
          />
        </div>
      </section>
      <section style={{ marginBottom: 16 }}>
        <h3>Стоимость доставки по изохронам</h3>
        <p className="section-subtitle">Используется первая достигнутая граница времени пути от склада. Полигон «Особая цена» переопределяет этот тариф.</p>
        <div className="form-grid">
          {([
            ['isochrone_price_60_minutes', 'До 1 часа, ₽'],
            ['isochrone_price_120_minutes', 'До 2 часов, ₽'],
            ['isochrone_price_180_minutes', 'До 3 часов, ₽'],
            ['isochrone_price_240_minutes', 'До 4 часов, ₽'],
          ] as const).map(([key, label]) => <Field
            key={key}
            label={label}
            type="number"
            min="0"
            step="1"
            value={tariffs[key]}
            onChange={(event) => setTariffs((current) => ({
              ...current,
              [key]: Number(event.target.value),
            }))}
          />)}
        </div>
      </section>
      <SettingGroup title="Маршрутизация" fields={routingFields} settings={draft} onNumber={onNumber} />
      <SettingGroup title="Операции" fields={operationFields} settings={draft} onNumber={onNumber} />
      <section className="settings-overtime" aria-label="Настройки переработки">
        <SwitchField
          label="Разрешить переработку"
          description="Планировщик сможет поставить ещё одну совместимую доставку или вывоз после обычного окончания смены"
          checked={draft.allow_soft_overtime}
          onCheckedChange={(value) => setDraft((current) => ({ ...current, allow_soft_overtime: value }))}
        />
        <Field
          label="Максимальная переработка, ч"
          type="number"
          step="0.25"
          min="0"
          max="12"
          disabled={!draft.allow_soft_overtime}
          value={draft.soft_overtime_limit_minutes / 60}
          onChange={(event) => setDraft((current) => ({
            ...current,
            soft_overtime_limit_minutes: Math.round(Math.max(0, Number(event.target.value)) * 60),
          }))}
          hint="Учитывается при построении маршрута и публикации доступных клиентских слотов; предел остаётся внутри календарного дня"
        />
      </section>
      <SettingGroup title="Целевая функция" fields={optimizationFields} settings={draft} onNumber={onNumber} />
      <div className="entity-list">
        <CheckboxField label="В каждом цикле доставки раньше вывозов" checked disabled onChange={() => undefined} />
        <CheckboxField label="Показывать процесс поиска маршрута" checked={draft.trace_enabled ?? false} onChange={(value) => setDraft((current) => ({ ...current, trace_enabled: value }))} />
      </div>
      <div className="divider" />
      <Button variant="primary" disabled={busy} onClick={() => void onSave({ settings: draft, ...tariffs })}>{busy ? 'Сохраняем…' : 'Сохранить настройки'}</Button>
    </div>
  );
}
