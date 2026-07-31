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
  Calendar03Icon,
  DragDropVerticalIcon,
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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  driverBoardQueryKey,
  getDriverBoard,
  moveDriverBoardTask,
  promoteCapitalRepair,
} from "@/features/logistics/driver-board/driver-board-api"
import type {
  CapitalRepairCard,
  DriverBoard,
  DriverBoardCard,
  DriverTaskKind,
} from "@/features/logistics/driver-board/driver-board-model"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

type ScheduledDragItem = {
  type: "scheduled"
  card: DriverBoardCard
  date: string
  index: number
}

type CapitalDragItem = {
  type: "capital"
  repair: CapitalRepairCard
}

type DriverDragItem = ScheduledDragItem | CapitalDragItem

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
  return card.taskText?.trim() || card.title
}

function taskKindLabel(kind: DriverTaskKind | null) {
  return kind ? kindLabels[kind] : "Логистическое задание"
}

function taskCardClassName(card: DriverBoardCard) {
  return cn(
    card.lane === "CURRENT" && "border-primary bg-primary/5",
    card.entryStatus === "PAUSED" && "bg-muted/60",
    (card.entryStatus === "DONE" || card.entryStatus === "CANCELLED") &&
      "opacity-65"
  )
}

function DriverTaskCardContent({
  card,
  dragHandle,
}: {
  card: DriverBoardCard
  dragHandle?: ReactNode
}) {
  return (
    <>
      <CardHeader>
        <CardTitle className="line-clamp-2 pr-10">{taskTitle(card)}</CardTitle>
        <CardDescription>
          Бытовка {card.unitNumber || "без номера"}
        </CardDescription>
        {dragHandle ? <CardAction>{dragHandle}</CardAction> : null}
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-xs text-muted-foreground">
          {taskKindLabel(card.kind)}
        </p>
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

function CurrentTaskCard({ card }: { card: DriverBoardCard }) {
  return (
    <Card
      size="sm"
      className={taskCardClassName(card)}
      data-testid={`current-task-${card.externalTaskId}`}
    >
      <DriverTaskCardContent card={card} />
    </Card>
  )
}

function ScheduledTaskCard({
  card,
  date,
  index,
  disabled,
}: {
  card: DriverBoardCard
  date: string
  index: number
  disabled: boolean
}) {
  const dragItem: ScheduledDragItem = {
    type: "scheduled",
    card,
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
    disabled,
    data: dragItem,
  })
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
      className={taskCardClassName(card)}
      data-testid={`scheduled-task-${card.externalTaskId}`}
    >
      <DriverTaskCardContent
        card={card}
        dragHandle={
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            className="touch-none"
            disabled={disabled}
            aria-label={`Переместить задание бытовки ${card.unitNumber || "без номера"}`}
            {...attributes}
            {...listeners}
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </Button>
        }
      />
    </Card>
  )
}

function InsertionSlot({
  date,
  index,
  disabled,
}: {
  date: string
  index: number
  disabled: boolean
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `driver-slot:${date}:${index}`,
    disabled,
    data: { type: "scheduled-slot", date, index },
  })

  return (
    <div
      ref={setNodeRef}
      aria-label={`Вставить на позицию ${index + 1} за ${formatDate(date)}`}
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
}: {
  date: string
  tasks: DriverBoardCard[]
  disabled: boolean
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
            <InsertionSlot date={date} index={index} disabled={disabled} />
            <ScheduledTaskCard
              card={card}
              date={date}
              index={index}
              disabled={disabled}
            />
          </div>
        ))}
      </SortableContext>
      <InsertionSlot date={date} index={tasks.length} disabled={disabled} />
    </section>
  )
}

