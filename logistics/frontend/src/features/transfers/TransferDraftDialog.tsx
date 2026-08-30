import { ArrowRightLeft, LogIn, PackageOpen, Plus, Trash2, Truck } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { beginPanelLogin, restorePanelUser } from '../../auth/panel-oidc';
import { Button, CheckboxField, EmptyState, Field, Modal, SelectField, Spinner } from '../../components/ui';
import type { AvailableWarehouse } from '../../domain/types';
import { formatDistance, formatDuration, localDateTimeToIso } from '../../utils/format';
import {
  createTransferDraft,
  estimateTransferArrival,
  loadTransferCargoCatalog,
  loadTransferRouteVehicles,
  type CreateTransferDraftInput,
  type CreatedTransferDraft,
  type TransferArrivalEstimate,
  type TransferCargoCatalog,
  type TransferRouteVehicle,
} from './transfer-client';

/** Shared panel authentication state visible while the transfer dialog is open. */
type SessionState =
  | { status: 'loading' }
  | { status: 'unauthenticated' }
  | { status: 'authenticated'; accessToken: string };

/** One client-side furniture row that becomes a per-cabin canonical requirement. */
interface FurnitureDraft {
  furnitureCatalogItemId: string;
  quantityPerCabin: number;
}

/** Editable requirement group; the key never crosses the API boundary. */
interface CabinGroupDraft {
  key: string;
  rentalTypeId: string;
  dimensionId: string | null;
  finishingId: string | null;
  characteristicIds: string[];
  linoleum: boolean;
  quantity: number;
  furniturePerCabin: FurnitureDraft[];
}

/** Loading state for a source-warehouse dependent resource. */
type LoadState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; message: string };

/** Calculation state kept separate from the canonical draft until a current result exists. */
type EstimateState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: TransferArrivalEstimate }
  | { status: 'error'; message: string };

function hasRoutingCoordinates(warehouse: AvailableWarehouse) {
  return warehouse.routing_ready && warehouse.latitude !== null && warehouse.longitude !== null;
}

function warehouseOptionLabel(warehouse: AvailableWarehouse) {
  if (warehouse.latitude === null || warehouse.longitude === null) return `${warehouse.name} · Нет координат в RWMS`;
  if (!warehouse.routing_ready) return `${warehouse.name} · ${warehouse.routing_unavailable_reason ?? 'Недоступен для маршрутизации'}`;
  return `${warehouse.name}${warehouse.city ? ` · ${warehouse.city}` : ''}`;
}

function currentReturnTo() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

function errorMessage(value: unknown, fallback: string) {
  return value instanceof Error ? value.message : fallback;
}

