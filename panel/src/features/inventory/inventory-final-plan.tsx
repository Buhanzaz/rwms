import { useState, type CSSProperties } from "react"
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import {
  ArrowDown01Icon,
  ArrowUp01Icon,
  DragDropVerticalIcon,
  FloppyDiskIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"
import { ForceCapitalRepairField } from "@/features/repair-estimates/force-capital-repair-field"
import type {
  InventoryCollisionCandidate,
  InventoryFinalPlan,
  InventoryFinalPlanEntry,
  InventoryReconciliationDecision,
  InventoryScheduleMode,
  UpdateInventoryFinalPlanRequest,
} from "@/features/inventory/model/inventory-service"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import { cn } from "@/lib/utils"

type EditableReconciliationStrategy = "REPLACE" | "MERGE"

type DraftEntry = InventoryFinalPlanEntry & {
  decisionStrategy: EditableReconciliationStrategy | null
  selectedCandidateValue: string
  mergeConfirmed: boolean
}

type InventoryFinalPlanProps = {
  plan: InventoryFinalPlan
  findings: InventoryFindingDto[]
  pending: boolean
  error: string | null
  onDirtyChange: (dirty: boolean) => void
  onOpenFinding: (findingId: string) => void
  onOpenCandidate: (candidate: InventoryCollisionCandidate) => void
  onSave: (request: UpdateInventoryFinalPlanRequest) => void
}

const PRIORITIES = [1, 2, 3, 4, 5] as const

function candidateValue(candidate: InventoryCollisionCandidate) {
  return `${candidate.targetKind}:${candidate.targetId}`
}

function toDraftEntry(entry: InventoryFinalPlanEntry): DraftEntry {
  const decision = entry.reconciliationDecision
  return {
    ...entry,
    decisionStrategy:
      decision?.strategy === "REPLACE" || decision?.strategy === "MERGE"
        ? decision.strategy
        : null,
    selectedCandidateValue:
      decision?.selectedTargetKind && decision.selectedTargetId
        ? `${decision.selectedTargetKind}:${decision.selectedTargetId}`
        : "",
    mergeConfirmed: false,
  }
}

function formatDate(value: string | null) {
  if (!value) return "Не требуется"
  const [year, month, day] = value.split("-")
  return `${day}.${month}.${year}`
}

function formatCandidateTotal(value: number) {
  return formatMoneyDecimal((value / 100).toFixed(2))
}

function targetKindLabel(kind: "ESTIMATE" | "REPAIR" | null) {
  if (kind === "ESTIMATE") return "Будет создана смета"
  if (kind === "REPAIR") return "Будет создан ремонт"
  return "Внешняя задача не создаётся"
}

function decisionFor(
  entry: DraftEntry
): InventoryReconciliationDecision | null {
  const activeCandidates = entry.collisionCandidates.filter(
    (candidate) => candidate.active
  )
  if (activeCandidates.length === 0) {
    return entry.hasWork
      ? {
          strategy: "CREATE",
          selectedTargetKind: null,
          selectedTargetId: null,
        }
      : null
  }
  if (!entry.decisionStrategy || !entry.selectedCandidateValue) return null
  const selected = activeCandidates.find(
    (candidate) => candidateValue(candidate) === entry.selectedCandidateValue
  )
  if (!selected) return null
  return {
    strategy: entry.decisionStrategy,
    selectedTargetKind: selected.targetKind,
    selectedTargetId: selected.targetId,
  }
}

function entryValidationError(
  entry: DraftEntry,
  movementScheduleMode: InventoryScheduleMode,
  repairScheduleMode: InventoryScheduleMode
) {
  if (!entry.hasWork) return null
  if (entry.priority === null) return "Выберите приоритет."
  const activeCandidates = entry.collisionCandidates.filter(
    (candidate) => candidate.active
  )
  if (activeCandidates.length > 1) {
    return "Найдено несколько активных смет или ремонтов этой бытовки. Выбор только одной записи оставит дубликат, поэтому сначала устраните лишние активные записи в ремонтном контуре."
  }
  const selectedCandidate = activeCandidates.find(
    (candidate) => candidateValue(candidate) === entry.selectedCandidateValue
  )
  if (selectedCandidate?.started && entry.decisionStrategy !== "MERGE") {
    return "Начатую работу нельзя заменить. Выберите слияние: система вычтет уже выполняемые работы и материалы и поставит оставшийся объём следом."
  }
  if (activeCandidates.length > 0 && !decisionFor(entry)) {
    return "Выберите существующую запись и способ сверки: заменить или слить вручную."
  }
  if (
    entry.decisionStrategy === "MERGE" &&
    selectedCandidate &&
    !selectedCandidate.started &&
    !entry.mergeConfirmed
  ) {
    return "Подтвердите, что итоговый состав вручную объединён со старой записью."
  }
  if (
    movementScheduleMode === "MANUAL" &&
    entry.movementToRepair &&
    !entry.movementScheduledDate
  ) {
    return "Укажите закреплённую дату перемещения."
  }
  if (repairScheduleMode === "MANUAL" && !entry.repairScheduledDate) {
    return "Укажите закреплённую дату ремонта."
  }
  if (
    entry.movementToRepair &&
    entry.movementScheduledDate &&
    entry.repairScheduledDate &&
    entry.repairScheduledDate < entry.movementScheduledDate
  ) {
    return "Ремонт не может быть запланирован раньше доставки в ремонт."
  }
  return null
}

function CandidateSummary({
  candidate,
  onOpen,
}: {
  candidate: InventoryCollisionCandidate
  onOpen: () => void
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>
          {candidate.targetKind === "ESTIMATE" ? "Смета" : "Ремонт"} ·{" "}
          {candidate.state}
        </CardTitle>
        <CardDescription>
          {candidate.sourceParty || "Источник не указан"} · версия{" "}
          {candidate.version}
        </CardDescription>
        <CardAction className="flex flex-wrap gap-1">
          {candidate.active ? (
            <Badge>Активна</Badge>
          ) : (
            <Badge variant="outline">История</Badge>
          )}
          {candidate.started ? (
            <Badge variant="destructive">Работа начата</Badge>
          ) : null}
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        {candidate.priority ? (
          <Badge variant="secondary">Приоритет {candidate.priority}</Badge>
        ) : null}
        {candidate.forceCapitalRepair ? (
          <Badge variant="destructive">Капитальный ремонт</Badge>
        ) : null}
        <Badge variant="secondary">
          Работ: {candidate.planSummary.workLineCount}
        </Badge>
        <Badge variant="secondary">
          Материалов: {candidate.planSummary.materialLineCount}
        </Badge>
        <Badge variant="outline">
          {formatCandidateTotal(candidate.planSummary.grandTotalMinor)} ₽
        </Badge>
      </CardContent>
      <CardFooter className="justify-end border-t">
        <Button type="button" variant="outline" onClick={onOpen}>
          Открыть {candidate.targetKind === "ESTIMATE" ? "смету" : "ремонт"}
        </Button>
      </CardFooter>
    </Card>
  )
}

function SortablePlanEntry({
  entry,
  index,
  total,
  finding,
  movementScheduleMode,
  repairScheduleMode,
  pending,
  onChange,
  onMove,
  onOpenFinding,
  onOpenCandidate,
}: {
  entry: DraftEntry
  index: number
  total: number
  finding: InventoryFindingDto | undefined
  movementScheduleMode: InventoryScheduleMode
  repairScheduleMode: InventoryScheduleMode
  pending: boolean
  onChange: (change: Partial<DraftEntry>) => void
  onMove: (direction: -1 | 1) => void
  onOpenFinding: () => void
  onOpenCandidate: (candidate: InventoryCollisionCandidate) => void
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: entry.findingId, disabled: pending })
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
  }
  const validationError = entryValidationError(
    entry,
    movementScheduleMode,
    repairScheduleMode
  )
  const activeCandidates = entry.collisionCandidates.filter(
    (candidate) => candidate.active
  )
  const selectedCandidate = activeCandidates.find(
    (candidate) => candidateValue(candidate) === entry.selectedCandidateValue
  )
  const cabinNumber =
    finding?.cabinNumber ?? finding?.canonicalNumber ?? entry.findingId

  return (
    <Card
      ref={setNodeRef}
      style={style}
      size="sm"
      className={cn(isDragging && "opacity-70")}
      aria-label={`Позиция ${index + 1}, бытовка ${cabinNumber}`}
    >
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="cursor-grab touch-none text-muted-foreground active:cursor-grabbing"
            aria-label={`Перетащить бытовку ${cabinNumber}`}
            disabled={pending}
            {...attributes}
            {...listeners}
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </button>
          <Badge variant="outline">#{index + 1}</Badge>
          <span>{cabinNumber}</span>
        </CardTitle>
        <CardDescription>
          {entry.hasWork
            ? targetKindLabel(entry.targetKind)
            : "Осмотр без замечаний — запись остаётся только в истории инвентаризации."}
        </CardDescription>
        <CardAction className="flex gap-1">
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            disabled={pending || index === 0}
            aria-label={`Поднять бытовку ${cabinNumber}`}
            onClick={() => onMove(-1)}
          >
            <HugeiconsIcon icon={ArrowUp01Icon} />
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            disabled={pending || index === total - 1}
            aria-label={`Опустить бытовку ${cabinNumber}`}
            onClick={() => onMove(1)}
          >
            <HugeiconsIcon icon={ArrowDown01Icon} />
          </Button>
        </CardAction>
      </CardHeader>

      <CardContent className="flex flex-col gap-6">
        <div className="flex flex-wrap gap-2">
          <Badge variant={entry.hasWork ? "secondary" : "outline"}>
            {entry.hasWork ? "Есть замечания" : "Без замечаний"}
          </Badge>
          {entry.targetKind === "ESTIMATE" ? (
            <Badge>После аренды: сначала смета</Badge>
          ) : null}
          {finding?.currentSnapshot?.status ? (
            <Badge variant="outline">
              Статус: {finding.currentSnapshot.status}
            </Badge>
          ) : null}
        </div>

        {entry.hasWork ? (
          <FieldGroup>
            <Field>
              <FieldLabel
                htmlFor={`inventory-plan-priority-${entry.findingId}`}
              >
                Приоритет
              </FieldLabel>
              <Select
                value={entry.priority === null ? "" : String(entry.priority)}
                disabled={pending}
                onValueChange={(value) =>
                  onChange({
                    priority: Number(value) as DraftEntry["priority"],
                  })
                }
              >
                <SelectTrigger
                  id={`inventory-plan-priority-${entry.findingId}`}
                  className="w-full max-w-xs"
                >
                  <SelectValue placeholder="Выберите приоритет" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {PRIORITIES.map((priority) => (
                      <SelectItem key={priority} value={String(priority)}>
                        {priority}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <FieldDescription>
                При одинаковой дате меньший номер приоритета идёт раньше.
              </FieldDescription>
            </Field>

            <Field orientation="horizontal">
              <Checkbox
                id={`inventory-plan-movement-${entry.findingId}`}
                checked={entry.movementToRepair}
                disabled={pending || entry.forceCapitalRepair}
                onCheckedChange={(value) =>
                  onChange({
                    movementToRepair: value === true,
                    movementScheduledDate:
                      value === true ? entry.movementScheduledDate : null,
                  })
                }
              />
              <FieldLabel
                htmlFor={`inventory-plan-movement-${entry.findingId}`}
                className="font-normal"
              >
                Нужна доставка бытовки в ремонт
              </FieldLabel>
            </Field>

            <ForceCapitalRepairField
              id={`inventory-plan-force-capital-${entry.findingId}`}
              checked={entry.forceCapitalRepair}
              disabled
            />

            {movementScheduleMode === "MANUAL" && entry.movementToRepair ? (
              <Field data-invalid={!entry.movementScheduledDate || undefined}>
                <FieldLabel
                  htmlFor={`inventory-plan-movement-date-${entry.findingId}`}
                >
                  Закреплённая дата перемещения
                </FieldLabel>
                <Input
                  id={`inventory-plan-movement-date-${entry.findingId}`}
                  type="date"
                  value={entry.movementScheduledDate ?? ""}
                  disabled={pending}
                  aria-invalid={!entry.movementScheduledDate}
                  onChange={(event) =>
                    onChange({
                      movementScheduledDate: event.target.value || null,
                    })
                  }
                />
              </Field>
            ) : (
              <p className="text-sm text-muted-foreground">
                Предварительная дата перемещения:{" "}
                {formatDate(entry.movementScheduledDate)}
              </p>
            )}

            {repairScheduleMode === "MANUAL" ? (
              <Field data-invalid={!entry.repairScheduledDate || undefined}>
                <FieldLabel
                  htmlFor={`inventory-plan-repair-date-${entry.findingId}`}
                >
                  Закреплённая дата ремонта
                </FieldLabel>
                <Input
                  id={`inventory-plan-repair-date-${entry.findingId}`}
                  type="date"
                  value={entry.repairScheduledDate ?? ""}
                  disabled={pending}
                  aria-invalid={!entry.repairScheduledDate}
                  onChange={(event) =>
                    onChange({
                      repairScheduledDate: event.target.value || null,
                    })
                  }
                />
              </Field>
            ) : (
              <p className="text-sm text-muted-foreground">
                Предварительная дата ремонта:{" "}
                {formatDate(entry.repairScheduledDate)}
              </p>
            )}
          </FieldGroup>
        ) : null}

        {activeCandidates.length > 0 ? (
          <FieldSet>
            <FieldLegend variant="label">
              Сверка существующих заданий
            </FieldLegend>
            <FieldDescription>
              Для незапущенной записи доступны замена и ручное слияние. Если
              работа уже выполняется, выберите слияние: она продолжится, а
              система поставит следом только новые работы и материалы из
              инвентаризации.
            </FieldDescription>
            <div className="flex flex-col gap-3">
              {activeCandidates.map((candidate) => (
                <CandidateSummary
                  key={candidateValue(candidate)}
                  candidate={candidate}
                  onOpen={() => onOpenCandidate(candidate)}
                />
              ))}
            </div>
            <FieldGroup>
              <Field>
                <FieldLabel
                  htmlFor={`inventory-plan-candidate-${entry.findingId}`}
                >
                  Существующая запись
                </FieldLabel>
                <Select
                  value={entry.selectedCandidateValue}
                  disabled={pending}
                  onValueChange={(value) =>
                    onChange({ selectedCandidateValue: value })
                  }
                >
                  <SelectTrigger
                    id={`inventory-plan-candidate-${entry.findingId}`}
                    className="w-full"
                  >
                    <SelectValue placeholder="Выберите запись" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {activeCandidates.map((candidate) => (
                        <SelectItem
                          key={candidateValue(candidate)}
                          value={candidateValue(candidate)}
                        >
                          {candidate.targetKind === "ESTIMATE"
                            ? "Смета"
                            : "Ремонт"}
                          {` · ${candidate.state} · версия ${candidate.version}`}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel id={`inventory-plan-strategy-${entry.findingId}`}>
                  Способ сверки
                </FieldLabel>
                <ToggleGroup
                  type="single"
                  variant="outline"
                  value={entry.decisionStrategy ?? ""}
                  disabled={pending}
                  aria-labelledby={`inventory-plan-strategy-${entry.findingId}`}
                  onValueChange={(value) =>
                    onChange({
                      decisionStrategy:
                        value === "REPLACE" || value === "MERGE" ? value : null,
                      mergeConfirmed: false,
                    })
                  }
                >
                  <ToggleGroupItem
                    value="REPLACE"
                    disabled={selectedCandidate?.started}
                  >
                    Заменить
                  </ToggleGroupItem>
                  <ToggleGroupItem value="MERGE">
                    {selectedCandidate?.started
                      ? "Слить остаток"
                      : "Слить вручную"}
                  </ToggleGroupItem>
                </ToggleGroup>
              </Field>
              {entry.decisionStrategy === "MERGE" &&
              selectedCandidate?.started ? (
                <Alert>
                  <AlertTitle>Текущая работа сохранится</AlertTitle>
                  <AlertDescription>
                    Совпадающие работы и материалы останутся в выполняемом
                    задании. Новое задание получит только остаток и будет
                    поставлено после текущего. Если остаток пуст, новое задание
                    не создаётся.
                  </AlertDescription>
                </Alert>
              ) : null}
              {entry.decisionStrategy === "MERGE" &&
              selectedCandidate &&
              !selectedCandidate.started ? (
                <Field orientation="horizontal">
                  <Checkbox
                    id={`inventory-plan-merge-confirmed-${entry.findingId}`}
                    checked={entry.mergeConfirmed}
                    disabled={pending}
                    onCheckedChange={(value) =>
                      onChange({ mergeConfirmed: value === true })
                    }
                  />
                  <FieldLabel
                    htmlFor={`inventory-plan-merge-confirmed-${entry.findingId}`}
                    className="font-normal"
                  >
                    Я открыл старую запись и вручную включил нужные работы и
                    материалы в итоговый состав осмотра
                  </FieldLabel>
                </Field>
              ) : null}
            </FieldGroup>
          </FieldSet>
        ) : null}

        {validationError ? (
          <Alert variant="destructive">
            <AlertTitle>Нужно решение менеджера</AlertTitle>
            <AlertDescription>{validationError}</AlertDescription>
          </Alert>
        ) : null}
      </CardContent>

      <CardFooter className="justify-end border-t">
        <Button type="button" variant="outline" onClick={onOpenFinding}>
          Открыть результаты осмотра
        </Button>
      </CardFooter>
    </Card>
  )
}

export function InventoryFinalPlanEditor({
  plan,
  findings,
  pending,
  error,
  onDirtyChange,
  onOpenFinding,
  onOpenCandidate,
  onSave,
}: InventoryFinalPlanProps) {
  const [movementScheduleMode, setMovementScheduleMode] =
    useState<InventoryScheduleMode>(plan.movementScheduleMode)
  const [repairScheduleMode, setRepairScheduleMode] =
    useState<InventoryScheduleMode>(plan.repairScheduleMode)
  const [entries, setEntries] = useState<DraftEntry[]>(() =>
    [...plan.entries]
      .sort((left, right) => left.order - right.order)
      .map(toDraftEntry)
  )
  const [submitted, setSubmitted] = useState(false)
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]))
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )

  function markDirty() {
    setSubmitted(false)
    onDirtyChange(true)
  }

  function updateEntry(findingId: string, change: Partial<DraftEntry>) {
    setEntries((current) =>
      current.map((entry) =>
        entry.findingId === findingId ? { ...entry, ...change } : entry
      )
    )
    markDirty()
  }

  function moveEntry(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= entries.length) return
    setEntries((current) => arrayMove(current, index, target))
    markDirty()
  }

  function handleDragEnd(event: DragEndEvent) {
    if (!event.over || event.active.id === event.over.id) return
    const oldIndex = entries.findIndex(
      (entry) => entry.findingId === event.active.id
    )
    const newIndex = entries.findIndex(
      (entry) => entry.findingId === event.over?.id
    )
    if (oldIndex < 0 || newIndex < 0) return
    setEntries((current) => arrayMove(current, oldIndex, newIndex))
    markDirty()
  }

  const validationErrors = entries
    .map((entry) =>
      entryValidationError(entry, movementScheduleMode, repairScheduleMode)
    )
    .filter((value) => value !== null)

  function submit() {
    setSubmitted(true)
    if (validationErrors.length > 0) return
    onSave({
      expectedSessionRevision: plan.sessionRevision,
      expectedFinalPlanVersion: plan.finalPlanVersion,
      movementScheduleMode,
      repairScheduleMode,
      entries: entries.map((entry, order) => ({
        findingId: entry.findingId,
        expectedFindingRevision: entry.findingRevision,
        order,
        priority: entry.priority,
        movementToRepair: entry.hasWork && entry.movementToRepair,
        movementScheduledDate:
          entry.hasWork &&
          entry.movementToRepair &&
          movementScheduleMode === "MANUAL"
            ? entry.movementScheduledDate
            : null,
        repairScheduledDate:
          entry.hasWork && repairScheduleMode === "MANUAL"
            ? entry.repairScheduledDate
            : null,
        reconciliationDecision: decisionFor(entry),
      })),
    })
  }

  return (
    <section
      className="flex flex-col gap-4"
      aria-labelledby="inventory-final-plan-title"
    >
      <Card>
        <CardHeader>
          <CardTitle id="inventory-final-plan-title">
            Итоговый план и сверка заданий
          </CardTitle>
          <CardDescription>
            Здесь показаны все бытовки, которые сейчас входят в инвентаризацию.
            Измените общий порядок, приоритеты и предварительные даты. Ничего не
            попадёт в рабочие доски до окончательного завершения.
          </CardDescription>
          <CardAction>
            <Badge variant="outline">План v{plan.finalPlanVersion}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <Field>
              <FieldLabel id="inventory-movement-schedule-mode">
                Даты перемещений
              </FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                value={movementScheduleMode}
                disabled={pending}
                aria-labelledby="inventory-movement-schedule-mode"
                onValueChange={(value) => {
                  if (value !== "AUTO" && value !== "MANUAL") return
                  setMovementScheduleMode(value)
                  markDirty()
                }}
              >
                <ToggleGroupItem value="AUTO">
                  Распределить автоматически
                </ToggleGroupItem>
                <ToggleGroupItem value="MANUAL">
                  Закрепить вручную
                </ToggleGroupItem>
              </ToggleGroup>
            </Field>
            <Field>
              <FieldLabel id="inventory-repair-schedule-mode">
                Даты ремонтов
              </FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                value={repairScheduleMode}
                disabled={pending}
                aria-labelledby="inventory-repair-schedule-mode"
                onValueChange={(value) => {
                  if (value !== "AUTO" && value !== "MANUAL") return
                  setRepairScheduleMode(value)
                  markDirty()
                }}
              >
                <ToggleGroupItem value="AUTO">
                  Распределить автоматически
                </ToggleGroupItem>
                <ToggleGroupItem value="MANUAL">
                  Закрепить вручную
                </ToggleGroupItem>
              </ToggleGroup>
            </Field>
          </FieldGroup>
        </CardContent>
      </Card>

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <SortableContext
          items={entries.map((entry) => entry.findingId)}
          strategy={verticalListSortingStrategy}
        >
          <div className="flex flex-col gap-3">
            {entries.map((entry, index) => (
              <SortablePlanEntry
                key={entry.findingId}
                entry={entry}
                index={index}
                total={entries.length}
                finding={findingsById.get(entry.findingId)}
                movementScheduleMode={movementScheduleMode}
                repairScheduleMode={repairScheduleMode}
                pending={pending}
                onChange={(change) => updateEntry(entry.findingId, change)}
                onMove={(direction) => moveEntry(index, direction)}
                onOpenFinding={() => onOpenFinding(entry.findingId)}
                onOpenCandidate={onOpenCandidate}
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>

      {submitted && validationErrors.length > 0 ? (
        <FieldError>
          Перед сохранением завершите сверку всех бытовок. Нерешённых позиций:{" "}
          {validationErrors.length}.
        </FieldError>
      ) : null}
      {error ? <FieldError>{error}</FieldError> : null}

      <div className="flex justify-end">
        <Button type="button" disabled={pending} onClick={submit}>
          <HugeiconsIcon
            icon={pending ? Loading03Icon : FloppyDiskIcon}
            data-icon="inline-start"
            className={pending ? "animate-spin" : undefined}
          />
          {pending ? "Сохраняем план…" : "Сохранить итоговый план"}
        </Button>
      </div>
    </section>
  )
}
