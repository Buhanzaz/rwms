import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  Vehicle,
  VehicleLoadConfigurationType,
  Warehouse,
} from '../domain/types';
import type {
  LogisticsRequestInput,
  ShiftInput,
  VehicleInput,
  VehicleLoadProfileInput,
  WarehouseUpdateInput,
} from '../api/client';
import { Button, CheckboxField, Field, Modal, SelectField } from './ui';
import { DatePicker, DateRangePicker } from './DatePicker';
import { formatDate, nextDate } from '../utils/format';
import { warehouseLocation } from '../domain/warehouse-presentation';

const warehouseSchema = z.object({
  loading_minutes: z.number().int().min(0),
  unloading_minutes: z.number().int().min(0),
  working_day_start: z.string().min(4),
  working_day_end: z.string().min(4),
}).refine((value) => value.working_day_end > value.working_day_start, {
  path: ['working_day_end'],
  message: 'Конец рабочего дня должен быть позже начала',
});

type WarehouseValues = z.infer<typeof warehouseSchema>;

export function WarehouseDialog({ warehouse, busy, onClose, onSubmit }: {
  warehouse: Warehouse;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: WarehouseUpdateInput) => Promise<void>;
}) {
  const { register, handleSubmit, formState: { errors } } = useForm<WarehouseValues>({
    resolver: zodResolver(warehouseSchema),
    defaultValues: {
      loading_minutes: warehouse.loading_minutes,
      unloading_minutes: warehouse.unloading_minutes,
      working_day_start: warehouse.working_day_start,
      working_day_end: warehouse.working_day_end,
    },
  });
  return (
    <Modal title="Настройки склада" description={warehouseLocation(warehouse)} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit({
        loading_minutes: values.loading_minutes,
        unloading_minutes: values.unloading_minutes,
        working_day_start: values.working_day_start,
        working_day_end: values.working_day_end,
      }))}>
        <Field label="Среднее время загрузки, мин" type="number" {...register('loading_minutes', { valueAsNumber: true })} />
        <Field label="Среднее время выгрузки, мин" type="number" {...register('unloading_minutes', { valueAsNumber: true })} />
        <Field label="Начало дня" type="time" {...register('working_day_start')} />
        <Field label="Конец дня" type="time" {...register('working_day_end')} error={errors.working_day_end?.message} />
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy}>{busy ? 'Сохраняем…' : 'Сохранить'}</Button></div>
      </form>
    </Modal>
  );
}

const nullablePositiveInteger = z.number().int().positive('Укажите положительное значение').nullable();
const nullableBoolean = z.boolean().nullable();
const loadConfigurationTypes = [
  'EMPTY_TRUCK',
  'CARGO_ON_TRUCK',
  'EMPTY_COMBINATION',
  'CARGO_ON_TRUCK_WITH_TRAILER',
  'CARGO_ON_TRAILER_WITH_TRAILER',
  'TWO_CARGO_SPLIT',
] as const satisfies readonly VehicleLoadConfigurationType[];
const loadProfileLabels: Record<VehicleLoadConfigurationType, string> = {
  EMPTY_TRUCK: 'Пустая машина',
  CARGO_ON_TRUCK: 'Одна бытовка на машине',
  EMPTY_COMBINATION: 'Машина + пустой прицеп',
  CARGO_ON_TRUCK_WITH_TRAILER: 'Бытовка на машине + пустой прицеп',
  CARGO_ON_TRAILER_WITH_TRAILER: 'Бытовка на прицепе',
  TWO_CARGO_SPLIT: 'Две бытовки: машина + прицеп',
};

const catalogSchema = z.object({
  name: z.string().trim().min(1, 'Введите название'),
  registration_number: z.string(),
  capacity: z.number().int().min(1, 'Вместимость должна быть не меньше 1').max(2, 'Для MVP вместимость не может превышать 2'),
  average_speed_city: z.number().positive(),
  average_speed_region: z.number().positive(),
  notes: z.string(),
  active: z.boolean(),
  vehicle_type: z.string(),
  manufacturer: z.string(),
  model: z.string(),
  is_hgv: nullableBoolean,
  tare_weight_kg: nullablePositiveInteger,
  max_gross_weight_kg: nullablePositiveInteger,
  length_mm: nullablePositiveInteger,
  width_mm: nullablePositiveInteger,
  height_mm: nullablePositiveInteger,
  axle_count: nullablePositiveInteger,
  max_axle_load_kg: nullablePositiveInteger,
  payload_capacity_kg: nullablePositiveInteger,
  platform_length_mm: nullablePositiveInteger,
  platform_width_mm: nullablePositiveInteger,
  platform_height_from_ground_mm: nullablePositiveInteger,
  max_platform_payload_kg: nullablePositiveInteger,
  max_cargo_length_mm: nullablePositiveInteger,
  max_cargo_width_mm: nullablePositiveInteger,
  max_cargo_height_mm: nullablePositiveInteger,
  max_cargo_weight_kg: nullablePositiveInteger,
  can_use_trailer: nullableBoolean,
  default_trailer_id: z.string(),
  combined_length_with_trailer_mm: nullablePositiveInteger,
  coupling_length_mm: nullablePositiveInteger,
  height_safety_margin_mm: z.number().int().min(0),
  width_safety_margin_mm: z.number().int().min(0),
  weight_safety_margin_kg: z.number().int().min(0),
  preview_cargo_length_mm: nullablePositiveInteger,
  preview_cargo_width_mm: nullablePositiveInteger,
  preview_cargo_height_mm: nullablePositiveInteger,
  preview_cargo_weight_kg: nullablePositiveInteger,
  load_profiles: z.array(z.object({
    id: z.string().optional(),
    configuration_type: z.enum(loadConfigurationTypes),
    max_actual_axle_load_kg: nullablePositiveInteger,
  })),
});
type CatalogValues = z.infer<typeof catalogSchema>;

