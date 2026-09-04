import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { WorkloadGenerationInput } from '../../api/client';
import { DatePicker } from '../../components/DatePicker';
import { Button, Field, Modal } from '../../components/ui';
import { formatDate, nextDate } from '../../utils/format';

const workloadSchema = z.object({
  start_date: z.string().date('Укажите дату начала'),
  days: z.number().int('Укажите целое число дней').min(1, 'Минимум 1 день').max(31, 'Максимум 31 день'),
  deliveries_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 доставок в день'),
  pickups_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 вывозов в день'),
  alternative_dates_count: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(3, 'Максимум 3 альтернативы'),
}).superRefine((values, context) => {
  if (values.alternative_dates_count > values.days - 1) {
    context.addIssue({
      code: 'custom',
      path: ['alternative_dates_count'],
      message: 'Альтернативных дат не может быть больше, чем дней минус один',
    });
  }
});

/** Values submitted by the operator-facing workload generator form. */
type WorkloadValues = z.infer<typeof workloadSchema>;

function previewEndDate(startDate: string, days: number): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(startDate) || !Number.isInteger(days) || days < 1) return null;
  return nextDate(startDate, days - 1);
}

/** Form for generating random delivery and pickup workload for the selected warehouse. */
export function WorkloadGeneratorDialog({ planningDate, busy, onClose, onSubmit }: {
  planningDate: string;
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
      description="Создаст случайные тестовые доставки и вывозы. Для выбранного дня или периода прежняя тестовая нагрузка и планы этих дат заменяются; остальные даты не затрагиваются."
      onClose={onClose}
    >
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit(values))}>
        <DatePicker
          className="span-2"
          label="Дата начала"
          value={values.start_date}
          onChange={(startDate) => setValue('start_date', startDate, { shouldDirty: true, shouldValidate: true })}
          disabled={busy}
        />
        {errors.start_date?.message ? <span className="span-2 field__error">{errors.start_date.message}</span> : null}
        <Field label="Дней" type="number" min="1" max="31" {...register('days', { valueAsNumber: true })} error={errors.days?.message} />
        <Field label="Доставок в день" type="number" min="0" max="10" {...register('deliveries_per_day', { valueAsNumber: true })} error={errors.deliveries_per_day?.message} />
        <Field label="Вывозов в день" type="number" min="0" max="10" {...register('pickups_per_day', { valueAsNumber: true })} error={errors.pickups_per_day?.message} />
        <Field label="Альтернативных дат" type="number" min="0" max="3" {...register('alternative_dates_count', { valueAsNumber: true })} error={errors.alternative_dates_count?.message} hint="Не больше дней минус один" />

        <div className="span-2 detail-grid" data-testid="workload-preview">
          <div className="detail-item"><small>Горизонт</small><strong>{endDate ? `${formatDate(values.start_date)} — ${formatDate(endDate)}` : 'Укажите период'}</strong></div>
          <div className="detail-item"><small>Всего позиций</small><strong>{deliveriesTotal + pickupsTotal}</strong></div>
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