function arrivalLabel(value: string, timeZone: string) {
  return new Intl.DateTimeFormat('ru-RU', {
    timeZone,
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function furnitureName(catalog: TransferCargoCatalog | null, id: string) {
  return catalog?.furniture.find((item) => item.id === id)?.name ?? 'Мебель';
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
  const [departureTime, setDepartureTime] = useState('');
  const [comment, setComment] = useState('');
  const [includeCabins, setIncludeCabins] = useState(false);
  const [groups, setGroups] = useState<CabinGroupDraft[]>([]);
  const [catalogState, setCatalogState] = useState<LoadState<TransferCargoCatalog>>({ status: 'idle' });
  const [vehicleState, setVehicleState] = useState<LoadState<TransferRouteVehicle[]>>({ status: 'idle' });
  const [selectedRouteVehicleId, setSelectedRouteVehicleId] = useState('');
  const [estimateState, setEstimateState] = useState<EstimateState>({ status: 'idle' });
  const [session, setSession] = useState<SessionState>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idempotencyIntentRef = useRef<{ fingerprint: string; key: string } | null>(null);
  const groupSequenceRef = useRef(0);

  const sourceWarehouse = warehouses.find((warehouse) => warehouse.warehouse_id === sourceWarehouseId) ?? null;
  const destinationWarehouse = warehouses.find((warehouse) => warehouse.warehouse_id === targetWarehouseId) ?? null;
  const cargoCatalog = catalogState.status === 'ready' ? catalogState.value : null;
  const vehicles = vehicleState.status === 'ready' ? vehicleState.value : [];
  const cabinCount = includeCabins ? groups.reduce((sum, group) => sum + group.quantity, 0) : 0;
  const plannedDepartureAt = date && departureTime && sourceWarehouse
    ? localDateTimeToIso(date, departureTime, sourceWarehouse.timezone)
    : null;
  const plannedArrivalAt = estimateState.status === 'ready' ? estimateState.value.estimated_arrival_at : null;

  const furnitureTotals = useMemo(() => {
    const totals = new Map<string, number>();
    if (!includeCabins) return totals;
    for (const group of groups) {
      for (const item of group.furniturePerCabin) {
        totals.set(
          item.furnitureCatalogItemId,
          (totals.get(item.furnitureCatalogItemId) ?? 0) + item.quantityPerCabin * group.quantity,
        );
      }
    }
    return totals;
  }, [groups, includeCabins]);

  const nextGroup = (): CabinGroupDraft => {
    groupSequenceRef.current += 1;
    return {
      key: `cabin-group-${groupSequenceRef.current}`,
      rentalTypeId: '',
      dimensionId: null,
      finishingId: null,
      characteristicIds: [],
      linoleum: false,
      quantity: 1,
      furniturePerCabin: [],
    };
  };

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
        setError(errorMessage(sessionError, 'Не удалось проверить сессию RWMS'));
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (session.status !== 'authenticated' || !includeCabins || !sourceWarehouseId) {
      setCatalogState({ status: 'idle' });
      return undefined;
    }
    let active = true;
    setCatalogState({ status: 'loading' });
    void loadTransferCargoCatalog(session.accessToken, sourceWarehouseId)
      .then((value) => { if (active) setCatalogState({ status: 'ready', value }); })
      .catch((catalogError: unknown) => {
        if (active) setCatalogState({ status: 'error', message: errorMessage(catalogError, 'Не удалось получить состав из RWMS') });
      });
    return () => { active = false; };
  }, [includeCabins, session, sourceWarehouseId]);

  useEffect(() => {
    const localWarehouseId = sourceWarehouse?.local_warehouse_id;
    setSelectedRouteVehicleId('');
    setEstimateState({ status: 'idle' });
    if (!localWarehouseId) {
      setVehicleState({ status: 'idle' });
      return undefined;
    }
    let active = true;
    setVehicleState({ status: 'loading' });
    void loadTransferRouteVehicles(localWarehouseId)
      .then((value) => {
        if (!active) return;
        setVehicleState({ status: 'ready', value });
        setSelectedRouteVehicleId(value[0]?.id ?? '');
      })
      .catch((vehicleError: unknown) => {
        if (active) setVehicleState({ status: 'error', message: errorMessage(vehicleError, 'Не удалось получить автомобили склада') });
      });
    return () => { active = false; };
  }, [sourceWarehouse?.local_warehouse_id]);

  useEffect(() => {
    setEstimateState({ status: 'idle' });
    if (
      !plannedDepartureAt
      || !sourceWarehouseId
      || !targetWarehouseId
      || sourceWarehouseId === targetWarehouseId
      || !selectedRouteVehicleId
      || cabinCount > 2
    ) return undefined;
    let active = true;
    setEstimateState({ status: 'loading' });
    void estimateTransferArrival({
      sourceWarehouseId,
      destinationWarehouseId: targetWarehouseId,
      plannedDepartureAt,
      vehicleId: selectedRouteVehicleId,
      cabinCount,
    })
      .then((value) => { if (active) setEstimateState({ status: 'ready', value }); })
      .catch((estimateError: unknown) => {
        if (active) setEstimateState({ status: 'error', message: errorMessage(estimateError, 'Не удалось рассчитать прибытие') });
      });
    return () => { active = false; };
  }, [cabinCount, plannedDepartureAt, selectedRouteVehicleId, sourceWarehouseId, targetWarehouseId]);

  const login = async () => {
    setError(null);
    try {
      await beginPanelLogin(currentReturnTo());
    } catch (loginError: unknown) {
      setError(errorMessage(loginError, 'Не удалось открыть вход RWMS'));
    }
  };

  const validateCabins = () => {
    if (!includeCabins) return null;
    if (catalogState.status !== 'ready') return 'Дождитесь загрузки справочников бытовок и мебели из RWMS';
    if (!groups.length) return 'Добавьте хотя бы одну бытовку';
    for (const [index, group] of groups.entries()) {
      if (!group.rentalTypeId) return `Выберите тип бытовок в группе ${index + 1}`;
      if (group.quantity < 1 || group.quantity > 100) return `Укажите корректное количество в группе ${index + 1}`;
      if (group.furniturePerCabin.some((item) => !item.furnitureCatalogItemId || item.quantityPerCabin < 1)) {
        return `Заполните мебель на одну бытовку в группе ${index + 1}`;
      }
      if (new Set(group.furniturePerCabin.map((item) => item.furnitureCatalogItemId)).size !== group.furniturePerCabin.length) {
        return `Одна позиция мебели повторяется в группе ${index + 1}`;
      }
    }
    return null;
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
    if (!departureTime) {
      setError('Укажите плановое время отправления');
      return;
    }
    if (vehicleState.status === 'loading' || estimateState.status === 'loading') {
      setError('Дождитесь загрузки автомобиля и расчёта планового прибытия');
      return;
    }
    const cabinError = validateCabins();
    if (cabinError) {
      setError(cabinError);
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
          plannedDepartureAt,
          plannedArrivalAt,
          logisticsComment: comment.trim() || null,
          tripDriverId: null,
          tripVehicleId: null,
          driverReposition: null,
          vehicleReposition: null,
          cabinGroups: includeCabins ? groups.map((group) => ({
            rentalTypeId: group.rentalTypeId,
            dimensionId: group.dimensionId,
            finishingId: group.finishingId,
            characteristicIds: group.characteristicIds,
            linoleum: group.linoleum,
            quantity: group.quantity,
            furniturePerCabin: group.furniturePerCabin,
            allocatedCabins: [],
          })) : [],
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
      setError(errorMessage(submitError, 'Не удалось создать черновик перемещения'));
    } finally {
      setBusy(false);
    }
  };

  const updateGroup = (index: number, value: CabinGroupDraft) => {
    setGroups((current) => current.map((group, groupIndex) => groupIndex === index ? value : group));
  };

  return (
    <Modal
      title="Создать перемещение"
      description="Логист задаёт маршрут и требуемый груз здесь; RWMS получает готовый черновик подготовки."
      onClose={onClose}
      wide
      footer={session.status === 'authenticated' ? <><Button onClick={onClose}>Отмена</Button><Button variant="primary" disabled={busy || routingWarehouses.length < 2 || vehicleState.status === 'loading' || estimateState.status === 'loading'} onClick={() => void submit()}>{busy ? 'Создаём…' : 'Создать черновик'}</Button></> : undefined}
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
        <form className="transfer-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <section className="transfer-section" aria-labelledby="transfer-route-title">
            <header className="transfer-section__header">
              <span><ArrowRightLeft size={16} aria-hidden="true" /></span>
              <div><h3 id="transfer-route-title">Маршрут</h3><p>Отправление задаёт логист, прибытие рассчитывается по грузовому маршруту.</p></div>
            </header>
            <div className="form-grid">
              <SelectField label="Склад отправления" value={sourceWarehouseId} onChange={(event) => setSourceWarehouseId(event.target.value)}>
                <option value="">Выберите склад</option>
                {warehouses.map((warehouse) => <option key={warehouse.warehouse_id} value={warehouse.warehouse_id} disabled={!hasRoutingCoordinates(warehouse)}>{warehouseOptionLabel(warehouse)}</option>)}
              </SelectField>
              <SelectField label="Склад назначения" value={targetWarehouseId} onChange={(event) => setTargetWarehouseId(event.target.value)}>
                <option value="">Выберите склад</option>
                {warehouses.map((warehouse) => <option key={warehouse.warehouse_id} value={warehouse.warehouse_id} disabled={!hasRoutingCoordinates(warehouse)}>{warehouseOptionLabel(warehouse)}</option>)}
              </SelectField>
              <Field label="Плановая дата" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
              <Field label="Плановое отправление" type="time" value={departureTime} onChange={(event) => setDepartureTime(event.target.value)} />
              <SelectField label="Автомобиль для расчёта маршрута" value={selectedRouteVehicleId} disabled={vehicleState.status !== 'ready' || vehicles.length === 0} onChange={(event) => setSelectedRouteVehicleId(event.target.value)}>
                <option value="">Не выбран</option>
                {vehicles.map((vehicle) => <option key={vehicle.id} value={vehicle.id}>{vehicle.name} · {vehicle.registrationNumber} · до {vehicle.capacity} бытовок</option>)}
              </SelectField>
              <div className="transfer-arrival" aria-live="polite">
                <small>Плановое прибытие</small>
                {estimateState.status === 'loading' ? <Spinner label="Считаем маршрут…" /> : null}
                {estimateState.status === 'ready' && destinationWarehouse ? <><strong>{arrivalLabel(estimateState.value.estimated_arrival_at, destinationWarehouse.timezone)}</strong><span>{formatDuration(estimateState.value.travel_seconds)} · {formatDistance(estimateState.value.distance_meters)}{estimateState.value.trailer_attached ? ' · с прицепом' : ''}</span></> : null}
                {estimateState.status === 'error' ? <span className="transfer-arrival__error">{estimateState.message}</span> : null}
                {estimateState.status === 'idle' ? <span>{cabinCount > 2 ? 'В одном рейсе больше двух бытовок: требуется изменить состав или транспорт.' : sourceWarehouse?.local_warehouse_id ? 'Укажите время и расчётный автомобиль.' : 'Склад ещё не синхронизирован с локальным планировщиком.'}</span> : null}
              </div>
              {vehicleState.status === 'error' ? <p className="field__error span-2">{vehicleState.message}</p> : null}
            </div>
          </section>

          <section className="transfer-section" aria-labelledby="transfer-cargo-title">
            <header className="transfer-section__header">
              <span><PackageOpen size={16} aria-hidden="true" /></span>
              <div><h3 id="transfer-cargo-title">Груз</h3><p>Пустое перемещение допустимо. Включите бытовки, чтобы передать точные требования в RWMS.</p></div>
            </header>
            <div className="transfer-cargo-toggle">
              <CheckboxField
                label="Перевозить бытовки"
                checked={includeCabins}
                onChange={(checked) => {
                  setIncludeCabins(checked);
                  if (checked && groups.length === 0) setGroups([nextGroup()]);
                }}
              />
              <span>{includeCabins ? `${cabinCount} бытовок в ${groups.length} группах` : 'Без бытовок'}</span>
            </div>
            {includeCabins && catalogState.status === 'loading' ? <Spinner label="Загружаем типы, отделки и мебель из RWMS…" /> : null}
            {includeCabins && catalogState.status === 'error' ? <div className="error-panel" role="alert"><strong>Справочники груза недоступны</strong><p>{catalogState.message}</p></div> : null}
            {includeCabins && cargoCatalog ? (
              <div className="transfer-cabin-groups">
                {groups.map((group, index) => {
                  const dimensions = cargoCatalog.dimensions.filter((dimension) => (
                    cargoCatalog.typeDimensions.length === 0
                    || cargoCatalog.typeDimensions.some((pair) => pair.typeId === group.rentalTypeId && pair.dimensionId === dimension.id)
                  ));
                  return (
                    <article className="transfer-cabin-card" aria-label={`Бытовка ${index + 1}`} key={group.key}>
                      <header><div><strong>Бытовка {index + 1}</strong><span>Отдельная конфигурация груза</span></div><Button type="button" size="sm" variant="ghost" aria-label={`Удалить бытовку ${index + 1}`} onClick={() => setGroups((current) => current.filter((_item, itemIndex) => itemIndex !== index))}><Trash2 size={14} aria-hidden="true" /></Button></header>
                      <div className="transfer-cabin-card__fields">
                        <SelectField label="Тип бытовки" aria-label={`Тип бытовки ${index + 1}`} value={group.rentalTypeId} onChange={(event) => updateGroup(index, { ...group, rentalTypeId: event.target.value, dimensionId: null })}>
                          <option value="">Выберите тип</option>
                          {cargoCatalog.rentalTypes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                        </SelectField>
                        <SelectField label="Исполнение / размер" aria-label={`Исполнение бытовки ${index + 1}`} value={group.dimensionId ?? ''} onChange={(event) => updateGroup(index, { ...group, dimensionId: event.target.value || null })}>
                          <option value="">Не указано</option>
                          {dimensions.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                        </SelectField>
                        <SelectField label="Отделка" aria-label={`Отделка бытовки ${index + 1}`} value={group.finishingId ?? ''} onChange={(event) => updateGroup(index, { ...group, finishingId: event.target.value || null })}>
                          <option value="">Не указано</option>
                          {cargoCatalog.finishings.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                        </SelectField>
                        <Field label="Количество" aria-label={`Количество бытовок ${index + 1}`} type="number" min="1" max="100" value={group.quantity} onChange={(event) => updateGroup(index, { ...group, quantity: Math.min(100, Math.max(1, Number(event.target.value) || 1)) })} />
                      </div>
                      <div className="transfer-characteristics">
                        <CheckboxField label="Линолеум" checked={group.linoleum} onChange={(checked) => updateGroup(index, { ...group, linoleum: checked })} />
                        {cargoCatalog.characteristics.map((characteristic) => <CheckboxField key={characteristic.id} label={characteristic.name} checked={group.characteristicIds.includes(characteristic.id)} onChange={(checked) => updateGroup(index, { ...group, characteristicIds: checked ? [...group.characteristicIds, characteristic.id] : group.characteristicIds.filter((id) => id !== characteristic.id) })} />)}
                      </div>
                      <div className="transfer-furniture">
                        <header><div><strong>Наполнение на одну бытовку</strong><span>Итог автоматически умножается на количество.</span></div><Button type="button" size="sm" disabled={cargoCatalog.furniture.length === 0} onClick={() => updateGroup(index, { ...group, furniturePerCabin: [...group.furniturePerCabin, { furnitureCatalogItemId: '', quantityPerCabin: 1 }] })}><Plus size={14} aria-hidden="true" />Добавить мебель</Button></header>
                        {group.furniturePerCabin.length === 0 ? <p>Мебель не добавлена.</p> : null}
                        {group.furniturePerCabin.map((item, furnitureIndex) => (
                          <div className="transfer-furniture__row" key={`${group.key}-furniture-${furnitureIndex}`}>
                            <SelectField label={`Мебель ${furnitureIndex + 1}`} value={item.furnitureCatalogItemId} onChange={(event) => updateGroup(index, { ...group, furniturePerCabin: group.furniturePerCabin.map((current, itemIndex) => itemIndex === furnitureIndex ? { ...current, furnitureCatalogItemId: event.target.value } : current) })}>
                              <option value="">Выберите позицию</option>
                              {cargoCatalog.furniture.map((furniture) => <option key={furniture.id} value={furniture.id}>{furniture.name} · доступно {furniture.availableStock} · резерв {furniture.reservedQuantity}</option>)}
                            </SelectField>
                            <Field label="На одну бытовку" aria-label={`Количество мебели ${furnitureIndex + 1} для бытовки ${index + 1}`} type="number" min="1" value={item.quantityPerCabin} onChange={(event) => updateGroup(index, { ...group, furniturePerCabin: group.furniturePerCabin.map((current, itemIndex) => itemIndex === furnitureIndex ? { ...current, quantityPerCabin: Math.max(1, Number(event.target.value) || 1) } : current) })} />
                            <Button type="button" size="sm" variant="ghost" aria-label={`Удалить мебель ${furnitureIndex + 1} из бытовки ${index + 1}`} onClick={() => updateGroup(index, { ...group, furniturePerCabin: group.furniturePerCabin.filter((_current, itemIndex) => itemIndex !== furnitureIndex) })}><Trash2 size={14} aria-hidden="true" /></Button>
                          </div>
                        ))}
                      </div>
                    </article>
                  );
                })}
                <Button type="button" className="transfer-add-cabin" onClick={() => setGroups((current) => [...current, nextGroup()])}><Plus size={15} aria-hidden="true" />Добавить ещё бытовку</Button>
              </div>
            ) : null}
          </section>

          <section className="transfer-section transfer-summary" aria-labelledby="transfer-summary-title">
            <header className="transfer-section__header">
              <span><Truck size={16} aria-hidden="true" /></span>
              <div><h3 id="transfer-summary-title">Итог</h3><p>Это требование к грузу; конкретные физические бытовки выбираются при подготовке и подтверждении.</p></div>
            </header>
            <div className="transfer-summary__facts"><span><small>Бытовки</small><strong>{cabinCount}</strong></span><span><small>Группы</small><strong>{includeCabins ? groups.length : 0}</strong></span><span><small>Мебель, шт.</small><strong>{[...furnitureTotals.values()].reduce((sum, quantity) => sum + quantity, 0)}</strong></span></div>
            {furnitureTotals.size > 0 ? <ul>{[...furnitureTotals.entries()].map(([id, quantity]) => <li key={id}>{furnitureName(cargoCatalog, id)} — {quantity}</li>)}</ul> : <p>Наполнение не добавлено.</p>}
          </section>

          <label className="field"><span className="field__label">Комментарий логиста</span><textarea className="input" aria-label="Комментарий логиста" rows={3} maxLength={2000} value={comment} onChange={(event) => setComment(event.target.value)} /></label>
        </form>
      ) : null}
      {routingWarehouses.length < 2 ? <div className="error-panel" role="alert"><strong>Недостаточно складов для маршрута</strong><p>Нужны как минимум два склада RWMS с координатами.</p></div> : null}
      {error ? <div className="error-panel" role="alert"><strong>Перемещение не сохранено</strong><p>{error}</p></div> : null}
    </Modal>
  );
}
