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
import { Input } from "@/components/ui/input"
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
} from "@/features/logistics/driver-board/driver-board-api"
import type {
  CapitalRepairCard,
  DriverBoard,
  DriverBoardCard,
  DriverTaskKind,
} from "@/features/logistics/driver-board/driver-board-model"
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
  MOVE_TO_SHIPMENT: "Переместить на отгрузку",
  GENERAL_MOVEMENT: "Свободное перемещение",
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

function isTaskMovable(card: DriverBoardCard) {
  return card.taskStatus === "ACTIVE" && card.entryStatus === "WAITING"
}

function DriverTaskCardContent({
  card,
  dragHandle,
  onPin,
  pinDisabled = false,
}: {
  card: DriverBoardCard
  dragHandle?: ReactNode
  onPin?: () => void
  pinDisabled?: boolean
}) {
  return (
    <>
      <CardHeader>
        <CardTitle className="line-clamp-2 pr-10">{taskTitle(card)}</CardTitle>
        <CardDescription>
          Бытовка {card.unitNumber || "без номера"}
        </CardDescription>
        {dragHandle || onPin ? (
          <CardAction className="flex items-center gap-1">
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
        ) : null}
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <p className="text-xs text-muted-foreground">
          {taskKindLabel(card.kind)}
        </p>
        {card.taskText?.trim() && card.taskText.trim() !== card.title.trim() ? (
          <p className="whitespace-pre-wrap text-sm">{card.taskText.trim()}</p>
        ) : null}
        <div className="flex flex-wrap gap-1">
          <Badge
            variant={card.entryStatus === "IN_PROGRESS" ? "default" : "outline"}
          >
            {entryStatusLabels[card.entryStatus]}
          </Badge>
          <Badge variant={card.priority <= 2 ? "default" : "secondary"}>
            Приоритет {card.priority}
          </Badge>
          {card.pinned ? <Badge variant="secondary">Закреплено</Badge> : null}
        </div>
      </CardContent>
    </>
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
      aria-label={`Переместить задание бытовки ${card.unitNumber || "без номера"}`}
    >
      <Card size="sm" className={taskCardClassName(card)}>
        <DriverTaskCardContent
          card={card}
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
      </Card>
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
      aria-label={`Переместить задание бытовки ${card.unitNumber || "без номера"}`}
    >
      <Card size="sm" className={taskCardClassName(card)}>
        <DriverTaskCardContent
          card={card}
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
      </Card>
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
  tasks,
  disabled,
  onPin,
}: {
  date: string
  tasks: DriverBoardCard[]
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
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
        <Badge variant="outline">{tasks.length}</Badge>
      </header>
      <SortableContext
        items={tasks.map((card) => `driver-task:${card.externalTaskId}`)}
        strategy={verticalListSortingStrategy}
      >
        {tasks.map((card, index) => (
          <div key={card.externalTaskId}>
            <InsertionSlot
              lane="SCHEDULED"
              date={date}
              index={index}
              disabled={disabled}
            />
            <ScheduledTaskCard
              card={card}
              date={date}
              index={index}
              disabled={disabled}
              onPin={onPin}
            />
          </div>
        ))}
      </SortableContext>
      <InsertionSlot
        lane="SCHEDULED"
        date={date}
        index={tasks.length}
        disabled={disabled}
      />
    </section>
  )
}

