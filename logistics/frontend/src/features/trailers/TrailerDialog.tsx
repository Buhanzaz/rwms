import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { TrailerInput } from '../../api/client';
import { Button, Field, Modal } from '../../components/ui';
import type { Trailer } from '../../domain/types';

const trailerSchema = z.object({
  name: z.string().trim().min(1, 'Введите марку прицепа'),
  registration_number: z.string().trim().min(1, 'Введите госномер'),
});

type TrailerValues = z.infer<typeof trailerSchema>;

/** Keeps the catalog form small while preserving existing route data on edit. */
function trailerInput(values: TrailerValues, trailer?: Trailer): TrailerInput {
  return {
    name: values.name,
    registration_number: values.registration_number,
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
  };
}

export function TrailerDialog({ trailer, busy, onClose, onSubmit }: {
  trailer?: Trailer | undefined;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: TrailerInput) => Promise<void>;
}) {
  const { register, handleSubmit, formState: { errors } } = useForm<TrailerValues>({
    resolver: zodResolver(trailerSchema),
    defaultValues: {
      name: trailer?.name ?? '',
      registration_number: trailer?.registration_number ?? '',
    },
  });
  return (
    <Modal title={trailer ? `Изменить прицеп · ${trailer.name}` : 'Добавить прицеп'} onClose={onClose}>
      <form className="form-grid" onSubmit={handleSubmit((values) => onSubmit(trailerInput(values, trailer)))}>
        <Field className="span-2" label="Марка" {...register('name')} error={errors.name?.message} />
        <Field className="span-2" label="Госномер" {...register('registration_number')} error={errors.registration_number?.message} />
        <div className="span-2 toolbar-row" style={{ justifyContent: 'flex-end', margin: '8px 0 0' }}>
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" disabled={busy}>Сохранить прицеп</Button>
        </div>
      </form>
    </Modal>
  );
}
