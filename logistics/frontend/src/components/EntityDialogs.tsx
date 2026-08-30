import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  Trailer,
  Vehicle,
  VehicleLoadConfigurationType,
  Warehouse,
} from '../domain/types';
import type {
  DriverInput,
  LogisticsRequestInput,
  AvailableDriver,
  ShiftInput,
  VehicleInput,
  VehicleLoadProfileInput,
  WarehouseUpdateInput,
} from '../api/client';
import { Button, CheckboxField, Field, Modal, SelectField } from './ui';
import { TruckConfigurationPreview } from '../features/vehicles/TruckConfigurationPreview';
import { DateRangePicker } from './DatePicker';

const optionalUuidSchema = z.string().trim().refine(
  (value) => value === '' || /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value),
  'Укажите корректный UUID',
);


const warehouseSchema = z.object({
  loading_minutes: z.number().int().min(0),
  unloading_minutes: z.number().int().min(0),
  turnaround_minutes: z.number().int().min(0),
  working_day_start: z.string().min(4),
  working_day_end: z.string().min(4),
  default_planning_date: z.string().date().optional(),
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
      turnaround_minutes: warehouse.turnaround_minutes,
      working_day_start: warehouse.working_day_start,
      working_day_end: warehouse.working_day_end,
      default_planning_date: warehouse.default_planning_date ?? undefined,
    },
  });
  return (
    <Modal title="Настроить склад" description={`${warehouse.address} · ${warehouse.timezone}`} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit({
        loading_minutes: values.loading_minutes,
        unloading_minutes: values.unloading_minutes,
        turnaround_minutes: values.turnaround_minutes,
        working_day_start: values.working_day_start,
        working_day_end: values.working_day_end,
        ...(values.default_planning_date ? { default_planning_date: values.default_planning_date } : {}),
      }))}>
        <div className="span-2 detail-item"><small>Связь с RWMS</small><strong>{warehouse.name}</strong><span>{warehouse.address}</span></div>
        <Field label="Загрузка, мин" type="number" {...register('loading_minutes', { valueAsNumber: true })} />
        <Field label="Выгрузка, мин" type="number" {...register('unloading_minutes', { valueAsNumber: true })} />
        <Field label="Оборот на складе, мин" type="number" {...register('turnaround_minutes', { valueAsNumber: true })} />
        <Field label="Начало дня" type="time" {...register('working_day_start')} />
        <Field label="Конец дня" type="time" {...register('working_day_end')} error={errors.working_day_end?.message} />
        <div className="span-2 detail-item"><small>Изохроны и цены</small><span>Настраиваются отдельно в разделе «Настройки», чтобы всегда сохранять полную тарифную лестницу.</span></div>
        <Field className="span-2" label="Дата планирования по умолчанию" type="date" {...register('default_planning_date')} />
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
  external_worker_id: optionalUuidSchema,
  rwms_assignment_mode: z.enum(['ASSIGNED_DRIVER', 'WAREHOUSE_DRIVERS']),
  passport_details: z.string(),
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
}).superRefine((value, context) => {
  if (value.rwms_assignment_mode === 'ASSIGNED_DRIVER' && !value.external_worker_id) {
    context.addIssue({ code: 'custom', path: ['external_worker_id'], message: 'Выберите водителя RWMS' });
  }
});
type CatalogValues = z.infer<typeof catalogSchema>;

export interface VehicleEditorInput extends VehicleInput {
  load_profiles: VehicleLoadProfileInput[];
}

const optionalNumber = (value: unknown): number | null => typeof value !== 'string' || value.trim() === '' ? null : Number(value);
const optionalNumberRegistration = () => ({ setValueAs: optionalNumber } as const);
const optionalBoolean = (value: unknown): boolean | null => value === 'true' ? true : value === 'false' ? false : null;