export interface VehicleEditorInput extends VehicleInput {
  load_profiles: VehicleLoadProfileInput[];
}

const optionalNumber = (value: unknown): number | null => typeof value !== 'string' || value.trim() === '' ? null : Number(value);
const optionalNumberRegistration = () => ({ setValueAs: optionalNumber } as const);
const optionalBoolean = (value: unknown): boolean | null => value === 'true' ? true : value === 'false' ? false : null;

export function CatalogDialog({ value, busy, onClose, onSubmit }: {
  value?: Vehicle | undefined;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: VehicleEditorInput) => Promise<void>;
}) {
  const vehicle = value;
  const existingProfiles = vehicle?.load_profiles ?? [];
  const { register, handleSubmit, watch, setValue, formState: { errors, dirtyFields } } = useForm<CatalogValues>({
    resolver: zodResolver(catalogSchema),
    defaultValues: {
      name: vehicle?.name ?? '', registration_number: vehicle?.registration_number ?? '',
      capacity: vehicle?.capacity ?? 1, average_speed_city: vehicle?.average_speed_city ?? 35, average_speed_region: vehicle?.average_speed_region ?? 65,
      notes: vehicle?.notes ?? '', active: vehicle?.active ?? true,
      vehicle_type: vehicle?.vehicle_type ?? '', manufacturer: vehicle?.manufacturer ?? '', model: vehicle?.model ?? '', is_hgv: vehicle?.is_hgv ?? null,
      tare_weight_kg: vehicle?.tare_weight_kg ?? null, max_gross_weight_kg: vehicle?.max_gross_weight_kg ?? null,
      length_mm: vehicle?.length_mm ?? null, width_mm: vehicle?.width_mm ?? null, height_mm: vehicle?.height_mm ?? null,
      axle_count: vehicle?.axle_count ?? null, max_axle_load_kg: vehicle?.max_axle_load_kg ?? null, payload_capacity_kg: vehicle?.payload_capacity_kg ?? null,
      platform_length_mm: vehicle?.platform_length_mm ?? null, platform_width_mm: vehicle?.platform_width_mm ?? null,
      platform_height_from_ground_mm: vehicle?.platform_height_from_ground_mm ?? null, max_platform_payload_kg: vehicle?.max_platform_payload_kg ?? null,
      max_cargo_length_mm: vehicle?.max_cargo_length_mm ?? null, max_cargo_width_mm: vehicle?.max_cargo_width_mm ?? null,
      max_cargo_height_mm: vehicle?.max_cargo_height_mm ?? null, max_cargo_weight_kg: vehicle?.max_cargo_weight_kg ?? null,
      can_use_trailer: vehicle?.can_use_trailer ?? null, default_trailer_id: vehicle?.default_trailer_id ?? '',
      combined_length_with_trailer_mm: vehicle?.combined_length_with_trailer_mm ?? null, coupling_length_mm: vehicle?.coupling_length_mm ?? null,
      height_safety_margin_mm: vehicle?.height_safety_margin_mm ?? 0, width_safety_margin_mm: vehicle?.width_safety_margin_mm ?? 0,
      weight_safety_margin_kg: vehicle?.weight_safety_margin_kg ?? 0,
      preview_cargo_length_mm: null, preview_cargo_width_mm: null, preview_cargo_height_mm: null, preview_cargo_weight_kg: null,
      load_profiles: loadConfigurationTypes.map((configurationType) => {
        const existing = existingProfiles.find((profile) => profile.configuration_type === configurationType);
        return { configuration_type: configurationType, max_actual_axle_load_kg: existing?.max_actual_axle_load_kg ?? null };
      }),
    },
  });
  const values = watch();
  const active = values.active;
  const preserveVehicleValue = <K extends keyof Vehicle & keyof CatalogValues>(key: K, current: CatalogValues[K]): CatalogValues[K] => {
    if (dirtyFields[key]) return current;
    const existing = vehicle?.[key];
    return existing === undefined ? current : existing as unknown as CatalogValues[K];
  };
  const submit = (formValues: CatalogValues) => {
    const loadProfiles = formValues.load_profiles.flatMap((profile, index) => {
      const existing = existingProfiles.find((candidate) => candidate.configuration_type === profile.configuration_type);
      const maxActualAxleLoad = dirtyFields.load_profiles?.[index]?.max_actual_axle_load_kg
        ? profile.max_actual_axle_load_kg
        : existing?.max_actual_axle_load_kg ?? profile.max_actual_axle_load_kg;
      return maxActualAxleLoad === null ? [] : [{
        configuration_type: profile.configuration_type,
        max_actual_axle_load_kg: maxActualAxleLoad,
      }];
    });
    return onSubmit({
      name: preserveVehicleValue('name', formValues.name),
      registration_number: preserveVehicleValue('registration_number', formValues.registration_number),
      capacity: preserveVehicleValue('capacity', formValues.capacity),
      active: preserveVehicleValue('active', formValues.active),
      average_speed_city: preserveVehicleValue('average_speed_city', formValues.average_speed_city),
      average_speed_region: preserveVehicleValue('average_speed_region', formValues.average_speed_region),
      notes: preserveVehicleValue('notes', formValues.notes),
      vehicle_type: preserveVehicleValue('vehicle_type', formValues.vehicle_type).trim() || null,
      manufacturer: preserveVehicleValue('manufacturer', formValues.manufacturer).trim() || null,
      model: preserveVehicleValue('model', formValues.model).trim() || null,
      is_hgv: preserveVehicleValue('is_hgv', formValues.is_hgv),
      tare_weight_kg: preserveVehicleValue('tare_weight_kg', formValues.tare_weight_kg),
      max_gross_weight_kg: preserveVehicleValue('max_gross_weight_kg', formValues.max_gross_weight_kg),
      length_mm: preserveVehicleValue('length_mm', formValues.length_mm),
      width_mm: preserveVehicleValue('width_mm', formValues.width_mm),
      height_mm: preserveVehicleValue('height_mm', formValues.height_mm),
      axle_count: preserveVehicleValue('axle_count', formValues.axle_count),
      max_axle_load_kg: preserveVehicleValue('max_axle_load_kg', formValues.max_axle_load_kg),
      payload_capacity_kg: preserveVehicleValue('payload_capacity_kg', formValues.payload_capacity_kg),
      platform_length_mm: preserveVehicleValue('platform_length_mm', formValues.platform_length_mm),
      platform_width_mm: preserveVehicleValue('platform_width_mm', formValues.platform_width_mm),
      platform_height_from_ground_mm: preserveVehicleValue('platform_height_from_ground_mm', formValues.platform_height_from_ground_mm),
      max_platform_payload_kg: preserveVehicleValue('max_platform_payload_kg', formValues.max_platform_payload_kg),
      max_cargo_length_mm: preserveVehicleValue('max_cargo_length_mm', formValues.max_cargo_length_mm),
      max_cargo_width_mm: preserveVehicleValue('max_cargo_width_mm', formValues.max_cargo_width_mm),
      max_cargo_height_mm: preserveVehicleValue('max_cargo_height_mm', formValues.max_cargo_height_mm),
      max_cargo_weight_kg: preserveVehicleValue('max_cargo_weight_kg', formValues.max_cargo_weight_kg),
      can_use_trailer: preserveVehicleValue('can_use_trailer', formValues.can_use_trailer),
      default_trailer_id: preserveVehicleValue('default_trailer_id', formValues.default_trailer_id) || null,
      combined_length_with_trailer_mm: preserveVehicleValue('combined_length_with_trailer_mm', formValues.combined_length_with_trailer_mm),
      coupling_length_mm: preserveVehicleValue('coupling_length_mm', formValues.coupling_length_mm),
      height_safety_margin_mm: preserveVehicleValue('height_safety_margin_mm', formValues.height_safety_margin_mm),
      width_safety_margin_mm: preserveVehicleValue('width_safety_margin_mm', formValues.width_safety_margin_mm),
      weight_safety_margin_kg: preserveVehicleValue('weight_safety_margin_kg', formValues.weight_safety_margin_kg),
      load_profiles: loadProfiles,
    });
  };
  return (
    <Modal wide title={`${value ? 'Изменить' : 'Добавить'} транспортное средство`} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(submit)}>
        <Field className="span-2" label="Название" {...register('name')} error={errors.name?.message} />
        <>
          <Field label="Марка авто" {...register('manufacturer')} />
          <Field label="Госномер" {...register('registration_number')} />
          <Field label="Вместимость, бытовок" type="number" min="1" {...register('capacity', { valueAsNumber: true })} error={errors.capacity?.message} hint="Количество бытовок на одну машину" />
          <Field
            label="Грузоподъёмность, т"
            type="number"
            min="0.1"
            step="0.1"
            value={values.payload_capacity_kg === null ? '' : values.payload_capacity_kg / 1_000}
            onChange={(event) => setValue('payload_capacity_kg', optionalNumber(event.target.value) === null ? null : Math.round(Number(event.target.value) * 1_000), { shouldValidate: true })}
            error={errors.payload_capacity_kg?.message}
          />
          <Field
            label="Габариты (длина), м"
            type="number"
            min="0.1"
            step="0.1"
            value={values.length_mm === null ? '' : values.length_mm / 1_000}
            onChange={(event) => setValue('length_mm', optionalNumber(event.target.value) === null ? null : Math.round(Number(event.target.value) * 1_000), { shouldValidate: true })}
            error={errors.length_mm?.message}
          />
          <Field
            label="Габариты (ширина), м"
            type="number"
            min="0.1"
            step="0.1"
            value={values.width_mm === null ? '' : values.width_mm / 1_000}
            onChange={(event) => setValue('width_mm', optionalNumber(event.target.value) === null ? null : Math.round(Number(event.target.value) * 1_000), { shouldValidate: true })}
            error={errors.width_mm?.message}
          />
          <Field
            label="Габариты (высота), м"
            type="number"
            min="0.1"
            step="0.1"
            value={values.height_mm === null ? '' : values.height_mm / 1_000}
            onChange={(event) => setValue('height_mm', optionalNumber(event.target.value) === null ? null : Math.round(Number(event.target.value) * 1_000), { shouldValidate: true })}
            error={errors.height_mm?.message}
          />

          <details className="span-2">
            <summary><strong>Дополнительные настройки</strong></summary>
            <p className="field__hint">Маршрутизация использует эти эксплуатационные данные. Заполняйте их по документам; неизвестные критические параметры не будут подменены.</p>
            <div className="form-grid" style={{ marginTop: 10 }}>
              <Field label="Тип транспорта" {...register('vehicle_type')} />
              <SelectField label="Грузовой автомобиль" {...register('is_hgv', { setValueAs: optionalBoolean })}><option value="">Не указано</option><option value="true">Да</option><option value="false">Нет</option></SelectField>
              <Field label="Модель" {...register('model')} />
              <Field label="Собственная масса машины, кг" type="number" min="1" {...register('tare_weight_kg', optionalNumberRegistration())} error={errors.tare_weight_kg?.message} />
              <Field label="Максимальная полная масса машины, кг" type="number" min="1" {...register('max_gross_weight_kg', optionalNumberRegistration())} error={errors.max_gross_weight_kg?.message} />
              <Field label="Количество осей машины" type="number" min="1" {...register('axle_count', optionalNumberRegistration())} error={errors.axle_count?.message} />
              <Field label="Допустимая нагрузка на ось машины, кг" type="number" min="1" {...register('max_axle_load_kg', optionalNumberRegistration())} error={errors.max_axle_load_kg?.message} />
            </div>

            <div className="form-grid" style={{ marginTop: 10 }}>
              <Field label="Длина платформы машины, мм" type="number" min="1" {...register('platform_length_mm', optionalNumberRegistration())} error={errors.platform_length_mm?.message} />
              <Field label="Ширина платформы машины, мм" type="number" min="1" {...register('platform_width_mm', optionalNumberRegistration())} error={errors.platform_width_mm?.message} />
              <Field label="Высота платформы машины от земли, мм" type="number" min="1" {...register('platform_height_from_ground_mm', optionalNumberRegistration())} error={errors.platform_height_from_ground_mm?.message} />
              <Field label="Максимальная масса на платформе машины, кг" type="number" min="1" {...register('max_platform_payload_kg', optionalNumberRegistration())} error={errors.max_platform_payload_kg?.message} />
              <Field label="Максимальная длина груза на машине, мм" type="number" min="1" {...register('max_cargo_length_mm', optionalNumberRegistration())} error={errors.max_cargo_length_mm?.message} />
              <Field label="Максимальная ширина груза на машине, мм" type="number" min="1" {...register('max_cargo_width_mm', optionalNumberRegistration())} error={errors.max_cargo_width_mm?.message} />
              <Field label="Максимальная высота груза на машине, мм" type="number" min="1" {...register('max_cargo_height_mm', optionalNumberRegistration())} error={errors.max_cargo_height_mm?.message} />
              <Field label="Максимальная масса груза на машине, кг" type="number" min="1" {...register('max_cargo_weight_kg', optionalNumberRegistration())} error={errors.max_cargo_weight_kg?.message} />
            </div>
            <div className="form-grid" style={{ marginTop: 10 }}>
              <SelectField label="Транспорт поддерживает работу с прицепом" {...register('can_use_trailer', { setValueAs: optionalBoolean })}><option value="">Не указано</option><option value="true">Да</option><option value="false">Нет</option></SelectField>
              <Field label="Точная полная длина автопоезда, мм" type="number" min="1" {...register('combined_length_with_trailer_mm', optionalNumberRegistration())} error={errors.combined_length_with_trailer_mm?.message} />
              <Field label="Длина сцепки, мм" type="number" min="1" {...register('coupling_length_mm', optionalNumberRegistration())} error={errors.coupling_length_mm?.message} hint="Используется только если точная полная длина не задана" />
              <Field label="Запас по высоте, мм" type="number" min="0" {...register('height_safety_margin_mm', { valueAsNumber: true })} error={errors.height_safety_margin_mm?.message} />
              <Field label="Запас по ширине, мм" type="number" min="0" {...register('width_safety_margin_mm', { valueAsNumber: true })} error={errors.width_safety_margin_mm?.message} />
              <Field label="Запас по массе, кг" type="number" min="0" {...register('weight_safety_margin_kg', { valueAsNumber: true })} error={errors.weight_safety_margin_kg?.message} />
            </div>
            <div className="form-grid" style={{ marginTop: 10 }}>
              {values.load_profiles.map((profile, index) => <Field key={profile.configuration_type} label={`${loadProfileLabels[profile.configuration_type]}, кг`} type="number" min="1" {...register(`load_profiles.${index}.max_actual_axle_load_kg`, optionalNumberRegistration())} error={errors.load_profiles?.[index]?.max_actual_axle_load_kg?.message} />)}
            </div>
          </details>
        </>
        <label className="field span-2"><span className="field__label">Заметки</span><textarea className="input" {...register('notes')} /></label>
        <div className="span-2"><CheckboxField label="Активен" checked={active} onChange={(checked) => setValue('active', checked)} /></div>
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy}>Сохранить</Button></div>
      </form>
    </Modal>
  );
}

