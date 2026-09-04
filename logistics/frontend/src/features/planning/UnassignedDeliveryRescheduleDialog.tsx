import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  type RequestRescheduleInput,
  type RequestRescheduleOptionsInput,
  type RequestRescheduleOptionsRead,
  type RequestRescheduleRetryInput,
  type RequestRescheduleSlotRead,
} from '../../api/client';
import { DatePicker } from '../../components/DatePicker';
import { Button, CheckboxField, EmptyState, ErrorPanel, Modal, Spinner } from '../../components/ui';
import type { LogisticsRequest, UUID } from '../../domain/types';
import { formatDate, formatDeliveryPrice, formatTime } from '../../utils/format';

interface UnassignedDeliveryRescheduleDialogProps {
  request: LogisticsRequest;
  timeZone: string;
  busy: boolean;
  onClose: () => void;
  calculate: (
    requestId: UUID,
    input: RequestRescheduleOptionsInput,
    signal?: AbortSignal,
  ) => Promise<RequestRescheduleOptionsRead>;
  onSubmit: (input: RequestRescheduleInput, idempotencyKey: UUID) => Promise<void>;
  onRetryQuarantined: (
    requestId: UUID,
    input: RequestRescheduleRetryInput,
    idempotencyKey: UUID,
  ) => Promise<void>;
}

