import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { TrailerInput } from '../../api/client';
import { Button, CheckboxField, Field, Modal } from '../../components/ui';
import type { Trailer } from '../../domain/types';

const nullablePositiveInteger = z.number().int().positive('Укажите положительное значение').nullable();

const trailerSchema = z.object({
  name: z.string().trim().min(1, 'Введите название'),
  registration_number: z.string().trim().min(1, 'Введите госномер'),
  active: z.boolean(),
  tare_weight_kg: nullablePositiveInteger,
  max_gross_weight_kg: nullablePositiveInteger,
  length_mm: nullablePositiveInteger,
  width_mm: nullablePositiveInteger,
  height_mm: nullablePositiveInteger,
  platform_length_mm: nullablePositiveInteger,
  platform_width_mm: nullablePositiveInteger,
  platform_height_from_ground_mm: nullablePositiveInteger,
  max_platform_payload_kg: nullablePositiveInteger,
  payload_capacity_kg: nullablePositiveInteger,
  axle_count: nullablePositiveInteger,
  max_axle_load_kg: nullablePositiveInteger,
  max_cargo_length_mm: nullablePositiveInteger,
  max_cargo_width_mm: nullablePositiveInteger,
  max_cargo_height_mm: nullablePositiveInteger,
  max_cargo_weight_kg: nullablePositiveInteger,
  notes: z.string(),
});

type TrailerValues = z.infer<typeof trailerSchema>;

const optionalNumber = (value: unknown): number | null => typeof value !== 'string' || value.trim() === '' ? null : Number(value);

function numericRegistration() {
  return { setValueAs: optionalNumber } as const;
}

export function TrailerDialog({ trailer, busy, onClose, onSubmit }: {
  trailer?: Trailer | undefined;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: TrailerInput) => Promise<void>;
}) {
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<TrailerValues>({
    resolver: zodResolver(trailerSchema),
    defaultValues: {
      name: trailer?.name ?? '',
      registration_number: trailer?.registration_number ?? '',
      active: trailer?.active ?? true,
      tare_weight_kg: trailer?.tare_weight_kg ?? null,
      max_gross_weight_kg: trailer?.max_gross_weight_kg ?? null,
      length_mm: trailer?.length_mm ?? null,
      width_mm: trailer?.width_mm ?? null,
      height_mm: trailer?.height_mm ?? null,
      platform_length_mm: trailer?.platform_length_mm ?? null,
      platform_width_mm: trailer?.platform_width_mm ?? null,
      platform_height_from_ground_mm: trailer?.platform_height_from_ground_mm ?? null,
      max_platform_payload_kg: trailer?.max_platform_payload_kg ?? null,
      payload_capacity_kg: trailer?.payload_capacity_kg ?? null,
      axle_count: trailer?.axle_count ?? null,
      max_axle_load_kg: trailer?.max_axle_load_kg ?? null,
      max_cargo_length_mm: trailer?.max_cargo_length_mm ?? null,
      max_cargo_width_mm: trailer?.max_cargo_width_mm ?? null,
      max_cargo_height_mm: trailer?.max_cargo_height_mm ?? null,
      max_cargo_weight_kg: trailer?.max_cargo_weight_kg ?? null,
      notes: trailer?.notes ?? '',
    },
  });
  const active = watch('active');
  return (
    <Modal wide title={trailer ? `Изменить прицеп · ${trailer.name}` : 'Добавить прицеп'} description="Пустое критическое поле не заменяется предположением: маршрут сообщит, какие данные нужно заполнить." onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit(onSubmit)}>
        <Field label="Название прицепа" {...register('name')} error={errors.name?.message} />
        <Field label="Госномер прицепа" {...register('registration_number')} error={errors.registration_number?.message} />
        <div className="span-2"><CheckboxField label="Прицеп активен" checked={active} onChange={(checked) => setValue('active', checked)} /></div>

        <details className="span-2" open>
          <summary><strong>Физические параметры</strong></summary>
          <div className="form-grid" style={{ marginTop: 10 }}>
            <Field label="Собственная масса прицепа, кг" type="number" min="1" {...register('tare_weight_kg', numericRegistration())} error={errors.tare_weight_kg?.message} />
            <Field label="Максимальная полная масса прицепа, кг" type="number" min="1" {...register('max_gross_weight_kg', numericRegistration())} error={errors.max_gross_weight_kg?.message} />
            <Field label="Длина прицепа, мм" type="number" min="1" {...register('length_mm', numericRegistration())} error={errors.length_mm?.message} />
            <Field label="Ширина прицепа, мм" type="number" min="1" {...register('width_mm', numericRegistration())} error={errors.width_mm?.message} />
            <Field label="Высота прицепа, мм" type="number" min="1" {...register('height_mm', numericRegistration())} error={errors.height_mm?.message} />
            <Field label="Количество осей прицепа" type="number" min="1" {...register('axle_count', numericRegistration())} error={errors.axle_count?.message} />
            <Field label="Допустимая нагрузка на ось прицепа, кг" type="number" min="1" {...register('max_axle_load_kg', numericRegistration())} error={errors.max_axle_load_kg?.message} />
            <Field label="Грузоподъёмность прицепа, кг" type="number" min="1" {...register('payload_capacity_kg', numericRegistration())} error={errors.payload_capacity_kg?.message} />
          </div>
        </details>

        <details className="span-2" open>
          <summary><strong>Платформа прицепа</strong></summary>
          <div className="form-grid" style={{ marginTop: 10 }}>
            <Field label="Длина платформы прицепа, мм" type="number" min="1" {...register('platform_length_mm', numericRegistration())} error={errors.platform_length_mm?.message} />
            <Field label="Ширина платформы прицепа, мм" type="number" min="1" {...register('platform_width_mm', numericRegistration())} error={errors.platform_width_mm?.message} />
            <Field label="Высота платформы прицепа от земли, мм" type="number" min="1" {...register('platform_height_from_ground_mm', numericRegistration())} error={errors.platform_height_from_ground_mm?.message} />
            <Field label="Максимальная масса на платформе прицепа, кг" type="number" min="1" {...register('max_platform_payload_kg', numericRegistration())} error={errors.max_platform_payload_kg?.message} />
          </div>
        </details>

        <details className="span-2" open>
          <summary><strong>Допустимый груз на прицепе</strong></summary>
          <div className="form-grid" style={{ marginTop: 10 }}>
            <Field label="Максимальная длина груза на прицепе, мм" type="number" min="1" {...register('max_cargo_length_mm', numericRegistration())} error={errors.max_cargo_length_mm?.message} />
            <Field label="Максимальная ширина груза на прицепе, мм" type="number" min="1" {...register('max_cargo_width_mm', numericRegistration())} error={errors.max_cargo_width_mm?.message} />
            <Field label="Максимальная высота груза на прицепе, мм" type="number" min="1" {...register('max_cargo_height_mm', numericRegistration())} error={errors.max_cargo_height_mm?.message} />
            <Field label="Максимальная масса груза на прицепе, кг" type="number" min="1" {...register('max_cargo_weight_kg', numericRegistration())} error={errors.max_cargo_weight_kg?.message} />
          </div>
        </details>

        <label className="field span-2"><span className="field__label">Заметки</span><textarea className="input" {...register('notes')} /></label>
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}>
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" disabled={busy}>Сохранить прицеп</Button>
        </div>
      </form>
    </Modal>
  );
}