const MAX_SHIFT_RANGE_DAYS = 31;

function shiftDurationMinutes(startTime: string, endTime: string): number {
  const [startHours = 0, startMinutes = 0] = startTime.split(':').map(Number);
  const [endHours = 0, endMinutes = 0] = endTime.split(':').map(Number);
  const start = startHours * 60 + startMinutes;
  const end = endHours * 60 + endMinutes;
  if (end === start) return 0;
  return end > start ? end - start : end + 24 * 60 - start;
}

function inclusiveDateRangeDays(dateFrom: string, dateTo: string): number {
  const parse = (value: string) => {
    const [year = 0, month = 0, day = 0] = value.split('-').map(Number);
    return Date.UTC(year, month - 1, day);
  };
  return Math.floor((parse(dateTo) - parse(dateFrom)) / 86_400_000) + 1;
}

const shiftSchema = z.object({
  driver_id: z.string().min(1, 'Выберите водителя'), vehicle_id: z.string().min(1, 'Выберите машину'), date_from: z.string().date(), date_to: z.string().date(),
  start_time: z.string(), end_time: z.string(), break_minutes: z.number().int().min(0), active: z.boolean(),
})
  .refine((value) => value.end_time !== value.start_time, {
    path: ['end_time'],
    message: 'Окончание смены должно отличаться от начала',
  })
  .refine((value) => {
    const durationMinutes = shiftDurationMinutes(value.start_time, value.end_time);
    return durationMinutes > 0 && value.break_minutes < durationMinutes;
  }, { path: ['break_minutes'], message: 'Перерыв должен быть короче смены' })
  .refine((value) => value.date_to >= value.date_from, { path: ['date_to'], message: 'Конец периода должен быть не раньше начала' })
  .refine((value) => inclusiveDateRangeDays(value.date_from, value.date_to) <= MAX_SHIFT_RANGE_DAYS, {
    path: ['date_to'],
    message: 'Период смены не может быть длиннее 31 дня',
  });