function CapitalCard({
  repair,
  disabled,
}: {
  repair: CapitalRepairCard
  disabled: boolean
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
    <Card
      ref={setNodeRef}
      style={style}
      size="sm"
      className="border-l-4"
      data-testid={`capital-repair-${repair.repairId}`}
    >
      <CardHeader>
        <CardTitle>Бытовка {repair.unitNumber}</CardTitle>
        <CardDescription>
          {Number(repair.plannedMinutes).toLocaleString("ru-RU")} мин.
        </CardDescription>
        <CardAction>
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            className="touch-none"
            disabled={disabled}
            aria-label={`Переместить капитальный ремонт бытовки ${repair.unitNumber} в текущее задание`}
            {...attributes}
            {...listeners}
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </Button>
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
      className={cn(
        "flex h-40 w-64 shrink-0 flex-col items-center justify-center gap-2 rounded-xl border border-dashed bg-muted/30 p-4 text-center text-sm text-muted-foreground",
        isOver && "border-primary bg-primary/8 text-primary"
      )}
    >
      <HugeiconsIcon icon={Calendar03Icon} className="size-5" />
      Перетащите сюда, чтобы выбрать другую дату
    </div>
  )
}

function CurrentColumn({
  board,
  disabled,
}: {
  board: DriverBoard
  disabled: boolean
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: "driver-current",
    disabled,
    data: { type: "current" },
  })
  const current = board.current[0] ?? null

  return (
    <section
      ref={setNodeRef}
      aria-label="Текущее задание"
      className={cn(
        "sticky left-0 z-20 flex min-h-0 flex-col border-r bg-background p-3",
        isOver && "bg-primary/8 ring-2 ring-primary/40 ring-inset"
      )}
    >
      <header className="mb-3 space-y-2">
        <div className="flex items-center justify-between gap-2">
          <h2 className="font-heading font-semibold">Текущее задание</h2>
          <Badge variant={current ? "default" : "outline"}>
            {current ? 1 : 0}
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
              {board.occupiedRepairPlaceCount}/{board.repairPlaceCount}
            </Badge>
          </div>
          <p className="mt-1 text-muted-foreground">
            Свободно: {board.availableRepairPlaceCount}
          </p>
          {board.repairPlacesOverCapacity ? (
            <p className="mt-1 text-destructive">
              Превышена вместимость склада
            </p>
          ) : null}
        </div>
      </header>
      <div className="min-h-44 flex-1 rounded-xl border border-dashed p-2">
        {current ? (
          <CurrentTaskCard card={current} />
        ) : (
          <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground">
            <HugeiconsIcon icon={TruckDeliveryIcon} className="size-6" />
            Водитель ожидает следующее задание
          </div>
        )}
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        Перетащите сюда капитальный ремонт для отправки на производство.
      </p>
    </section>
  )
}

