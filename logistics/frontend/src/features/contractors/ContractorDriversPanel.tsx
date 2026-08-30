import { ListChecks, Pencil, Plus, Sparkles, Trash2, UserRoundCheck } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { beginPanelLogin, restorePanelUser } from '../../auth/panel-oidc';
import { Badge, Button, CheckboxField, EmptyState, Field, Modal, Spinner } from '../../components/ui';
import type { LogisticsRequest, UUID } from '../../domain/types';
import { formatDate } from '../../utils/format';
import { userFacingErrorDetail } from '../../utils/user-facing-error';
import {
  createContractorDriver,
  deleteContractorDriver,
  listContractorDrivers,
  updateContractorDriver,
  type ContractorDriver,
  type ContractorDriverInput,
} from './contractor-client';

type Session = { status: 'loading' } | { status: 'anonymous' } | { status: 'ready'; token: string };

function returnTo(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

function requestSummary(request: LogisticsRequest): string {
  const kind = request.type === 'PICKUP' ? 'Вывоз' : 'Доставка';
  return `${kind} · ${request.name} · ${request.quantity} бытовк.`;
}

/** Profile editor for a reusable contractor; the selected header date never becomes profile state. */
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
    <Modal title={contractor ? 'Изменить наёмного водителя' : 'Добавить наёмного водителя'} description="Профиль сохраняется без дат. На рейс водитель назначается отдельно — на дату, выбранную в хедере." onClose={onClose}>
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

/** Manual handoff selector restricted to unassigned requests of the date selected in the header. */
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
    <Modal title="Распределить вручную" description={`${contractor.displayName} · ${formatDate(planningDate)}. Дата взята из хедера.`} onClose={onClose}>
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
export function ContractorDriversPanel({ warehouseId, planningDate, manualRequests, busy, onDispatch }: {
  warehouseId: UUID;
  planningDate: string;
  manualRequests: LogisticsRequest[];
  busy: boolean;
  onDispatch: (contractorWorkerId: UUID, mode: 'AUTO' | 'MANUAL', requestIds: UUID[]) => Promise<void>;
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

  const dispatch = async (contractor: ContractorDriver, mode: 'AUTO' | 'MANUAL', requestIds: UUID[]) => {
    setDispatchingWorkerId(contractor.workerId);
    setError(null);
    try {
      await onDispatch(contractor.workerId, mode, requestIds);
      setManualContractor(null);
    } catch (caught: unknown) {
      setError(userFacingErrorDetail(caught));
    } finally {
      setDispatchingWorkerId(null);
    }
  };

  const activeCount = useMemo(() => contractors.filter((contractor) => contractor.active).length, [contractors]);
  if (session.status === 'loading') return <Spinner label="Проверяем доступ…" />;
  if (session.status === 'anonymous') return <EmptyState title="Нужен вход в RWMS" description="Каталог наёмных водителей хранится в общей учётной записи RWMS." action={<Button variant="primary" onClick={() => void beginPanelLogin(returnTo())}>Войти</Button>} />;
  return (
    <>
      <div className="entity-card__row"><div><h2 className="section-title">Наёмные водители</h2><p className="section-subtitle">Выберите дату в хедере, затем сформируйте рейс автоматически или распределите задания вручную.</p></div><Button variant="primary" onClick={() => { setCreateIntentId(crypto.randomUUID()); setEditor(null); }}><Plus size={14} />Добавить</Button></div>
      <div className="contractor-planning-date"><span>Дата рейса</span><strong>{formatDate(planningDate)}</strong><small>выбрана в хедере</small></div>
      {error ? <div className="error-panel" role="alert"><strong>Не удалось выполнить действие</strong><p>{error}</p></div> : null}
      {loading ? <Spinner label="Загружаем наёмных водителей…" /> : <div className="entity-list">{contractors.map((contractor) => {
        const contractorBusy = busy || dispatchingWorkerId === contractor.workerId;
        return (
          <article className="entity-card contractor-card" key={contractor.workerId}>
            <div className="entity-card__row"><span><strong>{contractor.displayName}</strong><p>{contractor.phone}</p></span><Badge tone={contractor.active ? 'success' : 'neutral'}>{contractor.active ? 'активен' : 'неактивен'}</Badge></div>
            {contractor.comment ? <p>{contractor.comment}</p> : <p className="entity-card__subtitle">Без комментария</p>}
            <div className="contractor-card__actions">
              <Button size="sm" variant="primary" disabled={!contractor.active || contractorBusy || !manualRequests.length} title={!manualRequests.length ? 'На выбранную дату нет нераспределённых заданий' : undefined} onClick={() => void dispatch(contractor, 'AUTO', [])}><Sparkles size={13} />{contractorBusy ? 'Формируем…' : 'Сформировать рейс'}</Button>
              <Button size="sm" disabled={!contractor.active || contractorBusy} onClick={() => setManualContractor(contractor)}><ListChecks size={13} />Распределить вручную</Button>
              <Button size="sm" variant="ghost" disabled={contractorBusy} onClick={() => setEditor(contractor)}><Pencil size={13} />Изменить</Button>
            </div>
          </article>
        );
      })}</div>}
      {!loading && !contractors.length ? <EmptyState icon={<UserRoundCheck />} title="Наёмные водители не добавлены" description="Добавьте подрядчика без дат. Дату конкретного рейса вы выберете в хедере." /> : null}
      {!loading && contractors.length > 0 && activeCount === 0 ? <p className="field__hint">Все наёмные водители неактивны. Активируйте нужного водителя через «Изменить».</p> : null}
      {editor !== undefined ? <ContractorEditor contractor={editor} busy={saving} onClose={() => { setEditor(undefined); setCreateIntentId(null); }} onSave={save} onDelete={remove} /> : null}
      {manualContractor ? <ManualDispatchDialog contractor={manualContractor} planningDate={planningDate} requests={manualRequests} busy={busy || dispatchingWorkerId === manualContractor.workerId} onClose={() => setManualContractor(null)} onSubmit={(requestIds) => dispatch(manualContractor, 'MANUAL', requestIds)} /> : null}
    </>
  );
}