export function CatalogDialog({ kind, value, trailers = [], availableDrivers = [], busy, onClose, onSubmit }: {
  kind: 'driver' | 'vehicle';
  value?: Driver | Vehicle | undefined;
  trailers?: Trailer[];
  availableDrivers?: AvailableDriver[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: DriverInput | VehicleEditorInput) => Promise<void>;
}) {
  const driver = kind === 'driver' ? value as Driver | undefined : undefined;
  const vehicle = kind === 'vehicle' ? value as Vehicle | undefined : undefined;
  const existingProfiles = vehicle?.load_profiles ?? [];
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<CatalogValues>({
    resolver: zodResolver(catalogSchema),
    defaultValues: {
      name: value?.name ?? (kind === 'driver' ? 'Водители склада' : ''), external_worker_id: driver?.external_worker_id ?? '', rwms_assignment_mode: driver?.rwms_assignment_mode ?? 'WAREHOUSE_DRIVERS', passport_details: driver?.passport_details ?? '', registration_number: vehicle?.registration_number ?? '',
      capacity: vehicle?.capacity ?? 2, average_speed_city: vehicle?.average_speed_city ?? 35, average_speed_region: vehicle?.average_speed_region ?? 65,
      notes: value?.notes ?? '', active: value?.active ?? true,
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
  const canUseTrailer = values.can_use_trailer;
  const selectedTrailer = trailers.find((trailer) => trailer.id === values.default_trailer_id) ?? null;
  const previewProfiles = values.load_profiles.flatMap((profile) => profile.max_actual_axle_load_kg === null ? [] : [{
    configuration_type: profile.configuration_type,
    max_actual_axle_load_kg: profile.max_actual_axle_load_kg,
  }]);
  const submit = (formValues: CatalogValues) => kind === 'driver'
    ? onSubmit({
      external_worker_id: formValues.rwms_assignment_mode === 'ASSIGNED_DRIVER' ? formValues.external_worker_id || null : null,
      rwms_assignment_mode: formValues.rwms_assignment_mode,
      passport_details: formValues.passport_details,
      notes: formValues.notes,
      active: formValues.active,
    })
    : onSubmit({
      name: formValues.name,
      registration_number: formValues.registration_number,
      capacity: formValues.capacity,
      active: formValues.active,
      average_speed_city: formValues.average_speed_city,
      average_speed_region: formValues.average_speed_region,
      notes: formValues.notes,
      vehicle_type: formValues.vehicle_type.trim() || null,
      manufacturer: formValues.manufacturer.trim() || null,
      model: formValues.model.trim() || null,
      is_hgv: formValues.is_hgv,
      tare_weight_kg: formValues.tare_weight_kg,
      max_gross_weight_kg: formValues.max_gross_weight_kg,
      length_mm: formValues.length_mm,
      width_mm: formValues.width_mm,
      height_mm: formValues.height_mm,
      axle_count: formValues.axle_count,
      max_axle_load_kg: formValues.max_axle_load_kg,
      payload_capacity_kg: formValues.payload_capacity_kg,
      platform_length_mm: formValues.platform_length_mm,
      platform_width_mm: formValues.platform_width_mm,
      platform_height_from_ground_mm: formValues.platform_height_from_ground_mm,
      max_platform_payload_kg: formValues.max_platform_payload_kg,
      max_cargo_length_mm: formValues.max_cargo_length_mm,
      max_cargo_width_mm: formValues.max_cargo_width_mm,
      max_cargo_height_mm: formValues.max_cargo_height_mm,
      max_cargo_weight_kg: formValues.max_cargo_weight_kg,
      can_use_trailer: formValues.can_use_trailer,
      default_trailer_id: formValues.default_trailer_id || null,
      combined_length_with_trailer_mm: formValues.combined_length_with_trailer_mm,
      coupling_length_mm: formValues.coupling_length_mm,
      height_safety_margin_mm: formValues.height_safety_margin_mm,
      width_safety_margin_mm: formValues.width_safety_margin_mm,
      weight_safety_margin_kg: formValues.weight_safety_margin_kg,
      load_profiles: formValues.load_profiles.flatMap((profile) => profile.max_actual_axle_load_kg === null ? [] : [{
        configuration_type: profile.configuration_type,
        max_actual_axle_load_kg: profile.max_actual_axle_load_kg,
      }]),
    });
  return (
    <Modal wide={kind === 'vehicle'} title={`${value ? 'Изменить' : 'Добавить'} ${kind === 'driver' ? 'водителя' : 'машину'}`} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(submit)}>
        {kind === 'vehicle' ? <Field className="span-2" label="Название" {...register('name')} error={errors.name?.message} /> : null}
        {kind === 'driver' ? <>
          <SelectField className="span-2" label="Назначение в RWMS" {...register('rwms_assignment_mode')}>
            <option value="WAREHOUSE_DRIVERS">Общий пул квалифицированных водителей склада</option>
            <option value="ASSIGNED_DRIVER">Конкретный водитель</option>
          </SelectField>
          {values.rwms_assignment_mode === 'ASSIGNED_DRIVER' ? <SelectField className="span-2" label="Водитель RWMS" {...register('external_worker_id')} error={errors.external_worker_id?.message}>
            <option value="">Выберите водителя</option>
            {driver?.external_worker_id && !availableDrivers.some((candidate) => candidate.worker_id === driver.external_worker_id) ? <option value={driver.external_worker_id}>{driver.name} · текущая связь</option> : null}
            {availableDrivers.map((candidate) => <option key={candidate.worker_id} value={candidate.worker_id}>{candidate.display_name}</option>)}
          </SelectField> : <div className="span-2 explanation">Планы автоматически публикуются для всех активных работников склада с квалификацией «Водители».</div>}
          <label className="field span-2"><span className="field__label">Паспортные данные водителя</span><textarea className="input" {...register('passport_details')} /><span className="field__hint">Используются только в тестовом сообщении, когда для доставки или вывоза явно включено такое оповещение.</span></label>
        </> : <>
          <Field label="Госномер" {...register('registration_number')} />
          <Field label="Вместимость" type="number" min="1" {...register('capacity', { valueAsNumber: true })} error={errors.capacity?.message} hint="Допустимо 1–2 бытовки" />
          <Field label="Скорость в городе" type="number" {...register('average_speed_city', { valueAsNumber: true })} />
          <Field label="Скорость в области" type="number" {...register('average_speed_region', { valueAsNumber: true })} />

          <details className="span-2" open>
            <summary><strong>Грузовая маршрутизация · машина</strong></summary>
            <p className="field__hint">Заполняйте фактические данные из документов. Если критическое значение неизвестно, оставьте поле пустым — безопасный маршрут не будет выдуман.</p>
            <div className="form-grid" style={{ marginTop: 10 }}>
              <Field label="Тип транспорта" {...register('vehicle_type')} />
              <SelectField label="Грузовой автомобиль" {...register('is_hgv', { setValueAs: optionalBoolean })}><option value="">Не указано</option><option value="true">Да</option><option value="false">Нет</option></SelectField>
              <Field label="Производитель" {...register('manufacturer')} />
              <Field label="Модель" {...register('model')} />
              <Field label="Собственная масса машины, кг" type="number" min="1" {...register('tare_weight_kg', optionalNumberRegistration())} error={errors.tare_weight_kg?.message} />
              <Field label="Максимальная полная масса машины, кг" type="number" min="1" {...register('max_gross_weight_kg', optionalNumberRegistration())} error={errors.max_gross_weight_kg?.message} />
              <Field label="Длина машины, мм" type="number" min="1" {...register('length_mm', optionalNumberRegistration())} error={errors.length_mm?.message} />
              <Field label="Ширина машины, мм" type="number" min="1" {...register('width_mm', optionalNumberRegistration())} error={errors.width_mm?.message} />
              <Field label="Высота машины, мм" type="number" min="1" {...register('height_mm', optionalNumberRegistration())} error={errors.height_mm?.message} />
              <Field label="Количество осей машины" type="number" min="1" {...register('axle_count', optionalNumberRegistration())} error={errors.axle_count?.message} />
              <Field label="Допустимая нагрузка на ось машины, кг" type="number" min="1" {...register('max_axle_load_kg', optionalNumberRegistration())} error={errors.max_axle_load_kg?.message} />
              <Field label="Грузоподъёмность машины, кг" type="number" min="1" {...register('payload_capacity_kg', optionalNumberRegistration())} error={errors.payload_capacity_kg?.message} />
            </div>
          </details>

          <details className="span-2" open>
            <summary><strong>Платформа и допустимый груз</strong></summary>
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
          </details>

          <details className="span-2" open>
            <summary><strong>Прицеп и запасы безопасности</strong></summary>
            <div className="form-grid" style={{ marginTop: 10 }}>
              <SelectField label="Машина может использовать прицеп" {...register('can_use_trailer', { setValueAs: optionalBoolean })}><option value="">Не указано</option><option value="true">Да</option><option value="false">Нет</option></SelectField>
              <SelectField label="Основной прицеп" disabled={canUseTrailer !== true} {...register('default_trailer_id')}><option value="">Не назначен</option>{trailers.map((trailer) => <option key={trailer.id} value={trailer.id}>{trailer.name} · {trailer.registration_number || 'без номера'}</option>)}</SelectField>
              <Field label="Точная полная длина автопоезда, мм" type="number" min="1" {...register('combined_length_with_trailer_mm', optionalNumberRegistration())} error={errors.combined_length_with_trailer_mm?.message} />
              <Field label="Длина сцепки, мм" type="number" min="1" {...register('coupling_length_mm', optionalNumberRegistration())} error={errors.coupling_length_mm?.message} hint="Используется только если точная полная длина не задана" />
              <Field label="Запас по высоте, мм" type="number" min="0" {...register('height_safety_margin_mm', { valueAsNumber: true })} error={errors.height_safety_margin_mm?.message} />
              <Field label="Запас по ширине, мм" type="number" min="0" {...register('width_safety_margin_mm', { valueAsNumber: true })} error={errors.width_safety_margin_mm?.message} />
              <Field label="Запас по массе, кг" type="number" min="0" {...register('weight_safety_margin_kg', { valueAsNumber: true })} error={errors.weight_safety_margin_kg?.message} />
            </div>
          </details>

          <details className="span-2" open>
            <summary><strong>Фактические осевые нагрузки</strong></summary>
            <p className="field__hint">Это проверенные эксплуатационные значения для каждой конфигурации. Система не делит общую массу на количество осей.</p>
            <div className="form-grid" style={{ marginTop: 10 }}>
              {values.load_profiles.map((profile, index) => <Field key={profile.configuration_type} label={`${loadProfileLabels[profile.configuration_type]}, кг`} type="number" min="1" {...register(`load_profiles.${index}.max_actual_axle_load_kg`, optionalNumberRegistration())} error={errors.load_profiles?.[index]?.max_actual_axle_load_kg?.message} />)}
            </div>
          </details>

          <details className="span-2" open>
            <summary><strong>Бытовка для предпросмотра</strong></summary>
            <p className="field__hint">Эти четыре поля не сохраняются. В реальном рейсе размеры и масса берутся из назначенной доставки или вывоза.</p>
            <div className="form-grid" style={{ marginTop: 10 }}>
              <Field label="Длина бытовки для предпросмотра, мм" type="number" min="1" {...register('preview_cargo_length_mm', optionalNumberRegistration())} />
              <Field label="Ширина бытовки для предпросмотра, мм" type="number" min="1" {...register('preview_cargo_width_mm', optionalNumberRegistration())} />
              <Field label="Высота бытовки для предпросмотра, мм" type="number" min="1" {...register('preview_cargo_height_mm', optionalNumberRegistration())} />
              <Field label="Масса бытовки для предпросмотра, кг" type="number" min="1" {...register('preview_cargo_weight_kg', optionalNumberRegistration())} />
            </div>
          </details>

          <TruckConfigurationPreview
            vehicle={{
              length_mm: values.length_mm,
              width_mm: values.width_mm,
              height_mm: values.height_mm,
              tare_weight_kg: values.tare_weight_kg,
              platform_length_mm: values.platform_length_mm,
              platform_height_from_ground_mm: values.platform_height_from_ground_mm,
              can_use_trailer: values.can_use_trailer === true,
              combined_length_with_trailer_mm: values.combined_length_with_trailer_mm,
              coupling_length_mm: values.coupling_length_mm,
              height_safety_margin_mm: values.height_safety_margin_mm,
              width_safety_margin_mm: values.width_safety_margin_mm,
              weight_safety_margin_kg: values.weight_safety_margin_kg,
            }}
            trailer={selectedTrailer}
            cargo={{ length_mm: values.preview_cargo_length_mm, width_mm: values.preview_cargo_width_mm, height_mm: values.preview_cargo_height_mm, weight_kg: values.preview_cargo_weight_kg }}
            profiles={previewProfiles}
          />
        </>}
        <label className="field span-2"><span className="field__label">Заметки</span><textarea className="input" {...register('notes')} /></label>
        <div className="span-2"><CheckboxField label="Активен" checked={active} onChange={(checked) => setValue('active', checked)} /></div>
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy}>Сохранить</Button></div>
      </form>
    </Modal>
  );
}

const shiftSchema = z.object({
  driver_id: z.string().min(1, 'Выберите водителя'), vehicle_id: z.string().min(1, 'Выберите машину'), date_from: z.string().date(), date_to: z.string().date(),
  start_time: z.string(), end_time: z.string(), break_minutes: z.number().int().min(0), active: z.boolean(),
})
  .refine((value) => value.end_time > value.start_time, { path: ['end_time'], message: 'Конец смены должен быть позже начала' })
  .refine((value) => value.date_to >= value.date_from, { path: ['date_to'], message: 'Конец периода должен быть не раньше начала' });
type ShiftValues = z.infer<typeof shiftSchema>;

export function ShiftDialog({ shift, warehouse, drivers, vehicles, busy, onClose, onSubmit }: {
  shift?: DriverShift | undefined; warehouse: Warehouse; drivers: Driver[]; vehicles: Vehicle[]; busy: boolean; onClose: () => void; onSubmit: (input: ShiftInput) => Promise<void>;
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
        <Field className="span-2" label="Перерыв, мин" type="number" {...register('break_minutes', { valueAsNumber: true })} />
        <div className="span-2"><CheckboxField label="Смена активна" checked={active} onChange={(checked) => setValue('active', checked)} /></div>
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}><Button type="button" onClick={onClose}>Отмена</Button><Button type="submit" variant="primary" disabled={busy}>Сохранить смену</Button></div>
      </form>
    </Modal>
  );
}

