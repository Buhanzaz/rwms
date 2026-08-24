import { useEffect, useState } from 'react';
import type { PlanningSettings } from '../../domain/types';
import { Button, CheckboxField, Field } from '../../components/ui';

interface NumericSetting {
  key: Exclude<keyof PlanningSettings, 'deliveries_before_pickups' | 'allow_soft_overtime' | 'trace_enabled'>;
  label: string;
  step?: string;
  min?: number;
  max?: number;
  disabled?: boolean;
  hint?: string;
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
  { key: 'default_service_minutes', label: 'Обслуживание по умолчанию, мин', min: 0 },
  { key: 'default_buffer_minutes', label: 'Общий резерв по умолчанию, мин', min: 0 },
  { key: 'soft_overtime_limit_minutes', label: 'Мягкая переработка, мин', min: 0 },
];

const optimizationFields: NumericSetting[] = [
  { key: 'max_optimization_seconds', label: 'Лимит оптимизации, сек', min: 1 },
  { key: 'max_local_search_iterations', label: 'Итераций локального поиска', min: 0 },
  { key: 'empty_travel_weight', label: 'Вес пустого пробега', step: '0.1', min: 0 },
  { key: 'detour_weight', label: 'Вес крюка', step: '0.1', min: 0 },
  { key: 'cross_group_penalty', label: 'Штраф другой группы', step: '0.1', min: 0 },
  { key: 'driver_preference_bonus', label: 'Бонус предпочтения водителя', step: '0.1', min: 0 },
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

export function SettingsEditor({ settings, busy, onSave }: { settings: PlanningSettings; busy: boolean; onSave: (settings: PlanningSettings) => Promise<void> }) {
  const [draft, setDraft] = useState(settings);
  useEffect(() => setDraft(settings), [settings]);
  const onNumber = (key: NumericSetting['key'], value: number) => setDraft((current) => ({ ...current, [key]: value }));
  return (
    <div>
      <h2 className="section-title">Настройки алгоритма</h2>
      <p className="section-subtitle">Снимок настроек сохраняется в каждом запуске оптимизации.</p>
      <SettingGroup title="Маршрутизация" fields={routingFields} settings={draft} onNumber={onNumber} />
      <SettingGroup title="Операции" fields={operationFields} settings={draft} onNumber={onNumber} />
      <SettingGroup title="Целевая функция" fields={optimizationFields} settings={draft} onNumber={onNumber} />
      <div className="entity-list">
        <CheckboxField label="В каждом цикле доставки раньше вывозов" checked disabled onChange={() => undefined} />
        <CheckboxField label="Разрешить мягкую переработку" checked={draft.allow_soft_overtime} onChange={(value) => setDraft((current) => ({ ...current, allow_soft_overtime: value }))} />
        <CheckboxField label="Показывать процесс поиска маршрута" checked={draft.trace_enabled ?? false} onChange={(value) => setDraft((current) => ({ ...current, trace_enabled: value }))} />
      </div>
      <div className="divider" />
      <Button variant="primary" disabled={busy} onClick={() => void onSave(draft)}>{busy ? 'Сохраняем…' : 'Сохранить настройки'}</Button>
    </div>
  );
}