type ShiftValues = z.infer<typeof shiftSchema>;

/** Mirrors the backend's positive half-open overlap rule for bounded recurring shifts. */
function shiftRangesOverlap(
  left: Pick<ShiftValues, 'date_from' | 'date_to' | 'start_time' | 'end_time'>,
  right: Pick<DriverShift, 'date_from' | 'date_to' | 'start_time' | 'end_time'>,
): boolean {
  const dayMilliseconds = 86_400_000;
  const dateValue = (value: string) => {
    const [year = 0, month = 0, day = 0] = value.split('-').map(Number);
    return Date.UTC(year, month - 1, day);
  };
  const minuteValue = (value: string) => {
    const [hours = 0, minutes = 0] = value.split(':').map(Number);
    return hours * 60 + minutes;
  };
  const intervals = (value: typeof left) => {
    const result: Array<readonly [number, number]> = [];
    const startMinute = minuteValue(value.start_time);
    const endMinute = minuteValue(value.end_time);
    for (let day = dateValue(value.date_from); day <= dateValue(value.date_to); day += dayMilliseconds) {
      const start = day + startMinute * 60_000;
      let end = day + endMinute * 60_000;
      if (end < start) end += dayMilliseconds;
      result.push([start, end]);
    }
    return result;
  };
  return intervals(left).some(([leftStart, leftEnd]) => intervals(right).some(
    ([rightStart, rightEnd]) => Math.max(leftStart, rightStart) < Math.min(leftEnd, rightEnd),
  ));
}

