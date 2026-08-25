import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { WorkloadGenerationInput } from '../src/api/client';
import { WorkloadGeneratorDialog } from '../src/features/scenarios/WorkloadGeneratorDialog';

function renderDialog(onSubmit: (input: WorkloadGenerationInput) => Promise<void> = () => Promise.resolve()) {
  return render(
    <WorkloadGeneratorDialog
      planningDate="2026-08-25"
      seed={42}
      busy={false}
      onClose={() => undefined}
      onSubmit={onSubmit}
    />,
  );
}

describe('workload generator dialog', () => {
  it('shows defaults and a live horizon/totals preview', () => {
    renderDialog();

    expect(screen.getByLabelText('Дата начала')).toHaveValue('2026-08-25');
    expect(screen.getByLabelText('Дней')).toHaveValue(1);
    expect(screen.getByLabelText('Доставок в день')).toHaveValue(4);
    expect(screen.getByLabelText('Вывозов в день')).toHaveValue(4);
    expect(screen.getByLabelText('Альтернативных дат')).toHaveValue(0);
    expect(screen.getByLabelText('Seed')).toHaveValue(42);
    expect(screen.getByRole('checkbox', { name: 'Перегенерировать выбранный период' })).not.toBeChecked();
    expect(screen.getByText('Удаляются только созданные генератором незапланированные тестовые заявки.')).toBeVisible();
    expect(screen.getByTestId('workload-preview')).toHaveTextContent('25 августа 2026 г. — 25 августа 2026 г.');
    expect(screen.getByTestId('workload-preview')).toHaveTextContent('Всего заявок8');
  });

  it('automatically clamps alternatives when the selected horizon shrinks', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.clear(screen.getByLabelText('Дней'));
    await user.type(screen.getByLabelText('Дней'), '4');
    await user.clear(screen.getByLabelText('Альтернативных дат'));
    await user.type(screen.getByLabelText('Альтернативных дат'), '3');
    await user.clear(screen.getByLabelText('Дней'));
    await user.type(screen.getByLabelText('Дней'), '2');

    await waitFor(() => expect(screen.getByLabelText('Альтернативных дат')).toHaveValue(1));
  });

  it('submits the exact workload command after editing the form', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: WorkloadGenerationInput) => Promise<void>>(() => Promise.resolve());
    renderDialog(submit);

    await user.clear(screen.getByLabelText('Дата начала'));
    await user.type(screen.getByLabelText('Дата начала'), '2026-09-01');
    await user.clear(screen.getByLabelText('Дней'));
    await user.type(screen.getByLabelText('Дней'), '4');
    await user.clear(screen.getByLabelText('Доставок в день'));
    await user.type(screen.getByLabelText('Доставок в день'), '2');
    await user.clear(screen.getByLabelText('Вывозов в день'));
    await user.type(screen.getByLabelText('Вывозов в день'), '3');
    await user.clear(screen.getByLabelText('Альтернативных дат'));
    await user.type(screen.getByLabelText('Альтернативных дат'), '3');
    await user.clear(screen.getByLabelText('Seed'));
    await user.type(screen.getByLabelText('Seed'), '77');
    await user.click(screen.getByRole('button', { name: 'Сгенерировать нагрузку' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit).toHaveBeenCalledWith({
      start_date: '2026-09-01',
      days: 4,
      deliveries_per_day: 2,
      pickups_per_day: 3,
      alternative_dates_count: 3,
      seed: 77,
      replace_existing_generated: false,
    });
  });

  it('submits replacement intent and changes the submit action label', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: WorkloadGenerationInput) => Promise<void>>(() => Promise.resolve());
    renderDialog(submit);

    await user.click(screen.getByRole('checkbox', { name: 'Перегенерировать выбранный период' }));
    expect(screen.getByRole('button', { name: 'Перегенерировать нагрузку' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Перегенерировать нагрузку' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({
      days: 1,
      alternative_dates_count: 0,
      replace_existing_generated: true,
    }));
  });
});
