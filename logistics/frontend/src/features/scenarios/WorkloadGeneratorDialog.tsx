import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { WorkloadGenerationInput } from '../../api/client';
import { Button, CheckboxField, Field, Modal } from '../../components/ui';
import { formatDate, nextDate } from '../../utils/format';

const workloadSchema = z.object({
  start_date: z.string().date('Укажите дату начала'),
  days: z.number().int('Укажите целое число дней').min(1, 'Минимум 1 день').max(31, 'Максимум 31 день'),
  deliveries_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 доставок в день'),
  pickups_per_day: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(10, 'Максимум 10 вывозов в день'),
  alternative_dates_count: z.number().int('Укажите целое число').min(0, 'Минимум 0').max(3, 'Максимум 3 альтернативы'),
  seed: z.number().int('Seed должен быть целым числом'),
  replace_existing_generated: z.boolean(),
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
      seed,
      replace_existing_generated: false,
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
      description="Добавит воспроизводимые доставки и вывозы в текущий сценарий; удаление возможно только для созданных генератором незапланированных тестовых заявок."
      onClose={onClose}
    >
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit(values))}>
        <Field className="span-2" label="Дата начала" type="date" {...register('start_date')} error={errors.start_date?.message} />
        <Field label="Дней" type="number" min="1" max="31" {...register('days', { valueAsNumber: true })} error={errors.days?.message} />
        <Field label="Доставок в день" type="number" min="0" max="10" {...register('deliveries_per_day', { valueAsNumber: true })} error={errors.deliveries_per_day?.message} />
        <Field label="Вывозов в день" type="number" min="0" max="10" {...register('pickups_per_day', { valueAsNumber: true })} error={errors.pickups_per_day?.message} />
        <Field label="Альтернативных дат" type="number" min="0" max="3" {...register('alternative_dates_count', { valueAsNumber: true })} error={errors.alternative_dates_count?.message} hint="Не больше дней минус один" />
        <Field label="Seed" type="number" step="1" {...register('seed', { valueAsNumber: true })} error={errors.seed?.message} />

        <div className="span-2">
          <CheckboxField
            label="Перегенерировать выбранный период"
            checked={values.replace_existing_generated}
            onChange={(checked) => setValue('replace_existing_generated', checked, { shouldDirty: true, shouldValidate: true })}
          />
          <p className="field__hint">Удаляются только созданные генератором незапланированные тестовые заявки.</p>
        </div>

        <div className="span-2 detail-grid" data-testid="workload-preview">
          <div className="detail-item"><small>Горизонт</small><strong>{endDate ? `${formatDate(values.start_date)} — ${formatDate(endDate)}` : 'Укажите период'}</strong></div>
          <div className="detail-item"><small>Всего заявок</small><strong>{deliveriesTotal + pickupsTotal}</strong></div>
          <div className="detail-item"><small>Доставки</small><strong>{deliveriesTotal}</strong></div>
          <div className="detail-item"><small>Вывозы</small><strong>{pickupsTotal}</strong></div>
        </div>

        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}>
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Генерируем…' : values.replace_existing_generated ? 'Перегенерировать нагрузку' : 'Сгенерировать нагрузку'}</Button>
        </div>
      </form>
    </Modal>
  );
}
