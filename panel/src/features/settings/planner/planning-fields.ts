import type { PlanningSettings } from "./planning-types"

export interface NumericSetting {
  key: Exclude<
    keyof PlanningSettings,
    "deliveries_before_pickups" | "allow_soft_overtime"
  >
  label: string
  step?: string
  min?: number
  max?: number
  disabled?: boolean
  hint?: string
}

export const routingFields: NumericSetting[] = [
  { key: "city_speed_kmh", label: "Скорость в городе, км/ч", min: 1 },
  { key: "region_speed_kmh", label: "Скорость в области, км/ч", min: 1 },
  {
    key: "road_factor",
    label: "Коэффициент дорожного пути",
    step: "0.01",
    min: 1,
  },
  {
    key: "morning_traffic_multiplier",
    label: "Утренний трафик",
    step: "0.01",
    min: 1,
  },
  {
    key: "evening_traffic_multiplier",
    label: "Вечерний трафик",
    step: "0.01",
    min: 1,
  },
  {
    key: "max_detour_minutes",
    label: "Порог большого крюка, мин",
    min: 0,
    hint: "Превышение даёт предупреждение и штраф, но не создаёт лишний возврат на склад",
  },
  {
    key: "max_detour_ratio",
    label: "Порог доли крюка",
    step: "0.01",
    min: 0,
    hint: "Жёсткими остаются вместимость, окна, смена и запрещённая связь зон",
  },
  { key: "max_candidate_neighbors", label: "Число соседних точек", min: 1 },
]

export const operationFields: NumericSetting[] = [
  { key: "max_delivery_stops", label: "Доставок в цикле", min: 1, max: 2 },
  { key: "max_pickup_stops", label: "Вывозов в цикле", min: 1, max: 2 },
  { key: "default_load_minutes", label: "Среднее время загрузки, мин", min: 0 },
  {
    key: "default_unload_minutes",
    label: "Среднее время выгрузки, мин",
    min: 0,
  },
  { key: "default_pickup_minutes", label: "Вывоз, мин", min: 0 },
  { key: "default_route_buffer_minutes", label: "Резерв цикла, мин", min: 0 },
  {
    key: "max_customer_wait_minutes",
    label: "Максимум ожидания между клиентами, мин",
    min: 0,
    hint: "Более длинный разрыв разделяет задания на разные рейсы со стартом со склада",
  },
  {
    key: "default_service_minutes",
    label: "Обслуживание по умолчанию, мин",
    min: 0,
  },
  {
    key: "default_buffer_minutes",
    label: "Общий резерв по умолчанию, мин",
    min: 0,
  },
]

export const optimizationFields: NumericSetting[] = [
  { key: "max_optimization_seconds", label: "Лимит оптимизации, сек", min: 1 },
  {
    key: "max_local_search_iterations",
    label: "Итераций локального поиска",
    min: 0,
  },
  {
    key: "empty_travel_weight",
    label: "Вес пустого пробега",
    step: "0.1",
    min: 0,
  },
  { key: "detour_weight", label: "Вес крюка", step: "0.1", min: 0 },
  {
    key: "additional_resource_activation_penalty",
    label: "Штраф дополнительной машины/водителя",
    step: "1",
    min: 0,
    hint: "Эквивалент минут пути за подключение ещё одной смены; Временные окна и лимиты остаются важнее",
  },
  {
    key: "preferred_shift_utilization_percent",
    label: "Целевая загрузка смены, %",
    step: "1",
    min: 1,
    max: 100,
    hint: "До этой загрузки алгоритм предпочитает повторно использовать уже задействованного водителя",
  },
  {
    key: "driver_workload_weight",
    label: "Штраф нагрузки сверх цели",
    step: "0.1",
    min: 0,
    hint: "Стоимость каждой минуты работы сверх целевой загрузки смены",
  },
  {
    key: "paired_delivery_bonus",
    label: "Бонус пары доставок",
    step: "0.1",
    min: 0,
  },
  {
    key: "paired_pickup_bonus",
    label: "Бонус пары вывозов",
    step: "0.1",
    min: 0,
  },
  {
    key: "unassigned_hard_task_penalty",
    label: "Штраф обязательной задачи",
    step: "1",
    min: 0,
  },
  {
    key: "last_available_date_penalty",
    label: "Штраф последней даты",
    step: "1",
    min: 0,
  },
  { key: "max_trace_events", label: "Лимит записей расчёта", min: 0 },
  { key: "trace_sample_rate", label: "Шаг записи расчёта", min: 1 },
]
