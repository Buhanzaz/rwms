import { SingleDayPicker } from "@/components/ui/single-day-picker"
import { useMemo, useState, type CSSProperties } from "react"
import { createPortal } from "react-dom"
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  TouchSensor,
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
import {
  ChevronDownIcon,
  DragDropVerticalIcon,
  PinIcon,
  PinOffIcon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
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
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  driverBoardQueryKey,
  getDriverBoard,
  moveDriverBoardTask,
  pinDriverBoardTask,
} from "@/features/logistics/driver-board/driver-board-api"
import type {
  DriverBoard,
  DriverBoardCard,
} from "@/features/logistics/driver-board/driver-board-model"
import {
  DriverTripDetailsDialog,
  DriverTripDetailsView,
} from "@/features/logistics/driver-board/driver-trip-details"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"

/** Drag metadata that fences a logistics card to one driver queue. */
type LogisticsDragItem = {
  type: "logistics-task"
  card: DriverBoardCard
  lane: DriverBoardCard["lane"]
  date: string
  index: number
  localIndex: number
  sectionKey: string
}

/** Same-queue move submitted to the logistics board endpoint. */
type LogisticsMove = {
  item: LogisticsDragItem
  targetIndex: number
}

/** Driver section rendered inside one calendar-date column. */
type LogisticsDriverSection = {
  key: string
  label: string
  description: string
  currentTasks: DriverBoardCard[]
  scheduledTasks: DriverBoardCard[]
}

/** Date projection that retains unrendered source cards for queue indexes. */
type LogisticsDateColumn = {
  date: string
  allCurrentTasks: DriverBoardCard[]
  allScheduledTasks: DriverBoardCard[]
  currentTasks: DriverBoardCard[]
  scheduledTasks: DriverBoardCard[]
}

/** Cache snapshot used to roll back a rejected optimistic reorder. */
type OptimisticMoveContext = {
  queryKey: ReturnType<typeof driverBoardQueryKey>
  previousBoard: DriverBoard | undefined
}

const DRIVER_DIRECTORY_QUERY = {
  queueId: null,
  routeQueueKind: "MOVEMENT" as const,
  purpose: "DRIVER_DIRECTORY" as const,
}

const UNASSIGNED_SECTION_KEY = "unassigned"

const entryStatusLabels: Record<DriverBoardCard["entryStatus"], string> = {
  WAITING: "Ожидает",
  IN_PROGRESS: "В работе",
  PAUSED: "На паузе",
  DONE: "Завершено",
  CANCELLED: "Отменено",
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
    day: "2-digit",
    month: "long",
  }).format(new Date(`${value}T00:00:00`))
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

function isSupportedLogisticsTrip(card: DriverBoardCard) {
  return (
    card.kind === "TRANSFER" ||
    (card.tripDetails !== null &&
      (card.kind === "SHIPMENT" || card.kind === "RETURN"))
  )
}

function isTaskMovable(card: DriverBoardCard) {
  return card.taskStatus === "ACTIVE" && card.entryStatus === "WAITING"
}

function logisticsTripTitle(kind: DriverBoardCard["kind"]) {
  if (kind === "SHIPMENT") return "Отгрузить бытовку"
  if (kind === "RETURN") return "Вернуть бытовку"
  if (kind === "TRANSFER") return "Межскладское перемещение"
  return "Логистическая ходка"
}

function driverSectionKey(card: DriverBoardCard) {
  return card.driverAudience.mode === "ASSIGNED_DRIVER" &&
    card.driverAudience.workerId
    ? `driver:${card.driverAudience.workerId}`
    : UNASSIGNED_SECTION_KEY
}

function mergeDrivers(
  directoryDrivers: RepairTaskWorkerSnapshotDto[],
  cards: DriverBoardCard[]
) {
  const drivers = new Map(
    directoryDrivers.map((driver) => [driver.id, driver] as const)
  )
  for (const card of cards) {
    if (
      card.driverAudience.mode !== "ASSIGNED_DRIVER" ||
      !card.driverAudience.workerId ||
      drivers.has(card.driverAudience.workerId)
    ) {
      continue
    }
    drivers.set(card.driverAudience.workerId, {
      id: card.driverAudience.workerId,
      name: card.driverAudience.workerName?.trim() || "Водитель без имени",
    })
  }
  return Array.from(drivers.values()).sort((left, right) =>
    left.name.localeCompare(right.name, "ru")
  )
}