export function ShiftDialog({ shift, warehouse, drivers, vehicles, shifts = [], busy, onClose, onSubmit }: {
  shift?: DriverShift | undefined; warehouse: Warehouse; drivers: Driver[]; vehicles: Vehicle[]; shifts?: DriverShift[]; busy: boolean; onClose: () => void; onSubmit: (input: ShiftInput) => Promise<void>;
}) {
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<ShiftValues>({
    resolver: zodResolver(shiftSchema),
    defaultValues: {
      driver_id: shift?.driver_id ?? drivers[0]?.id ?? '', vehicle_id: shift?.vehicle_id ?? vehicles[0]?.id ?? '', date_from: shift?.date_from ?? warehouse.default_planning_date ?? '', date_to: shift?.date_to ?? warehouse.default_planning_date ?? '',
      start_time: shift?.start_time ?? '08:00', end_time: shift?.end_time ?? '20:00',
      break_minutes: shift?.break_minutes ?? 30, active: shift?.active ?? true,
    },
  });
  const active = watch('active');
  const values = watch();
  const overnight = values.end_time < values.start_time;
  const rangeDays = inclusiveDateRangeDays(values.date_from, values.date_to);
  const rangeReady = /^\d{4}-\d{2}-\d{2}$/.test(values.date_from) && /^\d{4}-\d{2}-\d{2}$/.test(values.date_to);
  const actualEndDate = rangeReady && overnight ? nextDate(values.date_to, 1) : values.date_to;
  const shiftConflict = active && rangeReady && values.start_time !== values.end_time
    ? shifts.find((candidate) => (
        candidate.active
        && candidate.id !== shift?.id
        && (candidate.driver_id === values.driver_id || candidate.vehicle_id === values.vehicle_id)
        && shiftRangesOverlap(values, candidate)
      ))
    : undefined;
  const conflictDriver = shiftConflict?.driver_id === values.driver_id
    ? drivers.find((driver) => driver.id === values.driver_id)
    : null;
  const conflictVehicle = !conflictDriver && shiftConflict?.vehicle_id === values.vehicle_id
    ? vehicles.find((vehicle) => vehicle.id === values.vehicle_id)
    : null;
  const submit = (values: ShiftValues) => onSubmit({
    driver_id: values.driver_id, vehicle_id: values.vehicle_id, date_from: values.date_from, date_to: values.date_to,
    start_time: values.start_time, end_time: values.end_time,
    break_minutes: values.break_minutes, active: values.active,
  });
  return (
    <Modal title={shift ? 'Изменить смену' : 'Добавить смену'} description={`Непрерывный период работы водителя · ${warehouse.timezone}`} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(submit)}>
        <SelectField label="Водитель" {...register('driver_id')} error={errors.driver_id?.message}>{drivers.map((driver) => <option key={driver.id} value={driver.id}>{driver.name}</option>)}</SelectField>
        <SelectField label="Машина" {...register('vehicle_id')} error={errors.vehicle_id?.message}>{vehicles.map((vehicle) => <option key={vehicle.id} value={vehicle.id}>{vehicle.name} · {vehicle.registration_number}</option>)}</SelectField>
        <DateRangePicker from={watch('date_from')} to={watch('date_to')} label="Период смены" onChange={(dateFrom, dateTo) => { setValue('date_from', dateFrom, { shouldValidate: true }); setValue('date_to', dateTo, { shouldValidate: true }); }} />
        {errors.date_to?.message ? <span className="span-2 field__error">{errors.date_to.message}</span> : null}
        <Field label="Начало" type="time" {...register('start_time')} />
        <Field label="Окончание" type="time" {...register('end_time')} error={errors.end_time?.message} />
        <Field className="span-2" label="Перерыв, мин" type="number" {...register('break_minutes', { valueAsNumber: true })} error={errors.break_minutes?.message} />
        <div className="span-2"><CheckboxField label="Смена активна" checked={active} onChange={(checked) => setValue('active', checked)} /></div>
        <div className="span-2 detail-item" role="status" aria-live="polite">
          <small>Фактический диапазон</small>
          <strong>{rangeReady ? `${formatDate(values.date_from)} ${values.start_time} — ${formatDate(actualEndDate)} ${values.end_time}` : 'Выберите начало и конец периода'}</strong>
          <span>{active ? 'Смена активна' : 'Смена неактивна'}{rangeReady ? ` · ${rangeDays} календарных дней${overnight ? ' · каждое окончание на следующий день' : ''}` : ''}</span>
        </div>
        {shiftConflict ? <div className="span-2 error-panel" role="alert">
          <strong>{conflictDriver ? 'Смены водителя пересекаются' : 'Машина уже занята в смене'}</strong>
          <p>{conflictDriver ? `У ${conflictDriver.name} уже есть активная смена` : `${conflictVehicle?.name ?? 'Выбранная машина'} уже назначена на активную смену`} {formatDate(shiftConflict.date_from)}–{formatDate(shiftConflict.date_to)}, {shiftConflict.start_time.slice(0, 5)}–{shiftConflict.end_time.slice(0, 5)}. Измените период, время или ресурс.</p>
        </div> : null}
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy || Boolean(shiftConflict)}>Сохранить смену</Button></div>
      </form>
    </Modal>
  );
}