const dateOptionSchema = z.object({ date: z.string().date(), priority: z.number().int(), window_start: z.string().nullable(), window_end: z.string().nullable(), is_hard: z.boolean() })
  .refine((value) => Boolean(value.window_start) === Boolean(value.window_end), { message: 'Укажите обе границы окна', path: ['window_end'] })
  .refine((value) => !value.window_start || !value.window_end || value.window_end > value.window_start, { message: 'Конец окна должен быть позже начала', path: ['window_end'] });
const requestSchema = z.object({
  type: z.enum(['DELIVERY', 'PICKUP']), name: z.string().trim().min(1, 'Введите название'), address_label: z.string(), latitude: z.number().min(-90).max(90), longitude: z.number().min(-180).max(180),
  quantity: z.number().int().positive(), service_minutes: z.number().int().min(0), priority: z.number().int(), status: z.enum(['DRAFT', 'READY', 'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'UNASSIGNED']),
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
      quantity: request?.quantity ?? 1, service_minutes: request?.service_minutes ?? 30, priority: request?.priority ?? 0, mandatory: request?.mandatory ?? false, status: request?.status ?? 'READY', split_allowed: request?.split_allowed ?? true, notes: request?.notes ?? '',
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
  return (
    <Modal wide title={request ? requestType === 'DELIVERY' ? 'Изменить доставку' : 'Изменить вывоз' : type === 'DELIVERY' ? 'Новая доставка' : 'Новый вывоз'} description="Стоимость доставки backend рассчитает по изохроне склада" onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(onSubmit)}>
        <input type="hidden" {...register('latitude', { valueAsNumber: true })} />
        <input type="hidden" {...register('longitude', { valueAsNumber: true })} />
        <SelectField label="Тип" {...register('type')}><option value="DELIVERY">Доставка</option><option value="PICKUP">Вывоз</option></SelectField>
        <SelectField label="Статус" {...register('status')}><option value="READY">Готова</option><option value="DRAFT">Черновик</option><option value="CANCELLED">Отменена</option></SelectField>
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
        <div className="span-2 entity-card__row"><strong>Фактические параметры одной бытовки</strong><span className="field__hint">Без полного набора безопасный грузовой маршрут не рассчитывается</span></div>
        <Field label="Длина бытовки, мм" type="number" min="1" {...register('cargo_length_mm', optionalNumberRegistration())} error={errors.cargo_length_mm?.message} />
        <Field label="Ширина бытовки, мм" type="number" min="1" {...register('cargo_width_mm', optionalNumberRegistration())} error={errors.cargo_width_mm?.message} />
        <Field label="Высота бытовки, мм" type="number" min="1" {...register('cargo_height_mm', optionalNumberRegistration())} error={errors.cargo_height_mm?.message} />
        <Field label="Масса бытовки, кг" type="number" min="1" {...register('cargo_weight_kg', optionalNumberRegistration())} error={errors.cargo_weight_kg?.message} />
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
                  <Field label="Дата" type="date" {...register(`date_options.${index}.date`)} />
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