function CapitalColumn({
  repairs,
  disabled,
}: {
  repairs: CapitalRepairCard[]
  disabled: boolean
}) {
  return (
    <section
      aria-label="Капитальные ремонты"
      className="sticky right-0 z-20 flex min-h-0 flex-col border-l bg-background p-3"
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
          />
        ))}
        {repairs.length === 0 ? (
          <p className="rounded-xl border border-dashed p-4 text-center text-sm text-muted-foreground">
            Капитальных ремонтов нет
          </p>
        ) : null}
      </div>
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
  onClose,
  onConfirm,
}: {
  item: ScheduledDragItem | null
  onClose: () => void
  onConfirm: (date: string) => void
}) {
  const [date, setDate] = useState("")
  const canSubmit = Boolean(item && isIsoCalendarDate(date))

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

function BoardLoading() {
  return (
    <Card aria-busy="true">
      <CardHeader>
        <CardTitle>Загрузка логистической очереди</CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        Получаем текущее задание, даты и капитальные ремонты…
      </CardContent>
    </Card>
  )
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить команду логистики."
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
    useState<ScheduledDragItem | null>(null)
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
    setCommandError(error)
    if (error instanceof ApiError && error.status === 409) {
      void refreshBoard()
    }
  }

  const moveMutation = useMutation({
    mutationFn: ({
      item,
      targetDate,
      targetIndex,
    }: {
      item: ScheduledDragItem
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

  const board = boardQuery.data
  const nonEmptyDates = useMemo(
    () => board?.dates.filter((column) => column.tasks.length > 0) ?? [],
    [board?.dates]
  )
  const disabled =
    !canEdit || moveMutation.isPending || promoteMutation.isPending

  function moveItem(
    item: ScheduledDragItem,
    targetDate: string,
    rawTargetIndex: number
  ) {
    if (!board || disabled) return
    const sourceColumn = board.dates.find((column) => column.date === item.date)
    const targetColumn = board.dates.find(
      (column) => column.date === targetDate
    )
    const sourceIndex =
      sourceColumn?.tasks.findIndex(
        (candidate) => candidate.externalTaskId === item.card.externalTaskId
      ) ?? item.index
    let targetIndex = Math.max(
      0,
      Math.min(rawTargetIndex, targetColumn?.tasks.length ?? 0)
    )

    if (
      item.date === targetDate &&
      sourceIndex >= 0 &&
      sourceIndex < targetIndex
    ) {
      targetIndex -= 1
    }
    if (item.date === targetDate && sourceIndex === targetIndex) return

    moveMutation.mutate({ item, targetDate, targetIndex })
  }

  function handleDragStart(event: DragStartEvent) {
    if (disabled) return
    const item = event.active.data.current as DriverDragItem | undefined
    if (item?.type === "scheduled" || item?.type === "capital") {
      setActiveItem(item)
    }
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveItem(null)
    if (disabled) return
    const item = event.active.data.current as DriverDragItem | undefined
    if (!item) return
    const overData = event.over?.data.current as
      { type?: unknown; date?: unknown; index?: unknown } | undefined

    if (item.type === "capital") {
      if (overData?.type === "current") {
        promoteMutation.mutate(item.repair)
      }
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
    const targetIndex =
      overData.type === "scheduled-slot" &&
      typeof overData.index === "number" &&
      Number.isInteger(overData.index)
        ? overData.index
        : (targetColumn?.tasks.length ?? 0)

    moveItem(item, targetDate, targetIndex)
  }

  if (!warehouseId) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Задания водителей</CardTitle>
          <CardDescription>
            Выберите склад, чтобы открыть логистическую очередь.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (!accessToken || boardQuery.isLoading) return <BoardLoading />

  if (boardQuery.isError || !board) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить задания водителей</AlertTitle>
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
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="font-heading text-xl font-semibold">
            Задания водителей
          </h1>
          <p className="text-sm text-muted-foreground">
            Текущее перемещение, запланированные даты и капитальные ремонты.
          </p>
        </div>
        {!canEdit ? <Badge variant="outline">Только просмотр</Badge> : null}
      </header>

      {commandError ? (
        <Alert
          variant={
            commandError instanceof ApiError && commandError.status === 409
              ? "default"
              : "destructive"
          }
        >
          <AlertTitle>
            {commandError instanceof ApiError && commandError.status === 409
              ? "Очередь уже изменилась"
              : "Команда не выполнена"}
          </AlertTitle>
          <AlertDescription>
            {commandError instanceof ApiError && commandError.status === 409
              ? "Данные обновляются. Повторите перенос после загрузки актуальной очереди."
              : errorMessage(commandError)}
          </AlertDescription>
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
          <CurrentColumn board={board} disabled={disabled} />
          <div
            className="min-w-0 overflow-x-auto p-3"
            aria-label="Запланированные задания по датам"
          >
            <div className="flex min-w-max items-start gap-3">
              {nonEmptyDates.map((column) => (
                <DateColumn
                  key={column.date}
                  date={column.date}
                  tasks={column.tasks}
                  disabled={disabled}
                />
              ))}
              <NewDateDropTarget disabled={disabled} />
            </div>
          </div>
          <CapitalColumn repairs={board.capitalRepairs} disabled={disabled} />
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
        onClose={() => setDatePickerItem(null)}
        onConfirm={(date) => {
          if (!datePickerItem) return
          const targetColumn = board.dates.find(
            (column) => column.date === date
          )
          const targetIndex = (targetColumn?.tasks ?? []).filter(
            (card) => card.externalTaskId !== datePickerItem.card.externalTaskId
          ).length
          moveItem(datePickerItem, date, targetIndex)
        }}
      />
    </div>
  )
}