function groupDriverTasks(
  currentTasks: DriverBoardCard[],
  scheduledTasks: DriverBoardCard[],
  directoryDrivers: RepairTaskWorkerSnapshotDto[]
) {
  const allTasks = [...currentTasks, ...scheduledTasks]
  const sections: LogisticsDriverSection[] = mergeDrivers(
    directoryDrivers,
    allTasks
  ).map((driver) => ({
    key: `driver:${driver.id}`,
    label: driver.name,
    description: "Личная очередь водителя",
    currentTasks: [],
    scheduledTasks: [],
  }))
  const unassignedCurrent = currentTasks.filter(
    (card) => driverSectionKey(card) === UNASSIGNED_SECTION_KEY
  )
  const unassignedScheduled = scheduledTasks.filter(
    (card) => driverSectionKey(card) === UNASSIGNED_SECTION_KEY
  )
  if (unassignedCurrent.length > 0 || unassignedScheduled.length > 0) {
    sections.push({
      key: UNASSIGNED_SECTION_KEY,
      label: "Не назначено",
      description: "Логистические задания без водителя",
      currentTasks: unassignedCurrent,
      scheduledTasks: unassignedScheduled,
    })
  }

  const sectionsByKey = new Map(
    sections.map((section) => [section.key, section] as const)
  )
  for (const card of currentTasks) {
    if (driverSectionKey(card) === UNASSIGNED_SECTION_KEY) continue
    sectionsByKey.get(driverSectionKey(card))?.currentTasks.push(card)
  }
  for (const card of scheduledTasks) {
    if (driverSectionKey(card) === UNASSIGNED_SECTION_KEY) continue
    sectionsByKey.get(driverSectionKey(card))?.scheduledTasks.push(card)
  }
  return sections
}

function queueTargetIndex(
  allCards: DriverBoardCard[],
  sectionCards: DriverBoardCard[],
  localIndex: number
) {
  if (sectionCards.length === 0) return allCards.length
  const boundedIndex = Math.max(0, Math.min(localIndex, sectionCards.length))
  if (boundedIndex < sectionCards.length) {
    const target = sectionCards[boundedIndex]
    const targetIndex = allCards.findIndex(
      (card) => card.externalTaskId === target?.externalTaskId
    )
    return targetIndex < 0 ? allCards.length : targetIndex
  }
  const finalCard = sectionCards.at(-1)
  const finalIndex = allCards.findIndex(
    (card) => card.externalTaskId === finalCard?.externalTaskId
  )
  return finalIndex < 0 ? allCards.length : finalIndex + 1
}

function normalizePositions(cards: DriverBoardCard[]) {
  return cards.map((card, position) => ({ ...card, position }))
}

/**
 * Reorders one card in its existing lane and date while the server command is
 * pending. The audience is deliberately immutable in this projection.
 */
function moveCardOptimistically(
  board: DriverBoard,
  { item, targetIndex }: LogisticsMove
) {
  const current = orderCards(board.current)
  const dates = board.dates.map((column) => ({
    ...column,
    tasks: orderCards(column.tasks),
  }))
  const queue =
    item.lane === "CURRENT"
      ? current
      : dates.find((column) => column.date === item.date)?.tasks
  if (!queue) return board
  const sourceIndex = queue.findIndex(
    (card) => card.externalTaskId === item.card.externalTaskId
  )
  if (sourceIndex < 0) return board
  const [moved] = queue.splice(sourceIndex, 1)
  if (!moved) return board
  queue.splice(Math.max(0, Math.min(targetIndex, queue.length)), 0, moved)
  return {
    ...board,
    current: normalizePositions(current),
    dates: dates.map((column) => ({
      ...column,
      tasks: normalizePositions(column.tasks),
    })),
  }
}

