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
    tare_weight_t: trailer?.tare_weight_t ?? null,
    max_gross_weight_t: trailer?.max_gross_weight_t ?? null,
    axle_load_t: trailer?.axle_load_t ?? null,
    payload_capacity_t: trailer?.payload_capacity_t ?? null,
    length_m: trailer?.length_m ?? null,
    width_m: trailer?.width_m ?? null,
    height_m: trailer?.height_m ?? null,
    platform_length_m: trailer?.platform_length_m ?? null,
    platform_height_from_ground_m: trailer?.platform_height_from_ground_m ?? null,
    max_platform_payload_t: trailer?.max_platform_payload_t ?? null,
    axle_count: trailer?.axle_count ?? null,
    max_cargo_length_m: trailer?.max_cargo_length_m ?? null,
    max_cargo_width_m: trailer?.max_cargo_width_m ?? null,
    max_cargo_height_m: trailer?.max_cargo_height_m ?? null,
    max_cargo_weight_t: trailer?.max_cargo_weight_t ?? null,
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
