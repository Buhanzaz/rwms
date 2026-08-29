import { ArrowRightLeft, LogIn } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { beginPanelLogin, restorePanelUser } from '../../auth/panel-oidc';
import { Button, EmptyState, Field, Modal, SelectField, Spinner } from '../../components/ui';
import type { AvailableWarehouse } from '../../domain/types';
import { createTransferDraft, type CreateTransferDraftInput, type CreatedTransferDraft } from './transfer-client';

/** Shared panel authentication state visible while the transfer dialog is open. */
type SessionState =
  | { status: 'loading' }
  | { status: 'unauthenticated' }
  | { status: 'authenticated'; accessToken: string };

function hasRoutingCoordinates(warehouse: AvailableWarehouse) {
  return warehouse.routing_ready && warehouse.latitude !== null && warehouse.longitude !== null;
}

function warehouseOptionLabel(warehouse: AvailableWarehouse) {
  if (warehouse.latitude === null || warehouse.longitude === null) return `${warehouse.name} · Нет координат в RWMS`;
  if (!warehouse.routing_ready) return `${warehouse.name} · ${warehouse.routing_unavailable_reason ?? 'Недоступен для маршрутизации'}`;
  return `${warehouse.name}${warehouse.city ? ` · ${warehouse.city}` : ''}`;
}

function toIsoTimestamp(value: string) {
  return value ? new Date(value).toISOString() : null;
}

function currentReturnTo() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

