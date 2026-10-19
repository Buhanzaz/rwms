import { useMemo, useState } from 'react';
import type { LogisticsEventInput } from '../../api/client';
import type { RoutePlan, UUID, WarehouseWorkspace } from '../../domain/types';
import { Button, Field, Modal, SelectField } from '../../components/ui';
import { formatDate, localDateTimeToIso } from '../../utils/format';
import {
  DISPATCHER_EVENT_LABELS,
  type DispatcherEventType,
} from './operations-presentation';

const TERMINAL_TASK_STATUSES = new Set(['COMPLETED', 'CANCELLED']);

interface OperationsEventDialogProps {
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  planningDate: string;
  busy: boolean;
  initialEventType?: DispatcherEventType;
  onClose: () => void;
  onSubmit: (input: LogisticsEventInput) => Promise<void>;
}

function requestMatchesEvent(type: DispatcherEventType, requestType: 'DELIVERY' | 'PICKUP'): boolean {
  if (type === 'DELIVERY_CANCELLED') return requestType === 'DELIVERY';
  if (type === 'PICKUP_CANCELLED') return requestType === 'PICKUP';
  return true;
}

export function OperationsEventDialog({
  workspace,
  plan,
  planningDate,
  busy,
  initialEventType = 'VEHICLE_BREAKDOWN',
  onClose,
  onSubmit,
}: OperationsEventDialogProps) {
  const [eventType, setEventType] = useState<DispatcherEventType>(initialEventType);
  const [vehicleId, setVehicleId] = useState<UUID>('');
  const [trailerId, setTrailerId] = useState<UUID>('');
  const [recoveryMode, setRecoveryMode] = useState<'AUTO' | 'MANUAL'>('AUTO');
  const [driverShiftId, setDriverShiftId] = useState<UUID>('');
  const [requestId, setRequestId] = useState<UUID>('');
  const [taskId, setTaskId] = useState<UUID>('');
  const [delayMinutes, setDelayMinutes] = useState('30');
  const [occurredTime, setOccurredTime] = useState('');
  const [reason, setReason] = useState('');

  const operationsWarehouseId = workspace.planning_root_warehouse_id ?? workspace.warehouse.id;
  const operationsWarehouse = workspace.warehouses.find((warehouse) => warehouse.id === operationsWarehouseId)
    ?? (workspace.warehouse.id === operationsWarehouseId ? workspace.warehouse : null);
  const operationsTimeZone = operationsWarehouse?.timezone ?? workspace.warehouse.timezone;
  const currentPlan = plan?.warehouse_id === operationsWarehouseId && plan.date === planningDate
    ? plan
    : null;
  const cancellation = eventType === 'DELIVERY_CANCELLED'
    || eventType === 'PICKUP_CANCELLED'
    || eventType === 'ORDER_CANCELLED';
  const groupWarehouseIds = useMemo(() => new Set(
    workspace.planning_group_warehouse_ids?.length
      ? workspace.planning_group_warehouse_ids
      : [operationsWarehouseId, workspace.warehouse.id],
  ), [operationsWarehouseId, workspace.planning_group_warehouse_ids, workspace.warehouse.id]);
  const requests = useMemo(() => workspace.requests.filter((request) => (
    groupWarehouseIds.has(request.warehouse_id)
    && request.scheduled_date === planningDate
    && requestMatchesEvent(eventType, request.type)
  )), [eventType, groupWarehouseIds, planningDate, workspace.requests]);
  const taskOptions = useMemo(() => requests.flatMap((request) => (
    (request.tasks ?? [])
      .filter((task) => !TERMINAL_TASK_STATUSES.has(task.status))
      .map((task) => ({ request, task }))
  )), [requests]);
  const warehouseNames = useMemo(() => new Map(
    [...workspace.warehouses, workspace.warehouse].map((warehouse) => [warehouse.id, warehouse.name]),
  ), [workspace.warehouse, workspace.warehouses]);
  const needsVehicle = eventType === 'VEHICLE_BREAKDOWN' || eventType === 'VEHICLE_DELAY';
  const resourceLoss = eventType === 'VEHICLE_BREAKDOWN'
    || eventType === 'TRAILER_BREAKDOWN';
  const delay = Number(delayMinutes);
  const valid = /^([01]\d|2[0-3]):[0-5]\d$/u.test(occurredTime)
    && reason.trim().length > 0
    && (!needsVehicle || Boolean(vehicleId))
    && (eventType !== 'TRAILER_BREAKDOWN' || Boolean(trailerId))
    && (eventType !== 'DRIVER_UNAVAILABLE' || Boolean(driverShiftId))
    && (!cancellation || Boolean(requestId))
    && (eventType !== 'TASK_BLOCKED' || Boolean(taskId))
    && (eventType !== 'VEHICLE_DELAY' || (Number.isInteger(delay) && delay >= 1 && delay <= 1440));

  const submit = async () => {
    if (!valid) return;
    const occurredAt = localDateTimeToIso(planningDate, occurredTime, operationsTimeZone);
    await onSubmit({
      event_type: eventType,
      occurred_at: occurredAt,
      effective_at: occurredAt,
      reason: reason.trim(),
      facts: {},
      recovery_mode: resourceLoss ? recoveryMode : 'MANUAL',
      plan_id: currentPlan?.id ?? null,
      expected_plan_version: currentPlan?.version ?? null,
      ...(needsVehicle ? { vehicle_id: vehicleId } : {}),
      ...(eventType === 'TRAILER_BREAKDOWN' ? { trailer_id: trailerId } : {}),
      ...(eventType === 'VEHICLE_DELAY' ? { delay_minutes: delay } : {}),
      ...(eventType === 'DRIVER_UNAVAILABLE' ? { driver_shift_id: driverShiftId } : {}),
      ...(cancellation ? { request_id: requestId } : {}),
      ...(eventType === 'TASK_BLOCKED' ? { task_id: taskId } : {}),
    });
  };

  return (
    <Modal
      title={DISPATCHER_EVENT_LABELS[eventType]}
      description={`${workspace.warehouse.name} · ${formatDate(planningDate)} Укажите местное время события (${operationsTimeZone}).`}
      onClose={onClose}
      footer={(
        <>
          <Button onClick={onClose} disabled={busy}>Отмена</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={() => void submit()}>
            {busy ? 'Анализируем…' : 'Зафиксировать и оценить влияние'}
          </Button>
        </>
      )}
    >
      <fieldset className="form-grid operations-event-form" aria-label="Обстоятельства события" disabled={busy}>
        <Field
          label={`Время события (${operationsTimeZone})`}
          type="time"
          value={occurredTime}
          onChange={(event) => setOccurredTime(event.target.value)}
          required
        />
        <SelectField
          label="Событие"
          value={eventType}
          onChange={(event) => {
            setEventType(event.target.value as DispatcherEventType);
            setRequestId('');
            setTaskId('');
          }}
        >
          {(Object.entries(DISPATCHER_EVENT_LABELS) as Array<[DispatcherEventType, string]>)
            .map(([value, label]) => <option value={value} key={value}>{label}</option>)}
        </SelectField>

        {needsVehicle ? (
          <SelectField label="Машина" value={vehicleId} onChange={(event) => setVehicleId(event.target.value)}>
            <option value="">Выберите машину</option>
            {workspace.vehicles.map((vehicle) => (
              <option value={vehicle.id} key={vehicle.id}>
                {vehicle.name} · {vehicle.registration_number || 'без номера'}{vehicle.active ? '' : ' · неактивна'}
              </option>
            ))}
          </SelectField>
        ) : null}

        {eventType === 'DRIVER_UNAVAILABLE' ? (
          <SelectField label="Смена водителя" value={driverShiftId} onChange={(event) => setDriverShiftId(event.target.value)}>
            <option value="">Выберите смену</option>
            {workspace.shifts.map((shift) => {
              const driver = workspace.drivers.find((candidate) => candidate.id === shift.driver_id);
              const vehicle = workspace.vehicles.find((candidate) => candidate.id === shift.vehicle_id);
              return (
                <option value={shift.id} key={shift.id}>
                  {driver?.name ?? 'Водитель'} · {vehicle?.registration_number ?? 'машина не указана'} · {shift.start_time.slice(0, 5)}–{shift.end_time.slice(0, 5)}
                </option>
              );
            })}
          </SelectField>
        ) : null}

        {eventType === 'TRAILER_BREAKDOWN' ? (
          <SelectField label="Прицеп" value={trailerId} onChange={(event) => setTrailerId(event.target.value)}>
            <option value="">Выберите прицеп</option>
            {(workspace.trailers ?? []).map((trailer) => (
              <option value={trailer.id} key={trailer.id}>
                {trailer.name} · {trailer.registration_number}{trailer.active ? '' : ' · неактивен'}
              </option>
            ))}
          </SelectField>
        ) : null}

        {resourceLoss ? (
          <>
            <SelectField label="Восстановление маршрутов" value={recoveryMode} onChange={(event) => setRecoveryMode(event.target.value as 'AUTO' | 'MANUAL')}>
              <option value="AUTO">Автоматически — применить безопасную замену</option>
              <option value="MANUAL">Вручную — сначала проверить предложение</option>
            </SelectField>
            <p className="field__hint form-grid__full">
              Доставки сохраняют приоритет. Проверяются свободные смены, габариты и дорожные ограничения.
              Груз в пути требует подтверждения фактического положения и передачи.
              Если замены нет, откройте затронутую заявку, чтобы согласовать перенос или наёмного водителя.
            </p>
          </>
        ) : null}

        {cancellation ? (
          <SelectField label="Отменённая заявка" value={requestId} onChange={(event) => setRequestId(event.target.value)}>
            <option value="">Выберите заявку выбранного дня</option>
            {requests.map((request) => (
              <option value={request.id} key={request.id}>
                {request.name} · {warehouseNames.get(request.warehouse_id) ?? 'склад не указан'} · {request.address_label}
              </option>
            ))}
          </SelectField>
        ) : null}

        {eventType === 'TASK_BLOCKED' ? (
          <SelectField label="Заблокированное задание" value={taskId} onChange={(event) => setTaskId(event.target.value)}>
            <option value="">Выберите активное задание выбранного дня</option>
            {taskOptions.map(({ request, task }) => (
              <option value={task.id} key={task.id}>
                {request.name} · часть {task.part_number} · {task.quantity} бытов. · {warehouseNames.get(request.warehouse_id) ?? 'склад не указан'}
              </option>
            ))}
          </SelectField>
        ) : null}

        {eventType === 'VEHICLE_DELAY' ? (
          <Field
            label="Задержка, минут"
            type="number"
            min={1}
            max={1440}
            value={delayMinutes}
            onChange={(event) => setDelayMinutes(event.target.value)}
          />
        ) : null}

        <Field
          className="form-grid__full"
          label="Причина"
          value={reason}
          maxLength={1000}
          placeholder="Кратко опишите подтверждённый факт"
          onChange={(event) => setReason(event.target.value)}
        />
        <p className="field__hint form-grid__full">
          {currentPlan
            ? 'После сохранения появятся последствия для маршрутов и варианты дальнейших действий.'
            : 'На этот день ещё нет плана. Событие сохранится в истории дня.'}
        </p>
      </fieldset>
    </Modal>
  );
}
