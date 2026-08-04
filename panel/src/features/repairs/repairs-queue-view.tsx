import {
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
  type SyntheticEvent,
} from "react"
import { createPortal } from "react-dom"
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  pointerWithin,
  PointerSensor,
  TouchSensor,
  useDroppable,
  useSensor,
  useSensors,
  type CollisionDetection,
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
  DragDropVerticalIcon,
  InformationCircleIcon,
  PinIcon,
  PinOffIcon,
} from "@hugeicons/core-free-icons"

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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import type {
  TaskBoardEntryDto,
  TaskBoardEntryStatus,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { cn } from "@/lib/utils"

const statusLabels: Record<TaskBoardEntryStatus, string> = {
  WAITING: "Ожидает",
  IN_PROGRESS: "В работе",
  PAUSED: "На паузе",
  DONE: "Завершено",
  CANCELLED: "Отменено",
}

function dropSlotId(date: string, index: number) {
  return `repair-slot:${date}:${index}`
}

function collisionType(
  collision: ReturnType<CollisionDetection>[number] | undefined
) {
  return collision?.data?.droppableContainer?.data.current?.type as
    string | undefined
}

const repairQueueCollisionDetection: CollisionDetection = (args) => {
  const collisions = args.pointerCoordinates
    ? pointerWithin(args)
    : closestCenter(args)
  const orderedCollisions = [...collisions].sort((left, right) => {
    const rank = (type: string | undefined) => {
      if (type === "repair-slot") return 0
      if (type === "repair") return 1
      if (type === "date") return 2
      return 3
    }
    return rank(collisionType(left)) - rank(collisionType(right))
  })
  const closestCollision = orderedCollisions[0]
  const closestContainer = closestCollision?.data?.droppableContainer
  const closestData = closestContainer?.data.current

  if (
    closestData?.type !== "repair" ||
    typeof closestData.date !== "string" ||
    typeof closestData.index !== "number"
  ) {
    return orderedCollisions
  }

  const closestRect = closestContainer.rect.current
  if (!closestRect) return orderedCollisions
  const verticalCenter =
    args.pointerCoordinates?.y ??
    args.collisionRect.top + args.collisionRect.height / 2
  const slotIndex =
    closestData.index +
    Number(verticalCenter > closestRect.top + closestRect.height / 2)
  const slot = args.droppableContainers.find(
    (container) =>
      !container.disabled &&
      container.id === dropSlotId(closestData.date, slotIndex)
  )
  if (!slot) return orderedCollisions

  return [
    {
      id: slot.id,
      data: {
        ...closestCollision.data,
        droppableContainer: slot,
      },
    },
  ]
}

type RepairQueueItem = {
  id: string
  repair: RepairTaskDto
  subtask: RepairTaskSubtaskDto
  entry: TaskBoardEntryDto
  queue: TaskBoardQueueDto
  date: string
  boardOrder: number
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "long",
    day: "2-digit",
    month: "long",
  }).format(new Date(`${value}T00:00:00`))
}

function isIsoCalendarDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00.000Z`)
  return (
    !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
  )
}

function repairKindLabel(repair: RepairTaskDto) {
  if (repair.kind === "REWORK") return "Доработка"
  if (repair.origin === "ESTIMATE") return "Ремонт по смете"
  if (repair.origin === "INVENTORY") return "Ремонт по инвентаризации"
  return "Прямой ремонт"
}

function taskLabel(item: RepairQueueItem) {
  if (item.entry.taskText) return item.entry.taskText
  if (item.entry.title) return item.entry.title
  if (item.subtask.taskText) return item.subtask.taskText
  if (item.subtask.taskTitle) return item.subtask.taskTitle
  return item.repair.kind === "REWORK" ? "Доработка" : "Ремонтные работы"
}

function operationalSubtask(repair: RepairTaskDto) {
  return (
    repair.subtasks
      .filter(
        (subtask) => subtask.status !== "DONE" && subtask.status !== "CANCELLED"
      )
      .sort((left, right) => {
        const statusOrder =
          Number(right.status === "IN_PROGRESS") -
          Number(left.status === "IN_PROGRESS")
        if (statusOrder !== 0) return statusOrder
        const entryTypeOrder =
          Number(right.entryType === "REAL") - Number(left.entryType === "REAL")
        return entryTypeOrder || left.sortOrder - right.sortOrder
      })[0] ??
    repair.subtasks
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)[0] ??
    null
  )
}

function isImmovable(entry: TaskBoardEntryDto) {
  return (
    entry.status === "IN_PROGRESS" ||
    entry.status === "PAUSED" ||
    entry.status === "DONE" ||
    entry.status === "CANCELLED"
  )
}

function minimumInsertionIndex(items: RepairQueueItem[]) {
  const firstMovableIndex = items.findIndex(
    (candidate) => !isImmovable(candidate.entry)
  )
  return firstMovableIndex < 0 ? items.length : firstMovableIndex
}

function cabinLabel(item: RepairQueueItem) {
  return item.repair.cabinNumber || "без номера"
}

function insertionSlotLabel(items: RepairQueueItem[], index: number) {
  const before = items[index - 1]
  const after = items[index]

  if (!before && !after) return "Вставить первым заданием"
  if (!before) return `Вставить перед бытовкой ${cabinLabel(after)}`
  if (!after) return `Вставить после бытовки ${cabinLabel(before)}`
  return `Вставить между бытовками ${cabinLabel(before)} и ${cabinLabel(after)}`
}

function queueCardClassName(item: RepairQueueItem) {
  return cn(
    item.entry.status === "IN_PROGRESS" && "border-primary bg-primary/5",
    item.entry.status === "PAUSED" && "bg-muted/60",
    (item.entry.status === "DONE" || item.entry.status === "CANCELLED") &&
      "opacity-65"
  )
}

/**
 * The sortable listeners live on the card itself so a user can start a drag
 * from any part of the card.  Action buttons are still real controls and
 * must not bubble their pointer/keyboard events into that listener.
 */
function stopCardDrag(event: SyntheticEvent) {
  event.stopPropagation()
}

function QueueCardContent({
  item,
  disabled,
  dragHandle,
  onPin,
  onInfo,
}: {
  item: RepairQueueItem
  disabled: boolean
  dragHandle: ReactNode
  onPin?: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onInfo?: (item: RepairQueueItem) => void
}) {
  return (
    <>
      <CardHeader>
        <CardTitle className="line-clamp-2 pr-16">{taskLabel(item)}</CardTitle>
        <CardDescription>
          Бытовка {item.repair.cabinNumber || "без номера"}
        </CardDescription>
        <CardAction className="flex items-center gap-1">
          <Button
            type="button"
            size="icon-sm"
            variant={item.entry.pinned ? "secondary" : "ghost"}
            disabled={disabled}
            aria-pressed={item.entry.pinned}
            aria-label={
              item.entry.pinned
                ? `Открепить задание бытовки ${item.repair.cabinNumber}`
                : `Закрепить задание бытовки ${item.repair.cabinNumber}`
            }
            onPointerDown={stopCardDrag}
            onKeyDown={stopCardDrag}
            onClick={(event) => {
              stopCardDrag(event)
              onPin?.(item.entry, !item.entry.pinned)
            }}
          >
            <HugeiconsIcon icon={item.entry.pinned ? PinOffIcon : PinIcon} />
          </Button>
          {dragHandle}
        </CardAction>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap gap-1">
          <Badge variant="destructive">На ремонте</Badge>
          <Badge
            variant={
              item.entry.status === "IN_PROGRESS" ? "default" : "outline"
            }
          >
            {statusLabels[item.entry.status]}
          </Badge>
          <Badge variant={item.entry.priority <= 2 ? "default" : "secondary"}>
            Приоритет {item.entry.priority}
          </Badge>
          <Badge variant="outline">№ {item.entry.queuePosition + 1}</Badge>
          {item.entry.pinned ? (
            <Badge variant="secondary">Закреплено</Badge>
          ) : null}
        </div>
      </CardContent>
      <CardFooter className="justify-end">
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={disabled}
          onPointerDown={stopCardDrag}
          onKeyDown={stopCardDrag}
          onClick={(event) => {
            stopCardDrag(event)
            onInfo?.(item)
          }}
        >
          <HugeiconsIcon
            icon={InformationCircleIcon}
            data-icon="inline-start"
          />
          Инфо
        </Button>
      </CardFooter>
    </>
  )
}

function createQueueItems(
  boards: TaskBoardSnapshotDto[],
  repairs: RepairTaskDto[]
) {
  const entriesByStage = new Map<
    string,
    {
      entry: TaskBoardEntryDto
      queue: TaskBoardQueueDto
      boardOrder: number
    }
  >()

  boards.forEach((board) => {
    let boardOrder = 0
    board.queues.forEach((queue) => {
      queue.entries.forEach((entry) => {
        if (entry.externalTaskId) {
          entriesByStage.set(`${entry.externalTaskId}:${entry.routeIndex}`, {
            entry,
            queue,
            boardOrder,
          })
        }
        boardOrder += 1
      })
    })
  })

  return repairs.flatMap((repair): RepairQueueItem[] => {
    const subtask = operationalSubtask(repair)
    if (!subtask?.externalTaskId) return []
    const stage = entriesByStage.get(
      `${subtask.externalTaskId}:${subtask.sortOrder}`
    )
    if (!stage) return []
    return [
      {
        id: `repair:${repair.id}`,
        repair,
        subtask,
        entry: stage.entry,
        queue: stage.queue,
        date: stage.entry.scheduledDate,
        boardOrder: stage.boardOrder,
      },
    ]
  })
}

function QueueCard({
  item,
  index,
  disabled,
  onPin,
  onInfo,
}: {
  item: RepairQueueItem
  index: number
  disabled: boolean
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onInfo: (item: RepairQueueItem) => void
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: item.id,
    disabled: disabled || isImmovable(item.entry),
    data: {
      type: "repair",
      item,
      date: item.date,
      index,
    },
  })
  const draggable = !disabled && !isImmovable(item.entry)
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0 : 1,
  }

  return (
    <Card
      ref={setNodeRef}
      style={style}
      size="sm"
      data-dragging={isDragging || undefined}
      data-dnd-draggable={draggable || undefined}
      aria-label={`Задание бытовки ${item.repair.cabinNumber || "без номера"}`}
      {...(draggable ? attributes : {})}
      {...(draggable ? listeners : {})}
      className={cn(
        queueCardClassName(item),
        draggable && "cursor-grab touch-none",
        isDragging && "cursor-grabbing"
      )}
    >
      <QueueCardContent
        item={item}
        disabled={disabled}
        onPin={onPin}
        onInfo={onInfo}
        dragHandle={
          <span
            data-dnd-handle
            aria-hidden="true"
            className="inline-flex size-8 items-center justify-center text-muted-foreground"
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </span>
        }
      />
    </Card>
  )
}

function InsertionSlot({
  date,
  index,
  label,
  disabled,
}: {
  date: string
  index: number
  label: string
  disabled: boolean
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: dropSlotId(date, index),
    disabled,
    data: {
      type: "repair-slot",
      date,
      index,
    },
  })

  return (
    <div
      ref={setNodeRef}
      aria-label={label}
      data-insertion-slot={index}
      data-insertion-active={isOver || undefined}
      data-insertion-disabled={disabled || undefined}
      className={cn(
        "relative flex h-3 shrink-0 items-center justify-center overflow-hidden rounded-md transition-[height,background-color,border-color] duration-150 ease-out",
        isOver && "h-14 border border-dashed border-primary/60 bg-primary/8"
      )}
    >
      <span
        className={cn(
          "absolute inset-x-3 top-1/2 h-0.5 -translate-y-1/2 rounded-full bg-primary opacity-0 transition-opacity",
          isOver && "opacity-100"
        )}
      />
      {isOver ? (
        <span
          role="status"
          className="relative z-10 rounded-full bg-card px-2 py-1 text-center text-xs font-medium text-primary shadow-sm"
        >
          {label}
        </span>
      ) : null}
    </div>
  )
}

function QueueCardOverlay({ item }: { item: RepairQueueItem }) {
  return (
    <Card
      size="sm"
      aria-hidden="true"
      className={cn(
        queueCardClassName(item),
        "pointer-events-none cursor-grabbing shadow-lg"
      )}
    >
      <QueueCardContent
        item={item}
        disabled
        dragHandle={
          <Button type="button" size="icon-sm" variant="ghost" disabled>
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </Button>
        }
      />
    </Card>
  )
}

function DayColumn({
  date,
  items,
  activeItem,
  disabled,
  onPin,
  onInfo,
}: {
  date: string
  items: RepairQueueItem[]
  activeItem: RepairQueueItem | null
  disabled: boolean
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onInfo: (item: RepairQueueItem) => void
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `date:${date}`,
    disabled: disabled || !activeItem,
    data: { type: "date", date },
  })
  const minimumIndex = minimumInsertionIndex(items)

  return (
    <section
      ref={setNodeRef}
      className={cn(
        "flex h-fit w-80 shrink-0 flex-col gap-3 rounded-lg border bg-card p-3",
        isOver && "ring-2 ring-primary/40"
      )}
      aria-label={`Задания на ${formatDate(date)}`}
    >
      <header className="sticky top-0 z-10 flex items-center justify-between gap-2 bg-card pb-1">
        <h2 className="font-heading text-sm font-medium capitalize">
          {formatDate(date)}
        </h2>
        <Badge variant="outline">{items.length}</Badge>
      </header>
      <div className="flex min-h-20 flex-col rounded-lg">
        <SortableContext
          items={items.map((item) => item.id)}
          strategy={verticalListSortingStrategy}
        >
          {items.map((item, index) => (
            <div key={item.id} className="contents">
              <InsertionSlot
                date={date}
                index={index}
                label={insertionSlotLabel(items, index)}
                disabled={disabled || !activeItem || index < minimumIndex}
              />
              <QueueCard
                item={item}
                index={index}
                disabled={disabled}
                onPin={onPin}
                onInfo={onInfo}
              />
            </div>
          ))}
        </SortableContext>
        <InsertionSlot
          date={date}
          index={items.length}
          label={insertionSlotLabel(items, items.length)}
          disabled={disabled || !activeItem || items.length < minimumIndex}
        />
        {items.length === 0 ? (
          <p className="p-3 text-center text-xs text-muted-foreground">
            Заданий нет
          </p>
        ) : null}
      </div>
    </section>
  )
}

function TaskInfoDialog({
  item,
  onClose,
  onOpenRepair,
}: {
  item: RepairQueueItem | null
  onClose: () => void
  onOpenRepair: (repairId: string) => void
}) {
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
            <DialogTitle>
              Задание бытовки {item.repair.cabinNumber || "без номера"}
            </DialogTitle>
            <DialogDescription>
              {repairKindLabel(item.repair)}
            </DialogDescription>
          </DialogHeader>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted-foreground">Задание</dt>
            <dd>{taskLabel(item)}</dd>
            <dt className="text-muted-foreground">Дата</dt>
            <dd className="capitalize">{formatDate(item.date)}</dd>
            <dt className="text-muted-foreground">Приоритет</dt>
            <dd>{item.entry.priority}</dd>
            <dt className="text-muted-foreground">Статус</dt>
            <dd>{statusLabels[item.entry.status]}</dd>
            <dt className="text-muted-foreground">Позиция</dt>
            <dd>{item.entry.queuePosition + 1}</dd>
            <dt className="text-muted-foreground">Очередь</dt>
            <dd>{item.queue.label}</dd>
            <dt className="text-muted-foreground">Закрепление</dt>
            <dd>{item.entry.pinned ? "Закреплено" : "Не закреплено"}</dd>
          </dl>
          <DialogFooter>
            <Button
              type="button"
              onClick={() => {
                onClose()
                onOpenRepair(item.repair.id)
              }}
            >
              Открыть ремонт
            </Button>
          </DialogFooter>
        </DialogContent>
      ) : null}
    </Dialog>
  )
}

function EmptyDropDialog({
  item,
  dates,
  onClose,
  onMove,
}: {
  item: RepairQueueItem | null
  dates: string[]
  onClose: () => void
  onMove: (targetDate: string) => void
}) {
  const [existingDate, setExistingDate] = useState("")
  const [newDate, setNewDate] = useState("")
  const targetDate = newDate || existingDate
  const canMove = Boolean(item && isIsoCalendarDate(targetDate))

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
            <DialogTitle>Выберите дату перемещения</DialogTitle>
            <DialogDescription>
              Для задания «{taskLabel(item)}» не удалось определить дату после
              перетаскивания.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="repair-empty-drop-existing-date">
                Дата из очереди
              </FieldLabel>
              <Select
                value={existingDate}
                onValueChange={(value) => {
                  setExistingDate(value)
                  setNewDate("")
                }}
              >
                <SelectTrigger
                  id="repair-empty-drop-existing-date"
                  className="w-full"
                >
                  <SelectValue placeholder="Выберите дату" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {dates.map((date) => (
                      <SelectItem key={date} value={date}>
                        {formatDate(date)}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="repair-empty-drop-new-date">
                Новая дата
              </FieldLabel>
              <Input
                id="repair-empty-drop-new-date"
                type="date"
                value={newDate}
                onChange={(event) => {
                  setNewDate(event.target.value)
                  setExistingDate("")
                }}
              />
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
            <Button
              type="button"
              disabled={!canMove}
              onClick={() => {
                if (!canMove) return
                onMove(targetDate)
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

export function RepairsQueueView({
  boards,
  repairs,
  disabled,
  onMove,
  onPin,
  onOpen,
}: {
  boards: TaskBoardSnapshotDto[]
  repairs: RepairTaskDto[]
  disabled: boolean
  onMove: (params: {
    entry: TaskBoardEntryDto
    queue: TaskBoardQueueDto
    targetIndex: number
    targetDate: string
  }) => void
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onOpen: (repairId: string) => void
}) {
  const [infoItem, setInfoItem] = useState<RepairQueueItem | null>(null)
  const [emptyDropItem, setEmptyDropItem] = useState<RepairQueueItem | null>(
    null
  )
  const [activeItem, setActiveItem] = useState<RepairQueueItem | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 220, tolerance: 6 },
    }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )
  const queueItems = useMemo(
    () => createQueueItems(boards, repairs),
    [boards, repairs]
  )
  const itemsByDate = useMemo(() => {
    const result = new Map<string, RepairQueueItem[]>()
    queueItems.forEach((item) => {
      const items = result.get(item.date) ?? []
      items.push(item)
      result.set(item.date, items)
    })
    result.forEach((items) => {
      items.sort(
        (left, right) =>
          left.entry.queuePosition - right.entry.queuePosition ||
          left.boardOrder - right.boardOrder ||
          left.repair.id.localeCompare(right.repair.id)
      )
    })
    return result
  }, [queueItems])
  const dates = [...itemsByDate.keys()].sort()

  function handleDragStart(event: DragStartEvent) {
    if (disabled) return
    const item = event.active.data.current?.item as RepairQueueItem | undefined
    if (!item || isImmovable(item.entry)) return
    setActiveItem(item)
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveItem(null)
    if (disabled) return
    const item = event.active.data.current?.item as RepairQueueItem | undefined
    if (!item || isImmovable(item.entry)) return

    const over = event.over
    const overData = over?.data.current as
      | {
          type?: unknown
          item?: RepairQueueItem
          date?: unknown
          index?: unknown
        }
      | undefined
    const overItem = overData?.item
    const rawTargetDate = overItem?.date ?? overData?.date
    const targetDate =
      typeof rawTargetDate === "string" && isIsoCalendarDate(rawTargetDate)
        ? rawTargetDate
        : undefined
    if (!targetDate || !over) {
      setEmptyDropItem(item)
      return
    }
    const targetItems = itemsByDate.get(targetDate) ?? []
    let targetIndex: number
    if (
      overData?.type === "repair-slot" &&
      typeof overData.index === "number" &&
      Number.isInteger(overData.index)
    ) {
      targetIndex = Math.min(targetItems.length, Math.max(0, overData.index))
    } else {
      const hoveredIndex = overItem
        ? targetItems.findIndex((candidate) => candidate.id === overItem.id)
        : targetItems.length
      const activeRect =
        event.active.rect.current.translated ??
        event.active.rect.current.initial
      const placeAfter =
        overItem &&
        activeRect &&
        activeRect.top + activeRect.height / 2 >
          over.rect.top + over.rect.height / 2
      targetIndex =
        hoveredIndex < 0
          ? targetItems.length
          : hoveredIndex + (placeAfter ? 1 : 0)
    }

    const minimumIndex = minimumInsertionIndex(targetItems)
    targetIndex = Math.max(minimumIndex, targetIndex)

    const sourceItems = itemsByDate.get(item.date) ?? []
    const sourceIndex = sourceItems.findIndex(
      (candidate) => candidate.id === item.id
    )
    if (
      item.date === targetDate &&
      (sourceIndex === targetIndex || sourceIndex + 1 === targetIndex)
    ) {
      return
    }
    if (
      item.date === targetDate &&
      sourceIndex >= 0 &&
      sourceIndex < targetIndex
    ) {
      targetIndex -= 1
    }
    targetIndex = Math.max(minimumIndex, targetIndex)

    onMove({
      entry: item.entry,
      queue: item.queue,
      targetIndex,
      targetDate,
    })
  }

  function handleEmptyDropMove(targetDate: string) {
    if (!emptyDropItem) return
    const targetItems = itemsByDate.get(targetDate) ?? []

    onMove({
      entry: emptyDropItem.entry,
      queue: emptyDropItem.queue,
      targetIndex: targetItems.filter(
        (candidate) => candidate.id !== emptyDropItem.id
      ).length,
      targetDate,
    })
  }

  if (dates.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Очереди пока пусты</CardTitle>
        </CardHeader>
        <CardContent className="text-muted-foreground">
          После постановки ремонта в очередь здесь появятся даты и задания.
        </CardContent>
      </Card>
    )
  }

  return (
    <>
      <DndContext
        sensors={sensors}
        collisionDetection={repairQueueCollisionDetection}
        onDragStart={handleDragStart}
        onDragCancel={() => setActiveItem(null)}
        onDragEnd={handleDragEnd}
      >
        <div className="flex h-full min-h-0 gap-3 overflow-x-auto pb-3">
          {dates.map((date) => (
            <DayColumn
              key={date}
              date={date}
              items={itemsByDate.get(date) ?? []}
              activeItem={activeItem}
              disabled={disabled}
              onPin={onPin}
              onInfo={setInfoItem}
            />
          ))}
        </div>
        {typeof document !== "undefined"
          ? createPortal(
              <DragOverlay dropAnimation={null}>
                {activeItem ? <QueueCardOverlay item={activeItem} /> : null}
              </DragOverlay>,
              document.body
            )
          : null}
      </DndContext>
      <TaskInfoDialog
        item={infoItem}
        onClose={() => setInfoItem(null)}
        onOpenRepair={onOpen}
      />
      <EmptyDropDialog
        key={emptyDropItem?.id ?? "empty-drop-closed"}
        item={emptyDropItem}
        dates={dates}
        onClose={() => setEmptyDropItem(null)}
        onMove={handleEmptyDropMove}
      />
    </>
  )
}