const dateOptionSchema = z.object({ date: z.string().date(), priority: z.number().int(), window_start: z.string().nullable(), window_end: z.string().nullable(), is_hard: z.boolean() })
  .refine((value) => Boolean(value.window_start) === Boolean(value.window_end), { message: 'Укажите обе границы окна', path: ['window_end'] })
  .refine((value) => !value.window_start || !value.window_end || value.window_end > value.window_start, { message: 'Конец окна должен быть позже начала', path: ['window_end'] });
const requestSchema = z.object({
  type: z.enum(['DELIVERY', 'PICKUP']), name: z.string().trim().min(1, 'Введите название'), address_label: z.string(), latitude: z.number().min(-90).max(90), longitude: z.number().min(-180).max(180),
  quantity: z.number().int().positive(), service_minutes: z.number().int().min(0), priority: z.number().int(),
  mandatory: z.boolean(),
  trailer_access_allowed: nullableBoolean,
  include_driver_passport_in_notification: z.boolean(),
  contact_name: z.string(),
  contact_phone: z.string(),
  cargo_length_mm: nullablePositiveInteger, cargo_width_mm: nullablePositiveInteger, cargo_height_mm: nullablePositiveInteger, cargo_weight_kg: nullablePositiveInteger,
  split_allowed: z.boolean(), notes: z.string(), date_options: z.array(dateOptionSchema).min(1, 'Добавьте хотя бы одну дату'),
}).refine((value) => {
  const cargoValues = [value.cargo_length_mm, value.cargo_width_mm, value.cargo_height_mm, value.cargo_weight_kg];
  return cargoValues.every((cargoValue) => cargoValue === null) || cargoValues.every((cargoValue) => cargoValue !== null);
}, { message: 'Укажите все четыре параметра груза или оставьте все поля пустыми', path: ['cargo_weight_kg'] });
type RequestValues = z.infer<typeof requestSchema>;

