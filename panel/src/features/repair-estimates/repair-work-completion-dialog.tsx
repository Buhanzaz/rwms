import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowUp01Icon,
  Copy01Icon,
  Delete02Icon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
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
} from "@/features/repair-estimates/model/repair-estimate"

export type RepairWorkCompletionResult = {
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  taskPlans: RepairEstimateTaskPlanDto[]
}

type RepairWorkCompletionDialogProps = {
  open: boolean
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
  reconcileInitialPlans?: (
    preparedPlans: RepairEstimateTaskPlanDto[]
  ) => RepairEstimateTaskPlanDto[]
  onOpenChange: (open: boolean) => void
  onComplete: (result: RepairWorkCompletionResult) => void
}

function routeQueueKindLabel(
  value: NonNullable<RepairEstimateTaskPlanDto["routeQueueKind"]>
) {
  if (value === "REPAIR") return "Ремонт"
  if (value === "MOVEMENT") return "Перемещение"
  return "Удержание"
}

function taskPlanTitle(
  plan: RepairEstimateTaskPlanDto,
  workTitle: string | undefined
) {
  if (plan.kind === "MOVE_TO_REPAIR") return "Перемещение на ремонт"
  if (plan.kind === "MOVE_FROM_REPAIR") return "Перемещение с ремонта"
  return workTitle || "Работы"
}

