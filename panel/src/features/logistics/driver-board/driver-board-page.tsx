import { SingleDayPicker } from "@/components/ui/single-day-picker"
import { useMemo, useState, type CSSProperties, type ReactNode } from "react"
import { createPortal } from "react-dom"
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  TouchSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core"
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Calendar03Icon,
  ChevronDownIcon,
  DragDropVerticalIcon,
  PinIcon,
  PinOffIcon,
  RefreshIcon,
  TruckDeliveryIcon,
} from "@hugeicons/core-free-icons"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  createManualMovement,
  driverBoardQueryKey,
  getDriverBoard,
  moveDriverBoardTask,
  pinDriverBoardTask,
  promoteCapitalRepair,
  returnCapitalRepair,
  scheduleCapitalRepair,
} from "@/features/logistics/driver-board/driver-board-api"
import type {
  CapitalRepairCard,
  DriverBoard,
  DriverBoardCard,
  DriverTaskKind,
  RepairPlaceCard,
} from "@/features/logistics/driver-board/driver-board-model"
import {
  DriverTripDetailsDialog,
  DriverTripDetailsView,
} from "@/features/logistics/driver-board/driver-trip-details"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

type TaskDragItem = {
  type: "task"
  card: DriverBoardCard
  lane: DriverBoardCard["lane"]
  date: string
  index: number
}

type CapitalDragItem = {
  type: "capital"
  repair: CapitalRepairCard
}

type DriverDragItem = TaskDragItem | CapitalDragItem

type DriverBoardMove = {
  item: TaskDragItem
  targetLane: "SCHEDULED" | "CURRENT"
  targetDate: string
  targetIndex: number
}

type CapitalRepairSchedule = {
  repair: CapitalRepairCard
  targetDate: string
  targetIndex: number
  temporaryExternalTaskId: string
}

type OptimisticMoveContext = {
  queryKey: ReturnType<typeof driverBoardQueryKey>
  previousBoard: DriverBoard | undefined
}

type OptimisticCapitalScheduleContext = {
  queryKey: ReturnType<typeof driverBoardQueryKey>
  previousBoard: DriverBoard | undefined
}

const entryStatusLabels: Record<DriverBoardCard["entryStatus"], string> = {
  WAITING: "Ожидает",
  IN_PROGRESS: "В работе",
  PAUSED: "На паузе",
  DONE: "Завершено",
  CANCELLED: "Отменено",
}

const kindLabels: Record<DriverTaskKind, string> = {
  DELIVER_TO_REPAIR: "Доставить в ремонт",
  REMOVE_FROM_REPAIR: "Вывезти после ремонта",
  CAPITAL_TO_PRODUCTION: "Переместить на производство",
  GENERAL_MOVEMENT: "Свободное перемещение",
  SHIPMENT: "Отгрузка",
  RETURN: "Возврат",
  TRANSFER: "Перемещение",
}

type RepairPlaceDisplayStatus = {
  label: string
  badgeVariant: "default" | "secondary" | "outline" | "destructive"
  stageName: string | null
}

function isPhysicalRepairPlace(repairPlace: RepairPlaceCard) {
  return (
    repairPlace.allocationState === "OCCUPIED" ||
    repairPlace.allocationState === "READY_TO_RELEASE"
  )
}

function repairPlaceDisplayStatus(
  repairPlace: RepairPlaceCard
): RepairPlaceDisplayStatus {
  if (repairPlace.allocationState === "READY_TO_RELEASE") {
    return {
      label: "Ожидает вывоза",
      badgeVariant: "secondary",
      stageName: null,
    }
  }

  const stageName = repairPlace.repairStageName?.trim() || null
  switch (repairPlace.repairStageState) {
    case "PLANNED":
    case "QUEUED":
      return { label: "Ожидает", badgeVariant: "outline", stageName }
    case "IN_PROGRESS":
      return { label: "В работе", badgeVariant: "default", stageName }
    case "DONE":
      return { label: "Этап завершён", badgeVariant: "secondary", stageName }
    case "CANCELLED":
      return { label: "Этап отменён", badgeVariant: "destructive", stageName }
    default:
      return { label: "В ремонте", badgeVariant: "secondary", stageName }
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
    day: "2-digit",
    month: "long",
  }).format(new Date(`${value}T00:00:00`))
}

function isIsoCalendarDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00.000Z`)
  return (
    !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().slice(0, 10) === value
  )
}

function taskTitle(card: DriverBoardCard) {
  return card.title
}

function taskKindLabel(kind: DriverTaskKind | null) {
  return kind ? kindLabels[kind] : "Задание перемещения"
}

function taskCardClassName(card: DriverBoardCard) {
  return cn(
    card.lane === "CURRENT" && "border-primary bg-primary/5",
    card.entryStatus === "PAUSED" && "bg-muted/60",
    (card.entryStatus === "DONE" || card.entryStatus === "CANCELLED") &&
      "opacity-65"
  )
}

function orderCards(cards: DriverBoardCard[]) {
  return cards
    .slice()
    .sort(
      (left, right) =>
        left.position - right.position ||
        left.externalTaskId.localeCompare(right.externalTaskId)
    )
}

function currentAndFutureDateColumns(board: DriverBoard) {
  const grouped = new Map<string, DriverBoardCard[]>()
  for (const column of board.dates
    .slice()
    .sort((left, right) => left.date.localeCompare(right.date))) {
    const effectiveDate =
      column.date < board.currentDate ? board.currentDate : column.date
    const tasks = grouped.get(effectiveDate) ?? []
    tasks.push(
      ...column.tasks.map((card) => ({
        ...card,
        scheduledDate: effectiveDate,
      }))
    )
    grouped.set(effectiveDate, tasks)
  }
  return [...grouped.entries()].map(([date, tasks]) => ({ date, tasks }))
}

function isTaskMovable(card: DriverBoardCard) {
  return card.taskStatus === "ACTIVE" && card.entryStatus === "WAITING"
}

function isMovementTask(card: DriverBoardCard) {
  return card.kind !== "SHIPMENT" && card.kind !== "RETURN"
}

function queueTargetIndex(
  allCards: DriverBoardCard[],
  visibleCards: DriverBoardCard[],
  visibleIndex: number
) {
  if (visibleCards.length === 0) return allCards.length
  const boundedIndex = Math.max(0, Math.min(visibleIndex, visibleCards.length))
  if (boundedIndex < visibleCards.length) {
    const targetCard = visibleCards[boundedIndex]
    const targetIndex = allCards.findIndex(
      (card) => card.externalTaskId === targetCard?.externalTaskId
    )
    return targetIndex < 0 ? allCards.length : targetIndex
  }
  const finalCard = visibleCards.at(-1)
  const finalIndex = allCards.findIndex(
    (card) => card.externalTaskId === finalCard?.externalTaskId
  )
  return finalIndex < 0 ? allCards.length : finalIndex + 1
}

function normalizeCardPositions(cards: DriverBoardCard[]) {
  return cards.map((card, position) =>
    card.position === position ? card : { ...card, position }
  )
}

/**
 * Applies the exact destination calculated from the drop target before the
 * command reaches the server.  The server remains authoritative: this value
 * is replaced with a fresh board after the command settles.
 */
function moveCardOptimistically(
  board: DriverBoard,
  { item, targetLane, targetDate, targetIndex }: DriverBoardMove
): DriverBoard {
  const current = orderCards(board.current)
  const dates = board.dates.map((column) => ({
    ...column,
    tasks: orderCards(column.tasks),
  }))
  const source =
    item.lane === "CURRENT"
      ? current
      : dates.find((column) => column.date === item.date)?.tasks
  if (!source) return board

  const sourceIndex = source.findIndex(
    (card) => card.externalTaskId === item.card.externalTaskId
  )
  if (sourceIndex < 0) return board

  const [moved] = source.splice(sourceIndex, 1)
  if (!moved) return board

  let target: DriverBoardCard[]
  if (targetLane === "CURRENT") {
    target = current
  } else {
    let targetColumn = dates.find((column) => column.date === targetDate)
    if (!targetColumn) {
      targetColumn = { date: targetDate, tasks: [] }
      dates.push(targetColumn)
    }
    target = targetColumn.tasks
  }

  const insertionIndex = Math.max(0, Math.min(targetIndex, target.length))
  target.splice(insertionIndex, 0, {
    ...moved,
    lane: targetLane,
    scheduledDate: targetDate,
    position: insertionIndex,
  })

  return {
    ...board,
    current: normalizeCardPositions(current),
    dates: dates.map((column) => ({
      ...column,
      tasks: normalizeCardPositions(column.tasks),
    })),
  }
}

function pendingCapitalCard(schedule: CapitalRepairSchedule): DriverBoardCard {
  return {
    driverTaskId: null,
    externalTaskId: schedule.temporaryExternalTaskId,
    taskBoardTaskId: schedule.temporaryExternalTaskId,
    taskBoardTaskVersion: 0,
    taskBoardEntryId: schedule.temporaryExternalTaskId,
    taskBoardEntryVersion: 0,
    title: "Переместить бытовку на производство",
    taskText: "Переместить бытовку на производство",
    unitNumber: schedule.repair.unitNumber,
    kind: "CAPITAL_TO_PRODUCTION",
    driverAudience: {
      mode: "UNASSIGNED",
      workerId: null,
      workerName: null,
    },
    workflowState: "REGISTERING",
    taskStatus: "ACTIVE",
    entryStatus: "WAITING",
    scheduledDate: schedule.targetDate,
    lane: "SCHEDULED",
    priority: schedule.repair.priority,
    pinned: false,
    position: schedule.targetIndex,
    tripDetails: null,
  }
}

function scheduleCapitalRepairOptimistically(
  board: DriverBoard,
  schedule: CapitalRepairSchedule
): DriverBoard {
  const dates = board.dates.map((column) => ({
    ...column,
    tasks: [...column.tasks],
  }))
  let targetColumn = dates.find((column) => column.date === schedule.targetDate)
  if (!targetColumn) {
    targetColumn = { date: schedule.targetDate, tasks: [] }
    dates.push(targetColumn)
  }
  const insertionIndex = Math.max(
    0,
    Math.min(schedule.targetIndex, targetColumn.tasks.length)
  )
  targetColumn.tasks.splice(insertionIndex, 0, {
    ...pendingCapitalCard(schedule),
    position: insertionIndex,
  })

  return {
    ...board,
    dates: dates.map((column) => ({
      ...column,
      tasks: normalizeCardPositions(column.tasks),
    })),
    capitalRepairs: board.capitalRepairs.filter(
      (repair) => repair.repairId !== schedule.repair.repairId
    ),
  }
}

function resolveOptimisticCapitalSchedule(
  board: DriverBoard,
  schedule: CapitalRepairSchedule,
  card: DriverBoardCard
): DriverBoard {
  let replaced = false
  const dates = board.dates.map((column) => ({
    ...column,
    tasks: column.tasks.map((candidate) => {
      if (candidate.externalTaskId !== schedule.temporaryExternalTaskId) {
        return candidate
      }
      replaced = true
      return card
    }),
  }))
  if (!replaced) return board

  return {
    ...board,
    dates: dates.map((column) => ({
      ...column,
      tasks: normalizeCardPositions(column.tasks),
    })),
  }
}

function DriverTaskDetailsCard({
  card,
  className,
  dragHandle,
  onPin,
  pinDisabled = false,
}: {
  card: DriverBoardCard
  className?: string
  dragHandle?: ReactNode
  onPin?: () => void
  pinDisabled?: boolean
}) {
  const [expanded, setExpanded] = useState(true)
  const trip = card.tripDetails
  const cabinLabel = trip
    ? `${trip.cabins.length} бытовок`
    : card.unitNumber || "без номера"

  return (
    <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
      <Card size="sm" className={className}>
        <CardHeader>
          <CardTitle className="line-clamp-2 pr-10">
            {trip
              ? `Задание №${trip.taskNumber} · Ходка №${trip.tripNumber}`
              : taskTitle(card)}
          </CardTitle>
          <CardDescription>
            {trip
              ? `${trip.clientName} · ${cabinLabel}`
              : `Бытовка ${cabinLabel}`}
          </CardDescription>
          <CardAction className="flex items-center gap-1">
            <CollapsibleTrigger asChild>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={
                  expanded
                    ? `Свернуть задание бытовки ${card.unitNumber || "без номера"}`
                    : `Развернуть задание бытовки ${card.unitNumber || "без номера"}`
                }
                onPointerDown={(event) => event.stopPropagation()}
                onKeyDown={(event) => event.stopPropagation()}
              >
                <HugeiconsIcon
                  icon={ChevronDownIcon}
                  className={cn(
                    "transition-transform",
                    expanded && "rotate-180"
                  )}
                />
              </Button>
            </CollapsibleTrigger>
            {onPin ? (
              <Button
                type="button"
                size="icon-sm"
                variant={card.pinned ? "secondary" : "ghost"}
                disabled={pinDisabled}
                aria-pressed={card.pinned}
                aria-label={
                  card.pinned
                    ? `Открепить задание бытовки ${card.unitNumber || "без номера"}`
                    : `Закрепить задание бытовки ${card.unitNumber || "без номера"}`
                }
                onPointerDown={(event) => event.stopPropagation()}
                onKeyDown={(event) => event.stopPropagation()}
                onClick={(event) => {
                  event.stopPropagation()
                  onPin()
                }}
              >
                <HugeiconsIcon icon={card.pinned ? PinOffIcon : PinIcon} />
              </Button>
            ) : null}
            {dragHandle}
          </CardAction>
        </CardHeader>
        <CollapsibleContent asChild>
          <CardContent className="flex flex-col gap-2">
            <p className="text-xs text-muted-foreground">
              {taskKindLabel(card.kind)}
            </p>
            {trip ? (
              <>
                <DriverTripDetailsView details={trip} live={false} />
                <DriverTripDetailsDialog card={card} />
              </>
            ) : null}
            {!trip &&
            card.taskText?.trim() &&
            card.taskText.trim() !== card.title.trim() ? (
              <p className="text-sm whitespace-pre-wrap">
                {card.taskText.trim()}
              </p>
            ) : null}
            <div className="flex flex-wrap gap-1">
              <Badge
                variant={
                  card.entryStatus === "IN_PROGRESS" ? "default" : "outline"
                }
              >
                {entryStatusLabels[card.entryStatus]}
              </Badge>
              <Badge variant={card.priority <= 2 ? "default" : "secondary"}>
                Приоритет {card.priority}
              </Badge>
              {card.pinned ? (
                <Badge variant="secondary">Закреплено</Badge>
              ) : null}
              {trip ? (
                <Badge variant="outline">
                  Водитель: {card.driverAudience.workerName ?? "не назначен"}
                </Badge>
              ) : null}
            </div>
          </CardContent>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

function RepairPlaceDetailsCard({
  repairPlace,
}: {
  repairPlace: RepairPlaceCard
}) {
  const status = repairPlaceDisplayStatus(repairPlace)

  return (
    <Card
      size="sm"
      aria-label={`Ремонтное место бытовки ${repairPlace.unitNumber}`}
    >
      <CardHeader>
        <CardTitle>Бытовка {repairPlace.unitNumber}</CardTitle>
        {status.stageName ? (
          <CardDescription>Этап: {status.stageName}</CardDescription>
        ) : null}
      </CardHeader>
      <CardContent className="flex flex-wrap gap-1">
        <Badge variant={status.badgeVariant}>{status.label}</Badge>
        <Badge variant={repairPlace.priority <= 2 ? "default" : "secondary"}>
          Приоритет {repairPlace.priority}
        </Badge>
      </CardContent>
    </Card>
  )
}

function CurrentTaskCard({
  card,
  date,
  index,
  disabled,
  onPin,
}: {
  card: DriverBoardCard
  date: string
  index: number
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const dragDisabled = disabled || !isTaskMovable(card)
  const dragItem: TaskDragItem = {
    type: "task",
    card,
    lane: "CURRENT",
    date,
    index,
  }
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: `driver-task:${card.externalTaskId}`,
    disabled: dragDisabled,
    data: dragItem,
  })
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0 : 1,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        !dragDisabled && "cursor-grab touch-none active:cursor-grabbing"
      )}
      data-testid={`current-task-${card.externalTaskId}`}
      {...attributes}
      {...listeners}
      aria-label={
        card.tripDetails
          ? `Переместить ходку №${card.tripDetails.tripNumber}`
          : `Переместить задание бытовки ${card.unitNumber || "без номера"}`
      }
    >
      <DriverTaskDetailsCard
        card={card}
        className={taskCardClassName(card)}
        onPin={() => onPin(card)}
        pinDisabled={disabled}
        dragHandle={
          <span
            className="flex size-8 items-center justify-center text-muted-foreground"
            aria-hidden="true"
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </span>
        }
      />
    </div>
  )
}

function ScheduledTaskCard({
  card,
  date,
  index,
  disabled,
  onPin,
}: {
  card: DriverBoardCard
  date: string
  index: number
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const dragDisabled = disabled || !isTaskMovable(card)
  const dragItem: TaskDragItem = {
    type: "task",
    card,
    lane: "SCHEDULED",
    date,
    index,
  }
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: `driver-task:${card.externalTaskId}`,
    disabled: dragDisabled,
    data: dragItem,
  })
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0 : 1,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        !dragDisabled && "cursor-grab touch-none active:cursor-grabbing"
      )}
      data-testid={`scheduled-task-${card.externalTaskId}`}
      {...attributes}
      {...listeners}
      aria-label={
        card.tripDetails
          ? `Переместить ходку №${card.tripDetails.tripNumber}`
          : `Переместить задание бытовки ${card.unitNumber || "без номера"}`
      }
    >
      <DriverTaskDetailsCard
        card={card}
        className={taskCardClassName(card)}
        onPin={() => onPin(card)}
        pinDisabled={disabled}
        dragHandle={
          <span
            className="flex size-8 items-center justify-center text-muted-foreground"
            aria-hidden="true"
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </span>
        }
      />
    </div>
  )
}

function InsertionSlot({
  lane,
  date,
  index,
  disabled,
}: {
  lane: "SCHEDULED" | "CURRENT"
  date: string
  index: number
  disabled: boolean
}) {
  const current = lane === "CURRENT"
  const { setNodeRef, isOver } = useDroppable({
    id: current
      ? `driver-current-slot:${date}:${index}`
      : `driver-slot:${date}:${index}`,
    disabled,
    data: {
      type: current ? "current-slot" : "scheduled-slot",
      lane,
      date,
      index,
    },
  })

  return (
    <div
      ref={setNodeRef}
      aria-label={
        current
          ? `Вставить в текущие задания на позицию ${index + 1}`
          : `Вставить на позицию ${index + 1} за ${formatDate(date)}`
      }
      data-insertion-active={isOver || undefined}
      className={cn(
        "relative h-3 shrink-0 rounded-md transition-[height,background-color] duration-150",
        isOver && "h-12 border border-dashed border-primary/60 bg-primary/8"
      )}
    >
      {isOver ? (
        <span className="absolute inset-x-3 top-1/2 h-0.5 -translate-y-1/2 rounded-full bg-primary" />
      ) : null}
    </div>
  )
}

function DateColumn({
  date,
  allTasks,
  tasks,
  disabled,
  onPin,
}: {
  date: string
  allTasks: DriverBoardCard[]
  tasks: DriverBoardCard[]
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const orderedTasks = orderCards(tasks)
  const { setNodeRef, isOver } = useDroppable({
    id: `driver-date:${date}`,
    disabled,
    data: { type: "scheduled-date", date },
  })

  return (
    <section
      ref={setNodeRef}
      aria-label={`Задания на ${formatDate(date)}`}
      className={cn(
        "flex h-fit w-80 shrink-0 flex-col rounded-xl border bg-card p-3 shadow-sm",
        isOver && "ring-2 ring-primary/40"
      )}
    >
      <header className="mb-2 flex items-center justify-between gap-2">
        <h2 className="font-heading text-sm font-medium capitalize">
          {formatDate(date)}
        </h2>
        <Badge variant="outline">{orderedTasks.length}</Badge>
      </header>
      <SortableContext
        items={orderedTasks.map((card) => `driver-task:${card.externalTaskId}`)}
        strategy={verticalListSortingStrategy}
      >
        {orderedTasks.map((card, visibleIndex) => {
          const globalIndex = allTasks.findIndex(
            (candidate) => candidate.externalTaskId === card.externalTaskId
          )
          return (
            <div key={card.externalTaskId}>
              <InsertionSlot
                lane="SCHEDULED"
                date={date}
                index={queueTargetIndex(allTasks, orderedTasks, visibleIndex)}
                disabled={disabled}
              />
              <ScheduledTaskCard
                card={card}
                date={date}
                index={globalIndex}
                disabled={disabled}
                onPin={onPin}
              />
            </div>
          )
        })}
      </SortableContext>
      <InsertionSlot
        lane="SCHEDULED"
        date={date}
        index={queueTargetIndex(allTasks, orderedTasks, orderedTasks.length)}
        disabled={disabled}
      />
    </section>
  )
}

function CapitalCard({
  repair,
  warehouseId,
  disabled,
  onPromote,
}: {
  repair: CapitalRepairCard
  warehouseId: string
  disabled: boolean
  onPromote: (repair: CapitalRepairCard) => void
}) {
  const [expanded, setExpanded] = useState(false)
  const dragItem: CapitalDragItem = { type: "capital", repair }
  const { attributes, listeners, setNodeRef, transform, isDragging } =
    useDraggable({
      id: `capital-repair:${repair.repairId}`,
      disabled,
      data: dragItem,
    })
  const style: CSSProperties = {
    transform: CSS.Translate.toString(transform),
    opacity: isDragging ? 0 : 1,
    borderLeftColor: repair.complexityColor,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        !disabled && "cursor-grab touch-none active:cursor-grabbing"
      )}
      data-testid={`capital-repair-${repair.repairId}`}
      {...attributes}
      {...listeners}
      aria-label={`Переместить капитальный ремонт бытовки ${repair.unitNumber} в текущие задания`}
    >
      <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
        <Card size="sm" className="border-l-4">
          <CardHeader>
            <CardTitle>Бытовка {repair.unitNumber}</CardTitle>
            <CardDescription>
              {Number(repair.plannedMinutes).toLocaleString("ru-RU")} мин.
            </CardDescription>
            <CardAction className="flex items-center gap-1">
              <CollapsibleTrigger asChild>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={
                    expanded
                      ? `Свернуть план капитального ремонта бытовки ${repair.unitNumber}`
                      : `Развернуть план капитального ремонта бытовки ${repair.unitNumber}`
                  }
                  onPointerDown={(event) => event.stopPropagation()}
                  onKeyDown={(event) => event.stopPropagation()}
                  onClick={(event) => event.stopPropagation()}
                >
                  <HugeiconsIcon
                    icon={ChevronDownIcon}
                    className={cn(
                      "transition-transform",
                      expanded && "rotate-180"
                    )}
                    aria-hidden="true"
                  />
                </Button>
              </CollapsibleTrigger>
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={disabled}
                aria-label={`Добавить капитальный ремонт бытовки ${repair.unitNumber} в текущие задания`}
                onPointerDown={(event) => event.stopPropagation()}
                onKeyDown={(event) => event.stopPropagation()}
                onClick={(event) => {
                  event.stopPropagation()
                  onPromote(repair)
                }}
              >
                В текущее
              </Button>
              <span
                className="flex size-8 items-center justify-center text-muted-foreground"
                aria-hidden="true"
              >
                <HugeiconsIcon icon={DragDropVerticalIcon} />
              </span>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="flex flex-wrap gap-1">
              <Badge
                style={{
                  backgroundColor: repair.complexityColor,
                  color: "#ffffff",
                }}
              >
                {repair.complexityName}
              </Badge>
              <Badge variant={repair.priority <= 2 ? "default" : "secondary"}>
                Приоритет {repair.priority}
              </Badge>
              {repair.forcedCapital ? (
                <Badge variant="outline">Принудительно</Badge>
              ) : null}
            </div>
            <CollapsibleContent
              onPointerDown={(event) => event.stopPropagation()}
            >
              <CapitalRepairPlan
                repair={repair}
                warehouseId={warehouseId}
                expanded={expanded}
              />
            </CollapsibleContent>
          </CardContent>
        </Card>
      </Collapsible>
    </div>
  )
}

function planLineName(line: RepairEstimateLineDto) {
  return (
    line.catalogSnapshot?.name.trim() ||
    line.description.trim() ||
    "Без названия"
  )
}

function planLineAmount(line: RepairEstimateLineDto) {
  const quantity = new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: 3,
  }).format(line.quantity)
  return `${quantity} ${line.unit.trim() || "единица не указана"}`
}

function CapitalRepairPlanLines({
  lines,
  emptyMessage,
}: {
  lines: RepairEstimateLineDto[]
  emptyMessage: string
}) {
  if (lines.length === 0) {
    return <p className="text-sm text-muted-foreground">{emptyMessage}</p>
  }

  return (
    <ul className="flex flex-col gap-2">
      {lines.map((line) => (
        <li key={line.id} className="flex items-start justify-between gap-3">
          <span className="min-w-0 text-sm break-words">
            {planLineName(line)}
          </span>
          <span className="shrink-0 text-sm text-muted-foreground">
            {planLineAmount(line)}
          </span>
        </li>
      ))}
    </ul>
  )
}

/**
 * Lazily reads the maintenance-owned repair plan only while its capital card
 * is expanded; the query cache remains shared with the repair workspace.
 */
function CapitalRepairPlan({
  repair,
  warehouseId,
  expanded,
}: {
  repair: CapitalRepairCard
  warehouseId: string
  expanded: boolean
}) {
  const planQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(warehouseId, repair.repairId),
    queryFn: async () => {
      const plan = await getRepairTask(repair.repairId, warehouseId)
      if (!plan) {
        throw new Error("Ремонт больше не найден.")
      }
      return plan
    },
    enabled: expanded,
  })

  if (planQuery.isPending) {
    return (
      <div
        role="status"
        className="flex flex-col gap-2"
        aria-label={`Загрузка плана капитального ремонта бытовки ${repair.unitNumber}`}
      >
        <p className="text-sm text-muted-foreground">
          Загружаем работы и материалы…
        </p>
        <Skeleton className="h-16 w-full" />
      </div>
    )
  }

  if (planQuery.isError || !planQuery.data) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить план ремонта</AlertTitle>
        <AlertDescription>
          {planQuery.error instanceof Error
            ? planQuery.error.message
            : "Повторите запрос."}
        </AlertDescription>
        <AlertAction>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onPointerDown={(event) => event.stopPropagation()}
            onKeyDown={(event) => event.stopPropagation()}
            onClick={(event) => {
              event.stopPropagation()
              void planQuery.refetch()
            }}
          >
            <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
            Повторить
          </Button>
        </AlertAction>
      </Alert>
    )
  }

  const stages = planQuery.data.subtasks
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)

  if (stages.length === 0) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Этапы капитального ремонта не запланированы.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      {stages.map((stage, index) => {
        const queueName = stage.queueName?.trim() || "Очередь не указана"
        const workHeadingId = `capital-${repair.repairId}-${stage.id}-work`
        const materialHeadingId = `capital-${repair.repairId}-${stage.id}-material`

        return (
          <section
            key={stage.id}
            aria-label={`Этап ${index + 1}: очередь ${queueName}`}
            className="flex flex-col gap-2 rounded-md border p-3"
          >
            <div className="flex flex-col gap-0.5">
              <p className="text-xs text-muted-foreground">Этап {index + 1}</p>
              <h4 className="font-medium">Очередь: {queueName}</h4>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <section aria-labelledby={workHeadingId}>
                <h5 id={workHeadingId} className="mb-2 font-medium">
                  Работы
                </h5>
                <CapitalRepairPlanLines
                  lines={stage.workLines}
                  emptyMessage="Работы не запланированы."
                />
              </section>
              <section aria-labelledby={materialHeadingId}>
                <h5 id={materialHeadingId} className="mb-2 font-medium">
                  Материалы
                </h5>
                <CapitalRepairPlanLines
                  lines={stage.materialLines}
                  emptyMessage="Материалы не запланированы."
                />
              </section>
            </div>
          </section>
        )
      })}
    </div>
  )
}

function NewDateDropTarget({ disabled }: { disabled: boolean }) {
  const { setNodeRef, isOver } = useDroppable({
    id: "driver-new-date",
    disabled,
    data: { type: "new-date" },
  })

  return (
    <div
      ref={setNodeRef}
      aria-label="Перенести задание на новую дату"
      data-testid="driver-new-date-drop-zone"
      className={cn(
        "flex h-full min-h-full min-w-80 flex-1 shrink-0 self-stretch rounded-xl border border-dashed bg-muted/30 p-4 text-center text-sm text-muted-foreground",
        isOver && "border-primary bg-primary/8 text-primary"
      )}
    >
      <div className="flex h-full min-h-72 w-full flex-col items-center justify-center gap-2">
        <HugeiconsIcon icon={Calendar03Icon} className="size-5" />
        Перетащите сюда, чтобы выбрать другую дату
      </div>
    </div>
  )
}

function CurrentColumn({
  board,
  disabled,
  onPin,
}: {
  board: DriverBoard
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const currentDate = board.currentDate
  const allCurrentTasks = orderCards(board.current)
  const currentTasks = allCurrentTasks.filter(isMovementTask)
  const physicalRepairPlaces = board.repairPlaces.filter(isPhysicalRepairPlace)
  const [repairPlacesExpanded, setRepairPlacesExpanded] = useState(false)
  const { setNodeRef, isOver } = useDroppable({
    id: "driver-current",
    disabled,
    data: {
      type: "current",
      lane: "CURRENT",
      date: currentDate,
      index: queueTargetIndex(
        allCurrentTasks,
        currentTasks,
        currentTasks.length
      ),
    },
  })

  return (
    <section
      ref={setNodeRef}
      aria-label={`Текущие задания на ${formatDate(currentDate)}`}
      className={cn(
        "sticky left-0 z-20 flex min-h-0 flex-col border-r bg-background p-3",
        isOver && "bg-primary/8 ring-2 ring-primary/40 ring-inset"
      )}
    >
      <header className="mb-3 flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <div>
            <h2 className="font-heading font-semibold">Текущие задания</h2>
            <p className="text-xs text-muted-foreground capitalize">
              {formatDate(currentDate)}
            </p>
          </div>
          <Badge variant={currentTasks.length > 0 ? "default" : "outline"}>
            {currentTasks.length}
          </Badge>
        </div>
        <div
          className="rounded-lg border bg-muted/35 p-2 text-xs"
          aria-label={`Ремонтные места: ${board.occupiedRepairPlaceCount} из ${board.repairPlaceCount} занято`}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="font-medium">Ремонтные места</span>
            <Badge
              variant={
                board.repairPlacesOverCapacity ? "destructive" : "outline"
              }
            >
              {board.occupiedRepairPlaceCount} из {board.repairPlaceCount}
            </Badge>
          </div>
          <p className="mt-1 text-muted-foreground">
            Физически свободно:{" "}
            {Math.max(
              0,
              board.repairPlaceCount - board.occupiedRepairPlaceCount
            )}
          </p>
          <div className="mt-1 flex items-start justify-between gap-1">
            <p className="text-muted-foreground">
              В списке — только бытовки, уже доставленные в ремонт. Назначенные
              доставки остаются среди запланированных заданий.
            </p>
            <Button
              type="button"
              size="icon-xs"
              variant="ghost"
              aria-controls="driver-repair-places"
              aria-expanded={repairPlacesExpanded}
              aria-label={
                repairPlacesExpanded
                  ? "Свернуть список ремонтных мест"
                  : "Развернуть список ремонтных мест"
              }
              onClick={() => setRepairPlacesExpanded((expanded) => !expanded)}
            >
              <HugeiconsIcon
                icon={ChevronDownIcon}
                data-icon="inline-start"
                aria-hidden="true"
              />
            </Button>
          </div>
          {repairPlacesExpanded ? (
            <div
              id="driver-repair-places"
              className="mt-2 flex max-h-72 flex-col gap-2 overflow-y-auto"
              aria-label="Бытовки в ремонтных местах"
            >
              {physicalRepairPlaces.length > 0 ? (
                physicalRepairPlaces.map((repairPlace) => (
                  <RepairPlaceDetailsCard
                    key={repairPlace.repairId}
                    repairPlace={repairPlace}
                  />
                ))
              ) : (
                <p role="status" className="text-muted-foreground">
                  В ремонтных местах пока нет бытовок.
                </p>
              )}
            </div>
          ) : null}
          {board.repairPlacesOverCapacity ? (
            <p className="mt-1 text-destructive">
              Превышена вместимость склада
            </p>
          ) : null}
        </div>
      </header>
      <div
        data-testid="driver-current-task-scroll"
        aria-label="Список текущих заданий"
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain rounded-xl border border-dashed p-2"
      >
        <SortableContext
          items={currentTasks.map(
            (card) => `driver-task:${card.externalTaskId}`
          )}
          strategy={verticalListSortingStrategy}
        >
          {currentTasks.length > 0 ? (
            <div className="flex flex-col gap-2">
              {currentTasks.map((card, visibleIndex) => {
                const globalIndex = allCurrentTasks.findIndex(
                  (candidate) =>
                    candidate.externalTaskId === card.externalTaskId
                )
                return (
                  <div key={card.externalTaskId}>
                    <InsertionSlot
                      lane="CURRENT"
                      date={currentDate}
                      index={queueTargetIndex(
                        allCurrentTasks,
                        currentTasks,
                        visibleIndex
                      )}
                      disabled={disabled}
                    />
                    <CurrentTaskCard
                      card={card}
                      date={currentDate}
                      index={globalIndex}
                      disabled={disabled}
                      onPin={onPin}
                    />
                  </div>
                )
              })}
              <InsertionSlot
                lane="CURRENT"
                date={currentDate}
                index={queueTargetIndex(
                  allCurrentTasks,
                  currentTasks,
                  currentTasks.length
                )}
                disabled={disabled}
              />
            </div>
          ) : (
            <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground">
              <HugeiconsIcon icon={TruckDeliveryIcon} className="size-6" />
              Общая очередь перемещений пуста
            </div>
          )}
        </SortableContext>
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        Перетащите сюда капитальный ремонт. Карточку можно вручную перенести
        обратно на выбранную дату.
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        После ручного освобождения очередь заполнится автоматически через{" "}
        {board.automaticRefillDelayMinutes} мин.
      </p>
    </section>
  )
}

function CapitalColumn({
  repairs,
  warehouseId,
  disabled,
  onPromote,
}: {
  repairs: CapitalRepairCard[]
  warehouseId: string
  disabled: boolean
  onPromote: (repair: CapitalRepairCard) => void
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: "driver-capital-repairs",
    data: { type: "capital-target" },
    disabled,
  })

  return (
    <section
      ref={setNodeRef}
      aria-label="Капитальные ремонты"
      className={cn(
        "sticky right-0 z-20 flex min-h-0 flex-col border-l bg-background p-3 transition-colors",
        isOver && !disabled && "bg-primary/10 ring-2 ring-primary ring-inset"
      )}
    >
      <header className="mb-3 flex items-center justify-between gap-2">
        <h2 className="font-heading font-semibold">Капитальные ремонты</h2>
        <Badge variant="outline">{repairs.length}</Badge>
      </header>
      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto">
        {repairs.map((repair) => (
          <CapitalCard
            key={repair.repairId}
            repair={repair}
            warehouseId={warehouseId}
            disabled={disabled}
            onPromote={onPromote}
          />
        ))}
        {repairs.length === 0 ? (
          <p className="rounded-xl border border-dashed p-4 text-center text-sm text-muted-foreground">
            Капитальных ремонтов нет
          </p>
        ) : null}
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        Перетащите сюда незавершённое задание капитального ремонта, чтобы
        вернуть бытовку в этот список.
      </p>
    </section>
  )
}

function DragCardOverlay({ item }: { item: DriverDragItem }) {
  if (item.type === "capital") {
    return (
      <Card
        size="sm"
        aria-hidden="true"
        className="w-72 border-l-4 shadow-lg"
        style={{ borderLeftColor: item.repair.complexityColor }}
      >
        <CardHeader>
          <CardTitle>Бытовка {item.repair.unitNumber}</CardTitle>
          <CardDescription>Капитальный ремонт</CardDescription>
        </CardHeader>
      </Card>
    )
  }

  return (
    <Card
      size="sm"
      aria-hidden="true"
      className={cn(
        "w-72 cursor-grabbing shadow-lg",
        taskCardClassName(item.card)
      )}
    >
      <CardHeader>
        <CardTitle>{taskTitle(item.card)}</CardTitle>
        <CardDescription>
          Бытовка {item.card.unitNumber || "без номера"}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Badge variant="outline">{taskKindLabel(item.card.kind)}</Badge>
      </CardContent>
    </Card>
  )
}

function DatePickerDialog({
  item,
  minimumDate,
  onClose,
  onConfirm,
}: {
  item: DriverDragItem | null
  minimumDate: string
  onClose: () => void
  onConfirm: (date: string) => void
}) {
  const [date, setDate] = useState("")
  const unitNumber =
    item?.type === "capital" ? item.repair.unitNumber : item?.card.unitNumber
  const canSubmit = Boolean(
    item && isIsoCalendarDate(date) && date >= minimumDate
  )

  return (
    <Dialog
      open={Boolean(item)}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      {item ? (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Выберите новую дату</DialogTitle>
            <DialogDescription>
              {item.type === "capital"
                ? `Капитальный ремонт бытовки ${unitNumber || "без номера"} будет добавлен в конец очереди выбранной даты.`
                : `Задание бытовки ${unitNumber || "без номера"} будет добавлено в конец очереди выбранной даты.`}
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="driver-board-new-date">Дата</FieldLabel>
              <SingleDayPicker
                label="Дата"
                hideLabel
                allowClear
                id="driver-board-new-date"
                min={minimumDate}
                value={date}
                onValueChange={(nextValue) => setDate(nextValue)}
              />
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
            <Button
              type="button"
              disabled={!canSubmit}
              onClick={() => {
                if (!canSubmit) return
                onConfirm(date)
                onClose()
              }}
            >
              Переместить
            </Button>
          </DialogFooter>
        </DialogContent>
      ) : null}
    </Dialog>
  )
}

function ManualMovementDialog({
  accessToken,
  warehouseId,
  saving,
  onClose,
  onSubmit,
}: {
  accessToken: string
  warehouseId: string
  saving: boolean
  onClose: () => void
  onSubmit: (command: {
    cabinId: string
    comment: string
    priority: number
  }) => void
}) {
  const [cabinId, setCabinId] = useState("")
  const [comment, setComment] = useState("")
  const [priority, setPriority] = useState("3")
  const [validationError, setValidationError] = useState<string | null>(null)
  const cabinsQuery = useQuery({
    queryKey: ["asset-rental-items", warehouseId, "manual-movement"],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: 0,
        size: 200,
      }),
  })
  const cabins = useMemo(
    () =>
      (cabinsQuery.data?.content ?? [])
        .slice()
        .sort((left, right) =>
          left.number.localeCompare(right.number, "ru", { numeric: true })
        ),
    [cabinsQuery.data?.content]
  )

  function submit() {
    const normalizedComment = comment.trim()
    if (!cabinId) {
      setValidationError("Выберите бытовку.")
      return
    }
    if (!normalizedComment) {
      setValidationError("Опишите, куда или зачем нужно переместить бытовку.")
      return
    }
    if (normalizedComment.length > 1_000) {
      setValidationError("Комментарий не должен превышать 1000 символов.")
      return
    }

    setValidationError(null)
    onSubmit({
      cabinId,
      comment: normalizedComment,
      priority: Number(priority),
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Создать перемещение</DialogTitle>
          <DialogDescription>
            Свободное перемещение не занимает ремонтное место и сразу попадает в
            текущую очередь водителей.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <Field>
            <FieldLabel htmlFor="manual-movement-cabin">Бытовка</FieldLabel>
            <Select
              value={cabinId}
              onValueChange={setCabinId}
              disabled={saving || cabinsQuery.isLoading}
            >
              <SelectTrigger id="manual-movement-cabin" className="w-full">
                <SelectValue
                  placeholder={
                    cabinsQuery.isLoading
                      ? "Загружаем бытовки…"
                      : "Выберите бытовку"
                  }
                />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {cabins.map((cabin) => (
                    <SelectItem key={cabin.id} value={cabin.id}>
                      {cabin.number}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
            {cabinsQuery.isError ? (
              <FieldError>
                Не удалось загрузить бытовки выбранного склада.
              </FieldError>
            ) : null}
          </Field>
          <Field>
            <FieldLabel htmlFor="manual-movement-comment">
              Комментарий
            </FieldLabel>
            <Textarea
              id="manual-movement-comment"
              value={comment}
              maxLength={1_000}
              disabled={saving}
              placeholder="Например: переставить бытовку к зоне отгрузки"
              onChange={(event) => setComment(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="manual-movement-priority">
              Приоритет
            </FieldLabel>
            <Select
              value={priority}
              onValueChange={setPriority}
              disabled={saving}
            >
              <SelectTrigger id="manual-movement-priority" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {[1, 2, 3, 4, 5].map((value) => (
                    <SelectItem key={value} value={String(value)}>
                      Приоритет {value}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>
          {validationError ? <FieldError>{validationError}</FieldError> : null}
        </FieldGroup>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={saving}
            onClick={onClose}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={saving || cabinsQuery.isLoading}
            onClick={submit}
          >
            Создать
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function BoardLoading() {
  return (
    <Card aria-busy="true">
      <CardHeader>
        <CardTitle>Загрузка перемещений</CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Получаем текущие задания, даты и капитальные ремонты…
      </CardContent>
    </Card>
  )
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить команду перемещения."
}

export function DriverBoardPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const warehouseId = selectedWarehouseId
  const canEdit = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
  const [activeItem, setActiveItem] = useState<DriverDragItem | null>(null)
  const [datePickerItem, setDatePickerItem] = useState<DriverDragItem | null>(
    null
  )
  const [manualMovementOpen, setManualMovementOpen] = useState(false)
  const [selectedScheduledDate, setSelectedScheduledDate] = useState("")
  const [commandError, setCommandError] = useState<unknown>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 220, tolerance: 6 },
    }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )

  const boardQuery = useQuery({
    queryKey: driverBoardQueryKey(warehouseId ?? "none"),
    queryFn: () => getDriverBoard(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
  })

  async function refreshBoard() {
    if (!warehouseId) return
    await queryClient.invalidateQueries({
      queryKey: driverBoardQueryKey(warehouseId),
      exact: true,
    })
  }

  function handleCommandError(error: unknown) {
    if (error instanceof ApiError && error.status === 409) {
      setCommandError(null)
      void refreshBoard()
      return
    }
    setCommandError(error)
  }

  const moveMutation = useMutation<
    DriverBoardCard,
    unknown,
    DriverBoardMove,
    OptimisticMoveContext
  >({
    mutationFn: ({ item, targetLane, targetDate, targetIndex }) =>
      moveDriverBoardTask({
        accessToken: accessToken!,
        externalTaskId: item.card.externalTaskId,
        command: {
          warehouseId: warehouseId!,
          expectedTaskVersion: item.card.taskBoardTaskVersion,
          expectedEntryVersion: item.card.taskBoardEntryVersion,
          targetLane,
          targetDate,
          targetIndex,
        },
      }),
    onMutate: async (move) => {
      const queryKey = driverBoardQueryKey(warehouseId!)
      await queryClient.cancelQueries({ queryKey, exact: true })
      const previousBoard = queryClient.getQueryData<DriverBoard>(queryKey)
      if (previousBoard) {
        queryClient.setQueryData(
          queryKey,
          moveCardOptimistically(previousBoard, move)
        )
      }
      setCommandError(null)
      return { queryKey, previousBoard }
    },
    onSuccess: async () => {
      setCommandError(null)
      await refreshBoard()
    },
    onError: async (error, _move, context) => {
      if (context?.previousBoard) {
        queryClient.setQueryData(context.queryKey, context.previousBoard)
      }
      setCommandError(error)
      await refreshBoard()
    },
  })

  const promoteMutation = useMutation({
    mutationFn: (repair: CapitalRepairCard) =>
      promoteCapitalRepair({
        accessToken: accessToken!,
        repairId: repair.repairId,
        warehouseId: warehouseId!,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      setCommandError(null)
      void refreshBoard()
    },
    onError: handleCommandError,
  })

  const scheduleCapitalMutation = useMutation<
    DriverBoardCard,
    unknown,
    CapitalRepairSchedule,
    OptimisticCapitalScheduleContext
  >({
    mutationFn: (schedule) =>
      scheduleCapitalRepair({
        accessToken: accessToken!,
        repairId: schedule.repair.repairId,
        warehouseId: warehouseId!,
        targetDate: schedule.targetDate,
        targetIndex: schedule.targetIndex,
        idempotencyKey: crypto.randomUUID(),
      }),
    onMutate: async (schedule) => {
      const queryKey = driverBoardQueryKey(warehouseId!)
      await queryClient.cancelQueries({ queryKey, exact: true })
      const previousBoard = queryClient.getQueryData<DriverBoard>(queryKey)
      if (previousBoard) {
        queryClient.setQueryData(
          queryKey,
          scheduleCapitalRepairOptimistically(previousBoard, schedule)
        )
      }
      setCommandError(null)
      return { queryKey, previousBoard }
    },
    onSuccess: async (card, schedule, context) => {
      if (context) {
        queryClient.setQueryData<DriverBoard>(context.queryKey, (current) =>
          current
            ? resolveOptimisticCapitalSchedule(current, schedule, card)
            : current
        )
      }
      setCommandError(null)
      await refreshBoard()
    },
    onError: async (error, _schedule, context) => {
      if (context?.previousBoard) {
        queryClient.setQueryData(context.queryKey, context.previousBoard)
      }
      setCommandError(error)
      await refreshBoard()
    },
  })

  const returnCapitalMutation = useMutation({
    mutationFn: (card: DriverBoardCard) =>
      returnCapitalRepair({
        accessToken: accessToken!,
        externalTaskId: card.externalTaskId,
        warehouseId: warehouseId!,
        expectedTaskVersion: card.taskBoardTaskVersion,
      }),
    onSuccess: () => {
      setCommandError(null)
      void refreshBoard()
    },
    onError: handleCommandError,
  })

  const createMutation = useMutation({
    mutationFn: (command: {
      cabinId: string
      comment: string
      priority: number
    }) =>
      createManualMovement({
        accessToken: accessToken!,
        command: {
          warehouseId: warehouseId!,
          cabinId: command.cabinId,
          comment: command.comment,
          priority: command.priority,
          idempotencyKey: crypto.randomUUID(),
        },
      }),
    onSuccess: () => {
      setCommandError(null)
      setManualMovementOpen(false)
      void refreshBoard()
    },
    onError: handleCommandError,
  })

  const pinMutation = useMutation({
    mutationFn: (card: DriverBoardCard) =>
      pinDriverBoardTask({
        accessToken: accessToken!,
        warehouseId: warehouseId!,
        taskId: card.taskBoardTaskId,
        expectedTaskVersion: card.taskBoardTaskVersion,
        pinned: !card.pinned,
      }),
    onSuccess: () => {
      setCommandError(null)
      void refreshBoard()
    },
    onError: handleCommandError,
  })

  const board = boardQuery.data
  const dateColumns = useMemo(
    () => (board ? currentAndFutureDateColumns(board) : []),
    [board]
  )
  const effectiveSelectedScheduledDate =
    board && selectedScheduledDate >= board.currentDate
      ? selectedScheduledDate
      : ""
  const nonEmptyDates = useMemo(
    () =>
      dateColumns
        .map((column) => ({
          ...column,
          allTasks: orderCards(column.tasks),
          tasks: orderCards(column.tasks.filter(isMovementTask)),
        }))
        .filter((column) => column.tasks.length > 0)
        .sort((left, right) => left.date.localeCompare(right.date)),
    [dateColumns]
  )
  const displayedDates = useMemo(
    () =>
      effectiveSelectedScheduledDate
        ? nonEmptyDates.filter(
            (column) => column.date === effectiveSelectedScheduledDate
          )
        : nonEmptyDates,
    [effectiveSelectedScheduledDate, nonEmptyDates]
  )
  const disabled =
    !canEdit ||
    moveMutation.isPending ||
    promoteMutation.isPending ||
    scheduleCapitalMutation.isPending ||
    returnCapitalMutation.isPending ||
    createMutation.isPending ||
    pinMutation.isPending

  function moveItem(
    item: TaskDragItem,
    targetLane: "SCHEDULED" | "CURRENT",
    targetDate: string,
    rawTargetIndex: number
  ) {
    if (!board || disabled) return
    if (targetDate < board.currentDate) {
      setCommandError(new Error("Нельзя перенести задание на прошедшую дату."))
      return
    }
    if (
      targetLane === "CURRENT" &&
      item.lane === "SCHEDULED" &&
      item.card.kind !== "CAPITAL_TO_PRODUCTION"
    ) {
      setCommandError(
        new Error(
          "В текущие задания можно добавить только капитальный ремонт. Обычные запланированные задания остаются на выбранной дате."
        )
      )
      return
    }
    const sourceCards =
      item.lane === "CURRENT"
        ? orderCards(board.current)
        : orderCards(
            dateColumns.find((column) => column.date === item.date)?.tasks ?? []
          )
    const targetCards =
      targetLane === "CURRENT"
        ? orderCards(board.current)
        : orderCards(
            dateColumns.find((column) => column.date === targetDate)?.tasks ??
              []
          )
    const resolvedSourceIndex = sourceCards.findIndex(
      (candidate) => candidate.externalTaskId === item.card.externalTaskId
    )
    const sourceIndex =
      resolvedSourceIndex === undefined || resolvedSourceIndex < 0
        ? item.index
        : resolvedSourceIndex
    let targetIndex = Math.max(0, Math.min(rawTargetIndex, targetCards.length))

    if (
      item.lane === targetLane &&
      (targetLane === "CURRENT" || item.date === targetDate) &&
      sourceIndex >= 0 &&
      sourceIndex < targetIndex
    ) {
      targetIndex -= 1
    }
    if (
      item.lane === targetLane &&
      (targetLane === "CURRENT" || item.date === targetDate) &&
      sourceIndex === targetIndex
    ) {
      return
    }

    moveMutation.mutate({ item, targetLane, targetDate, targetIndex })
  }

  function scheduleCapitalItem(
    repair: CapitalRepairCard,
    targetDate: string,
    targetIndex: number
  ) {
    if (!board || disabled) return
    if (targetDate < board.currentDate) {
      setCommandError(
        new Error("Нельзя поставить капитальный ремонт на прошедшую дату.")
      )
      return
    }
    scheduleCapitalMutation.mutate({
      repair,
      targetDate,
      targetIndex,
      temporaryExternalTaskId: `pending-capital-repair:${repair.repairId}`,
    })
  }

  function handleDragStart(event: DragStartEvent) {
    if (disabled) return
    const item = event.active.data.current as DriverDragItem | undefined
    if (item?.type === "task" || item?.type === "capital") {
      setActiveItem(item)
    }
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveItem(null)
    if (disabled) return
    const item = event.active.data.current as DriverDragItem | undefined
    if (!item) return
    const overData = event.over?.data.current as
      | {
          type?: unknown
          lane?: unknown
          date?: unknown
          index?: unknown
        }
      | undefined

    const overCurrent =
      overData?.lane === "CURRENT" || overData?.type === "current"

    if (item.type === "capital") {
      if (overCurrent) {
        promoteMutation.mutate(item.repair)
        return
      }
      if (!event.over || overData?.type === "new-date") {
        setDatePickerItem(item)
        return
      }
      if (!overData) return
      const targetDate =
        typeof overData.date === "string" && isIsoCalendarDate(overData.date)
          ? overData.date
          : null
      if (!targetDate) return
      const targetColumn = dateColumns.find(
        (column) => column.date === targetDate
      )
      const targetIndex =
        overData.type === "scheduled-slot" &&
        typeof overData.index === "number" &&
        Number.isInteger(overData.index)
          ? overData.index
          : overData.type === "task" &&
              overData.lane === "SCHEDULED" &&
              typeof overData.index === "number" &&
              Number.isInteger(overData.index)
            ? overData.index
            : (targetColumn?.tasks.length ?? 0)
      scheduleCapitalItem(item.repair, targetDate, targetIndex)
      return
    }

    if (overData?.type === "capital-target") {
      if (item.card.kind === "CAPITAL_TO_PRODUCTION") {
        returnCapitalMutation.mutate(item.card)
      }
      return
    }

    if (overCurrent) {
      let currentTargetIndex =
        typeof overData?.index === "number" && Number.isInteger(overData.index)
          ? overData.index
          : (board?.current.length ?? 0)
      if (
        overData?.type === "task" &&
        item.lane === "CURRENT" &&
        item.index < currentTargetIndex
      ) {
        currentTargetIndex += 1
      }
      moveItem(
        item,
        "CURRENT",
        board?.currentDate ?? item.date,
        currentTargetIndex
      )
      return
    }

    if (!event.over || overData?.type === "new-date") {
      setDatePickerItem(item)
      return
    }
    if (!overData) return

    const targetDate =
      typeof overData.date === "string" && isIsoCalendarDate(overData.date)
        ? overData.date
        : null
    if (!targetDate) return
    const targetColumn = dateColumns.find(
      (column) => column.date === targetDate
    )
    let targetIndex =
      overData.type === "scheduled-slot" &&
      typeof overData.index === "number" &&
      Number.isInteger(overData.index)
        ? overData.index
        : overData.type === "task" &&
            overData.lane === "SCHEDULED" &&
            typeof overData.index === "number" &&
            Number.isInteger(overData.index)
          ? overData.index
          : (targetColumn?.tasks.length ?? 0)
    if (
      overData.type === "task" &&
      overData.lane === "SCHEDULED" &&
      item.lane === "SCHEDULED" &&
      item.date === targetDate &&
      item.index < targetIndex
    ) {
      targetIndex += 1
    }

    moveItem(item, "SCHEDULED", targetDate, targetIndex)
  }

  if (!warehouseId) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Перемещение</CardTitle>
          <CardDescription>
            Выберите склад, чтобы открыть очередь перемещений.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (!accessToken || boardQuery.isLoading) return <BoardLoading />

  if (boardQuery.isError || !board) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить перемещения</AlertTitle>
        <AlertDescription>{errorMessage(boardQuery.error)}</AlertDescription>
        <AlertAction>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => void boardQuery.refetch()}
          >
            <HugeiconsIcon
              icon={RefreshIcon}
              data-icon="inline-start"
              aria-hidden="true"
            />
            Повторить
          </Button>
        </AlertAction>
      </Alert>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <label
            htmlFor="driver-board-date-filter"
            className="text-sm font-medium"
          >
            Дата заданий
          </label>
          <SingleDayPicker
            label="Дата заданий"
            hideLabel
            allowClear
            id="driver-board-date-filter"
            className="w-auto"
            min={board.currentDate}
            value={effectiveSelectedScheduledDate}
            onValueChange={(nextValue) => {
              const value = nextValue
              if (!value || value >= board.currentDate) {
                setSelectedScheduledDate(value)
              }
            }}
          />
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!effectiveSelectedScheduledDate}
            onClick={() => setSelectedScheduledDate("")}
          >
            Все даты
          </Button>
        </div>
        {canEdit ? (
          <Button
            type="button"
            size="sm"
            disabled={disabled}
            onClick={() => setManualMovementOpen(true)}
          >
            <HugeiconsIcon
              icon={Add01Icon}
              data-icon="inline-start"
              aria-hidden="true"
            />
            Создать перемещение
          </Button>
        ) : (
          <Badge variant="outline">Только просмотр</Badge>
        )}
      </div>

      {commandError ? (
        <Alert variant="destructive">
          <AlertTitle>Команда не выполнена</AlertTitle>
          <AlertDescription>{errorMessage(commandError)}</AlertDescription>
        </Alert>
      ) : null}

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragStart={handleDragStart}
        onDragCancel={() => setActiveItem(null)}
        onDragEnd={handleDragEnd}
      >
        <div className="grid min-h-0 flex-1 grid-cols-[20rem_minmax(0,1fr)_20rem] overflow-hidden rounded-xl border bg-muted/20">
          <CurrentColumn
            board={board}
            disabled={disabled}
            onPin={(card) => pinMutation.mutate(card)}
          />
          <div
            className="h-full min-w-0 overflow-x-auto p-3"
            aria-label="Запланированные задания по датам"
          >
            <div className="flex min-h-full min-w-full items-stretch gap-3">
              {displayedDates.map((column) => (
                <DateColumn
                  key={column.date}
                  date={column.date}
                  allTasks={column.allTasks}
                  tasks={column.tasks}
                  disabled={disabled}
                  onPin={(card) => pinMutation.mutate(card)}
                />
              ))}
              <NewDateDropTarget disabled={disabled} />
            </div>
          </div>
          <CapitalColumn
            repairs={board.capitalRepairs}
            warehouseId={warehouseId}
            disabled={disabled}
            onPromote={(repair) => promoteMutation.mutate(repair)}
          />
        </div>
        {typeof document !== "undefined"
          ? createPortal(
              <DragOverlay dropAnimation={null}>
                {activeItem ? <DragCardOverlay item={activeItem} /> : null}
              </DragOverlay>,
              document.body
            )
          : null}
      </DndContext>

      <DatePickerDialog
        key={
          datePickerItem
            ? `${datePickerItem.type}:${
                datePickerItem.type === "task"
                  ? datePickerItem.card.externalTaskId
                  : datePickerItem.repair.repairId
              }`
            : "closed"
        }
        item={datePickerItem}
        minimumDate={board.currentDate}
        onClose={() => setDatePickerItem(null)}
        onConfirm={(date) => {
          if (!datePickerItem) return
          const targetColumn = dateColumns.find(
            (column) => column.date === date
          )
          const targetIndex = targetColumn?.tasks.length ?? 0
          if (datePickerItem.type === "capital") {
            scheduleCapitalItem(datePickerItem.repair, date, targetIndex)
            return
          }
          moveItem(datePickerItem, "SCHEDULED", date, targetIndex)
        }}
      />
      {manualMovementOpen ? (
        <ManualMovementDialog
          accessToken={accessToken}
          warehouseId={warehouseId}
          saving={createMutation.isPending}
          onClose={() => {
            if (!createMutation.isPending) setManualMovementOpen(false)
          }}
          onSubmit={(command) => createMutation.mutate(command)}
        />
      ) : null}
    </div>
  )
}