export function RequestDialog({ request, point, initialAddress, type, defaultDate, busy, onClose, onSubmit }: {
  request?: LogisticsRequest | undefined; point?: { latitude: number; longitude: number } | undefined; initialAddress?: string | undefined; type: LogisticsRequest['type']; defaultDate: string; busy: boolean; onClose: () => void; onSubmit: (input: LogisticsRequestInput) => Promise<void>;
}) {
  const { register, control, handleSubmit, watch, setValue, formState: { errors } } = useForm<RequestValues>({
    resolver: zodResolver(requestSchema),
    defaultValues: {
      type: request?.type ?? type, name: request?.name ?? '', address_label: initialAddress ?? request?.address_label ?? '', latitude: point?.latitude ?? request?.latitude ?? 55.75, longitude: point?.longitude ?? request?.longitude ?? 37.62,
      quantity: request?.quantity ?? 1, service_minutes: request?.service_minutes ?? 30, priority: request?.priority ?? 0, mandatory: request?.mandatory ?? false, split_allowed: request?.split_allowed ?? true, notes: request?.notes ?? '',
      trailer_access_allowed: request?.trailer_access_allowed ?? null,
      include_driver_passport_in_notification: request?.include_driver_passport_in_notification ?? false,
      contact_name: request?.contact_name ?? '',
      contact_phone: request?.contact_phone ?? '',
      cargo_length_mm: request?.cargo_length_mm ?? null, cargo_width_mm: request?.cargo_width_mm ?? null,
      cargo_height_mm: request?.cargo_height_mm ?? null, cargo_weight_kg: request?.cargo_weight_kg ?? null,
      date_options: request?.date_options.map((option) => ({ date: option.date, priority: option.priority, window_start: option.window_start, window_end: option.window_end, is_hard: option.is_hard })) ?? [{ date: defaultDate, priority: 1, window_start: null, window_end: null, is_hard: false }],
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'date_options' });
  const splitAllowed = watch('split_allowed');
  const mandatory = watch('mandatory');
  const requestType = watch('type');
  const includePassport = watch('include_driver_passport_in_notification');
  const cargoFromOrder = request && [
    request.cargo_length_mm,
    request.cargo_width_mm,
    request.cargo_height_mm,
    request.cargo_weight_kg,
  ].every((value) => value != null)
    ? `${(request.cargo_length_mm! / 1_000).toFixed(1)} × ${(request.cargo_width_mm! / 1_000).toFixed(1)} × ${(request.cargo_height_mm! / 1_000).toFixed(1)} м · ${(request.cargo_weight_kg! / 1_000).toFixed(1)} т`
    : null;
  const submit = (values: RequestValues) => onSubmit({
    ...values,
    // Cargo is owned by the customer order. Preserve the canonical values
    // when editing a request instead of treating logistics as their editor.
    cargo_length_mm: request?.cargo_length_mm ?? null,
    cargo_width_mm: request?.cargo_width_mm ?? null,
    cargo_height_mm: request?.cargo_height_mm ?? null,
    cargo_weight_kg: request?.cargo_weight_kg ?? null,
  });
  return (
    <Modal wide title={request ? requestType === 'DELIVERY' ? 'Изменить доставку' : 'Изменить вывоз' : type === 'DELIVERY' ? 'Новая доставка' : 'Новый вывоз'} description="Стоимость доставки рассчитывается по изохроне склада" onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(submit)}>
        <input type="hidden" {...register('latitude', { valueAsNumber: true })} />
        <input type="hidden" {...register('longitude', { valueAsNumber: true })} />
        <SelectField label="Тип" {...register('type')}><option value="DELIVERY">Доставка</option><option value="PICKUP">Вывоз</option></SelectField>
        <Field className="span-2" label="Название / номер" {...register('name')} error={errors.name?.message} />
        <Field className="span-2" label="Адрес (подпись)" {...register('address_label')} />
        <Field label="Количество бытовок" type="number" min="1" {...register('quantity', { valueAsNumber: true })} />
        <Field label="Обслуживание, мин" type="number" min="0" {...register('service_minutes', { valueAsNumber: true })} />
        <SelectField className="span-2" label="Машина с прицепом проедет к адресу" {...register('trailer_access_allowed', { setValueAs: optionalBoolean })}>
          <option value="">Не согласовано — планирование будет заблокировано</option>
          <option value="true">Да, проезд с прицепом согласован</option>
          <option value="false">Нет, только без прицепа</option>
        </SelectField>
        <Field label="Контактное лицо" {...register('contact_name')} />
        <Field label="Телефон / контакт" {...register('contact_phone')} />
        <div className="span-2 planning-request-form__checks">
          <CheckboxField label={requestType === 'DELIVERY' ? 'Обязательная доставка' : 'Обязательный вывоз'} checked={mandatory} onChange={(checked) => setValue('mandatory', checked)} />
          <CheckboxField label="Оповещение с паспортными данными водителя" checked={includePassport} onChange={(checked) => setValue('include_driver_passport_in_notification', checked)} />
        </div>
        <div className="span-2 detail-item">
          <small>Параметры из заказа клиента</small>
          <strong>{cargoFromOrder ?? 'Ожидаем параметры бытовки из заказа'}</strong>
          <span>Логист выбирает заказ, а габариты и масса поступают из него автоматически.</span>
        </div>
        <Field label="Приоритет" type="number" {...register('priority', { valueAsNumber: true })} />
        <div className="field"><span className="field__label">Разбиение</span><CheckboxField label="Разрешить части по вместимости" checked={splitAllowed} onChange={(checked) => setValue('split_allowed', checked)} /></div>
        <label className="field span-2"><span className="field__label">Заметки</span><textarea className="input" {...register('notes')} /></label>
        <div className="span-2">
          <div className="entity-card__row"><strong>Допустимые даты и окна</strong><Button type="button" size="sm" onClick={() => append({ date: defaultDate, priority: 0, window_start: null, window_end: null, is_hard: false })}><Plus size={14} />Дата</Button></div>
          {typeof errors.date_options?.message === 'string' ? <span className="field__error">{errors.date_options.message}</span> : null}
          <div className="entity-list" style={{ marginTop: 8 }}>
            {fields.map((field, index) => (
              <div className="entity-card" key={field.id}>
                <div className="form-grid">
                  <DatePicker label="Дата" value={watch(`date_options.${index}.date`)} onChange={(date) => setValue(`date_options.${index}.date`, date, { shouldValidate: true })} />
                  <Field label="Приоритет даты" type="number" {...register(`date_options.${index}.priority`, { valueAsNumber: true })} />
                  <Field label="Начало окна" type="time" {...register(`date_options.${index}.window_start`, { setValueAs: (value: string) => value || null })} />
                  <Field label="Конец окна" type="time" {...register(`date_options.${index}.window_end`, { setValueAs: (value: string) => value || null })} error={errors.date_options?.[index]?.window_end?.message} />
                  <CheckboxField label="Жёсткое окно" checked={watch(`date_options.${index}.is_hard`)} onChange={(checked) => setValue(`date_options.${index}.is_hard`, checked)} />
                  <Button type="button" size="sm" variant="danger" disabled={fields.length === 1} onClick={() => remove(index)}><Trash2 size={13} />Удалить дату</Button>
                </div>
              </div>
            ))}
          </div>
        </div>
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy}>Сохранить {requestType === 'DELIVERY' ? 'доставку' : 'вывоз'}</Button></div>
      </form>
    </Modal>
  );
}

