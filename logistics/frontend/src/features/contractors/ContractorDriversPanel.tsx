import { Copy, ListChecks, Pencil, Plus, Route as RouteIcon, Sparkles, Trash2, UserRoundCheck } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import type { ContractorDispatchMode, ContractorDispatchResult } from '../../api/client';
import { beginPanelLogin, restorePanelUser } from '../../auth/panel-oidc';
import { Badge, Button, CheckboxField, EmptyState, Field, Modal, Spinner } from '../../components/ui';
import type { LogisticsRequest, UUID } from '../../domain/types';
import { formatDate } from '../../utils/format';
import { customerDeliveryPurposeFromRequest, customerDeliveryPurposeLabel } from '../../utils/customer-presentation';
import { userFacingErrorDetail } from '../../utils/user-facing-error';
import {
  createContractorDriver,
  createContractorRouteShare,
  deleteContractorDriver,
  listContractorDrivers,
  updateContractorDriver,
  type ContractorDriver,
  type ContractorDriverInput,
} from './contractor-client';

type Session = { status: 'loading' } | { status: 'anonymous' } | { status: 'ready'; token: string };

const DAY_MS = 24 * 60 * 60 * 1_000;
const SHARE_LIFETIME_SAFETY_MS = 2 * 60 * 1_000;
const MAX_ROUTE_SHARE_TASKS = 50;
const ROUTE_NOT_READY_MESSAGE = 'Маршрут ещё не готов для отправки.';

/** Exact canonical contractor route reconstructed from a durable handoff result or request projection. */
interface ContractorRouteSummary {
  commandId: UUID;
  contractorWorkerId: UUID | null;
  contractorName: string;
  planningDate: string;
  externalTaskIds: UUID[];
  ready: boolean;
}

/** Copy feedback remains scoped to the route whose explicit share action produced it. */
interface ContractorRouteFeedback {
  tone: 'success' | 'error' | 'fallback';
  message: string;
  url?: string;
}

/** Deterministic route-share expiry and an operator explanation when the selected date is unusable. */
interface ContractorRouteShareExpiry {
  expiresAt: string | null;
  reason: string | null;
}