function LogisticsTaskCard({
  card,
  date,
  lane,
  index,
  localIndex,
  sectionKey,
  disabled,
  onPin,
}: {
  card: DriverBoardCard
  date: string
  lane: DriverBoardCard["lane"]
  index: number
  localIndex: number
  sectionKey: string
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const [expanded, setExpanded] = useState(true)
  const dragDisabled = disabled || !isTaskMovable(card)
  const trip = card.tripDetails
  const taskTitle = logisticsTripTitle(card.kind)
  const item: LogisticsDragItem = {
    type: "logistics-task",
    card,
    lane,
    date,
    index,
    localIndex,
    sectionKey,
  }
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: `logistics-task:${card.externalTaskId}`,
    disabled: dragDisabled,
    data: item,
  })
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0 : 1,
  }

  const cabins = trip
    ? `Бытовки: ${trip.cabins.length}`
    : card.unitNumber
      ? `Бытовка: ${card.unitNumber}`
      : "Состав не указан"

  return (
    <div
      ref={setNodeRef}
      style={style}
      data-testid={`logistics-task-${card.externalTaskId}`}
      className={cn(
        !dragDisabled && "cursor-grab touch-none active:cursor-grabbing"
      )}
      aria-label={`Переместить ${taskTitle}: ${cabins}`}
      {...attributes}
      {...listeners}
    >
      <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
        <Card
          size="sm"
          className={cn(
            lane === "CURRENT" && "border-primary bg-primary/5",
            card.entryStatus === "PAUSED" && "bg-muted/60"
          )}
        >
          <CardHeader>
            <CardTitle className="line-clamp-2 pr-10">{taskTitle}</CardTitle>
            <CardDescription>
              {trip
                ? `${trip.clientName} · ${cabins}`
                : card.taskText?.trim() || card.title}
            </CardDescription>
            <CardAction className="flex items-center gap-1">
              <CollapsibleTrigger asChild>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={
                    expanded
                      ? `Свернуть логистическое задание: ${cabins}`
                      : `Развернуть логистическое задание: ${cabins}`
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
              <Button
                type="button"
                size="icon-sm"
                variant={card.pinned ? "secondary" : "ghost"}
                disabled={disabled}
                aria-pressed={card.pinned}
                aria-label={
                  card.pinned
                    ? `Открепить логистическое задание: ${cabins}`
                    : `Закрепить логистическое задание: ${cabins}`
                }
                onPointerDown={(event) => event.stopPropagation()}
                onKeyDown={(event) => event.stopPropagation()}
                onClick={(event) => {
                  event.stopPropagation()
                  onPin(card)
                }}
              >
                <HugeiconsIcon icon={card.pinned ? PinOffIcon : PinIcon} />
              </Button>
              <span
                className="flex size-8 items-center justify-center text-muted-foreground"
                aria-hidden="true"
              >
                <HugeiconsIcon icon={DragDropVerticalIcon} />
              </span>
            </CardAction>
          </CardHeader>
          <CollapsibleContent asChild>
            <CardContent className="flex flex-col gap-2">
              <div className="flex flex-wrap gap-1">
                <Badge
                  variant={
                    card.entryStatus === "IN_PROGRESS" ? "default" : "outline"
                  }
                >
                  {entryStatusLabels[card.entryStatus]}
                </Badge>
                <Badge variant="outline">
                  Водитель: {card.driverAudience.workerName ?? "не назначен"}
                </Badge>
              </div>
              {trip ? (
                <DriverTripDetailsView
                  details={trip}
                  live={false}
                  kind={card.kind}
                />
              ) : (
                <p className="text-sm text-muted-foreground">
                  {cabins}. Дополнительные факты появятся после формирования
                  логистической проекции.
                </p>
              )}
              {trip ? <DriverTripDetailsDialog card={card} /> : null}
            </CardContent>
          </CollapsibleContent>
        </Card>
      </Collapsible>
    </div>
  )
}

function QueueInsertionSlot({
  date,
  lane,
  sectionKey,
  localIndex,
  index,
  disabled,
}: {
  date: string
  lane: DriverBoardCard["lane"]
  sectionKey: string
  localIndex: number
  index: number
  disabled: boolean
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `logistics-slot:${date}:${lane}:${sectionKey}:${localIndex}`,
    disabled,
    data: {
      type: "logistics-slot",
      date,
      lane,
      sectionKey,
      localIndex,
      index,
    },
  })
  return (
    <div
      ref={setNodeRef}
      aria-label={`Вставить на позицию ${localIndex + 1}`}
      className={cn(
        "relative h-3 rounded-md transition-[height,background-color] duration-150",
        isOver && "h-12 border border-dashed border-primary/60 bg-primary/8"
      )}
    >
      {isOver ? (
        <span className="absolute inset-x-3 top-1/2 h-0.5 -translate-y-1/2 rounded-full bg-primary" />
      ) : null}
    </div>
  )
}

