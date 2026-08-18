import { useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
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
  buildRepairEstimateTaskPlans,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
  LogisticsPlanningMode,
  RepairPriority,
} from "@/features/repair-estimates/model/repair-estimate"
import { RepairEstimateLinesSnapshot } from "@/features/repair-estimates/repair-estimate-lines-snapshot"
import { ForceCapitalRepairField } from "@/features/repair-estimates/force-capital-repair-field"
import {
  getWarehouseQueueCapabilities,
  warehouseQueueCapabilitiesQueryKey,
} from "@/features/repair-estimates/api/warehouse-queue-capabilities"

export type RepairWorkCompletionResult = {
  completionMode: RepairEstimateCompletionMode
  movementToRepair: boolean
  forceCapitalRepair: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  taskPlans: RepairEstimateTaskPlanDto[]
  priority: RepairPriority
}

const PRIORITIES: Array<{
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
  initialMovementToRepair?: boolean
  initialForceCapitalRepair?: boolean
  initialLogisticsPlanningMode?: LogisticsPlanningMode
  initialLogisticsScheduledDate?: string | null
  initialPriority?: RepairPriority
  initialCompletionMode?: RepairEstimateCompletionMode
  initialTaskPlans?: RepairEstimateTaskPlanDto[]
  movementRouteAvailable?: boolean
  logisticsSelectionAvailable?: boolean
  selectPriority?: boolean
  showForceCapitalRepair?: boolean
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
  initialMovementToRepair,
  initialForceCapitalRepair = false,
  initialLogisticsPlanningMode = "AUTO",
  initialLogisticsScheduledDate = null,
  initialPriority = 3,
  initialCompletionMode = "AUTO",
  initialTaskPlans,
  movementRouteAvailable = true,
  logisticsSelectionAvailable = true,
  selectPriority = true,
  showForceCapitalRepair = false,
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
    (queueCapabilitiesQuery.data?.movementQueueDefinitions.length ?? 0) > 0
  const previewQuery = useQuery({
    queryKey: [
      "repair-work",
      "completion-preview",
      warehouseId,
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
      initialCompletionMode,
      initialTaskPlans?.map((plan) => [plan.id, plan.sortOrder]),
    ],
    queryFn: async () => {
      if (initialTaskPlans) {
        return { taskPlans: initialTaskPlans, issues: [] }
      }
      const snapshot = await getOperationalRepairEstimateCatalog(warehouseId)
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
            key={`${movementAvailable && initialMovementToRepair ? "movement" : "no-movement"}:${initialForceCapitalRepair ? "capital" : "ordinary"}:${initialLogisticsPlanningMode}:${initialLogisticsScheduledDate ?? "auto"}:${previewQuery.data.taskPlans.map((plan) => `${plan.id}:${plan.sortOrder}`).join(":")}`}
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
            initialMovementToRepair={initialMovementToRepair}
            initialForceCapitalRepair={initialForceCapitalRepair}
            initialLogisticsPlanningMode={initialLogisticsPlanningMode}
            initialLogisticsScheduledDate={initialLogisticsScheduledDate}
            initialPriority={initialPriority}
            completionMode={initialCompletionMode}
            movementAvailable={movementAvailable}
            logisticsSelectionAvailable={logisticsSelectionAvailable}
            selectPriority={selectPriority}
            showForceCapitalRepair={showForceCapitalRepair}
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
  initialMovementToRepair: initialMovementToRepairInput,
  initialForceCapitalRepair,
  initialLogisticsPlanningMode,
  initialLogisticsScheduledDate,
  initialPriority,
  completionMode,
  movementAvailable,
  logisticsSelectionAvailable,
  selectPriority,
  showForceCapitalRepair,
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
  initialMovementToRepair?: boolean
  initialForceCapitalRepair: boolean
  initialLogisticsPlanningMode: LogisticsPlanningMode
  initialLogisticsScheduledDate: string | null
  initialPriority: RepairPriority
  completionMode: RepairEstimateCompletionMode
  movementAvailable: boolean
  logisticsSelectionAvailable: boolean
  selectPriority: boolean
  showForceCapitalRepair: boolean
  onCancel: () => void
  onComplete: (result: RepairWorkCompletionResult) => void
}) {
  const empty = allowEmpty && lines.length === 0
  const initialCapitalRepair = !empty && initialForceCapitalRepair
  const initialMovementToRepair =
    empty || (logisticsSelectionAvailable && !movementAvailable)
      ? false
      : !initialCapitalRepair && (initialMovementToRepairInput ?? false)
  const initialFixedDate =
    initialMovementToRepair &&
    initialLogisticsPlanningMode === "FIXED_DATE" &&
    Boolean(initialLogisticsScheduledDate)
  const [movementToRepair, setMovementToRepair] = useState(
    initialMovementToRepair
  )
  const [forceCapitalRepair, setForceCapitalRepair] =
    useState(initialCapitalRepair)
  const [logisticsPlanningMode, setLogisticsPlanningMode] =
    useState<LogisticsPlanningMode>(initialFixedDate ? "FIXED_DATE" : "AUTO")
  const [logisticsScheduledDate, setLogisticsScheduledDate] = useState(
    initialFixedDate ? initialLogisticsScheduledDate! : ""
  )
  const plans = initialPlans
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
  const [selectedPriority, setSelectedPriority] = useState(
    String(initialPriority)
  )

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
          <RepairWorkCompletionSummary lines={lines} plans={plans} />

          {selectPriority ? (
            <Field data-disabled={pending}>
              <FieldLabel>Приоритет ремонта</FieldLabel>
              <ToggleGroup
                type="single"
                value={selectedPriority}
                variant="outline"
                spacing={2}
                className="grid w-full grid-cols-1 sm:grid-cols-5"
                aria-label="Приоритет ремонта"
                disabled={pending}
                onValueChange={(value) => {
                  if (value) setSelectedPriority(value)
                }}
              >
                {PRIORITIES.map((item) => (
                  <ToggleGroupItem
                    key={item.value}
                    value={String(item.value)}
                    className="h-auto min-h-16 flex-col px-3 py-2"
                    aria-label={`Приоритет ${item.value}: ${item.label}`}
                  >
                    <span className="text-base font-semibold">
                      {item.value}
                    </span>
                    <span>{item.label}</span>
                    <span className="text-[0.625rem] font-normal text-muted-foreground">
                      {item.description}
                    </span>
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              <FieldDescription>
                Без перемещения он применяется к ремонтным заданиям. При
                перемещении на ремонт это же значение получает задание водителя;
                после доставки ремонтная очередь получает системный приоритет 1,
                а после ремонта автоматически создаётся задание на перемещение с
                ремонта с выбранным приоритетом.
              </FieldDescription>
            </Field>
          ) : null}

          {movementAvailable && logisticsSelectionAvailable ? (
            <>
              <Field orientation="horizontal" data-disabled={pending}>
                <Checkbox
                  id="repair-work-movement-to-repair"
                  aria-label="Создать перемещение на ремонт"
                  checked={movementToRepair}
                  disabled={pending}
                  onCheckedChange={(checked) => {
                    const required = checked === true
                    setMovementToRepair(required)
                    if (required) setForceCapitalRepair(false)
                    if (!required) {
                      setLogisticsPlanningMode("AUTO")
                      setLogisticsScheduledDate("")
                    }
                  }}
                />
                <div className="flex flex-col gap-1">
                  <FieldLabel htmlFor="repair-work-movement-to-repair">
                    Создать перемещение на ремонт
                  </FieldLabel>
                  <FieldDescription>
                    До завершения доставки бытовка не появится в очередях
                    ремонтных работ. После завершения ремонта задание на
                    перемещение с ремонта создастся автоматически.
                  </FieldDescription>
                </div>
              </Field>

              {showForceCapitalRepair ? (
                <ForceCapitalRepairField
                  id="repair-work-force-capital-repair"
                  checked={forceCapitalRepair}
                  disabled={pending}
                  onCheckedChange={(checked) => {
                    setForceCapitalRepair(checked)
                    if (!checked) return
                    setMovementToRepair(false)
                    setLogisticsPlanningMode("AUTO")
                    setLogisticsScheduledDate("")
                  }}
                />
              ) : null}

              {movementToRepair ? (
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

              {movementToRepair && logisticsPlanningMode === "FIXED_DATE" ? (
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
          {!movementAvailable && showForceCapitalRepair ? (
            <ForceCapitalRepairField
              id="repair-work-force-capital-repair"
              checked={forceCapitalRepair}
              disabled={pending}
              onCheckedChange={setForceCapitalRepair}
            />
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
            (movementToRepair &&
              logisticsPlanningMode === "FIXED_DATE" &&
              !logisticsScheduledDate)
          }
          onClick={() => {
            const result: RepairWorkCompletionResult = {
              completionMode: empty ? "MANUAL" : completionMode,
              movementToRepair: empty ? false : movementToRepair,
              forceCapitalRepair: empty ? false : forceCapitalRepair,
              logisticsPlanningMode:
                empty || !movementToRepair ? "AUTO" : logisticsPlanningMode,
              logisticsScheduledDate:
                !empty &&
                movementToRepair &&
                logisticsPlanningMode === "FIXED_DATE"
                  ? logisticsScheduledDate
                  : null,
              taskPlans: empty ? [] : plans,
              priority: Number(selectedPriority) as RepairPriority,
            }
            onComplete(result)
          }}
        >
          {pending ? pendingLabel : empty ? emptyCompleteLabel : completeLabel}
        </Button>
      </DialogFooter>
    </div>
  )
}

function RepairWorkCompletionSummary({
  lines,
  plans,
}: {
  lines: RepairEstimateLineDto[]
  plans: RepairEstimateTaskPlanDto[]
}) {
  const lineById = new Map(lines.map((line) => [line.id, line]))
  const repairPlans = plans
  const workCount = lines.filter((line) => line.lineType === "WORK").length
  const materialCount = lines.filter(
    (line) => line.lineType === "MATERIAL"
  ).length

  return (
    <section
      className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4"
      aria-label="Проверка состава сметы"
    >
      <div>
        <h3 className="font-heading text-base font-medium">Итоги сметы</h3>
        <p className="text-sm text-muted-foreground">
          Проверьте все работы, материалы и сформированные задания перед
          завершением.
        </p>
      </div>

      <dl className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-md border bg-background p-3">
          <dt className="text-xs text-muted-foreground">Работы</dt>
          <dd className="mt-1 text-lg font-semibold">{workCount}</dd>
        </div>
        <div className="rounded-md border bg-background p-3">
          <dt className="text-xs text-muted-foreground">Материалы</dt>
          <dd className="mt-1 text-lg font-semibold">{materialCount}</dd>
        </div>
        <div className="rounded-md border bg-background p-3">
          <dt className="text-xs text-muted-foreground">Ремонтные задания</dt>
          <dd className="mt-1 text-lg font-semibold">{repairPlans.length}</dd>
        </div>
      </dl>

      <section className="flex flex-col gap-2" aria-label="Ремонтные задания">
        <div>
          <h4 className="text-sm font-medium">Ремонтные задания</h4>
          <p className="text-xs text-muted-foreground">
            Очереди определены общим каталогом и будут созданы автоматически.
          </p>
        </div>
        {repairPlans.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Ремонтных заданий нет.
          </p>
        ) : (
          repairPlans.map((plan, index) => {
            const planLines = plan.includedLineIds
              .map((lineId) => lineById.get(lineId))
              .filter((line): line is RepairEstimateLineDto => Boolean(line))
            return (
              <Card key={plan.id} size="sm">
                <CardHeader>
                  <CardTitle className="flex flex-wrap items-center gap-2 text-sm">
                    <span>Задание {index + 1}</span>
                    <Badge variant="secondary">
                      {plan.queueName ?? "Очередь не назначена"}
                    </Badge>
                  </CardTitle>
                </CardHeader>
                <CardContent className="flex flex-wrap gap-1.5">
                  {planLines.map((line) => (
                    <Badge key={line.id} variant="outline">
                      {line.lineType === "WORK" ? "Работа" : "Материал"}:{" "}
                      {line.description || "Без названия"}
                    </Badge>
                  ))}
                </CardContent>
              </Card>
            )
          })
        )}
      </section>

      <section
        className="flex flex-col gap-2"
        aria-label="Состав работ и материалов"
      >
        <h4 className="text-sm font-medium">Работы и материалы</h4>
        <RepairEstimateLinesSnapshot lines={lines} />
      </section>
    </section>
  )
}
