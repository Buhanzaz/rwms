import { useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import {
  applyRepairEstimateMovementPlans,
  buildRepairEstimateTaskPlans,
  createRepairEstimateMovementTaskPlan,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
  LogisticsPlanningMode,
  RepairPriority,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  getWarehouseQueueCapabilities,
  warehouseQueueCapabilitiesQueryKey,
} from "@/features/repair-estimates/api/warehouse-queue-capabilities"

export type RepairWorkCompletionResult = {
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  taskPlans: RepairEstimateTaskPlanDto[]
  priority: RepairPriority
}

type RepairWorkCompletionDialogProps = {
  open: boolean
  accessToken: string | null
  warehouseId: string
  lines: RepairEstimateLineDto[]
  pending: boolean
  error: string | null
  title: string
  description: string
  completeLabel: string
  pendingLabel: string
  previewKey: string
  allowEmpty?: boolean
  emptyTitle?: string
  emptyDescription?: string
  emptyCompleteLabel?: string
  initialCompletionMode?: RepairEstimateCompletionMode
  initialMovementRequired?: boolean
  initialLogisticsPlanningMode?: LogisticsPlanningMode
  initialLogisticsScheduledDate?: string | null
  initialPriority?: RepairPriority
  movementRouteAvailable?: boolean
  routingSelectionAvailable?: boolean
  planStructureEditingAvailable?: boolean
  selectPriority?: boolean
  reconcileInitialPlans?: (
    preparedPlans: RepairEstimateTaskPlanDto[]
  ) => RepairEstimateTaskPlanDto[]
  onOpenChange: (open: boolean) => void
  onComplete: (result: RepairWorkCompletionResult) => void
}

export function RepairWorkCompletionDialog({
  open,
  accessToken,
  warehouseId,
  lines,
  pending,
  error,
  title,
  description,
  completeLabel,
  pendingLabel,
  previewKey,
  allowEmpty = false,
  emptyTitle = "Бытовка готова",
  emptyDescription = "Пустая смета завершит осмотр, переведёт бытовку в статус «Свободная» и не создаст задание или перемещение.",
  emptyCompleteLabel = "Завершить и освободить",
  initialMovementRequired,
  initialLogisticsPlanningMode = "AUTO",
  initialLogisticsScheduledDate = null,
  initialPriority = 3,
  movementRouteAvailable = true,
  selectPriority = true,
  onOpenChange,
  onComplete,
}: RepairWorkCompletionDialogProps) {
  const queueCapabilitiesQuery = useQuery({
    queryKey: warehouseQueueCapabilitiesQueryKey(warehouseId),
    queryFn: () => getWarehouseQueueCapabilities(accessToken!, warehouseId),
    enabled: open && Boolean(accessToken && warehouseId),
  })
  const movementAvailable =
    movementRouteAvailable &&
    queueCapabilitiesQuery.data?.movementToShipmentAvailable === true
  const previewQuery = useQuery({
    queryKey: [
      "repair-work",
      "completion-preview",
      previewKey,
      lines.map((line) => [
        line.id,
        line.lineType,
        line.description,
        line.quantity,
        line.catalogSnapshot?.nodeId,
        line.customQueueBinding?.queueId,
        line.customQueueBinding?.queueName,
        line.customQueueBinding?.queueKind,
        line.lineComment,
      ]),
    ],
    queryFn: async () => {
      const snapshot = await getOperationalRepairEstimateCatalog()
      const catalog = createRepairEstimateCatalogIndex(snapshot)
      const taskPlans = buildRepairEstimateTaskPlans(lines, catalog)
      return {
        taskPlans,
        issues: validateAutoCompletion(lines, catalog),
      }
    },
    enabled: open,
  })

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!pending) onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        {error ? (
          <p role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}

        {previewQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">Подготовка планов...</p>
        ) : previewQuery.isError || !previewQuery.data ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось подготовить планы задания.
          </p>
        ) : (
          <RepairWorkCompletionForm
            key={`${movementAvailable && initialMovementRequired ? "movement" : "no-movement"}:${initialLogisticsPlanningMode}:${initialLogisticsScheduledDate ?? "auto"}:${previewQuery.data.taskPlans.map((plan) => `${plan.id}:${plan.sortOrder}`).join(":")}`}
            lines={lines}
            initialPlans={previewQuery.data.taskPlans}
            autoIssues={previewQuery.data.issues}
            pending={pending}
            allowEmpty={allowEmpty}
            emptyTitle={emptyTitle}
            emptyDescription={emptyDescription}
            completeLabel={completeLabel}
            pendingLabel={pendingLabel}
            emptyCompleteLabel={emptyCompleteLabel}
            initialMovementRequired={initialMovementRequired}
            initialLogisticsPlanningMode={initialLogisticsPlanningMode}
            initialLogisticsScheduledDate={initialLogisticsScheduledDate}
            initialPriority={initialPriority}
            movementAvailable={movementAvailable}
            selectPriority={selectPriority}
            onCancel={() => onOpenChange(false)}
            onComplete={onComplete}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function RepairWorkCompletionForm({
  lines,
  initialPlans,
  autoIssues,
  pending,
  allowEmpty,
  emptyTitle,
  emptyDescription,
  completeLabel,
  pendingLabel,
  emptyCompleteLabel,
  initialMovementRequired,
  initialLogisticsPlanningMode,
  initialLogisticsScheduledDate,
  initialPriority,
  movementAvailable,
  selectPriority,
  onCancel,
  onComplete,
}: {
  lines: RepairEstimateLineDto[]
  initialPlans: RepairEstimateTaskPlanDto[]
  autoIssues: string[]
  pending: boolean
  allowEmpty: boolean
  emptyTitle: string
  emptyDescription: string
  completeLabel: string
  pendingLabel: string
  emptyCompleteLabel: string
  initialMovementRequired?: boolean
  initialLogisticsPlanningMode: LogisticsPlanningMode
  initialLogisticsScheduledDate: string | null
  initialPriority: RepairPriority
  movementAvailable: boolean
  selectPriority: boolean
  onCancel: () => void
  onComplete: (result: RepairWorkCompletionResult) => void
}) {
  const empty = allowEmpty && lines.length === 0
  const initialMovement =
    empty || !movementAvailable ? false : (initialMovementRequired ?? true)
  const initialFixedDate =
    initialMovement &&
    initialLogisticsPlanningMode === "FIXED_DATE" &&
    Boolean(initialLogisticsScheduledDate)
  const [movementRequired, setMovementRequired] = useState(initialMovement)
  const [logisticsPlanningMode, setLogisticsPlanningMode] =
    useState<LogisticsPlanningMode>(
      initialFixedDate ? "FIXED_DATE" : "AUTO"
    )
  const [logisticsScheduledDate, setLogisticsScheduledDate] = useState(
    initialFixedDate ? initialLogisticsScheduledDate! : ""
  )
  const [movementPlans] = useState(() => [
    createRepairEstimateMovementTaskPlan("MOVE_TO_REPAIR"),
    createRepairEstimateMovementTaskPlan("MOVE_FROM_REPAIR"),
  ])
  const [plans, setPlans] = useState(() => {
    const orderedInitialPlans = initialPlans
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)
    const hasBothMovementPlans =
      orderedInitialPlans.some((plan) => plan.kind === "MOVE_TO_REPAIR") &&
      orderedInitialPlans.some((plan) => plan.kind === "MOVE_FROM_REPAIR")
    if (initialMovement && hasBothMovementPlans) return orderedInitialPlans
    return applyRepairEstimateMovementPlans({
      plans: orderedInitialPlans,
      movementRequired: initialMovement,
      movementPlans,
    })
  })
  const [preparedResult, setPreparedResult] =
    useState<RepairWorkCompletionResult | null>(null)
  const [selectedPriority, setSelectedPriority] = useState(
    String(initialPriority)
  )

  if (preparedResult) {
    const priorities: Array<{
      value: RepairPriority
      label: string
      description: string
    }> = [
      { value: 1, label: "Самый срочный", description: "Первым в очереди" },
      { value: 2, label: "Высокий", description: "Выше обычных заданий" },
      { value: 3, label: "Средний", description: "Обычный порядок" },
      { value: 4, label: "Низкий", description: "После обычных заданий" },
      {
        value: 5,
        label: "Самый неприоритетный",
        description: "В конце очереди",
      },
    ]
    return (
      <div className="flex flex-col gap-4">
        <div>
          <h3 className="font-heading text-base font-medium">
            Выберите приоритет задания
          </h3>
          <p className="text-sm text-muted-foreground">
            Приоритет определяет автоматическую позицию нового задания в
            очереди. Закреплённые задания сохранят своё положение.
          </p>
        </div>
        <ToggleGroup
          type="single"
          value={selectedPriority}
          variant="outline"
          spacing={2}
          className="grid w-full grid-cols-1 sm:grid-cols-5"
          aria-label="Приоритет задания"
          onValueChange={setSelectedPriority}
        >
          {priorities.map((item) => (
            <ToggleGroupItem
              key={item.value}
              value={String(item.value)}
              className="h-auto min-h-16 flex-col px-3 py-2"
              aria-label={`Приоритет ${item.value}: ${item.label}`}
            >
              <span className="text-base font-semibold">{item.value}</span>
              <span>{item.label}</span>
              <span className="text-[0.625rem] font-normal text-muted-foreground">
                {item.description}
              </span>
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => setPreparedResult(null)}
          >
            Назад
          </Button>
          <Button
            type="button"
            disabled={!selectedPriority || pending}
            onClick={() =>
              onComplete({
                ...preparedResult,
                priority: Number(selectedPriority) as RepairPriority,
              })
            }
          >
            {pending ? pendingLabel : completeLabel}
          </Button>
        </DialogFooter>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {empty ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>{emptyTitle}</CardTitle>
          </CardHeader>
          <CardContent className="text-muted-foreground">
            {emptyDescription}
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-4">
          {movementAvailable ? (
            <>
              <Field orientation="horizontal" data-disabled={pending}>
                <Checkbox
                  id="repair-work-movement-required"
                  aria-label="Перемещение на отгрузку"
                  checked={movementRequired}
                  disabled={pending}
                  onCheckedChange={(checked) => {
                    const required = checked === true
                    setMovementRequired(required)
                    if (!required) {
                      setLogisticsPlanningMode("AUTO")
                      setLogisticsScheduledDate("")
                    }
                    setPlans((current) =>
                      applyRepairEstimateMovementPlans({
                        plans: current,
                        movementRequired: required,
                        movementPlans,
                      })
                    )
                  }}
                />
                <div className="flex flex-col gap-1">
                  <FieldLabel htmlFor="repair-work-movement-required">
                    Перемещение на отгрузку
                  </FieldLabel>
                  <FieldDescription>
                    После завершения будет создано предусмотренное процессом
                    задание на перемещение.
                  </FieldDescription>
                </div>
              </Field>

              {movementRequired ? (
                <Field>
                  <FieldLabel>Добавление в логистику</FieldLabel>
                  <ToggleGroup
                    type="single"
                    value={logisticsPlanningMode}
                    variant="outline"
                    spacing={2}
                    className="grid w-full grid-cols-1 sm:grid-cols-2"
                    aria-label="Способ добавления логистического задания"
                    disabled={pending}
                    onValueChange={(value) => {
                      if (value !== "AUTO" && value !== "FIXED_DATE") return
                      setLogisticsPlanningMode(value)
                      if (value === "AUTO") setLogisticsScheduledDate("")
                    }}
                  >
                    <ToggleGroupItem value="AUTO">
                      Автоматически добавить в очередь
                    </ToggleGroupItem>
                    <ToggleGroupItem value="FIXED_DATE">
                      Выбрать конкретную дату
                    </ToggleGroupItem>
                  </ToggleGroup>
                  <FieldDescription>
                    Автоматический режим учитывает доступные ремонтные места,
                    приоритет и текущий порядок логистической очереди.
                  </FieldDescription>
                </Field>
              ) : null}

              {movementRequired &&
              logisticsPlanningMode === "FIXED_DATE" ? (
                <Field
                  data-invalid={!logisticsScheduledDate}
                  data-disabled={pending}
                >
                  <FieldLabel htmlFor="repair-work-logistics-date">
                    Дата логистического задания
                  </FieldLabel>
                  <Input
                    id="repair-work-logistics-date"
                    type="date"
                    value={logisticsScheduledDate}
                    disabled={pending}
                    required
                    aria-invalid={!logisticsScheduledDate}
                    onChange={(event) =>
                      setLogisticsScheduledDate(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Задание будет добавлено в конец очереди выбранной даты.
                  </FieldDescription>
                </Field>
              ) : null}
            </>
          ) : null}
        </div>
      )}

      {!empty && autoIssues.length > 0 ? (
        <div
          role="alert"
          className="flex flex-col gap-1 text-xs text-destructive"
        >
          <span>Нельзя создать задание:</span>
          <ul className="ml-4 list-disc">
            {autoIssues.slice(0, 3).map((issue) => (
              <li key={issue}>
                {issue.replace(
                  "не задан маршрут очереди",
                  "в каталоге не назначена очередь"
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          disabled={pending}
          onClick={onCancel}
        >
          Отмена
        </Button>
        <Button
          type="button"
          disabled={
            pending ||
            (!empty && autoIssues.length > 0) ||
            (movementRequired &&
              logisticsPlanningMode === "FIXED_DATE" &&
              !logisticsScheduledDate)
          }
          onClick={() => {
            const result: RepairWorkCompletionResult = {
              completionMode: empty ? "MANUAL" : "AUTO",
              movementRequired: empty ? false : movementRequired,
              logisticsPlanningMode:
                empty || !movementRequired ? "AUTO" : logisticsPlanningMode,
              logisticsScheduledDate:
                !empty &&
                movementRequired &&
                logisticsPlanningMode === "FIXED_DATE"
                  ? logisticsScheduledDate
                  : null,
              taskPlans: empty ? [] : plans,
              priority: 3,
            }
            if (empty || !selectPriority) onComplete(result)
            else setPreparedResult(result)
          }}
        >
          {pending
            ? pendingLabel
            : empty
              ? emptyCompleteLabel
              : selectPriority
                ? "Далее"
                : completeLabel}
        </Button>
      </DialogFooter>
    </div>
  )
}
