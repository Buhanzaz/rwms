import { CalendarCheck2, Clock3, Scissors, ShieldCheck, Truck } from 'lucide-react';
import { useEffect, useMemo, useState, type FormEvent } from 'react';
import type { RequestPlanningDetailsInput } from '../../api/client';
import { Badge, Button, CheckboxField, EmptyState, Field, SelectField } from '../../components/ui';
import { requestPlanningMissingFields } from '../../domain/planning-readiness';
import { isRequestVisibleOnDate, requestPlanningDates } from '../../domain/request-dates';
import type { LogisticsRequest, ScenarioWorkspace, UUID } from '../../domain/types';
import { formatDate } from '../../utils/format';

/** Tri-state operator answer before trailer access has been explicitly confirmed. */
type TrailerAgreement = '' | 'true' | 'false';

function RequestPlanningCard({ request, planningDate, busy, onSave, onSplit, onSelect }: {
  request: LogisticsRequest;
  planningDate: string;
  busy: boolean;
  onSave: (requestId: UUID, input: RequestPlanningDetailsInput) => Promise<void>;
  onSplit: (requestId: UUID, quantities: number[]) => Promise<void>;
  onSelect: (requestId: UUID) => void;
}) {
  const option = request.date_options.find((candidate) => candidate.date === planningDate);
  const sourceFixedWindow = request.source_system === 'RWMS'
    && option?.is_hard === true
    && option.window_start != null
    && option.window_end != null;
  const sourceRequiresHardWindow = sourceFixedWindow || option?.travel_zone_hours != null;
  const [windowStart, setWindowStart] = useState(option?.window_start ?? '');
  const [windowEnd, setWindowEnd] = useState(option?.window_end ?? '');
  const [isHard, setIsHard] = useState(sourceRequiresHardWindow || (option?.is_hard ?? true));
  const [trailerAgreement, setTrailerAgreement] = useState<TrailerAgreement>(
    typeof request.trailer_access_allowed === 'boolean'
      ? String(request.trailer_access_allowed) as TrailerAgreement
      : '',
  );
  const [includePassport, setIncludePassport] = useState(request.include_driver_passport_in_notification ?? false);
  const [contactName, setContactName] = useState(request.contact_name ?? '');
  const [contactPhone, setContactPhone] = useState(request.contact_phone ?? '');

  useEffect(() => {
    setWindowStart(option?.window_start ?? '');
    setWindowEnd(option?.window_end ?? '');
    setIsHard(sourceRequiresHardWindow || (option?.is_hard ?? true));
    setTrailerAgreement(
      typeof request.trailer_access_allowed === 'boolean'
        ? String(request.trailer_access_allowed) as TrailerAgreement
        : '',
    );
    setIncludePassport(request.include_driver_passport_in_notification ?? false);
    setContactName(request.contact_name ?? '');
    setContactPhone(request.contact_phone ?? '');
  }, [option?.is_hard, option?.window_end, option?.window_start, planningDate, request.contact_name, request.contact_phone, request.include_driver_passport_in_notification, request.trailer_access_allowed, sourceRequiresHardWindow]);

  const missing = requestPlanningMissingFields(request, planningDate);
  const windowInvalid = Boolean(windowStart && windowEnd && windowEnd <= windowStart);
  const canSave = Boolean(option && windowStart && windowEnd && trailerAgreement && !windowInvalid && !busy);
  const taskParts = request.tasks?.map((task) => task.quantity) ?? [];
  const alreadyUnitSplit = taskParts.length === request.quantity && taskParts.every((quantity) => quantity === 1);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSave) return;
    void onSave(request.id, {
      date: planningDate,
      window_start: windowStart,
      window_end: windowEnd,
      is_hard: sourceRequiresHardWindow || isHard,
      trailer_access_allowed: trailerAgreement === 'true',
      include_driver_passport_in_notification: includePassport,
      contact_name: contactName.trim(),
      contact_phone: contactPhone.trim(),
    });
  };

  return (
    <article className="planning-request-card" data-testid={`planning-request-${request.id}`} onClick={() => onSelect(request.id)}>
      <header className="planning-request-card__head">
        <span>
          <strong>{request.type === 'DELIVERY' ? 'Доставка' : 'Вывоз'} · {request.name}</strong>
          <small>{request.address_label || 'Адрес не подписан'}</small>
        </span>
        <span>
          <Badge tone={request.type === 'DELIVERY' ? 'accent' : 'warning'}>{request.type === 'DELIVERY' ? 'Д' : 'В'}</Badge>
          <Badge tone={missing.length ? 'danger' : 'success'}>{missing.length ? 'нужно заполнить' : 'готово'}</Badge>
        </span>
      </header>
      <div className="planning-request-card__facts">
        <span><Truck size={13} /> <strong>{request.quantity}</strong> БК</span>
        <span><CalendarCheck2 size={13} /> {formatDate(planningDate)}</span>
        {option?.travel_zone_hours ? <span><Clock3 size={13} /> зона {option.travel_zone_hours} ч</span> : null}
        <span><ShieldCheck size={13} /> {request.status}</span>
      </div>
      {missing.length ? <p className="planning-request-card__missing">{missing.join(' · ')}</p> : null}
      <form className="planning-request-form" onSubmit={submit} onClick={(event) => event.stopPropagation()}>
        <Field label="Доставка/вывоз с" type="time" value={windowStart} onChange={(event) => setWindowStart(event.target.value)} disabled={!option || busy || sourceFixedWindow} />
        <Field label="До" type="time" value={windowEnd} onChange={(event) => setWindowEnd(event.target.value)} disabled={!option || busy || sourceFixedWindow} error={windowInvalid ? 'Окончание должно быть позже начала' : undefined} />
        <SelectField className="span-2" label="Машина с прицепом проедет к адресу" value={trailerAgreement} onChange={(event) => setTrailerAgreement(event.target.value as TrailerAgreement)} disabled={!option || busy} error={!trailerAgreement ? 'Обязательно зафиксируйте ответ клиента/логиста' : undefined}>
          <option value="">Не согласовано</option>
          <option value="true">Да, проезд с прицепом согласован</option>
          <option value="false">Нет, только машина без прицепа</option>
        </SelectField>
        <Field label="Контактное лицо" value={contactName} onChange={(event) => setContactName(event.target.value)} disabled={busy} />
        <Field label="Телефон / контакт" value={contactPhone} onChange={(event) => setContactPhone(event.target.value)} disabled={busy} />
        <div className="span-2 planning-request-form__checks">
          <CheckboxField
            label={sourceFixedWindow ? 'Жёсткое временное окно · зафиксировано RWMS/CustomerApp' : sourceRequiresHardWindow ? 'Жёсткое временное окно · зафиксировано CustomerApp' : 'Жёсткое временное окно'}
            checked={sourceRequiresHardWindow || isHard}
            onChange={setIsHard}
            disabled={!option || busy || sourceRequiresHardWindow}
          />
          <CheckboxField label="Оповещение с паспортными данными водителя" checked={includePassport} onChange={setIncludePassport} disabled={busy} />
        </div>
        <div className="span-2 planning-request-form__actions">
          <span className="planning-request-card__parts">Подзадачи: {taskParts.length ? taskParts.join(' + ') : 'будут созданы backend'}</span>
          {request.quantity > 1 ? (
            <Button type="button" size="sm" disabled={busy || alreadyUnitSplit} onClick={() => void onSplit(request.id, Array.from({ length: request.quantity }, () => 1))}>
              <Scissors size={13} />{alreadyUnitSplit ? 'Разбито по 1 БК' : 'Создать части по 1 БК'}
            </Button>
          ) : null}
          <Button type="submit" size="sm" variant="primary" disabled={!canSave}>Сохранить условия</Button>
        </div>
      </form>
    </article>
  );
}