function returnTo(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

function requestSummary(request: LogisticsRequest): string {
  const kind = request.type === 'PICKUP'
    ? 'Вывоз'
    : customerDeliveryPurposeLabel(customerDeliveryPurposeFromRequest(request));
  return `${kind} · ${request.name} · ${request.quantity} бытовк.`;
}

function canDispatchToContractor(request: LogisticsRequest): boolean {
  return request.type !== 'PICKUP' || request.source_system !== 'RWMS';
}

/** Keeps share replay stable by deriving expiry only from the selected planning date. */
function contractorRouteShareExpiry(
  planningDate: string,
  nowMs = Date.now(),
): ContractorRouteShareExpiry {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(planningDate);
  if (!match) return { expiresAt: null, reason: 'Выберите корректную дату рейса в хедере.' };
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const planningDayStart = Date.UTC(year, month - 1, day);
  if (new Date(planningDayStart).toISOString().slice(0, 10) !== planningDate) {
    return { expiresAt: null, reason: 'Выберите корректную дату рейса в хедере.' };
  }
  const expiresAtMs = planningDayStart + 2 * DAY_MS;
  const remainingMs = expiresAtMs - nowMs;
  if (remainingMs < SHARE_LIFETIME_SAFETY_MS) {
    return { expiresAt: null, reason: 'Срок маршрута для выбранной даты уже закончился. Выберите актуальную дату.' };
  }
  if (remainingMs > 30 * DAY_MS - SHARE_LIFETIME_SAFETY_MS) {
    return { expiresAt: null, reason: 'Выбранная дата дальше допустимого срока ссылки. Выберите дату в пределах 30 дней.' };
  }
  return { expiresAt: new Date(expiresAtMs).toISOString(), reason: null };
}

function routeFromDispatch(result: ContractorDispatchResult): ContractorRouteSummary | null {
  if (!result.contractor_handoff_command_id) return null;
  const ready = result.external_task_ids.length > 0
    && result.external_task_ids.length <= MAX_ROUTE_SHARE_TASKS
    && new Set(result.external_task_ids).size === result.external_task_ids.length;
  return {
    commandId: result.contractor_handoff_command_id,
    contractorWorkerId: result.contractor_worker_id,
    contractorName: result.contractor_name,
    planningDate: result.planning_date,
    externalTaskIds: ready ? [...result.external_task_ids] : [],
    ready,
  };
}

function persistedContractorRoutes(
  requests: LogisticsRequest[],
  planningDate: string,
  projectionComplete: boolean,
): ContractorRouteSummary[] {
  const groupedRequests = new Map<UUID, LogisticsRequest[]>();
  for (const request of requests) {
    const commandId = request.contractor_handoff_command_id;
    if (request.scheduled_date !== planningDate || !commandId) continue;
    groupedRequests.set(commandId, [...(groupedRequests.get(commandId) ?? []), request]);
  }
  return [...groupedRequests.entries()].map(([commandId, commandRequests]) => {
    const workerIds = [...new Set(commandRequests
      .map((request) => request.assigned_contractor_worker_id)
      .filter((workerId): workerId is UUID => Boolean(workerId)))];
    const contractorWorkerId = workerIds.length === 1 ? workerIds[0]! : null;
    const contractorName = commandRequests.find(
      (request) => request.assigned_contractor_worker_id === contractorWorkerId
        && request.assigned_contractor_name?.trim(),
    )?.assigned_contractor_name?.trim() || 'Наёмный водитель';
    const sequences = commandRequests.map((request) => request.contractor_handoff_sequence);
    const validSequences = sequences.every(
      (sequence): sequence is number => Number.isInteger(sequence) && sequence != null && sequence >= 0,
    );
    const orderedRequests = validSequences
      ? [...commandRequests].sort(
        (left, right) => Number(left.contractor_handoff_sequence) - Number(right.contractor_handoff_sequence),
      )
      : [];
    const contiguousSequence = validSequences
      && orderedRequests.every((request, index) => request.contractor_handoff_sequence === index);
    const externalTaskIds = orderedRequests.flatMap((request) => request.external_task_ids ?? []);
    const uniqueTaskIds = new Set(externalTaskIds);
    const ready = projectionComplete
      && contractorWorkerId != null
      && workerIds.length === 1
      && commandRequests.every((request) => (
        request.assignment_type === 'CONTRACTOR_HANDOFF'
        && request.assigned_contractor_worker_id === contractorWorkerId
      ))
      && contiguousSequence
      && externalTaskIds.length > 0
      && externalTaskIds.length <= MAX_ROUTE_SHARE_TASKS
      && uniqueTaskIds.size === externalTaskIds.length;
    return {
      commandId,
      contractorWorkerId,
      contractorName,
      planningDate,
      externalTaskIds: ready ? externalTaskIds : [],
      ready,
    };
  });
}

function mergeContractorRoutes(
  persisted: ContractorRouteSummary[],
  recent: ContractorRouteSummary[],
): ContractorRouteSummary[] {
  const routes = new Map<UUID, ContractorRouteSummary>();
  for (const route of persisted) {
    routes.set(route.commandId, { ...route, externalTaskIds: [...route.externalTaskIds] });
  }
  for (const route of recent) {
    routes.set(route.commandId, { ...route, externalTaskIds: [...route.externalTaskIds] });
  }
  return [...routes.values()];
}

async function copyRouteUrl(url: string): Promise<boolean> {
  if (!navigator.clipboard?.writeText) return false;
  try {
    await navigator.clipboard.writeText(url);
    return true;
  } catch {
    return false;
  }
}

function absolutePublicRouteUrl(publicPath: string | null): string {
  if (!publicPath) throw new Error('Ссылка на маршрут недоступна. Обновите данные и повторите действие.');
  const url = new URL(publicPath, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/contractor-routes/')) {
    throw new Error('Сервис вернул недопустимую ссылку. Обновите данные и повторите действие.');
  }
  return url.toString();
}

/** One explicit route-share action with a disabled, explained state for incomplete projections. */
function ContractorRouteCard({ route, feedback, copying, onCopy }: {
  route: ContractorRouteSummary;
  feedback: ContractorRouteFeedback | undefined;
  copying: boolean;
  onCopy: (route: ContractorRouteSummary) => Promise<void>;
}) {
  const expiry = contractorRouteShareExpiry(route.planningDate);
  const unavailableReason = route.ready ? expiry.reason : ROUTE_NOT_READY_MESSAGE;
  const statusId = `contractor-route-status-${route.commandId}`;
  return (
    <section className="entity-card" aria-label={`Маршрут наёмного водителя ${route.contractorName}`}>
      <div className="entity-card__row">
        <span>
          <strong><RouteIcon size={13} aria-hidden="true" /> Маршрут на {formatDate(route.planningDate)}</strong>
          <p>{route.ready ? `Заданий: ${route.externalTaskIds.length}` : 'Состав маршрута синхронизируется'}</p>
        </span>
        <Badge tone={route.ready ? 'success' : 'neutral'}>{route.ready ? 'готов' : 'не готов'}</Badge>
      </div>
      <div className="toolbar-row" style={{ margin: '8px 0 0' }}>
        <Button
          size="sm"
          variant="primary"
          aria-describedby={statusId}
          disabled={copying || !route.ready || expiry.expiresAt == null}
          title={unavailableReason ?? 'Создать и скопировать защищённую ссылку на маршрут'}
          onClick={() => void onCopy(route)}
        ><Copy size={13} aria-hidden="true" />{copying ? 'Готовим ссылку…' : 'Скопировать маршрут'}</Button>
      </div>
      <div id={statusId} aria-live="polite">
        {unavailableReason ? <p className={route.ready ? 'field__hint' : 'field__error'}>{unavailableReason}</p> : null}
        {feedback ? <p className={feedback.tone === 'error' ? 'field__error' : 'field__hint'} role={feedback.tone === 'error' ? 'alert' : 'status'}>{feedback.message}</p> : null}
        {feedback?.url ? <input className="input" aria-label="Ссылка на маршрут для ручного копирования" readOnly value={feedback.url} onFocus={(event) => event.currentTarget.select()} /> : null}
      </div>
    </section>
  );
}

/** Profile editor for a reusable contractor; route dates are never stored in a profile. */
function ContractorEditor({ contractor, busy, onClose, onSave, onDelete }: {
  contractor: ContractorDriver | null;
  busy: boolean;
  onClose: () => void;
  onSave: (input: ContractorDriverInput) => Promise<void>;
  onDelete: (contractor: ContractorDriver) => Promise<void>;
}) {
  const [name, setName] = useState(contractor?.displayName ?? '');
  const [phone, setPhone] = useState(contractor?.phone ?? '');
  const [comment, setComment] = useState(contractor?.comment ?? '');
  const [active, setActive] = useState(contractor?.active ?? true);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!name.trim() || !phone.trim()) return;
    void onSave({ displayName: name.trim(), phone: phone.trim(), comment: comment.trim(), active });
  };
  return (
    <Modal title={contractor ? 'Изменить наёмного водителя' : 'Добавить наёмного водителя'} description="Профиль водителя хранится отдельно от назначения на рейс." onClose={onClose}>
      <form className="form-grid" onSubmit={submit}>
        <Field className="span-2" label="Имя / название" value={name} onChange={(event) => setName(event.target.value)} required />
        <Field className="span-2" label="Телефон" type="tel" value={phone} onChange={(event) => setPhone(event.target.value)} required />
        <label className="field span-2"><span className="field__label">Комментарий</span><textarea className="input" value={comment} onChange={(event) => setComment(event.target.value)} /></label>
        {contractor ? <div className="span-2"><CheckboxField label="Активен и может получать задания" checked={active} onChange={setActive} /></div> : null}
        {contractor && confirmDelete ? (
          <div className="span-2 contractor-delete-confirm" role="alert">
            <p>Удалить профиль «{contractor.displayName}»? Профиль с историей назначений удалить нельзя.</p>
            <div className="toolbar-row contractor-editor__actions">
              <Button type="button" disabled={busy} onClick={() => setConfirmDelete(false)}>Не удалять</Button>
              <Button type="button" variant="danger" disabled={busy} onClick={() => void onDelete(contractor)}>{busy ? 'Удаляем…' : 'Удалить водителя'}</Button>
            </div>
          </div>
        ) : (
          <div className="span-2 toolbar-row contractor-editor__actions">
            {contractor ? <Button type="button" variant="danger" disabled={busy} onClick={() => setConfirmDelete(true)}><Trash2 size={14} />Удалить</Button> : null}
            <span className="contractor-editor__spacer" />
            <Button type="button" onClick={onClose}>Отмена</Button>
            <Button type="submit" variant="primary" disabled={busy || !name.trim() || !phone.trim()}>{busy ? 'Сохраняем…' : 'Сохранить'}</Button>
          </div>
        )}
      </form>
    </Modal>
  );
}

