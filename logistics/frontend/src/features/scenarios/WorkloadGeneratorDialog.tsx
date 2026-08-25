import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { WorkloadGenerationInput } from '../../api/client';
import { Button, Field, Modal } from '../../components/ui';
import { formatDate, nextDate } from '../../utils/format';

const workloadSchema = z.object({
  start_date: z.string().date('Укажите дату начала'),
  days: z.number().int('Укажите целое число дней').min(1, 'Минимум 1 день').max(31, 'Максимум 31 день'),
  deliveries_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 доставок в день'),
  pickups_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 вывозов в день'),
  alternative_dates_count: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(3, 'Максимум 3 альтернативы'),
  cargo_length_mm: z.number().int('Укажите целое число').min(1, 'Укажите длину груза').max(30_000, 'Максимум 30 м'),
  cargo_width_mm: z.number().int('Укажите целое число').min(1, 'Укажите ширину груза').max(10_000, 'Максимум 10 м'),
  cargo_height_mm: z.number().int('Укажите целое число').min(1, 'Укажите высоту груза').max(10_000, 'Максимум 10 м'),
  cargo_weight_kg: z.number().int('Укажите целое число').min(1, 'Укажите массу груза').max(100_000, 'Максимум 100 т'),
  seed: z.number().int('Seed должен быть целым числом'),
}).superRefine((values, context) => {
  if (values.alternative_dates_count > values.days - 1) {
    context.addIssue({
      code: 'custom',
      path: ['alternative_dates_count'],
      message: 'Альтернативных дат не может быть больше, чем дней минус один',
    });
  }
});

/** Values submitted by the reproducible workload generator form. */
type WorkloadValues = z.infer<typeof workloadSchema>;

function previewEndDate(startDate: string, days: number): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(startDate) || !Number.isInteger(days) || days < 1) return null;
  return nextDate(startDate, days - 1);
}

/** Form for generating deterministic delivery and pickup workload in the current scenario. */
export function WorkloadGeneratorDialog({ planningDate, seed, busy, onClose, onSubmit }: {
  planningDate: string;
  seed: number;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: WorkloadGenerationInput) => Promise<void>;
}) {
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<WorkloadValues>({
    resolver: zodResolver(workloadSchema),
    mode: 'onChange',
    defaultValues: {
      start_date: planningDate,
      days: 1,
      deliveries_per_day: 4,
      pickups_per_day: 4,
      alternative_dates_count: 0,
      cargo_length_mm: 6_000,
      cargo_width_mm: 2_400,
      cargo_height_mm: 2_400,
      cargo_weight_kg: 2_500,
      seed,
    },
  });
  const values = watch();
  const days = watch('days');
  const alternativeDatesCount = watch('alternative_dates_count');
  useEffect(() => {
    if (!Number.isInteger(days) || !Number.isInteger(alternativeDatesCount)) return;
    const maxAlternatives = Math.max(0, days - 1);
    if (alternativeDatesCount > maxAlternatives) {
      setValue('alternative_dates_count', maxAlternatives, { shouldDirty: true, shouldValidate: true });
    }
  }, [alternativeDatesCount, days, setValue]);
  const endDate = previewEndDate(values.start_date, values.days);
  const deliveriesTotal = Number.isInteger(values.days) && Number.isInteger(values.deliveries_per_day)
    ? Math.max(0, values.days) * Math.max(0, values.deliveries_per_day)
    : 0;
  const pickupsTotal = Number.isInteger(values.days) && Number.isInteger(values.pickups_per_day)
    ? Math.max(0, values.days) * Math.max(0, values.pickups_per_day)
    : 0;

  return (
    <Modal
      wide
      title="Сгенерировать рабочую нагрузку"
      description="Создаст воспроизводимые доставки и вывозы. Для выбранного дня или периода прежняя нагрузка генератора заменяется атомарно; ручные и RWMS-заявки, а также другие даты не затрагиваются."
      onClose={onClose}
    >
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit(values))}>
        <Field className="span-2" label="Дата начала" type="date" {...register('start_date')} error={errors.start_date?.message} />
        <Field label="Дней" type="number" min="1" max="31" {...register('days', { valueAsNumber: true })} error={errors.days?.message} />
        <Field label="Доставок в день" type="number" min="0" max="10" {...register('deliveries_per_day', { valueAsNumber: true })} error={errors.deliveries_per_day?.message} />
        <Field label="Вывозов в день" type="number" min="0" max="10" {...register('pickups_per_day', { valueAsNumber: true })} error={errors.pickups_per_day?.message} />
        <Field label="Альтернативных дат" type="number" min="0" max="3" {...register('alternative_dates_count', { valueAsNumber: true })} error={errors.alternative_dates_count?.message} hint="Не больше дней минус один" />
        <Field label="Seed" type="number" step="1" {...register('seed', { valueAsNumber: true })} error={errors.seed?.message} />

        <div className="span-2 section-heading">
          <strong>Параметры одной грузовой единицы</strong>
          <small>Генератор сохранит эти фактические габариты и массу в каждой заявке.</small>
        </div>
        <Field label="Длина груза, мм" type="number" min="1" max="30000" {...register('cargo_length_mm', { valueAsNumber: true })} error={errors.cargo_length_mm?.message} />
        <Field label="Ширина груза, мм" type="number" min="1" max="10000" {...register('cargo_width_mm', { valueAsNumber: true })} error={errors.cargo_width_mm?.message} />
        <Field label="Высота груза, мм" type="number" min="1" max="10000" {...register('cargo_height_mm', { valueAsNumber: true })} error={errors.cargo_height_mm?.message} />
        <Field label="Масса груза, кг" type="number" min="1" max="100000" {...register('cargo_weight_kg', { valueAsNumber: true })} error={errors.cargo_weight_kg?.message} />

        <div className="span-2 detail-grid" data-testid="workload-preview">
          <div className="detail-item"><small>Горизонт</small><strong>{endDate ? `${formatDate(values.start_date)} — ${formatDate(endDate)}` : 'Укажите период'}</strong></div>
          <div className="detail-item"><small>Всего заявок</small><strong>{deliveriesTotal + pickupsTotal}</strong></div>
          <div className="detail-item"><small>Доставки</small><strong>{deliveriesTotal}</strong></div>
          <div className="detail-item"><small>Вывозы</small><strong>{pickupsTotal}</strong></div>
        </div>

        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}>
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Генерируем…' : 'Сгенерировать и заменить нагрузку'}</Button>
        </div>
      </form>
    </Modal>
  );
}