/** Dispatcher preparation board for all requests visible on one planning day. */
export function PlanningDayRequests({ workspace, planningDate, busy, onPlanningDateChange, onSave, onSplit, onSelect }: {
  workspace: ScenarioWorkspace;
  planningDate: string;
  busy: boolean;
  onPlanningDateChange: (date: string) => void;
  onSave: (requestId: UUID, input: RequestPlanningDetailsInput) => Promise<void>;
  onSplit: (requestId: UUID, quantities: number[]) => Promise<void>;
  onSelect: (requestId: UUID) => void;
}) {
  const dates = useMemo(
    () => [...new Set([planningDate, ...workspace.requests.flatMap(requestPlanningDates)])].sort(),
    [planningDate, workspace.requests],
  );
  const requests = workspace.requests.filter((request) => isRequestVisibleOnDate(request, planningDate));
  const prepared = requests.filter((request) => request.status === 'READY' && requestPlanningMissingFields(request, planningDate).length === 0).length;
  const ready = requests.filter((request) => request.status === 'READY').length;

  return (
    <section className="planning-day-requests" aria-label="Подготовка заявок на день">
      <div className="date-board">
        <strong>Планируемый день</strong>
        <div>{dates.map((date) => {
          const count = workspace.requests.filter((request) => isRequestVisibleOnDate(request, date)).length;
          return <button type="button" key={date} aria-pressed={date === planningDate} onClick={() => onPlanningDateChange(date)}>{formatDate(date)} <small>{count}</small></button>;
        })}</div>
        <p>К маршрутизации готовы {prepared} из {ready} заявок со статусом READY. Сначала сохраните окно времени и фактический допуск прицепа для каждой заявки.</p>
      </div>
      <div className="entity-list">
        {requests.map((request) => (
          <RequestPlanningCard
            key={request.id}
            request={request}
            planningDate={planningDate}
            busy={busy}
            onSave={onSave}
            onSplit={onSplit}
            onSelect={onSelect}
          />
        ))}
      </div>
      {!requests.length ? <EmptyState title="На выбранный день заявок нет" description="Выберите другую дату или согласуйте новую дату в разделе заявок." /> : null}
    </section>
  );
}