function CapitalCard({
  repair,
  disabled,
  onPromote,
}: {
  repair: CapitalRepairCard
  disabled: boolean
  onPromote: (repair: CapitalRepairCard) => void
}) {
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
      <Card size="sm" className="border-l-4">
        <CardHeader>
          <CardTitle>Бытовка {repair.unitNumber}</CardTitle>
          <CardDescription>
            {Number(repair.plannedMinutes).toLocaleString("ru-RU")} мин.
          </CardDescription>
          <CardAction className="flex items-center gap-1">
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
        <CardContent className="flex flex-wrap gap-1">
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
        </CardContent>
      </Card>
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
  const currentTasks = orderCards(board.current)
  const { setNodeRef, isOver } = useDroppable({
    id: "driver-current",
    disabled,
    data: {
      type: "current",
      lane: "CURRENT",
      date: currentDate,
      index: currentTasks.length,
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
            <p className="text-xs capitalize text-muted-foreground">
              {formatDate(currentDate)}
            </p>
          </div>
          <Badge variant={currentTasks.length > 0 ? "default" : "outline"}>
            {currentTasks.length}
          </Badge>
        </div>
        <div
          className="rounded-lg border bg-muted/35 p-2 text-xs"
          aria-label={`Ремонтные места: ${board.usedRepairPlaceCount} из ${board.repairPlaceCount} используется`}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="font-medium">Ремонтные места</span>
            <Badge
              variant={
                board.repairPlacesOverCapacity ? "destructive" : "outline"
              }
            >
              {board.usedRepairPlaceCount}/{board.repairPlaceCount}
            </Badge>
          </div>
          <p className="mt-1 text-muted-foreground">
            Свободно: {board.availableRepairPlaceCount}
          </p>
          <p className="mt-1 text-muted-foreground">
            Учитываются занятые места и уже назначенные доставки в ремонт.
          </p>
          {board.repairPlacesOverCapacity ? (
            <p className="mt-1 text-destructive">
              Превышена вместимость склада
            </p>
          ) : null}
        </div>
      </header>
      <div className="min-h-44 flex-1 rounded-xl border border-dashed p-2">
        <SortableContext
          items={currentTasks.map((card) => `driver-task:${card.externalTaskId}`)}
          strategy={verticalListSortingStrategy}
        >
          {currentTasks.length > 0 ? (
            <div className="flex flex-col gap-2">
              {currentTasks.map((card, index) => (
                <div key={card.externalTaskId}>
                  <InsertionSlot
                    lane="CURRENT"
                    date={currentDate}
                    index={index}
                    disabled={disabled}
                  />
                  <CurrentTaskCard
                    card={card}
                    date={currentDate}
                    index={index}
                    disabled={disabled}
                    onPin={onPin}
                  />
                </div>
              ))}
              <InsertionSlot
                lane="CURRENT"
                date={currentDate}
                index={currentTasks.length}
                disabled={disabled}
              />
            </div>
          ) : (
            <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground">
              <HugeiconsIcon icon={TruckDeliveryIcon} className="size-6" />
              Водитель ожидает следующее задание
            </div>
          )}
        </SortableContext>
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        Перетащите сюда запланированное перемещение или капитальный ремонт.
        Карточку можно вручную перенести обратно на выбранную дату.
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
  disabled,
  onPromote,
}: {
  repairs: CapitalRepairCard[]
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
        isOver && !disabled && "bg-primary/10 ring-2 ring-inset ring-primary"
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
      <DriverTaskCardContent card={item.card} />
    </Card>
  )
}

function DatePickerDialog({
  item,
  minimumDate,
  onClose,
  onConfirm,
}: {
  item: TaskDragItem | null
  minimumDate: string
  onClose: () => void
  onConfirm: (date: string) => void
}) {
  const [date, setDate] = useState("")
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
              Задание бытовки {item.card.unitNumber || "без номера"} будет
              добавлено в конец очереди выбранной даты.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="driver-board-new-date">Дата</FieldLabel>
              <Input
                id="driver-board-new-date"
                type="date"
                min={minimumDate}
                value={date}
                onChange={(event) => setDate(event.target.value)}
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
    onSubmit({ cabinId, comment: normalizedComment, priority: Number(priority) })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Создать перемещение</DialogTitle>
          <DialogDescription>
            Свободное перемещение не занимает ремонтное место и сразу попадает
            в текущую очередь водителей.
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
          <Button type="button" variant="outline" disabled={saving} onClick={onClose}>
            Отмена
          </Button>
          <Button type="button" disabled={saving || cabinsQuery.isLoading} onClick={submit}>
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
  const [datePickerItem, setDatePickerItem] =
    useState<TaskDragItem | null>(null)
  const [manualMovementOpen, setManualMovementOpen] = useState(false)
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

  const moveMutation = useMutation({
    mutationFn: ({
      item,
      targetLane,
      targetDate,
      targetIndex,
    }: {
      item: TaskDragItem
      targetLane: "SCHEDULED" | "CURRENT"
      targetDate: string
      targetIndex: number
    }) =>
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
    onSuccess: () => {
      setCommandError(null)
      void refreshBoard()
    },
    onError: handleCommandError,
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
  const nonEmptyDates = useMemo(
    () =>
      board?.dates
        .filter((column) => column.tasks.length > 0)
        .map((column) => ({
          ...column,
          tasks: orderCards(column.tasks),
        }))
        .sort((left, right) => left.date.localeCompare(right.date)) ?? [],
    [board?.dates]
  )
  const disabled =
    !canEdit ||
    moveMutation.isPending ||
    promoteMutation.isPending ||
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
    if (
      targetLane === "CURRENT" &&
      item.lane !== "CURRENT" &&
      item.card.kind === "DELIVER_TO_REPAIR" &&
      !board.inboundRepairPlaceAvailable
    ) {
      setCommandError(
        new Error(
          "На складе нет свободного или освобождаемого ремонтного места. Сначала завершите или вывезите текущий ремонт."
        )
      )
      return
    }
    const sourceCards =
      item.lane === "CURRENT"
        ? orderCards(board.current)
        : orderCards(
            board.dates.find((column) => column.date === item.date)?.tasks ?? []
          )
    const targetCards =
      targetLane === "CURRENT"
        ? orderCards(board.current)
        : orderCards(
            board.dates.find((column) => column.date === targetDate)?.tasks ?? []
          )
    const resolvedSourceIndex = sourceCards.findIndex(
      (candidate) => candidate.externalTaskId === item.card.externalTaskId
    )
    const sourceIndex =
      resolvedSourceIndex === undefined || resolvedSourceIndex < 0
        ? item.index
        : resolvedSourceIndex
    let targetIndex = Math.max(
      0,
      Math.min(rawTargetIndex, targetCards.length)
    )

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

    const overCurrent = overData?.lane === "CURRENT" || overData?.type === "current"

    if (item.type === "capital") {
      if (overCurrent) {
        promoteMutation.mutate(item.repair)
      }
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
        typeof overData?.index === "number" &&
        Number.isInteger(overData.index)
          ? overData.index
          : board?.current.length ?? 0
      if (
        overData?.type === "task" &&
        item.lane === "CURRENT" &&
        item.index < currentTargetIndex
      ) {
        currentTargetIndex += 1
      }
      moveItem(item, "CURRENT", board?.currentDate ?? item.date, currentTargetIndex)
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
    const targetColumn = board?.dates.find(
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
            <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
            Повторить
          </Button>
        </AlertAction>
      </Alert>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex items-center justify-end gap-2">
        {canEdit ? (
          <Button
            type="button"
            size="sm"
            disabled={disabled}
            onClick={() => setManualMovementOpen(true)}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
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
              {nonEmptyDates.map((column) => (
                <DateColumn
                  key={column.date}
                  date={column.date}
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
        key={datePickerItem?.card.externalTaskId ?? "closed"}
        item={datePickerItem}
        minimumDate={board.currentDate}
        onClose={() => setDatePickerItem(null)}
        onConfirm={(date) => {
          if (!datePickerItem) return
          const targetColumn = board.dates.find(
            (column) => column.date === date
          )
          const targetIndex = targetColumn?.tasks.length ?? 0
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
