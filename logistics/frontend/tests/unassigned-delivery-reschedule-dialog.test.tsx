import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, type RequestRescheduleInput, type RequestRescheduleOptionsRead } from '../src/api/client';
import { UnassignedDeliveryRescheduleDialog } from '../src/features/planning/UnassignedDeliveryRescheduleDialog';
import { requestFixture } from './fixtures';

const TARGET_DATE = '2026-09-02';
const SOURCE_PLAN_ID = '11111111-1111-4111-8111-111111111111';
const HOLD_ID = '22222222-2222-4222-8222-222222222222';

function optionsFixture(overrides: Partial<RequestRescheduleOptionsRead> = {}): RequestRescheduleOptionsRead {
  return {
    request_id: 'request-1',
    request_version: 7,
    source_plan_id: SOURCE_PLAN_ID,
    source_plan_version: 13,
    order_id: 'order-1',
    order_version: 11,
    session_id: 'session-1',
    session_version: 5,
    current_slot: {
      slot_id: 'slot-current',
      slot_version: 2,
      date: '2026-09-01',
      kind: 'FIXED_WINDOW',
      window_start: '12:00:00',
      window_end: '15:00:00',
      delivery_price_rubles: 22_000,
      expires_at: '2026-09-01T18:30:00+03:00',
    },
    options: [
      {
        slot_id: 'slot-morning',
        slot_version: 3,
        date: TARGET_DATE,
        kind: 'FIXED_WINDOW',
        window_start: '09:00:00',
        window_end: '12:00:00',
        delivery_price_rubles: 24_000,
        expires_at: '2026-09-01T18:30:00+03:00',
      },
      {
        slot_id: 'slot-day',
        slot_version: 4,
        date: TARGET_DATE,
        kind: 'DURING_DAY',
        window_start: null,
        window_end: null,
        delivery_price_rubles: 19_000,
        expires_at: '2026-09-01T18:30:00+03:00',
      },
    ],
    ...overrides,
  };
}

function rwmsDelivery() {
  return requestFixture({
    version: 6,
    source_system: 'RWMS',
    external_id: 'order-1',
    type: 'DELIVERY',
    scheduled_date: '2026-09-01',
    contact_name: 'ООО Север',
  });
}

async function selectTargetDate(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Новая дата доставки' }));
  await user.click(await screen.findByRole('button', { name: /^среда, 2 сентября 2026 г\.$/u }));
}