function LogisticsQueue({
  label,
  date,
  lane,
  sectionKey,
  allCards,
  cards,
  disabled,
  onPin,
}: {
  label: string
  date: string
  lane: DriverBoardCard["lane"]
  sectionKey: string
  allCards: DriverBoardCard[]
  cards: DriverBoardCard[]
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const orderedCards = orderCards(cards)
  if (orderedCards.length === 0) return null
  return (
    <div className="flex flex-col gap-1" aria-label={label}>
      <div className="flex items-center justify-between gap-2 px-1">
        <p className="text-xs font-medium text-muted-foreground">{label}</p>
        <Badge variant="outline">{orderedCards.length}</Badge>
      </div>
      <SortableContext
        items={orderedCards.map(
          (card) => `logistics-task:${card.externalTaskId}`
        )}
        strategy={verticalListSortingStrategy}
      >
        {orderedCards.map((card, localIndex) => {
          const globalIndex = allCards.findIndex(
            (candidate) => candidate.externalTaskId === card.externalTaskId
          )
          return (
            <div key={card.externalTaskId}>
              <QueueInsertionSlot
                date={date}
                lane={lane}
                sectionKey={sectionKey}
                localIndex={localIndex}
                index={queueTargetIndex(allCards, orderedCards, localIndex)}
                disabled={disabled}
              />
              <LogisticsTaskCard
                card={card}
                date={date}
                lane={lane}
                index={globalIndex}
                localIndex={localIndex}
                sectionKey={sectionKey}
                disabled={disabled}
                onPin={onPin}
              />
            </div>
          )
        })}
        <QueueInsertionSlot
          date={date}
          lane={lane}
          sectionKey={sectionKey}
          localIndex={orderedCards.length}
          index={queueTargetIndex(allCards, orderedCards, orderedCards.length)}
          disabled={disabled}
        />
      </SortableContext>
    </div>
  )
}

function DriverSection({
  date,
  section,
  allCurrentTasks,
  allScheduledTasks,
  disabled,
  onPin,
}: {
  date: string
  section: LogisticsDriverSection
  allCurrentTasks: DriverBoardCard[]
  allScheduledTasks: DriverBoardCard[]
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const taskCount = section.currentTasks.length + section.scheduledTasks.length
  const [expanded, setExpanded] = useState(taskCount > 0)
  return (
    <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
      <Card size="sm" aria-label={`Очередь водителя: ${section.label}`}>
        <CardHeader>
          <CardTitle className="truncate">{section.label}</CardTitle>
          <CardDescription>{section.description}</CardDescription>
          <CardAction className="flex items-center gap-1">
            <Badge variant={taskCount > 0 ? "secondary" : "outline"}>
              {taskCount}
            </Badge>
            <CollapsibleTrigger asChild>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={
                  expanded
                    ? `Свернуть очередь водителя ${section.label}`
                    : `Развернуть очередь водителя ${section.label}`
                }
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
          </CardAction>
        </CardHeader>
        <CollapsibleContent asChild>
          <CardContent className="flex flex-col gap-2">
            <LogisticsQueue
              label="В работе"
              date={date}
              lane="CURRENT"
              sectionKey={section.key}
              allCards={allCurrentTasks}
              cards={section.currentTasks}
              disabled={disabled}
              onPin={onPin}
            />
            <LogisticsQueue
              label="Запланировано"
              date={date}
              lane="SCHEDULED"
              sectionKey={section.key}
              allCards={allScheduledTasks}
              cards={section.scheduledTasks}
              disabled={disabled}
              onPin={onPin}
            />
            {taskCount === 0 ? (
              <p className="text-sm text-muted-foreground">Заданий нет</p>
            ) : null}
          </CardContent>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

function DateColumn({
  column,
  drivers,
  disabled,
  onPin,
}: {
  column: LogisticsDateColumn
  drivers: RepairTaskWorkerSnapshotDto[]
  disabled: boolean
  onPin: (card: DriverBoardCard) => void
}) {
  const sections = groupDriverTasks(
    column.currentTasks,
    column.scheduledTasks,
    drivers
  )
  const taskCount = column.currentTasks.length + column.scheduledTasks.length
  return (
    <section
      aria-label={`Логистика на ${formatDate(column.date)}`}
      className="flex h-fit w-80 shrink-0 flex-col gap-2 rounded-xl border bg-card p-3 shadow-sm"
    >
      <header className="flex items-center justify-between gap-2">
        <h2 className="font-heading text-sm font-medium capitalize">
          {formatDate(column.date)}
        </h2>
        <Badge variant="outline">{taskCount}</Badge>
      </header>
      <div className="flex flex-col gap-2">
        {sections.map((section) => (
          <DriverSection
            key={section.key}
            date={column.date}
            section={section}
            allCurrentTasks={column.allCurrentTasks}
            allScheduledTasks={column.allScheduledTasks}
            disabled={disabled}
            onPin={onPin}
          />
        ))}
      </div>
    </section>
  )
}

function logisticsDateColumns(board: DriverBoard) {
  const columns = new Map<string, LogisticsDateColumn>()
  for (const source of board.dates) {
    const allScheduledTasks = orderCards(source.tasks)
    const scheduledTasks = allScheduledTasks.filter(isSupportedLogisticsTrip)
    if (scheduledTasks.length === 0) continue
    columns.set(source.date, {
      date: source.date,
      allCurrentTasks: [],
      allScheduledTasks,
      currentTasks: [],
      scheduledTasks,
    })
  }
  const allCurrentTasks = orderCards(board.current)
  const currentTasks = allCurrentTasks.filter(isSupportedLogisticsTrip)
  if (currentTasks.length > 0) {
    const existing = columns.get(board.currentDate)
    columns.set(board.currentDate, {
      date: board.currentDate,
      allCurrentTasks,
      allScheduledTasks: existing?.allScheduledTasks ?? [],
      currentTasks,
      scheduledTasks: existing?.scheduledTasks ?? [],
    })
  }
  return Array.from(columns.values()).sort((left, right) =>
    left.date.localeCompare(right.date)
  )
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить команду логистической доски."
}

/** Logistics board with date columns and immutable per-driver queues. */
export function LogisticsBoardPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const warehouseId = selectedWarehouseId
  const canEdit = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
  const [selectedDate, setSelectedDate] = useState("")
  const [activeItem, setActiveItem] = useState<LogisticsDragItem | null>(null)
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
  const driverGroupsQuery = useQuery({
    queryKey: repairWorkerGroupsQueryKey({
      warehouseId: warehouseId ?? "none",
      ...DRIVER_DIRECTORY_QUERY,
    }),
    queryFn: () =>
      listRepairWorkerGroups(
        {
          warehouseId: warehouseId!,
          ...DRIVER_DIRECTORY_QUERY,
        },
        accessToken!
      ),
    enabled: Boolean(accessToken && warehouseId),
  })
  const drivers = useMemo(
    () =>
      Array.from(
        new Map(
          (driverGroupsQuery.data ?? [])
            .filter((group) => group.active)
            .flatMap((group) => group.members)
            .map((driver) => [driver.id, driver] as const)
        ).values()
      ).sort((left, right) => left.name.localeCompare(right.name, "ru")),
    [driverGroupsQuery.data]
  )

  async function refreshBoard() {
    if (!warehouseId) return
    await queryClient.invalidateQueries({
      queryKey: driverBoardQueryKey(warehouseId),
      exact: true,
    })
  }

  const moveMutation = useMutation<
    DriverBoardCard,
    unknown,
    LogisticsMove,
    OptimisticMoveContext
  >({
    mutationFn: ({ item, targetIndex }) =>
      moveDriverBoardTask({
        accessToken: accessToken!,
        externalTaskId: item.card.externalTaskId,
        command: {
          warehouseId: warehouseId!,
          expectedTaskVersion: item.card.taskBoardTaskVersion,
          expectedEntryVersion: item.card.taskBoardEntryVersion,
          targetLane: item.lane,
          targetDate: item.date,
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
    onError: setCommandError,
  })

  const board = boardQuery.data
  const columns = useMemo(
    () => (board ? logisticsDateColumns(board) : []),
    [board]
  )
  const displayedColumns = selectedDate
    ? columns.filter((column) => column.date === selectedDate)
    : columns
  const disabled = !canEdit || moveMutation.isPending || pinMutation.isPending

  function handleDragStart(event: DragStartEvent) {
    const item = event.active.data.current as LogisticsDragItem | undefined
    if (!disabled && item?.type === "logistics-task") setActiveItem(item)
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveItem(null)
    if (disabled || !board) return
    const item = event.active.data.current as LogisticsDragItem | undefined
    const target = event.over?.data.current as
      | {
          type?: unknown
          lane?: unknown
          date?: unknown
          sectionKey?: unknown
          index?: unknown
        }
      | undefined
    if (
      item?.type !== "logistics-task" ||
      (target?.type !== "logistics-slot" &&
        target?.type !== "logistics-task") ||
      target.lane !== item.lane ||
      target.date !== item.date ||
      target.sectionKey !== item.sectionKey ||
      typeof target.index !== "number" ||
      !Number.isInteger(target.index)
    ) {
      return
    }

    const allCards =
      item.lane === "CURRENT"
        ? orderCards(board.current)
        : orderCards(
            board.dates.find((column) => column.date === item.date)?.tasks ?? []
          )
    const sourceIndex = allCards.findIndex(
      (card) => card.externalTaskId === item.card.externalTaskId
    )
    if (sourceIndex < 0) return
    let rawTargetIndex = target.index
    if (target.type === "logistics-task" && sourceIndex < rawTargetIndex) {
      rawTargetIndex += 1
    }
    let targetIndex = Math.max(0, Math.min(rawTargetIndex, allCards.length))
    if (sourceIndex < targetIndex) targetIndex -= 1
    if (sourceIndex === targetIndex) return
    moveMutation.mutate({ item, targetIndex })
  }

  if (!warehouseId) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Доска логистики</CardTitle>
          <CardDescription>
            Выберите склад, чтобы открыть задания водителей.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }
  if (!accessToken || boardQuery.isLoading || driverGroupsQuery.isLoading) {
    return (
      <Card aria-busy="true">
        <CardHeader>
          <CardTitle>Загрузка логистики</CardTitle>
          <CardDescription>
            Получаем даты, водителей, отгрузки и возвраты…
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }
  if (boardQuery.isError || !board) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить доску логистики</AlertTitle>
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
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="logistics-board-date" className="text-sm font-medium">
            Дата заданий
          </label>
          <SingleDayPicker
            label="Дата заданий"
            hideLabel
            allowClear
            id="logistics-board-date"
            className="w-auto"
            value={selectedDate}
            onValueChange={(nextValue) => setSelectedDate(nextValue)}
          />
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!selectedDate}
            onClick={() => setSelectedDate("")}
          >
            Все даты
          </Button>
        </div>
        {!canEdit ? <Badge variant="outline">Только просмотр</Badge> : null}
      </div>

      {commandError ? (
        <Alert variant="destructive">
          <AlertTitle>Команда не выполнена</AlertTitle>
          <AlertDescription>{errorMessage(commandError)}</AlertDescription>
        </Alert>
      ) : null}
      {driverGroupsQuery.isError ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить список водителей</AlertTitle>
          <AlertDescription>
            Показываются водители, уже указанные в заданиях. Пустые личные
            очереди появятся после восстановления справочника.
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
        <div
          className="min-h-0 flex-1 overflow-x-auto rounded-xl border bg-muted/20 p-3"
          aria-label="Логистические задания по датам и водителям"
        >
          <div className="flex min-h-full min-w-full items-start gap-3">
            {displayedColumns.map((column) => (
              <DateColumn
                key={column.date}
                column={column}
                drivers={drivers}
                disabled={disabled}
                onPin={(card) => pinMutation.mutate(card)}
              />
            ))}
            {displayedColumns.length === 0 ? (
              <Card className="min-w-80" size="sm">
                <CardHeader>
                  <CardTitle>Заданий нет</CardTitle>
                  <CardDescription>
                    На выбранную дату нет отгрузок или возвратов.
                  </CardDescription>
                </CardHeader>
              </Card>
            ) : null}
          </div>
        </div>
        {typeof document !== "undefined"
          ? createPortal(
              <DragOverlay dropAnimation={null}>
                {activeItem ? (
                  <Card size="sm" className="w-72 shadow-lg" aria-hidden>
                    <CardHeader>
                      <CardTitle>
                        {logisticsTripTitle(activeItem.card.kind)}
                      </CardTitle>
                      {activeItem.card.tripDetails ? (
                        <CardDescription>
                          Бытовки: {activeItem.card.tripDetails.cabins.length}
                        </CardDescription>
                      ) : null}
                    </CardHeader>
                  </Card>
                ) : null}
              </DragOverlay>,
              document.body
            )
          : null}
      </DndContext>
    </div>
  )
}
