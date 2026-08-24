import { ArrowUpFromLine, Download, Link2Off } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  api,
  ApiError,
  type RwmsApplyResult,
  type RwmsPlanStatusResult,
  type RwmsPlanTaskStatus,
  type RwmsSyncResult,
} from '../../api/client';
import type { RoutePlan, UUID, Warehouse } from '../../domain/types';
import { Badge, Button, CheckboxField, Modal, SelectField, Spinner } from '../../components/ui';

interface RwmsIntegrationDialogProps {
  scenarioId: UUID;
  planningDate: string;
  warehouses: Warehouse[];
  plan: RoutePlan | null;
  busy: boolean;
  onClose: () => void;
  onSync: (warehouseId: UUID, date: string) => Promise<RwmsSyncResult>;
  onApply: (
    planId: UUID,
    expectedVersion: number,
    publishUnassignedTaskIds: UUID[],
  ) => Promise<RwmsApplyResult>;
  onStatus?: (planId: UUID, expectedVersion: number) => Promise<RwmsPlanStatusResult>;
}

function operationError(error: unknown): string {
  if (error instanceof ApiError && error.code) return `${error.code}: ${error.message}`;
  return error instanceof Error ? error.message : 'Неизвестная ошибка';
}

function publicationStatusLabel(
  status: RwmsPlanTaskStatus | undefined,
  pending: boolean,
  error: string | null,
): string {
  if (!status && pending) return 'Статус RWMS: проверяется';
  if (!status && error) return 'Статус RWMS: не удалось проверить';
  if (!status) return 'Статус RWMS: не опубликовано';
  if (status.driver_audience_mode === 'WAREHOUSE_DRIVERS') {
    return 'Статус RWMS: опубликовано свободным водителям';
  }
  return `Статус RWMS: забрал ${status.driver_name ?? 'водитель'}`;
}