describe('unassigned delivery reschedule', () => {
  it('calculates all slots for the chosen date and submits only the selected fresh slot fences', async () => {
    const user = userEvent.setup();
    const calculate = vi.fn().mockResolvedValue(optionsFixture());
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={onSubmit}
        onRetryQuarantined={() => Promise.resolve()}
      />,
    );

    expect(container.querySelector('input[type="time"]')).toBeNull();
    expect(screen.getByRole('button', { name: 'Перенести в выбранный слот' })).toBeDisabled();
    await selectTargetDate(user);

    await waitFor(() => expect(calculate).toHaveBeenCalledWith(
      'request-1',
      { expected_request_version: 6, date: TARGET_DATE },
      expect.any(AbortSignal),
    ));
    expect(await screen.findByRole('button', { name: /Слот 09:00–12:00/u })).toBeVisible();
    expect(screen.getByRole('button', { name: /Слот В течение дня/u })).toBeVisible();

    await user.click(screen.getByRole('button', { name: /Слот 09:00–12:00/u }));
    expect(screen.getByRole('button', { name: 'Перенести в выбранный слот' })).toBeDisabled();
    await user.click(screen.getByLabelText('Подтверждаю: выбранные дата и слот согласованы с клиентом'));
    await user.click(screen.getByRole('button', { name: 'Перенести в выбранный слот' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({
      expected_request_version: 7,
      source_plan_id: SOURCE_PLAN_ID,
      source_plan_version: 13,
      expected_order_version: 11,
      expected_session_version: 5,
      slot_id: 'slot-morning',
      slot_version: 3,
    }, expect.any(String)));
  });

  it('does not allow a manual or empty-slot transfer', async () => {
    const user = userEvent.setup();
    const calculate = vi.fn().mockResolvedValue(optionsFixture({ options: [] }));
    const onSubmit = vi.fn();
    const { container } = render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={onSubmit}
        onRetryQuarantined={() => Promise.resolve()}
      />,
    );

    await selectTargetDate(user);
    expect(await screen.findByText('На эту дату доступных слотов нет')).toBeVisible();
    expect(container.querySelector('input[type="time"]')).toBeNull();
    expect(screen.getByRole('button', { name: 'Перенести в выбранный слот' })).toBeDisabled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('reuses the same idempotency key when an unchanged apply attempt is retried', async () => {
    const user = userEvent.setup();
    const calculate = vi.fn().mockResolvedValue(optionsFixture());
    const onSubmit = vi.fn<(input: RequestRescheduleInput, idempotencyKey: string) => Promise<void>>()
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce(undefined);
    render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={onSubmit}
        onRetryQuarantined={() => Promise.resolve()}
      />,
    );

    await selectTargetDate(user);
    await user.click(await screen.findByRole('button', { name: /Слот 09:00–12:00/u }));
    await user.click(screen.getByLabelText('Подтверждаю: выбранные дата и слот согласованы с клиентом'));
    await user.click(screen.getByRole('button', { name: 'Перенести в выбранный слот' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Перенести в выбранный слот' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));

    const firstKey = onSubmit.mock.calls[0]?.[1];
    const secondKey = onSubmit.mock.calls[1]?.[1];
    expect(firstKey).toEqual(expect.any(String));
    expect(secondKey).toBe(firstKey);
  });

  it('continues only the persisted command when backend recovery is quarantined', async () => {
    const user = userEvent.setup();
    const calculate = vi.fn().mockRejectedValue(new ApiError(409, {
      code: 'REQUEST_RESCHEDULE_QUARANTINED',
      detail: 'Сохранённый перенос требует ручного безопасного повтора.',
      hold_id: HOLD_ID,
      quarantine_count: 2,
    }, 'conflict'));
    const onRetryQuarantined = vi.fn().mockResolvedValue(undefined);
    render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={() => Promise.resolve()}
        onRetryQuarantined={onRetryQuarantined}
      />,
    );

    await selectTargetDate(user);
    expect(await screen.findByText('Перенос требует безопасного восстановления')).toBeVisible();
    expect(screen.queryByRole('button', { name: /Слот/u })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Повторить' }));

    await waitFor(() => expect(onRetryQuarantined).toHaveBeenCalledWith(
      'request-1',
      { hold_id: HOLD_ID, expected_quarantine_count: 2 },
      expect.any(String),
    ));
  });

  it('reuses one idempotency key while retrying the same quarantined hold generation', async () => {
    const user = userEvent.setup();
    const quarantined = new ApiError(409, {
      code: 'REQUEST_RESCHEDULE_QUARANTINED',
      detail: 'Сохранённый перенос требует ручного безопасного повтора.',
      hold_id: HOLD_ID,
      quarantine_count: 2,
    }, 'conflict');
    const calculate = vi.fn().mockRejectedValue(quarantined);
    const onRetryQuarantined = vi.fn()
      .mockRejectedValueOnce(quarantined)
      .mockResolvedValueOnce(undefined);
    render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={() => Promise.resolve()}
        onRetryQuarantined={onRetryQuarantined}
      />,
    );

    await selectTargetDate(user);
    await user.click(await screen.findByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(onRetryQuarantined).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(onRetryQuarantined).toHaveBeenCalledTimes(2));

    expect(onRetryQuarantined.mock.calls[1]?.[1]).toEqual(onRetryQuarantined.mock.calls[0]?.[1]);
    expect(onRetryQuarantined.mock.calls[1]?.[2]).toBe(onRetryQuarantined.mock.calls[0]?.[2]);
  });

  it('does not expose an unsafe recovery retry without exact hold fences', async () => {
    const user = userEvent.setup();
    const calculate = vi.fn().mockRejectedValue(new ApiError(409, {
      code: 'REQUEST_RESCHEDULE_QUARANTINED',
      detail: 'Сохранённый перенос требует ручного безопасного повтора.',
    }, 'conflict'));
    const onRetryQuarantined = vi.fn();
    render(
      <UnassignedDeliveryRescheduleDialog
        request={rwmsDelivery()}
        timeZone="Europe/Moscow"
        busy={false}
        onClose={() => undefined}
        calculate={calculate}
        onSubmit={() => Promise.resolve()}
        onRetryQuarantined={onRetryQuarantined}
      />,
    );

    await selectTargetDate(user);
    expect(await screen.findByText('Перенос требует безопасного восстановления')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(onRetryQuarantined).not.toHaveBeenCalled();
  });
});