export function TransferDraftDialog({
  warehouses,
  sourceWarehouseId: initialSourceWarehouseId,
  destinationWarehouseId,
  scheduledDate,
  onClose,
  onCreated,
}: {
  warehouses: AvailableWarehouse[];
  sourceWarehouseId?: string | undefined;
  destinationWarehouseId: string;
  scheduledDate: string;
  onClose: () => void;
  onCreated: (draft: CreatedTransferDraft) => void;
}) {
  const routingWarehouses = useMemo(() => warehouses.filter(hasRoutingCoordinates), [warehouses]);
  const defaultSource = routingWarehouses.some((warehouse) => warehouse.warehouse_id === initialSourceWarehouseId)
    ? initialSourceWarehouseId ?? ''
    : routingWarehouses.find((warehouse) => warehouse.warehouse_id !== destinationWarehouseId)?.warehouse_id ?? '';
  const [sourceWarehouseId, setSourceWarehouseId] = useState(defaultSource);
  const [targetWarehouseId, setTargetWarehouseId] = useState(destinationWarehouseId);
  const [date, setDate] = useState(scheduledDate);
  const [departureAt, setDepartureAt] = useState('');
  const [arrivalAt, setArrivalAt] = useState('');
  const [comment, setComment] = useState('');
  const [session, setSession] = useState<SessionState>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idempotencyIntentRef = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    let active = true;
    void restorePanelUser()
      .then((user) => {
        if (!active) return;
        setSession(user ? { status: 'authenticated', accessToken: user.access_token } : { status: 'unauthenticated' });
      })
      .catch((sessionError: unknown) => {
        if (!active) return;
        setSession({ status: 'unauthenticated' });
        setError(sessionError instanceof Error ? sessionError.message : 'Не удалось проверить сессию RWMS');
      });
    return () => { active = false; };
  }, []);

  const login = async () => {
    setError(null);
    try {
      await beginPanelLogin(currentReturnTo());
    } catch (loginError: unknown) {
      setError(loginError instanceof Error ? loginError.message : 'Не удалось открыть вход RWMS');
    }
  };

  const submit = async () => {
    setError(null);
    if (sourceWarehouseId === targetWarehouseId) {
      setError('Склад отправления и склад назначения должны отличаться');
      return;
    }
    if (!sourceWarehouseId || !targetWarehouseId) {
      setError('Выберите склад отправления и склад назначения');
      return;
    }
    if (!date) {
      setError('Выберите плановую дату');
      return;
    }
    if (departureAt && arrivalAt && Date.parse(arrivalAt) <= Date.parse(departureAt)) {
      setError('Время прибытия должно быть позже времени отправления');
      return;
    }
    setBusy(true);
    try {
      const user = await restorePanelUser();
      if (!user) {
        setSession({ status: 'unauthenticated' });
        return;
      }
      setSession({ status: 'authenticated', accessToken: user.access_token });
      const intention: Omit<CreateTransferDraftInput, 'accessToken' | 'idempotencyKey'> = {
        warehouseId: sourceWarehouseId,
        destinationWarehouseId: targetWarehouseId,
        scheduledDate: date,
        plan: {
          plannedDepartureAt: toIsoTimestamp(departureAt),
          plannedArrivalAt: toIsoTimestamp(arrivalAt),
          logisticsComment: comment.trim() || null,
          tripDriverId: null,
          tripVehicleId: null,
          driverReposition: null,
          vehicleReposition: null,
          cabinGroups: [],
          looseFurniture: [],
        },
      };
      const fingerprint = JSON.stringify(intention);
      if (idempotencyIntentRef.current?.fingerprint !== fingerprint) {
        idempotencyIntentRef.current = { fingerprint, key: crypto.randomUUID() };
      }
      const draft = await createTransferDraft({
        accessToken: user.access_token,
        idempotencyKey: idempotencyIntentRef.current.key,
        ...intention,
      });
      onCreated(draft);
    } catch (submitError: unknown) {
      setError(submitError instanceof Error ? submitError.message : 'Не удалось создать черновик перемещения');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title="Создать перемещение"
      description="Черновик сохраняется напрямую в канонической логистике RWMS, не покидая карту."
      onClose={onClose}
      wide
      footer={session.status === 'authenticated' ? <><Button onClick={onClose}>Отмена</Button><Button variant="primary" disabled={busy || routingWarehouses.length < 2} onClick={() => void submit()}>{busy ? 'Создаём…' : 'Создать черновик'}</Button></> : undefined}
    >
      {session.status === 'loading' ? <Spinner label="Проверяем сессию RWMS…" /> : null}
      {session.status === 'unauthenticated' ? (
        <EmptyState
          icon={<LogIn size={22} aria-hidden="true" />}
          title="Нужен вход в RWMS"
          description="Войдите своей учётной записью пользователя. После входа вы вернётесь в эту логистику и сможете сохранить перемещение."
          action={<Button variant="primary" onClick={() => void login()}><LogIn size={15} aria-hidden="true" />Войти в RWMS</Button>}
        />
      ) : null}
      {session.status === 'authenticated' ? (
        <form className="form-grid" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <div className="span-2 detail-item"><small>Маршрут</small><strong><ArrowRightLeft size={14} aria-hidden="true" /> Межскладское перемещение</strong><span>Склад обслуживания и физический источник сохраняются раздельно в RWMS.</span></div>
          <SelectField label="Склад отправления" value={sourceWarehouseId} onChange={(event) => setSourceWarehouseId(event.target.value)}>
            <option value="">Выберите склад</option>
            {warehouses.map((warehouse) => <option key={warehouse.warehouse_id} value={warehouse.warehouse_id} disabled={!hasRoutingCoordinates(warehouse)}>{warehouseOptionLabel(warehouse)}</option>)}
          </SelectField>
          <SelectField label="Склад назначения" value={targetWarehouseId} onChange={(event) => setTargetWarehouseId(event.target.value)}>
            <option value="">Выберите склад</option>
            {warehouses.map((warehouse) => <option key={warehouse.warehouse_id} value={warehouse.warehouse_id} disabled={!hasRoutingCoordinates(warehouse)}>{warehouseOptionLabel(warehouse)}</option>)}
          </SelectField>
          <Field label="Плановая дата" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
          <div />
          <Field label="Плановое отправление" type="datetime-local" value={departureAt} onChange={(event) => setDepartureAt(event.target.value)} />
          <Field label="Плановое прибытие" type="datetime-local" value={arrivalAt} onChange={(event) => setArrivalAt(event.target.value)} />
          <label className="field span-2"><span className="field__label">Комментарий логиста</span><textarea className="input" aria-label="Комментарий логиста" rows={3} maxLength={2000} value={comment} onChange={(event) => setComment(event.target.value)} /></label>
          <p className="field__hint span-2">Водителя, автомобиль, перемещение ресурса, бытовки и мебель можно добавить в сохранённый канонический план.</p>
        </form>
      ) : null}
      {routingWarehouses.length < 2 ? <div className="error-panel" role="alert"><strong>Недостаточно складов для маршрута</strong><p>Нужны как минимум два склада RWMS с координатами.</p></div> : null}
      {error ? <div className="error-panel" role="alert"><strong>Перемещение не сохранено</strong><p>{error}</p></div> : null}
    </Modal>
  );
}