interface QuarantineRecovery {
  holdId: UUID;
  quarantineCount: number;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

function quarantineRecovery(error: unknown): QuarantineRecovery | null {
  if (!(error instanceof ApiError)
      || error.status !== 409
      || error.code !== 'REQUEST_RESCHEDULE_QUARANTINED') return null;
  const holdId = error.problem?.hold_id;
  const quarantineCount = error.problem?.quarantine_count;
  if (typeof holdId !== 'string'
      || !UUID_PATTERN.test(holdId)
      || !Number.isInteger(quarantineCount)
      || typeof quarantineCount !== 'number'
      || quarantineCount < 1) return null;
  return { holdId, quarantineCount };
}

function slotWindow(slot: RequestRescheduleSlotRead): string {
  if (slot.kind === 'DURING_DAY') return 'В течение дня';
  if (slot.window_start && slot.window_end) {
    return `${slot.window_start.slice(0, 5)}–${slot.window_end.slice(0, 5)}`;
  }
  return 'Окно уточняется';
}

/** Moves an existing RWMS delivery only through fresh owner-calculated customer slots. */
export function UnassignedDeliveryRescheduleDialog({
  request,
  timeZone,
  busy,
  onClose,
  calculate,
  onSubmit,
  onRetryQuarantined,
}: UnassignedDeliveryRescheduleDialogProps) {
  const [date, setDate] = useState('');
  const [options, setOptions] = useState<RequestRescheduleOptionsRead | null>(null);
  const [selectedSlotId, setSelectedSlotId] = useState<UUID | null>(null);
  const [customerAgreed, setCustomerAgreed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [quarantined, setQuarantined] = useState(false);
  const [recovery, setRecovery] = useState<QuarantineRecovery | null>(null);
  const [retry, setRetry] = useState(0);
  const intentRef = useRef<{ signature: string; key: UUID } | null>(null);
  const recoveryIntentRef = useRef<{ signature: string; key: UUID } | null>(null);

  useEffect(() => {
    setOptions(null);
    setSelectedSlotId(null);
    setCustomerAgreed(false);
    setError(null);
    setQuarantined(false);
    setRecovery(null);
    if (!date) return undefined;

    const controller = new AbortController();
    setLoading(true);
    void calculate(request.id, {
      expected_request_version: request.version,
      date,
    }, controller.signal).then((result) => {
      if (!controller.signal.aborted) setOptions(result);
    }).catch((loadError: unknown) => {
      if (controller.signal.aborted) return;
      const isQuarantined = loadError instanceof ApiError
        && loadError.status === 409
        && loadError.code === 'REQUEST_RESCHEDULE_QUARANTINED';
      const exactRecovery = quarantineRecovery(loadError);
      const inProgress = loadError instanceof ApiError
        && loadError.status === 409
        && loadError.code === 'REQUEST_RESCHEDULE_IN_PROGRESS';
      setQuarantined(isQuarantined);
      setRecovery(exactRecovery);
      setError(isQuarantined
        ? new Error(exactRecovery
          ? 'Предыдущий перенос дошёл до владельца заказа, но локальное применение не завершилось. Безопасно продолжите сохранённую операцию — новая дата или слот созданы не будут.'
          : 'Сервис не вернул точный номер незавершённого переноса. Обновите данные; новую операцию запускать нельзя.')
        : inProgress
          ? new Error('Для этой доставки уже применяется выбранный клиентский слот. Дождитесь завершения recovery и повторите проверку.')
        : loadError instanceof ApiError && loadError.status === 409
          ? new Error('Заявка или клиентские слоты уже изменились. Обновите расчёт и выберите актуальный слот.')
          : loadError);
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [calculate, date, request.id, request.version, retry]);

  const slots = useMemo(
    () => options?.options.filter((slot) => slot.date === date) ?? [],
    [date, options?.options],
  );
  const selectedSlot = slots.find((slot) => slot.slot_id === selectedSlotId) ?? null;

  const retryQuarantined = async () => {
    if (!recovery) return;
    setSaving(true);
    setError(null);
    const input: RequestRescheduleRetryInput = {
      hold_id: recovery.holdId,
      expected_quarantine_count: recovery.quarantineCount,
    };
    const signature = JSON.stringify(input);
    if (recoveryIntentRef.current?.signature !== signature) {
      recoveryIntentRef.current = { signature, key: crypto.randomUUID() };
    }
    try {
      await onRetryQuarantined(request.id, input, recoveryIntentRef.current.key);
    } catch (retryError: unknown) {
      const nextRecovery = quarantineRecovery(retryError);
      const quarantineWithoutFences = retryError instanceof ApiError
        && retryError.status === 409
        && retryError.code === 'REQUEST_RESCHEDULE_QUARANTINED'
        && !nextRecovery;
      if (nextRecovery) {
        setQuarantined(true);
        setRecovery(nextRecovery);
      } else if (quarantineWithoutFences) {
        setRecovery(null);
      }
      setError(nextRecovery
        ? new Error('Повтор снова остановлен. Можно безопасно повторить только эту сохранённую операцию.')
        : quarantineWithoutFences
          ? new Error('Сервис не вернул точную версию незавершённого переноса. Обновите данные; повторять старую команду нельзя.')
        : retryError);
    } finally {
      setSaving(false);
    }
  };

  const submit = async () => {
    if (!options || !selectedSlot || !customerAgreed) return;
    setSaving(true);
    setError(null);
    const input: RequestRescheduleInput = {
      expected_request_version: options.request_version,
      source_plan_id: options.source_plan_id,
      source_plan_version: options.source_plan_version,
      expected_order_version: options.order_version,
      expected_session_version: options.session_version,
      slot_id: selectedSlot.slot_id,
      slot_version: selectedSlot.slot_version,
    };
    const signature = JSON.stringify(input);
    if (intentRef.current?.signature !== signature) {
      intentRef.current = { signature, key: crypto.randomUUID() };
    }
    try {
      await onSubmit(input, intentRef.current.key);
    } catch (submitError: unknown) {
      const exactRecovery = quarantineRecovery(submitError);
      const isQuarantined = submitError instanceof ApiError
        && submitError.status === 409
        && submitError.code === 'REQUEST_RESCHEDULE_QUARANTINED';
      const stale = submitError instanceof ApiError && submitError.status === 409;
      setQuarantined(isQuarantined);
      setRecovery(exactRecovery);
      setError(isQuarantined
        ? new Error(exactRecovery
          ? 'Перенос сохранён, но его локальное применение не завершилось. Продолжите точно эту операцию.'
          : 'Перенос остановлен, но сервис не вернул его точную версию. Обновите данные.')
        : stale
        ? new Error('Выбранный слот уже изменился. Выполните новый расчёт и выберите другой доступный вариант.')
        : submitError);
      if (stale) {
        setOptions(null);
        setSelectedSlotId(null);
        setCustomerAgreed(false);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      wide
      title={`Перенести доставку «${request.name}»`}
      description="Выберите дату. Сервис рассчитает реальные доступные слоты для существующего заказа — вручную задать время нельзя."
      onClose={onClose}
      footer={(
        <>
          <Button disabled={busy || saving} onClick={onClose}>Отмена</Button>
          <Button
            variant="primary"
            disabled={busy || saving || loading || !selectedSlot || !customerAgreed}
            onClick={() => void submit()}
          >
            {saving ? 'Переносим…' : 'Перенести в выбранный слот'}
          </Button>
        </>
      )}
    >
      <div className="detail-grid">
        <div className="detail-item"><small>Текущая дата</small><strong>{request.scheduled_date ? formatDate(request.scheduled_date) : 'Не назначена'}</strong></div>
        <div className="detail-item"><small>Клиент</small><strong>{request.contact_name?.trim() || request.name}</strong></div>
        <div className="detail-item"><small>Адрес</small><strong>{request.address_label}</strong></div>
        <div className="detail-item"><small>Количество</small><strong>{request.quantity} бытов.</strong></div>
      </div>

      <div className="divider" />
      <DatePicker value={date} onChange={setDate} label="Новая дата доставки" disabled={busy || saving} />

      {loading ? <div className="slot-planner__status"><Spinner label="Рассчитываем доступные слоты…" /></div> : null}
      {quarantined && saving ? <div className="slot-planner__status"><Spinner label="Продолжаем сохранённый перенос…" /></div> : null}
      {error ? (
        <ErrorPanel
          title={quarantined ? 'Перенос требует безопасного восстановления' : 'Не удалось получить актуальные слоты'}
          error={error}
          {...(recovery
            ? { onRetry: () => void retryQuarantined() }
            : !quarantined && date ? { onRetry: () => setRetry((value) => value + 1) } : {})}
        />
      ) : null}
      {!date ? <EmptyState title="Выберите новую дату" description="После выбора сервис проверит загрузку, маршрут, машину и клиентские ограничения." /> : null}
      {date && !loading && !error && options && !slots.length ? (
        <EmptyState title="На эту дату доступных слотов нет" description="Выберите другую дату — список будет рассчитан заново." />
      ) : null}

      {options ? (
        <div className="operations-reason" aria-label="Текущий клиентский слот">
          <strong>Сейчас согласовано: {formatDate(options.current_slot.date)}, {slotWindow(options.current_slot)}</strong>
          <p>Ниже показаны только заново рассчитанные доступные варианты на выбранную дату.</p>
        </div>
      ) : null}

      {slots.length ? (
        <section className="slot-cards" aria-label="Рассчитанные слоты доставки">
          {slots.map((slot) => {
            const selected = selectedSlotId === slot.slot_id;
            return (
              <button
                type="button"
                key={`${slot.slot_id}-${slot.slot_version}`}
                className={`slot-card slot-card--available${selected ? ' slot-card--selected' : ''}`}
                aria-pressed={selected}
                aria-label={`Слот ${slotWindow(slot)} на ${formatDate(slot.date)}`}
                onClick={() => {
                  setSelectedSlotId(slot.slot_id);
                  setCustomerAgreed(false);
                }}
              >
                <span className="slot-card__head"><strong>{slotWindow(slot)}</strong><b>Доступен</b></span>
                <span className="slot-card__metrics">
                  <span>Дата <strong>{formatDate(slot.date)}</strong></span>
                  <span>Стоимость <strong>{formatDeliveryPrice(slot.delivery_price_rubles)}</strong></span>
                  <span>Расчёт до <strong>{formatTime(slot.expires_at, timeZone)}</strong></span>
                </span>
              </button>
            );
          })}
        </section>
      ) : null}

      {selectedSlot ? (
        <div className="operations-reason">
          <strong>Выбран новый клиентский слот: {formatDate(selectedSlot.date)}, {slotWindow(selectedSlot)}</strong>
          <p>Дата и время изменятся только после явного подтверждения. При применении сервис повторно проверит версии заказа, сессии и слота.</p>
          <CheckboxField
            label="Подтверждаю: выбранные дата и слот согласованы с клиентом"
            checked={customerAgreed}
            onChange={setCustomerAgreed}
            disabled={busy || saving}
          />
        </div>
      ) : null}
    </Modal>
  );
}
