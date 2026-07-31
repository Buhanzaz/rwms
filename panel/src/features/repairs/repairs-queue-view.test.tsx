import type { ReactNode } from "react"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react"
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairsQueueView } from "@/features/repairs/repairs-queue-view"
import type {
  TaskBoardEntryDto,
  TaskBoardEntryStatus,
  TaskBoardQueueDto,
  TaskBoardQueueKind,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"

const dndMocks = vi.hoisted(() => ({
  onDragStart: null as ((event: unknown) => void) | null,
  onDragCancel: null as (() => void) | null,
  onDragEnd: null as ((event: unknown) => void) | null,
  collisionDetection: null as
    ((args: { pointerCoordinates: unknown }) => unknown) | null,
  overlayDropAnimation: undefined as unknown,
  closestCenter: vi.fn(() => [{ id: "closest" }]),
  pointerWithin: vi.fn((): unknown[] => []),
  sortableData: new Map<string, unknown>(),
  droppableData: new Map<string, unknown>(),
  droppableDisabled: new Map<string, boolean>(),
  overId: null as string | null,
}))

vi.mock("@dnd-kit/core", () => ({
  closestCenter: dndMocks.closestCenter,
  DndContext: ({
    children,
    collisionDetection,
    onDragStart,
    onDragCancel,
    onDragEnd,
  }: {
    children: ReactNode
    collisionDetection: (args: { pointerCoordinates: unknown }) => unknown
    onDragStart: (event: unknown) => void
    onDragCancel: () => void
    onDragEnd: (event: unknown) => void
  }) => {
    dndMocks.collisionDetection = collisionDetection
    dndMocks.onDragStart = onDragStart
    dndMocks.onDragCancel = onDragCancel
    dndMocks.onDragEnd = onDragEnd
    return children
  },
  DragOverlay: ({
    children,
    dropAnimation,
  }: {
    children: ReactNode
    dropAnimation: unknown
  }) => {
    dndMocks.overlayDropAnimation = dropAnimation
    return <div data-testid="drag-overlay">{children}</div>
  },
  KeyboardSensor: class KeyboardSensor {},
  pointerWithin: dndMocks.pointerWithin,
  PointerSensor: class PointerSensor {},
  TouchSensor: class TouchSensor {},
  useDroppable: ({
    id,
    data,
    disabled = false,
  }: {
    id: string
    data: unknown
    disabled?: boolean
  }) => {
    dndMocks.droppableData.set(id, data)
    dndMocks.droppableDisabled.set(id, disabled)
    return { setNodeRef: vi.fn(), isOver: dndMocks.overId === id }
  },
  useSensor: vi.fn(() => ({})),
  useSensors: vi.fn((...sensors: unknown[]) => sensors),
}))

vi.mock("@dnd-kit/sortable", () => ({
  SortableContext: ({ children }: { children: ReactNode }) => children,
  sortableKeyboardCoordinates: vi.fn(),
  useSortable: ({ id, data }: { id: string; data: unknown }) => {
    dndMocks.sortableData.set(id, data)
    return {
      attributes: {},
      listeners: {},
      setNodeRef: vi.fn(),
      transform: null,
      transition: undefined,
      isDragging: false,
    }
  },
  verticalListSortingStrategy: vi.fn(),
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

beforeAll(() => {
  HTMLElement.prototype.scrollIntoView = vi.fn()
})

afterAll(() => {
  HTMLElement.prototype.scrollIntoView = originalScrollIntoView
})

function entry(params: {
  id: string
  externalTaskId: string
  routeIndex?: number
  routeLength?: number
  date: string
  queue: TaskBoardQueueDto
  title: string
  status?: TaskBoardEntryStatus
  priority?: number
  pinned?: boolean
  queuePosition?: number
}): TaskBoardEntryDto {
  return {
    id: params.id,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    queueKey: params.queue.key,
    queueId: params.queue.settingsQueueId,
    entryType: params.routeIndex === 1 ? "SHADOW" : "REAL",
    routeIndex: params.routeIndex ?? 0,
    routeLength: params.routeLength ?? 1,
    queuePosition: params.queuePosition ?? 0,
    taskId: `task-${params.externalTaskId}`,
    externalTaskId: params.externalTaskId,
    taskVersion: 1,
    title: params.title,
    unitNumber: null,
    taskStatus: "ACTIVE",
    scheduledDate: params.date,
    priority: params.priority ?? 3,
    pinned: params.pinned ?? false,
    status: params.status ?? "WAITING",
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: null,
    pausedAt: null,
    activeWorkSeconds: 0,
    assignments: [],
    detailsHref: null,
  }
}

function queue(
  key: string,
  label: string,
  kind: TaskBoardQueueKind
): TaskBoardQueueDto {
  return {
    key,
    label,
    kind,
    settingsQueueId: `settings-${key}`,
    settingsCollapsed: false,
    entries: [],
  }
}

function board(
  date: string,
  queues: TaskBoardQueueDto[]
): TaskBoardSnapshotDto {
  const entries = queues.flatMap((candidate) => candidate.entries)
  return {
    warehouseId: WAREHOUSE_ID,
    selectedDate: date,
    availableDates: [date],
    queues,
    totalEntries: entries.length,
    realEntries: entries.filter((candidate) => candidate.entryType === "REAL")
      .length,
    shadowEntries: entries.filter(
      (candidate) => candidate.entryType === "SHADOW"
    ).length,
  }
}

function repair(params: {
  id: string
  externalTaskId: string
  cabinNumber: string
  status?: TaskBoardEntryStatus
  priority?: 1 | 2 | 3 | 4 | 5
  pinned?: boolean
}): RepairTaskDto {
  return {
    id: params.id,
    version: 1,
    status: params.status === "IN_PROGRESS" ? "IN_PROGRESS" : "QUEUED",
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "NOT_READY",
    startedAt: null,
    completedAt: null,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: `rental-${params.id}`,
    cabinNumber: params.cabinNumber,
    actorId: "operator-1",
    dispatchDate: "2026-07-24",
    priority: params.priority ?? 3,
    subtasks: [
      {
        id: `${params.id}-stage-0`,
        externalTaskId: params.externalTaskId,
        taskTitle: `Основное задание ${params.cabinNumber}`,
        taskText: null,
        kind: "REPAIR_WORK",
        status: params.status ?? "WAITING",
        workLines: [],
        materialLines: [],
        groupComment: "",
        queueName: "Ремонт",
        queueId: "settings-repair",
        routeQueueKind: "REPAIR",
        sortOrder: 0,
        entryType: "REAL",
        queuePosition: 0,
        scheduledDate: "2026-07-24",
        priority: params.priority ?? 3,
        pinned: params.pinned ?? false,
        plannedDurationMinutes: null,
        startedAt: null,
        completedAt: null,
        activeStartedAt: null,
        activeWorkSeconds: 0,
        workerGroup: null,
        assignments: [],
      },
      {
        id: `${params.id}-stage-1`,
        externalTaskId: params.externalTaskId,
        taskTitle: `Скрытый следующий этап ${params.cabinNumber}`,
        taskText: null,
        kind: "MOVE_FROM_REPAIR",
        status: "WAITING",
        workLines: [],
        materialLines: [],
        groupComment: "",
        queueName: "Перемещение",
        queueId: "settings-movement",
        routeQueueKind: "MOVEMENT",
        sortOrder: 1,
        entryType: "SHADOW",
        queuePosition: 0,
        scheduledDate: "2026-07-25",
        priority: params.priority ?? 3,
        pinned: false,
        plannedDurationMinutes: null,
        startedAt: null,
        completedAt: null,
        activeStartedAt: null,
        activeWorkSeconds: 0,
        workerGroup: null,
        assignments: [],
      },
    ],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    createdAt: "2026-07-24T08:00:00Z",
    updatedAt: "2026-07-24T08:00:00Z",
  }
}

afterEach(() => {
  cleanup()
  dndMocks.onDragStart = null
  dndMocks.onDragCancel = null
  dndMocks.onDragEnd = null
  dndMocks.collisionDetection = null
  dndMocks.overlayDropAnimation = undefined
  dndMocks.sortableData.clear()
  dndMocks.droppableData.clear()
  dndMocks.droppableDisabled.clear()
  dndMocks.overId = null
  vi.clearAllMocks()
})

function completeDropWithoutDateTarget(
  activeData: unknown,
  over: unknown = null
) {
  act(() => {
    dndMocks.onDragEnd?.({
      active: {
        data: { current: activeData },
        rect: {
          current: {
            translated: { top: 0, height: 10 },
            initial: { top: 0, height: 10 },
          },
        },
      },
      over,
    })
  })
}

function startDrag(activeData: unknown) {
  act(() => {
    dndMocks.onDragStart?.({
      active: {
        data: { current: activeData },
      },
    })
  })
}

function dropOnSlot(activeData: unknown, date: string, index: number) {
  const slotId = `repair-slot:${date}:${index}`
  const slotData = dndMocks.droppableData.get(slotId)
  if (!slotData) throw new Error(`Missing test drop slot ${slotId}`)

  act(() => {
    dndMocks.onDragEnd?.({
      active: {
        data: { current: activeData },
        rect: {
          current: {
            translated: { top: 0, height: 10 },
            initial: { top: 0, height: 10 },
          },
        },
      },
      over: {
        id: slotId,
        data: { current: slotData },
        rect: { top: 0, height: 10 },
      },
    })
  })
}

describe("RepairsQueueView", () => {
  it("treats pointer drops outside every real target as no collision", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [sourceEntry] }]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const pointerArgs = { pointerCoordinates: { x: 1000, y: 1000 } }
    expect(dndMocks.collisionDetection?.(pointerArgs)).toEqual([])
    expect(dndMocks.pointerWithin).toHaveBeenCalledWith(pointerArgs)
    expect(dndMocks.closestCenter).not.toHaveBeenCalled()

    const keyboardArgs = { pointerCoordinates: null }
    expect(dndMocks.collisionDetection?.(keyboardArgs)).toEqual([
      { id: "closest" },
    ])
    expect(dndMocks.closestCenter).toHaveBeenCalledWith(keyboardArgs)
  })

  it("turns the upper and lower half of a hovered card into exact insertion slots", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [sourceEntry] }]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const repairContainer = {
      id: "repair:repair-a",
      disabled: false,
      data: {
        current: dndMocks.sortableData.get("repair:repair-a"),
      },
      rect: { current: { top: 100, height: 100 } },
    }
    const slotContainers = [0, 1].map((index) => ({
      id: `repair-slot:2026-07-24:${index}`,
      disabled: false,
      data: {
        current: dndMocks.droppableData.get(`repair-slot:2026-07-24:${index}`),
      },
      rect: { current: { top: 90 + index * 120, height: 10 } },
    }))

    for (const [pointerY, expectedSlot] of [
      [125, 0],
      [175, 1],
    ] as const) {
      dndMocks.pointerWithin.mockReturnValueOnce([
        {
          id: repairContainer.id,
          data: {
            droppableContainer: repairContainer,
            value: 0,
          },
        },
      ])
      const collisionArgs = {
        pointerCoordinates: { x: 10, y: pointerY },
        collisionRect: { top: pointerY, height: 10 },
        droppableContainers: slotContainers,
      }

      expect(dndMocks.collisionDetection?.(collisionArgs)).toEqual([
        expect.objectContaining({
          id: `repair-slot:2026-07-24:${expectedSlot}`,
        }),
      ])
    }
  })

  it("expands the hovered slot and names the surrounding cabins", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const firstEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
      queuePosition: 0,
    })
    const secondEntry = entry({
      id: "entry-b",
      externalTaskId: "external-b",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить дверь",
      queuePosition: 1,
    })
    const thirdEntry = entry({
      id: "entry-c",
      externalTaskId: "external-c",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить окно",
      queuePosition: 2,
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [
            {
              ...repairQueue,
              entries: [firstEntry, secondEntry, thirdEntry],
            },
          ]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
          repair({
            id: "repair-b",
            externalTaskId: "external-b",
            cabinNumber: "БЫТ-102",
          }),
          repair({
            id: "repair-c",
            externalTaskId: "external-c",
            cabinNumber: "БЫТ-103",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    dndMocks.overId = "repair-slot:2026-07-24:1"
    startDrag(dndMocks.sortableData.get("repair:repair-c"))

    const activeSlot = screen.getByLabelText(
      "Вставить между бытовками БЫТ-101 и БЫТ-102"
    )
    expect(activeSlot.getAttribute("data-insertion-active")).toBe("true")
    expect(activeSlot.className).toContain("h-14")
    expect(screen.getByRole("status").textContent).toBe(
      "Вставить между бытовками БЫТ-101 и БЫТ-102"
    )
  })

  it("uses an always-mounted overlay and clears it when dragging is cancelled", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [sourceEntry] }]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const overlay = screen.getByTestId("drag-overlay")
    expect(within(overlay).queryByText("Починить крышу")).toBeNull()
    expect(dndMocks.overlayDropAnimation).toBeNull()

    startDrag(dndMocks.sortableData.get("repair:repair-a"))

    expect(within(overlay).getByText("Починить крышу")).toBeTruthy()
    expect(within(overlay).getByText("Бытовка БЫТ-101")).toBeTruthy()

    act(() => dndMocks.onDragCancel?.())

    expect(within(overlay).queryByText("Починить крышу")).toBeNull()
  })

  it("shows one compact card per repair and opens task information", () => {
    const repairQueue = queue("repair", "Ремонтная очередь", "REPAIR")
    const movementQueue = queue("movement", "Перемещение", "MOVEMENT")
    const firstRepair = repair({
      id: "repair-a",
      externalTaskId: "external-a",
      cabinNumber: "БЫТ-101",
      priority: 1,
      pinned: true,
    })
    const currentEntry = entry({
      id: "entry-a-current",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Срочно починить крышу",
      priority: 1,
      pinned: true,
      routeLength: 2,
    })
    const shadowEntry = entry({
      id: "entry-a-shadow",
      externalTaskId: "external-a",
      routeIndex: 1,
      routeLength: 2,
      date: "2026-07-25",
      queue: movementQueue,
      title: "Вернуть бытовку после ремонта",
    })
    const onOpen = vi.fn()
    const onPin = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [currentEntry] }]),
          board("2026-07-25", [{ ...movementQueue, entries: [shadowEntry] }]),
        ]}
        repairs={[firstRepair]}
        disabled={false}
        onMove={vi.fn()}
        onPin={onPin}
        onOpen={onOpen}
      />
    )

    expect(screen.getAllByRole("button", { name: "Инфо" })).toHaveLength(1)
    expect(screen.getByText("Срочно починить крышу")).toBeTruthy()
    expect(screen.queryByText("Вернуть бытовку после ремонта")).toBeNull()
    expect(screen.queryByText("Скрытый следующий этап БЫТ-101")).toBeNull()

    fireEvent.click(
      screen.getByRole("button", {
        name: "Открепить задание бытовки БЫТ-101",
      })
    )
    expect(onPin).toHaveBeenCalledWith(currentEntry, false)

    fireEvent.click(screen.getByRole("button", { name: "Инфо" }))
    const dialog = screen.getByRole("dialog")
    expect(
      within(dialog).getByRole("heading", {
        name: "Задание бытовки БЫТ-101",
      })
    ).toBeTruthy()
    expect(within(dialog).getByText("Ремонтная очередь")).toBeTruthy()
    expect(within(dialog).getByText("Закреплено")).toBeTruthy()

    fireEvent.click(
      within(dialog).getByRole("button", { name: "Открыть ремонт" })
    )
    expect(onOpen).toHaveBeenCalledWith("repair-a")
  })

  it("keeps the operational entry queue and uses the aggregate day index", () => {
    const movementQueue = queue("movement", "Перемещение", "MOVEMENT")
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const holdingQueue = queue("holding", "Ожидание", "HOLDING")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: movementQueue,
      title: "Переместить БЫТ-101",
    })
    const firstTargetEntry = entry({
      id: "entry-b",
      externalTaskId: "external-b",
      date: "2026-07-25",
      queue: repairQueue,
      title: "Починить БЫТ-102",
    })
    const secondTargetEntry = entry({
      id: "entry-c",
      externalTaskId: "external-c",
      date: "2026-07-25",
      queue: holdingQueue,
      title: "Проверить БЫТ-103",
    })
    const sourceBoardQueue = {
      ...movementQueue,
      entries: [sourceEntry],
    }
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [sourceBoardQueue]),
          board("2026-07-25", [
            { ...repairQueue, entries: [firstTargetEntry] },
            { ...holdingQueue, entries: [secondTargetEntry] },
          ]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
          repair({
            id: "repair-b",
            externalTaskId: "external-b",
            cabinNumber: "БЫТ-102",
          }),
          repair({
            id: "repair-c",
            externalTaskId: "external-c",
            cabinNumber: "БЫТ-103",
          }),
        ]}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const activeData = dndMocks.sortableData.get("repair:repair-a")
    const overData = dndMocks.sortableData.get("repair:repair-c")
    act(() => {
      dndMocks.onDragEnd?.({
        active: {
          data: { current: activeData },
          rect: {
            current: {
              translated: { top: 0, height: 10 },
              initial: { top: 0, height: 10 },
            },
          },
        },
        over: {
          data: { current: overData },
          rect: { top: 100, height: 10 },
        },
      })
    })

    expect(onMove).toHaveBeenCalledWith({
      entry: sourceEntry,
      queue: sourceBoardQueue,
      targetIndex: 1,
      targetDate: "2026-07-25",
    })
  })

  it.each([
    ["в начало", 0, 0],
    ["между заданиями", 1, 1],
    ["в конец", 2, 2],
  ])(
    "persists a cross-date insertion %s",
    (_label, slotIndex, expectedIndex) => {
      const sourceQueue = queue("source", "Исходная очередь", "REPAIR")
      const targetQueue = queue("target", "Целевая очередь", "REPAIR")
      const sourceEntry = entry({
        id: "entry-source",
        externalTaskId: "external-source",
        date: "2026-07-24",
        queue: sourceQueue,
        title: "Исходное задание",
      })
      const firstTargetEntry = entry({
        id: "entry-target-a",
        externalTaskId: "external-target-a",
        date: "2026-07-25",
        queue: targetQueue,
        title: "Первое целевое задание",
        queuePosition: 0,
      })
      const secondTargetEntry = entry({
        id: "entry-target-b",
        externalTaskId: "external-target-b",
        date: "2026-07-25",
        queue: targetQueue,
        title: "Второе целевое задание",
        queuePosition: 1,
      })
      const sourceBoardQueue = { ...sourceQueue, entries: [sourceEntry] }
      const onMove = vi.fn()

      render(
        <RepairsQueueView
          boards={[
            board("2026-07-24", [sourceBoardQueue]),
            board("2026-07-25", [
              {
                ...targetQueue,
                entries: [firstTargetEntry, secondTargetEntry],
              },
            ]),
          ]}
          repairs={[
            repair({
              id: "repair-source",
              externalTaskId: "external-source",
              cabinNumber: "БЫТ-100",
            }),
            repair({
              id: "repair-target-a",
              externalTaskId: "external-target-a",
              cabinNumber: "БЫТ-101",
            }),
            repair({
              id: "repair-target-b",
              externalTaskId: "external-target-b",
              cabinNumber: "БЫТ-102",
            }),
          ]}
          disabled={false}
          onMove={onMove}
          onPin={vi.fn()}
          onOpen={vi.fn()}
        />
      )

      const activeData = dndMocks.sortableData.get("repair:repair-source")
      startDrag(activeData)
      dropOnSlot(activeData, "2026-07-25", slotIndex)

      expect(onMove).toHaveBeenCalledWith({
        entry: sourceEntry,
        queue: sourceBoardQueue,
        targetIndex: expectedIndex,
        targetDate: "2026-07-25",
      })
    }
  )

  it("removes the source before calculating an exact same-date position", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const entries = ["a", "b", "c"].map((suffix, index) =>
      entry({
        id: `entry-${suffix}`,
        externalTaskId: `external-${suffix}`,
        date: "2026-07-24",
        queue: repairQueue,
        title: `Задание ${suffix}`,
        queuePosition: index,
      })
    )
    const boardQueue = { ...repairQueue, entries }
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[board("2026-07-24", [boardQueue])]}
        repairs={["a", "b", "c"].map((suffix, index) =>
          repair({
            id: `repair-${suffix}`,
            externalTaskId: `external-${suffix}`,
            cabinNumber: `БЫТ-10${index + 1}`,
          })
        )}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const activeData = dndMocks.sortableData.get("repair:repair-a")
    startDrag(activeData)
    dropOnSlot(activeData, "2026-07-24", 3)

    expect(onMove).toHaveBeenCalledWith({
      entry: entries[0],
      queue: boardQueue,
      targetIndex: 2,
      targetDate: "2026-07-24",
    })
  })

  it("keeps insertion slots before active and paused tasks blocked", () => {
    const sourceQueue = queue("source", "Исходная очередь", "REPAIR")
    const targetQueue = queue("target", "Целевая очередь", "REPAIR")
    const sourceEntry = entry({
      id: "entry-source",
      externalTaskId: "external-source",
      date: "2026-07-24",
      queue: sourceQueue,
      title: "Исходное задание",
    })
    const activeEntry = entry({
      id: "entry-active",
      externalTaskId: "external-active",
      date: "2026-07-25",
      queue: targetQueue,
      title: "Задание в работе",
      status: "IN_PROGRESS",
      queuePosition: 0,
    })
    const pausedEntry = entry({
      id: "entry-paused",
      externalTaskId: "external-paused",
      date: "2026-07-25",
      queue: targetQueue,
      title: "Приостановленное задание",
      status: "PAUSED",
      queuePosition: 1,
    })
    const waitingEntry = entry({
      id: "entry-waiting",
      externalTaskId: "external-waiting",
      date: "2026-07-25",
      queue: targetQueue,
      title: "Ожидающее задание",
      queuePosition: 2,
    })
    const sourceBoardQueue = { ...sourceQueue, entries: [sourceEntry] }
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [sourceBoardQueue]),
          board("2026-07-25", [
            {
              ...targetQueue,
              entries: [activeEntry, pausedEntry, waitingEntry],
            },
          ]),
        ]}
        repairs={[
          repair({
            id: "repair-source",
            externalTaskId: "external-source",
            cabinNumber: "БЫТ-100",
          }),
          repair({
            id: "repair-active",
            externalTaskId: "external-active",
            cabinNumber: "БЫТ-101",
            status: "IN_PROGRESS",
          }),
          repair({
            id: "repair-paused",
            externalTaskId: "external-paused",
            cabinNumber: "БЫТ-102",
            status: "PAUSED",
          }),
          repair({
            id: "repair-waiting",
            externalTaskId: "external-waiting",
            cabinNumber: "БЫТ-103",
          }),
        ]}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    const activeData = dndMocks.sortableData.get("repair:repair-source")
    startDrag(activeData)

    expect(dndMocks.droppableDisabled.get("repair-slot:2026-07-25:0")).toBe(
      true
    )
    expect(dndMocks.droppableDisabled.get("repair-slot:2026-07-25:1")).toBe(
      true
    )
    expect(dndMocks.droppableDisabled.get("repair-slot:2026-07-25:2")).toBe(
      false
    )

    dropOnSlot(activeData, "2026-07-25", 0)

    expect(onMove).toHaveBeenCalledWith({
      entry: sourceEntry,
      queue: sourceBoardQueue,
      targetIndex: 2,
      targetDate: "2026-07-25",
    })
  })

  it("orders compact tasks by their synchronized position across queues", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const movementQueue = queue("movement", "Перемещение", "MOVEMENT")
    const laterEntry = entry({
      id: "entry-later",
      externalTaskId: "external-later",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Позднее задание",
      queuePosition: 2,
    })
    const earlierEntry = entry({
      id: "entry-earlier",
      externalTaskId: "external-earlier",
      date: "2026-07-24",
      queue: movementQueue,
      title: "Раннее задание",
      queuePosition: 0,
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [
            { ...repairQueue, entries: [laterEntry] },
            { ...movementQueue, entries: [earlierEntry] },
          ]),
        ]}
        repairs={[
          repair({
            id: "repair-later",
            externalTaskId: "external-later",
            cabinNumber: "БЫТ-202",
          }),
          repair({
            id: "repair-earlier",
            externalTaskId: "external-earlier",
            cabinNumber: "БЫТ-201",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    expect(
      screen
        .getAllByRole("button", { name: /Переместить задание бытовки/ })
        .map((button) => button.getAttribute("aria-label"))
    ).toEqual([
      "Переместить задание бытовки БЫТ-201",
      "Переместить задание бытовки БЫТ-202",
    ])
  })

  it("keeps active tasks immovable while leaving pin available", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const activeEntry = entry({
      id: "entry-active",
      externalTaskId: "external-active",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Ремонт в работе",
      status: "IN_PROGRESS",
    })
    const activeRepair = repair({
      id: "repair-active",
      externalTaskId: "external-active",
      cabinNumber: "БЫТ-104",
      status: "IN_PROGRESS",
    })
    const onPin = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [activeEntry] }]),
        ]}
        repairs={[activeRepair]}
        disabled={false}
        onMove={vi.fn()}
        onPin={onPin}
        onOpen={vi.fn()}
      />
    )

    const moveButton = screen.getByRole("button", {
      name: "Переместить задание бытовки БЫТ-104",
    }) as HTMLButtonElement
    const pinButton = screen.getByRole("button", {
      name: "Закрепить задание бытовки БЫТ-104",
    }) as HTMLButtonElement
    expect(moveButton.disabled).toBe(true)
    expect(pinButton.disabled).toBe(false)

    fireEvent.click(pinButton)
    expect(onPin).toHaveBeenCalledWith(activeEntry, true)
  })

  it("opens a date dialog when a movable card is dropped outside a date target", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [sourceEntry] }]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={vi.fn()}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    completeDropWithoutDateTarget(dndMocks.sortableData.get("repair:repair-a"))

    expect(
      screen.getByRole("heading", { name: "Выберите дату перемещения" })
    ).toBeTruthy()
    expect(
      screen.getByRole("combobox", { name: "Дата из очереди" })
    ).toBeTruthy()
    expect(screen.getByLabelText("Новая дата")).toBeTruthy()
  })

  it("appends an empty-area drop to the selected existing date", () => {
    const sourceQueue = queue("repair", "Ремонт", "REPAIR")
    const targetQueue = queue("movement", "Перемещение", "MOVEMENT")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: sourceQueue,
      title: "Починить крышу",
    })
    const targetEntry = entry({
      id: "entry-b",
      externalTaskId: "external-b",
      date: "2026-07-25",
      queue: targetQueue,
      title: "Починить дверь",
    })
    const sourceBoardQueue = { ...sourceQueue, entries: [sourceEntry] }
    const targetBoardQueue = { ...targetQueue, entries: [targetEntry] }
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [sourceBoardQueue]),
          board("2026-07-25", [targetBoardQueue]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
          repair({
            id: "repair-b",
            externalTaskId: "external-b",
            cabinNumber: "БЫТ-102",
          }),
        ]}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    completeDropWithoutDateTarget(dndMocks.sortableData.get("repair:repair-a"))
    fireEvent.click(screen.getByRole("combobox", { name: "Дата из очереди" }))
    fireEvent.click(screen.getByRole("option", { name: /25 июля/i }))
    fireEvent.click(screen.getByRole("button", { name: "Переместить" }))

    expect(onMove).toHaveBeenCalledWith({
      entry: sourceEntry,
      queue: sourceBoardQueue,
      targetIndex: 1,
      targetDate: "2026-07-25",
    })
  })

  it("moves an empty-area drop to a new ISO calendar date", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })
    const sourceBoardQueue = { ...repairQueue, entries: [sourceEntry] }
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[board("2026-07-24", [sourceBoardQueue])]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    completeDropWithoutDateTarget(dndMocks.sortableData.get("repair:repair-a"))
    fireEvent.change(screen.getByLabelText("Новая дата"), {
      target: { value: "2026-07-29" },
    })
    fireEvent.click(screen.getByRole("button", { name: "Переместить" }))

    expect(onMove).toHaveBeenCalledWith({
      entry: sourceEntry,
      queue: sourceBoardQueue,
      targetIndex: 0,
      targetDate: "2026-07-29",
    })
  })

  it("cancels an empty-area drop without moving the task", () => {
    const repairQueue = queue("repair", "Ремонт", "REPAIR")
    const sourceEntry = entry({
      id: "entry-a",
      externalTaskId: "external-a",
      date: "2026-07-24",
      queue: repairQueue,
      title: "Починить крышу",
    })
    const onMove = vi.fn()

    render(
      <RepairsQueueView
        boards={[
          board("2026-07-24", [{ ...repairQueue, entries: [sourceEntry] }]),
        ]}
        repairs={[
          repair({
            id: "repair-a",
            externalTaskId: "external-a",
            cabinNumber: "БЫТ-101",
          }),
        ]}
        disabled={false}
        onMove={onMove}
        onPin={vi.fn()}
        onOpen={vi.fn()}
      />
    )

    completeDropWithoutDateTarget(
      dndMocks.sortableData.get("repair:repair-a"),
      { data: { current: { type: "empty-area" } } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отмена" }))

    expect(onMove).not.toHaveBeenCalled()
    expect(
      screen.queryByRole("heading", { name: "Выберите дату перемещения" })
    ).toBeNull()
  })
})