export function SimulationOverrideDialog({ kind, driverName, busy, onClose, onSubmit }: {
  kind: 'delay' | 'unavailable'; driverName: string; busy: boolean; onClose: () => void;
  onSubmit: (delayMinutes: number, reason: string) => Promise<void>;
}) {
  const schema = z.object({ delay_minutes: z.number().int().min(kind === 'delay' ? 1 : 0).max(24 * 60), reason: z.string().trim().min(3, 'Укажите причину') });
  type Values = z.infer<typeof schema>;
  const { register, handleSubmit, setValue, formState: { errors } } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { delay_minutes: kind === 'delay' ? 20 : 0, reason: kind === 'delay' ? 'Проверка задержки' : 'Водитель недоступен' } });
  return (
    <Modal title={kind === 'delay' ? `Добавить задержку · ${driverName}` : `Водитель недоступен · ${driverName}`} description="Изменение применяется как override симуляции и не перезаписывает исходный план" onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit(values.delay_minutes, values.reason))}>
        {kind === 'delay' ? <><div className="span-2 toolbar-row">{[10, 20, 30, 60].map((value) => <Button type="button" size="sm" key={value} onClick={() => setValue('delay_minutes', value)}>{value} мин</Button>)}</div><Field className="span-2" label="Задержка, мин" type="number" {...register('delay_minutes', { valueAsNumber: true })} error={errors.delay_minutes?.message} /></> : null}
        <Field className="span-2" label="Причина" {...register('reason')} error={errors.reason?.message} />
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant={kind === 'delay' ? 'primary' : 'danger'} disabled={busy}>Применить к симуляции</Button></div>
      </form>
    </Modal>
  );
}

export function ConfirmDialog({ title, description, confirmLabel, dangerous = false, busy, onClose, onConfirm }: {
  title: string; description: string; confirmLabel: string; dangerous?: boolean; busy: boolean; onClose: () => void; onConfirm: () => Promise<void>;
}) {
  return (
    <Modal title={title} description={description} onClose={onClose} footer={<><Button onClick={onClose}>Отмена</Button><Button variant={dangerous ? 'danger' : 'primary'} disabled={busy} onClick={() => void onConfirm()}>{confirmLabel}</Button></>}>
      <p className={dangerous ? 'error-panel' : 'section-subtitle'}>{description}</p>
    </Modal>
  );
}

/** Collect the mandatory audit reason before accepting an empty support positioning leg. */
export function EmptyPositioningConfirmDialog({ busy, onClose, onConfirm }: {
  busy: boolean;
  onClose: () => void;
  onConfirm: (reason: string) => Promise<void>;
}) {
  const schema = z.object({
    reason: z.string().trim().min(3, 'Укажите причину пустого перегона'),
  });
  type Values = z.infer<typeof schema>;
  const { register, handleSubmit, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { reason: '' },
  });
  return (
    <Modal
      title="Разрешить пустой перегон"
      description="Попутный груз не выбран. Причина сохранится в истории плана."
      onClose={onClose}
    >
      <form className="form-grid" onSubmit={handleSubmit(({ reason }) => onConfirm(reason.trim()))}>
        <Field
          className="span-2"
          label="Причина пустого перегона"
          placeholder="Почему рейс нужно выполнить без попутного груза"
          {...register('reason')}
          error={errors.reason?.message}
        />
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}>
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" disabled={busy}>Утвердить пустой перегон</Button>
        </div>
      </form>
    </Modal>
  );
}