export function RepairWorkCompletionDialog({
  open,
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
  initialCompletionMode,
  initialMovementRequired,
  reconcileInitialPlans,
  onOpenChange,
  onComplete,
}: RepairWorkCompletionDialogProps) {
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
        line.lineComment,
      ]),
    ],
    queryFn: async () => {
      const snapshot = await getOperationalRepairEstimateCatalog()
      const catalog = createRepairEstimateCatalogIndex(snapshot)
      const taskPlans = buildRepairEstimateTaskPlans(lines, catalog)
      return {
        taskPlans: reconcileInitialPlans
          ? reconcileInitialPlans(taskPlans)
          : taskPlans,
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
            key={`${initialCompletionMode ?? "MANUAL"}:${initialMovementRequired ? "movement" : "no-movement"}:${previewQuery.data.taskPlans.map((plan) => `${plan.id}:${plan.sortOrder}`).join(":")}`}
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
            initialCompletionMode={initialCompletionMode}
            initialMovementRequired={initialMovementRequired}
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
  initialCompletionMode,
  initialMovementRequired,
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
  initialCompletionMode?: RepairEstimateCompletionMode
  initialMovementRequired?: boolean
  onCancel: () => void
  onComplete: (result: RepairWorkCompletionResult) => void
}) {
  const [completionMode, setCompletionMode] =
    useState<RepairEstimateCompletionMode>(initialCompletionMode ?? "MANUAL")
  const empty = allowEmpty && lines.length === 0
  const initialMovement = empty ? false : (initialMovementRequired ?? true)
  const [movementRequired, setMovementRequired] = useState(initialMovement)
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
  const lineById = new Map(lines.map((line) => [line.id, line]))

  function updatePlan(
    planId: string,
    update: (plan: RepairEstimateTaskPlanDto) => RepairEstimateTaskPlanDto
  ) {
    setPlans((current) =>
      current.map((plan) => (plan.id === planId ? update(plan) : plan))
    )
  }

  function movePlan(index: number, delta: number) {
    setPlans((current) => {
      const target = index + delta
      if (target < 0 || target >= current.length) return current
      const next = [...current]
      const [plan] = next.splice(index, 1)
      next.splice(target, 0, plan)
      return next
    })
  }

  function duplicatePlan(plan: RepairEstimateTaskPlanDto, index: number) {
    const suffix =
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`
    const duplicate: RepairEstimateTaskPlanDto = {
      ...plan,
      id: `task-plan-${suffix}`,
      includedLineIds: [...plan.includedLineIds],
      generationStatus: "PENDING_GENERATION",
      workflowRequestRef: null,
    }
    setPlans((current) => [
      ...current.slice(0, index + 1),
      duplicate,
      ...current.slice(index + 1),
    ])
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
        <div className="grid gap-4 sm:grid-cols-2">
          <Field data-disabled={pending}>
            <FieldLabel htmlFor="repair-work-completion-mode">Режим</FieldLabel>
            <Select
              disabled={pending}
              value={completionMode}
              onValueChange={(value) =>
                setCompletionMode(value as RepairEstimateCompletionMode)
              }
            >
              <SelectTrigger
                id="repair-work-completion-mode"
                aria-label="Режим завершения"
                className="w-full"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value="MANUAL">Выбрать вручную</SelectItem>
                  <SelectItem value="AUTO">
                    Автоматически распределить по очередям
                  </SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>

          <Field orientation="horizontal" data-disabled={pending}>
            <Checkbox
              id="repair-work-movement-required"
              aria-label="Создать перемещение на ремонт и возврат"
              checked={movementRequired}
              disabled={pending}
              onCheckedChange={(checked) => {
                const required = checked === true
                setMovementRequired(required)
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
                Создать перемещение на ремонт и возврат
              </FieldLabel>
              <FieldDescription>
                Отметьте, если бытовку нужно отправить на ремонт и вернуть после
                завершения.
              </FieldDescription>
            </div>
          </Field>
        </div>
      )}

      {completionMode === "AUTO" && autoIssues.length > 0 ? (
        <div
          role="alert"
          className="flex flex-col gap-1 text-xs text-destructive"
        >
          <span>Автоматическое распределение недоступно:</span>
          <ul className="ml-4 list-disc">
            {autoIssues.slice(0, 3).map((issue) => (
              <li key={issue}>{issue}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {!empty && completionMode === "MANUAL" ? (
        <section className="flex flex-col gap-3">
          <div>
            <h3 className="font-heading text-sm font-medium">Планы задач</h3>
            <p className="text-xs text-muted-foreground">
              Порядок и маршрут сохраняются вместе с заданием. Планы без
              маршрута останутся в ожидании генерации.
            </p>
          </div>

          {plans.length === 0 ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>Планов задач нет</CardTitle>
              </CardHeader>
              <CardContent className="text-muted-foreground">
                Строки без рабочего плана попадут в отдельное подзадание без
                маршрута.
              </CardContent>
            </Card>
          ) : (
            plans.map((plan, index) => {
              const includedLines = plan.includedLineIds
                .map((lineId) => lineById.get(lineId))
                .filter(Boolean)
              const explicitQueueValue = plan.queueCode
                ? `QUEUE_CODE:${plan.queueCode}`
                : null
              const routeSelection =
                explicitQueueValue ?? plan.routeQueueKind ?? "NONE"
              return (
                <Card key={plan.id} size="sm">
                  <CardHeader>
                    <CardTitle className="flex flex-wrap items-center justify-between gap-2">
                      <span>
                        {index + 1}.{" "}
                        {taskPlanTitle(
                          plan,
                          plan.primaryLineId
                            ? lineById.get(plan.primaryLineId)?.description
                            : undefined
                        )}
                      </span>
                      <Badge variant="secondary">
                        {plan.queueCode
                          ? plan.queueCode
                          : plan.routeQueueKind
                            ? routeQueueKindLabel(plan.routeQueueKind)
                            : "Маршрут не задан"}
                      </Badge>
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="flex flex-col gap-3">
                    <p className="text-xs text-muted-foreground">
                      {includedLines
                        .map((line) => line?.description)
                        .filter(Boolean)
                        .join("; ")}
                    </p>

                    <div className="grid gap-3 md:grid-cols-[14rem_minmax(0,1fr)_auto] md:items-end">
                      <Field className="md:self-start" data-disabled={pending}>
                        <FieldLabel htmlFor={`plan-route-${plan.id}`}>
                          Маршрут очереди
                        </FieldLabel>
                        <Select
                          disabled={pending}
                          value={routeSelection}
                          onValueChange={(value) =>
                            updatePlan(plan.id, (current) => {
                              if (value.startsWith("QUEUE_CODE:")) {
                                return current
                              }
                              return {
                                ...current,
                                queueCode: null,
                                routeQueueKind:
                                  value === "NONE"
                                    ? null
                                    : (value as NonNullable<
                                        RepairEstimateTaskPlanDto["routeQueueKind"]
                                      >),
                              }
                            })
                          }
                        >
                          <SelectTrigger
                            id={`plan-route-${plan.id}`}
                            aria-label={`Маршрут очереди плана ${index + 1}`}
                            className="w-full"
                          >
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectGroup>
                              {explicitQueueValue ? (
                                <SelectItem value={explicitQueueValue}>
                                  Очередь {plan.queueCode} (из каталога)
                                </SelectItem>
                              ) : null}
                              <SelectItem value="NONE">Не задан</SelectItem>
                              <SelectItem value="REPAIR">Ремонт</SelectItem>
                              <SelectItem value="MOVEMENT">
                                Перемещение
                              </SelectItem>
                              <SelectItem value="HOLDING">Удержание</SelectItem>
                            </SelectGroup>
                          </SelectContent>
                        </Select>
                      </Field>

                      <Field data-disabled={pending}>
                        <FieldLabel htmlFor={`plan-comment-${plan.id}`}>
                          Комментарий группы
                        </FieldLabel>
                        <Textarea
                          id={`plan-comment-${plan.id}`}
                          aria-label={`Комментарий группы плана ${index + 1}`}
                          disabled={pending}
                          value={plan.groupComment}
                          onChange={(event) =>
                            updatePlan(plan.id, (current) => ({
                              ...current,
                              groupComment: event.target.value,
                            }))
                          }
                        />
                      </Field>

                      <div className="flex flex-wrap gap-1">
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="outline"
                          aria-label="Переместить план выше"
                          disabled={pending || index === 0}
                          onClick={() => movePlan(index, -1)}
                        >
                          <HugeiconsIcon icon={ArrowUp01Icon} />
                        </Button>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="outline"
                          aria-label="Переместить план ниже"
                          disabled={pending || index === plans.length - 1}
                          onClick={() => movePlan(index, 1)}
                        >
                          <HugeiconsIcon icon={ArrowDown01Icon} />
                        </Button>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="outline"
                          aria-label="Дублировать план"
                          disabled={pending || plan.kind !== "REPAIR_WORK"}
                          onClick={() => duplicatePlan(plan, index)}
                        >
                          <HugeiconsIcon icon={Copy01Icon} />
                        </Button>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          aria-label="Удалить план"
                          disabled={pending || plan.kind !== "REPAIR_WORK"}
                          onClick={() =>
                            setPlans((current) =>
                              current.filter((item) => item.id !== plan.id)
                            )
                          }
                        >
                          <HugeiconsIcon icon={Delete02Icon} />
                        </Button>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              )
            })
          )}
        </section>
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
            pending || (completionMode === "AUTO" && autoIssues.length > 0)
          }
          onClick={() =>
            onComplete({
              completionMode: empty ? "MANUAL" : completionMode,
              movementRequired: empty ? false : movementRequired,
              taskPlans: empty ? [] : plans,
            })
          }
        >
          {pending ? pendingLabel : empty ? emptyCompleteLabel : completeLabel}
        </Button>
      </DialogFooter>
    </div>
  )
}