export function RwmsIntegrationDialog({
  scenarioId,
  planningDate,
  warehouses,
  plan,
  busy,
  onClose,
  onSync,
  onApply,
  onStatus = api.getPlanRwmsStatus,
}: RwmsIntegrationDialogProps) {
  const linkedWarehouses = useMemo(
    () => warehouses.filter((warehouse): warehouse is Warehouse & { external_warehouse_id: UUID } => Boolean(warehouse.external_warehouse_id)),
    [warehouses],
  );
  const [warehouseId, setWarehouseId] = useState<UUID>(linkedWarehouses[0]?.external_warehouse_id ?? '');
  const [pending, setPending] = useState<'sync' | 'apply' | null>(null);
  const [syncResult, setSyncResult] = useState<RwmsSyncResult | null>(null);
  const [applyResult, setApplyResult] = useState<RwmsApplyResult | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [statusResult, setStatusResult] = useState<RwmsPlanStatusResult | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [statusPending, setStatusPending] = useState(false);
  const statusRequestSequence = useRef(0);
  const publishableTasks = useMemo(
    () => plan?.unassigned.filter((item) => item.task.type === 'DELIVERY') ?? [],
    [plan],
  );
  const [publishTaskIds, setPublishTaskIds] = useState<UUID[]>([]);
  const taskStatuses = useMemo(
    () => new Map(statusResult?.tasks.map((status) => [status.task_id, status]) ?? []),
    [statusResult],
  );
  const planId = plan?.id ?? null;
  const planVersion = plan?.version ?? null;

  const refreshStatuses = useCallback(async () => {
    if (!planId || planVersion === null) return;
    const sequence = ++statusRequestSequence.current;
    setStatusPending(true);
    setStatusError(null);
    try {
      const result = await onStatus(planId, planVersion);
      if (statusRequestSequence.current === sequence) setStatusResult(result);
    } catch (error: unknown) {
      if (statusRequestSequence.current === sequence) setStatusError(operationError(error));
    } finally {
      if (statusRequestSequence.current === sequence) setStatusPending(false);
    }
  }, [onStatus, planId, planVersion]);

  useEffect(() => {
    if (!linkedWarehouses.some((warehouse) => warehouse.external_warehouse_id === warehouseId)) {
      setWarehouseId(linkedWarehouses[0]?.external_warehouse_id ?? '');
    }
  }, [linkedWarehouses, warehouseId]);

  useEffect(() => {
    const available = statusError
      ? new Set<UUID>()
      : new Set(
        publishableTasks
          .filter((item) => !taskStatuses.has(item.task.id))
          .map((item) => item.task.id),
      );
    setPublishTaskIds((current) => current.filter((taskId) => available.has(taskId)));
  }, [plan?.id, plan?.version, publishableTasks, statusError, taskStatuses]);

  useEffect(() => {
    setStatusResult(null);
    setStatusError(null);
    if (planId) void refreshStatuses();
    return () => {
      statusRequestSequence.current += 1;
    };
  }, [planId, planVersion, refreshStatuses]);

  const synchronize = async () => {
    if (!warehouseId) return;
    setPending('sync');
    setSyncError(null);
    try {
      setSyncResult(await onSync(warehouseId, planningDate));
    } catch (error: unknown) {
      setSyncResult(null);
      setSyncError(operationError(error));
    } finally {
      setPending(null);
    }
  };

  const apply = async () => {
    if (!plan) return;
    setPending('apply');
    setApplyError(null);
    try {
      setApplyResult(await onApply(plan.id, plan.version, publishTaskIds));
      setPublishTaskIds([]);
      await refreshStatuses();
    } catch (error: unknown) {
      setApplyResult(null);
      setApplyError(operationError(error));
    } finally {
      setPending(null);
    }
  };

  return (
    <Modal
      wide
      title="Обмен с RWMS"
      description="Только явные операции: сначала импорт заявок, затем отдельная отправка назначений"
      onClose={onClose}
    >
      <div className="rwms-integration" data-scenario-id={scenarioId}>
        <section className="rwms-integration__section" aria-labelledby="rwms-sync-title">
          <div className="rwms-integration__heading">
            <div>
              <h3 id="rwms-sync-title">1. Получить заявки из RWMS</h3>
              <p>Будут загружены только заявки, доступные на {planningDate}. Существующие данные обновляются по версии заказа.</p>
            </div>
            <Download size={20} />
          </div>
          {linkedWarehouses.length ? (
            <>
              <SelectField label="Связанный склад RWMS" value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
                {linkedWarehouses.map((warehouse) => (
                  <option key={warehouse.id} value={warehouse.external_warehouse_id}>
                    {warehouse.name} · {warehouse.external_warehouse_id}
                  </option>
                ))}
              </SelectField>
              <div className="rwms-integration__actions">
                <Button variant="primary" disabled={busy || pending !== null} onClick={() => void synchronize()}>
                  {pending === 'sync' ? <Spinner label="Синхронизация…" /> : 'Синхронизировать заявки'}
                </Button>
              </div>
            </>
          ) : (
            <div className="rwms-integration__notice" role="status">
              <Link2Off size={18} />
              <span>Нет связанного склада. Откройте склад в редакторе и укажите его UUID из RWMS.</span>
            </div>
          )}
          {syncError ? <div className="rwms-integration__error" role="alert"><strong>Синхронизация не выполнена</strong><span>{syncError}</span></div> : null}
          {syncResult ? (
            <div className="rwms-integration__result" aria-label="Результат синхронизации RWMS">
              <div className="rwms-integration__counts">
                <span><strong>{syncResult.imported}</strong> добавлено</span>
                <span><strong>{syncResult.updated}</strong> обновлено</span>
                <span><strong>{syncResult.skipped}</strong> без изменений</span>
                <span><strong>{syncResult.failures.length}</strong> ошибок</span>
              </div>
              {syncResult.failures.length ? (
                <ul className="rwms-integration__failures">
                  {syncResult.failures.map((failure, index) => (
                    <li key={`${failure.order_id ?? 'unknown'}-${failure.code}-${index}`}>
                      <div><code>{failure.order_id ?? 'заказ без ID'}</code><Badge tone="danger">{failure.code}</Badge></div>
                      <p>{failure.message}</p>
                    </li>
                  ))}
                </ul>
              ) : <p className="rwms-integration__success">Все полученные заявки обработаны без ошибок.</p>}
            </div>
          ) : null}
        </section>

        <section className="rwms-integration__section" aria-labelledby="rwms-apply-title">
          <div className="rwms-integration__heading">
            <div>
              <h3 id="rwms-apply-title">2. Передать назначения в RWMS</h3>
              <p>{plan ? `План ${plan.date}, версия ${plan.version}. Backend проверит эту версию перед отправкой.` : 'Сначала постройте или откройте план выбранного дня.'}</p>
            </div>
            <ArrowUpFromLine size={20} />
          </div>
          <div className="rwms-integration__actions">
            <Button disabled={!plan || busy || pending !== null || statusPending} onClick={() => void refreshStatuses()}>
              {statusPending ? <Spinner label="Обновление…" /> : 'Обновить статусы'}
            </Button>
            <Button variant="primary" disabled={!plan || busy || pending !== null || statusPending} onClick={() => void apply()}>
              {pending === 'apply' ? <Spinner label="Отправка…" /> : 'Передать план в RWMS'}
            </Button>
          </div>
          {publishableTasks.length ? (
            <div className="rwms-integration__pool" aria-label="Будущие доставки в общий пул">
              <strong>Дополнительные задания для DriverApp</strong>
              <p>Отметьте только те нераспределённые доставки, которые логист явно разрешает показать свободным водителям. RWMS повторно проверит дату, заказ и состав бытовок.</p>
              {publishableTasks.map((item) => {
                const taskStatus = taskStatuses.get(item.task.id);
                return (
                  <CheckboxField
                    key={item.task.id}
                    checked={publishTaskIds.includes(item.task.id)}
                    disabled={busy || pending !== null || statusPending || Boolean(statusError) || Boolean(taskStatus)}
                    onChange={(checked) => setPublishTaskIds((current) => (
                      checked
                        ? [...current, item.task.id]
                        : current.filter((taskId) => taskId !== item.task.id)
                    ))}
                    label={`${item.request?.name ?? `Задача ${item.task.part_number}`} · ${item.task.quantity} шт. · ${item.reasons.join('; ')} · ${publicationStatusLabel(taskStatus, statusPending, statusError)}`}
                  />
                );
              })}
            </div>
          ) : null}
          {statusError ? <div className="rwms-integration__error" role="alert"><strong>Статусы RWMS не обновлены</strong><span>{statusError}</span></div> : null}
          {applyError ? <div className="rwms-integration__error" role="alert"><strong>План не передан</strong><span>{applyError}</span></div> : null}
          {applyResult ? (
            <div className="rwms-integration__result" aria-label="Результат отправки плана в RWMS">
              <div className="rwms-integration__counts">
                <span><strong>{applyResult.applied.length}</strong> применено</span>
                <span><strong>{applyResult.rejected.length}</strong> отклонено</span>
              </div>
              {applyResult.applied.length ? (
                <ul className="rwms-integration__applied">
                  {applyResult.applied.map((assignment, index) => (
                    <li key={`${assignment.document_id}-${index}`}>
                      <code>{assignment.order_id}</code>
                      <span>Документ {assignment.document_id}</span>
                      {assignment.replayed ? <Badge tone="neutral">повтор</Badge> : <Badge tone="success">применено</Badge>}
                    </li>
                  ))}
                </ul>
              ) : null}
              {applyResult.rejected.length ? (
                <ul className="rwms-integration__failures">
                  {applyResult.rejected.map((rejection, index) => (
                    <li key={`${rejection.order_id}-${rejection.code}-${index}`}>
                      <div><code>{rejection.order_id}</code><Badge tone="danger">{rejection.code}</Badge></div>
                      <p>{rejection.message}</p>
                    </li>
                  ))}
                </ul>
              ) : <p className="rwms-integration__success">RWMS принял все назначения плана.</p>}
            </div>
          ) : null}
        </section>
      </div>
    </Modal>
  );
}