/** Manual handoff selector restricted to unassigned requests for the chosen planning date. */
function ManualDispatchDialog({ contractor, planningDate, requests, busy, onClose, onSubmit }: {
  contractor: ContractorDriver;
  planningDate: string;
  requests: LogisticsRequest[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (requestIds: UUID[]) => Promise<void>;
}) {
  const [selectedIds, setSelectedIds] = useState<UUID[]>([]);
  const toggle = (requestId: UUID, checked: boolean) => setSelectedIds((current) => (
    checked ? [...current, requestId] : current.filter((id) => id !== requestId)
  ));
  return (
    <Modal title="Назначить на рейс вручную" description={`${contractor.displayName} · ${formatDate(planningDate)}`} onClose={onClose}>
      {requests.length ? (
        <div className="contractor-manual-list">
          {requests.map((request) => (
            <label className="contractor-request-option" key={request.id}>
              <input type="checkbox" checked={selectedIds.includes(request.id)} onChange={(event) => toggle(request.id, event.target.checked)} />
              <span><strong>{requestSummary(request)}</strong><small>{request.address_label}</small></span>
            </label>
          ))}
          <div className="toolbar-row contractor-editor__actions">
            <Button type="button" onClick={onClose}>Отмена</Button>
            <Button type="button" variant="primary" disabled={busy || !selectedIds.length} onClick={() => void onSubmit(selectedIds)}>{busy ? 'Передаём…' : `Передать выбранные (${selectedIds.length})`}</Button>
          </div>
        </div>
      ) : <EmptyState title="Нет заданий для ручного распределения" description={`На ${formatDate(planningDate)} нет нераспределённых доставок или вывозов.`} />}
    </Modal>
  );
}

/** Separate task-board contractor catalog; profiles never become planner shifts or vehicle cycles. */
export function ContractorDriversPanel({ warehouseId, warehouseName = 'выбранный склад', planningDate, manualRequests, routeRequests = [], routeRequestsComplete = true, busy, onDispatch }: {
  warehouseId: UUID;
  warehouseName?: string;
  planningDate: string;
  manualRequests: LogisticsRequest[];
  routeRequests?: LogisticsRequest[];
  routeRequestsComplete?: boolean;
  busy: boolean;
  onDispatch: (contractorWorkerId: UUID, mode: ContractorDispatchMode, requestIds: UUID[]) => Promise<ContractorDispatchResult | void>;
}) {
  const [session, setSession] = useState<Session>({ status: 'loading' });
  const [contractors, setContractors] = useState<ContractorDriver[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<ContractorDriver | null | undefined>(undefined);
  const [manualContractor, setManualContractor] = useState<ContractorDriver | null>(null);
  const [createIntentId, setCreateIntentId] = useState<UUID | null>(null);
  const [saving, setSaving] = useState(false);
  const [dispatchingWorkerId, setDispatchingWorkerId] = useState<UUID | null>(null);
  const [recentRoutes, setRecentRoutes] = useState<ContractorRouteSummary[]>([]);
  const [copyingCommandId, setCopyingCommandId] = useState<UUID | null>(null);
  const [routeFeedback, setRouteFeedback] = useState<Record<UUID, ContractorRouteFeedback>>({});

  useEffect(() => {
    setRecentRoutes([]);
    setCopyingCommandId(null);
    setRouteFeedback({});
  }, [planningDate, warehouseId]);

  useEffect(() => {
    let current = true;
    void restorePanelUser().then((user) => {
      if (current) setSession(user ? { status: 'ready', token: user.access_token } : { status: 'anonymous' });
    }).catch(() => { if (current) setSession({ status: 'anonymous' }); });
    return () => { current = false; };
  }, []);

  const reload = useCallback(async (token: string) => {
    setLoading(true);
    setError(null);
    try {
      setContractors(await listContractorDrivers(token, warehouseId));
    } catch (caught: unknown) {
      setError(userFacingErrorDetail(caught));
    } finally {
      setLoading(false);
    }
  }, [warehouseId]);

  useEffect(() => {
    if (session.status === 'ready') void reload(session.token);
    else if (session.status !== 'loading') setLoading(false);
  }, [reload, session]);

  const save = async (input: ContractorDriverInput) => {
    if (session.status !== 'ready') return;
    setSaving(true);
    setError(null);
    try {
      if (editor) await updateContractorDriver(session.token, warehouseId, editor, input);
      else await createContractorDriver(session.token, warehouseId, input, createIntentId ?? crypto.randomUUID());
      setEditor(undefined);
      setCreateIntentId(null);
      await reload(session.token);
    } catch (caught: unknown) {
      setError(userFacingErrorDetail(caught));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (contractor: ContractorDriver) => {
    if (session.status !== 'ready') return;
    setSaving(true);
    setError(null);
    try {
      await deleteContractorDriver(session.token, warehouseId, contractor);
      setEditor(undefined);
      await reload(session.token);
    } catch (caught: unknown) {
      setError(userFacingErrorDetail(caught));
    } finally {
      setSaving(false);
    }
  };

  const dispatch = async (contractor: ContractorDriver, mode: ContractorDispatchMode, requestIds: UUID[]) => {
    setDispatchingWorkerId(contractor.workerId);
    setError(null);
    try {
      const result = await onDispatch(contractor.workerId, mode, requestIds);
      const route = result ? routeFromDispatch(result) : null;
      if (route) {
        setRecentRoutes((current) => [
          ...current.filter((candidate) => candidate.commandId !== route.commandId),
          route,
        ]);
      }
      setManualContractor(null);
    } catch (caught: unknown) {
      setError(userFacingErrorDetail(caught));
    } finally {
      setDispatchingWorkerId(null);
    }
  };

  const activeCount = useMemo(() => contractors.filter((contractor) => contractor.active).length, [contractors]);
  const supportedRequests = useMemo(
    () => manualRequests.filter(canDispatchToContractor),
    [manualRequests],
  );
  const routes = useMemo(
    () => mergeContractorRoutes(
      persistedContractorRoutes(routeRequests, planningDate, routeRequestsComplete),
      recentRoutes.filter((route) => route.planningDate === planningDate),
    ),
    [planningDate, recentRoutes, routeRequests, routeRequestsComplete],
  );
  const copyRoute = async (route: ContractorRouteSummary) => {
    if (session.status !== 'ready') return;
    if (
      !route.ready
      || !route.contractorWorkerId
      || route.externalTaskIds.length === 0
      || route.externalTaskIds.length > MAX_ROUTE_SHARE_TASKS
      || new Set(route.externalTaskIds).size !== route.externalTaskIds.length
    ) {
      setRouteFeedback((current) => ({
        ...current,
        [route.commandId]: { tone: 'error', message: ROUTE_NOT_READY_MESSAGE },
      }));
      return;
    }
    const expiry = contractorRouteShareExpiry(route.planningDate);
    if (!expiry.expiresAt) {
      setRouteFeedback((current) => ({
        ...current,
        [route.commandId]: { tone: 'error', message: expiry.reason ?? 'Ссылка для этой даты недоступна.' },
      }));
      return;
    }
    setCopyingCommandId(route.commandId);
    setRouteFeedback((current) => {
      const next = { ...current };
      delete next[route.commandId];
      return next;
    });
    try {
      const currentUser = await restorePanelUser();
      if (!currentUser) throw new Error('Сессия RWMS завершена. Войдите снова и повторите действие.');
      const share = await createContractorRouteShare(
        currentUser.access_token,
        warehouseId,
        {
          contractorWorkerId: route.contractorWorkerId,
          expiresAt: expiry.expiresAt,
          externalTaskIds: route.externalTaskIds,
        },
        route.commandId,
      );
      const url = absolutePublicRouteUrl(share.publicPath);
      const copied = await copyRouteUrl(url);
      setRouteFeedback((current) => ({
        ...current,
        [route.commandId]: copied
          ? { tone: 'success', message: 'Ссылка на маршрут скопирована.' }
          : { tone: 'fallback', message: 'Автокопирование недоступно. Скопируйте ссылку вручную.', url },
      }));
    } catch (caught: unknown) {
      setRouteFeedback((current) => ({
        ...current,
        [route.commandId]: { tone: 'error', message: userFacingErrorDetail(caught) },
      }));
    } finally {
      setCopyingCommandId(null);
    }
  };
  const contractorWorkerIds = new Set(contractors.map((contractor) => contractor.workerId));
  const detachedRoutes = loading
    ? []
    : routes.filter(
      (route) => route.contractorWorkerId == null || !contractorWorkerIds.has(route.contractorWorkerId),
    );
  const hasUnsupportedRwmsPickup = supportedRequests.length !== manualRequests.length;
  if (session.status === 'loading') return <Spinner label="Проверяем доступ…" />;
  if (session.status === 'anonymous') return <EmptyState title="Нужен вход в RWMS" description="Каталог наёмных водителей хранится в общей учётной записи RWMS." action={<Button variant="primary" onClick={() => void beginPanelLogin(returnTo())}>Войти</Button>} />;
  return (
    <>
      <div className="entity-card__row"><div><h2 className="section-title">Наёмные водители</h2><p className="section-subtitle">К наёмным водителям через {warehouseName}</p></div><Button variant="primary" onClick={() => { setCreateIntentId(crypto.randomUUID()); setEditor(null); }}><Plus size={14} />Добавить водителя</Button></div>
      <div className="contractor-planning-date"><span>Дата рейса</span><strong>{formatDate(planningDate)}</strong></div>
      {hasUnsupportedRwmsPickup ? <p className="field__hint" role="note">Вывоз RWMS пока нельзя передать наёмному водителю — для него нужен внутренний маршрут.</p> : null}
      {error ? <div className="error-panel" role="alert"><strong>Не удалось выполнить действие</strong><p>{error}</p></div> : null}
      {loading ? <Spinner label="Загружаем наёмных водителей…" /> : <div className="entity-list">{contractors.map((contractor) => {
        const contractorBusy = busy || dispatchingWorkerId === contractor.workerId;
        const contractorRoutes = routes.filter((route) => route.contractorWorkerId === contractor.workerId);
        return (
          <article className="entity-card contractor-card" key={contractor.workerId}>
            <div className="entity-card__row"><span><strong>{contractor.displayName}</strong><p>{contractor.phone}</p></span><Badge tone={contractor.active ? 'success' : 'neutral'}>{contractor.active ? 'активен' : 'неактивен'}</Badge></div>
            {contractor.comment ? <p>{contractor.comment}</p> : <p className="entity-card__subtitle">Без комментария</p>}
            {contractorRoutes.map((route) => (
              <ContractorRouteCard
                key={route.commandId}
                route={route}
                feedback={routeFeedback[route.commandId]}
                copying={copyingCommandId === route.commandId}
                onCopy={copyRoute}
              />
            ))}
            <div className="contractor-card__actions">
              <Button size="sm" variant="primary" disabled={!contractor.active || contractorBusy || !supportedRequests.length} title={!supportedRequests.length ? 'На выбранную дату нет заданий, доступных для наёмного водителя' : undefined} onClick={() => void dispatch(contractor, 'AUTO', [])}><Sparkles size={13} />{contractorBusy ? 'Назначаем…' : 'Назначить на рейс'}</Button>
              <Button size="sm" disabled={!contractor.active || contractorBusy || !supportedRequests.length} onClick={() => setManualContractor(contractor)}><ListChecks size={13} />Выстроить вручную</Button>
              <Button size="sm" variant="ghost" disabled={contractorBusy} onClick={() => setEditor(contractor)}><Pencil size={13} />Изменить</Button>
            </div>
          </article>
        );
      })}</div>}
      {detachedRoutes.map((route) => (
        <ContractorRouteCard
          key={route.commandId}
          route={route}
          feedback={routeFeedback[route.commandId]}
          copying={copyingCommandId === route.commandId}
          onCopy={copyRoute}
        />
      ))}
      {!loading && !contractors.length ? <EmptyState icon={<UserRoundCheck />} title="Наёмные водители не добавлены" description="Добавьте водителя, чтобы затем назначить его на рейс." /> : null}
      {!loading && contractors.length > 0 && activeCount === 0 ? <p className="field__hint">Все наёмные водители неактивны. Активируйте нужного водителя через «Изменить».</p> : null}
      {editor !== undefined ? <ContractorEditor contractor={editor} busy={saving} onClose={() => { setEditor(undefined); setCreateIntentId(null); }} onSave={save} onDelete={remove} /> : null}
      {manualContractor ? <ManualDispatchDialog contractor={manualContractor} planningDate={planningDate} requests={supportedRequests} busy={busy || dispatchingWorkerId === manualContractor.workerId} onClose={() => setManualContractor(null)} onSubmit={(requestIds) => dispatch(manualContractor, 'MANUAL', requestIds)} /> : null}
    </>
  );
}
