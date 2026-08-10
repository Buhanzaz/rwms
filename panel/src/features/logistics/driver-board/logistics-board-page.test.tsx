import type { ReactNode } from "react"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  DriverBoard,
  DriverBoardCard,
} from "@/features/logistics/driver-board/driver-board-model"
import { LogisticsBoardPage } from "@/features/logistics/driver-board/logistics-board-page"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  releasePointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
  setPointerCapture: { configurable: true, value: () => undefined },
})

const apiMocks = vi.hoisted(() => ({
  getDriverBoard: vi.fn(),
  moveDriverBoardTask: vi.fn(),
  pinDriverBoardTask: vi.fn(),
}))

const directoryMocks = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))

const contextMocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
}))

const dndMocks = vi.hoisted(() => ({
  onDragEnd: null as ((event: unknown) => void) | null,
  sortableData: new Map<string, unknown>(),
  droppableData: new Map<string, unknown>(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: contextMocks.useAuth,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: contextMocks.useWarehouse,
}))
vi.mock("@/features/logistics/driver-board/driver-board-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/logistics/driver-board/driver-board-api")
  >("@/features/logistics/driver-board/driver-board-api")
  return {
    ...actual,
    getDriverBoard: apiMocks.getDriverBoard,
    moveDriverBoardTask: apiMocks.moveDriverBoardTask,
    pinDriverBoardTask: apiMocks.pinDriverBoardTask,
  }
})
vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  listRepairWorkerGroups: directoryMocks.listRepairWorkerGroups,
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
}))
vi.mock("@dnd-kit/core", () => ({
  closestCenter: vi.fn(),
  DndContext: ({
    children,
    onDragEnd,
  }: {
    children: ReactNode
    onDragEnd: (event: unknown) => void
  }) => {
    dndMocks.onDragEnd = onDragEnd
    return children
  },
  DragOverlay: ({ children }: { children: ReactNode }) => children,
  KeyboardSensor: class KeyboardSensor {},
  PointerSensor: class PointerSensor {},
  TouchSensor: class TouchSensor {},
  useDroppable: ({ id, data }: { id: string; data: unknown }) => {
    dndMocks.droppableData.set(id, data)
    return { setNodeRef: vi.fn(), isOver: false }
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
const IVAN_ID = "00000000-0000-4000-8000-000000000011"
const PETR_ID = "00000000-0000-4000-8000-000000000012"

function user(): CurrentUser {
  return {
    id: "logistics-manager",
    username: "manager",
    displayName: "Менеджер",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "EDIT" }],
  }
}

function card(
  externalTaskId: string,
  params: Partial<DriverBoardCard> = {}
): DriverBoardCard {
  return {
    driverTaskId: "00000000-0000-4000-8000-000000000101",
    externalTaskId,
    taskBoardTaskId: "00000000-0000-4000-8000-000000000102",
    taskBoardTaskVersion: 7,
    taskBoardEntryId: "00000000-0000-4000-8000-000000000103",
    taskBoardEntryVersion: 11,
    title: "Отгрузить бытовку",
    taskText: null,
    unitNumber: "БТ-100",
    kind: "SHIPMENT",
    driverAudience: {
      mode: "ASSIGNED_DRIVER",
      workerId: IVAN_ID,
      workerName: "Иванов Иван",
    },
    workflowState: "SCHEDULED",
    taskStatus: "ACTIVE",
    entryStatus: "WAITING",
    scheduledDate: "2026-08-01",
    lane: "SCHEDULED",
    priority: 3,
    pinned: false,
    position: 0,
    ...params,
  }
}

const firstShipment = card("00000000-0000-4000-8000-000000000021", {
  unitNumber: "БТ-ОТГ-1",
  position: 0,
})
const movement = card("00000000-0000-4000-8000-000000000022", {
  title: "Переместить на склад",
  unitNumber: "БТ-ПЕР",
  kind: "TRANSFER",
  driverAudience: {
    mode: "WAREHOUSE_DRIVERS",
    workerId: null,
    workerName: null,
  },
  position: 1,
})
const secondShipment = card("00000000-0000-4000-8000-000000000023", {
  unitNumber: "БТ-ОТГ-2",
  position: 2,
})
const petrReturn = card("00000000-0000-4000-8000-000000000024", {
  title: "Вернуть бытовку",
  unitNumber: "БТ-ВОЗ",
  kind: "RETURN",
  driverAudience: {
    mode: "ASSIGNED_DRIVER",
    workerId: PETR_ID,
    workerName: "Петров Пётр",
  },
  position: 3,
})
const unassignedReturn = card("00000000-0000-4000-8000-000000000025", {
  title: "Возврат без водителя",
  unitNumber: "БТ-БЕЗ",
  kind: "RETURN",
  driverAudience: {
    mode: "UNASSIGNED",
    workerId: null,
    workerName: null,
  },
  scheduledDate: "2026-08-02",
  position: 0,
})

const board: DriverBoard = {
  warehouseId: WAREHOUSE_ID,
  currentDate: "2026-08-01",
  queueId: "00000000-0000-4000-8000-000000000201",
  queueVersion: 4,
  repairPlaceCount: 0,
  occupiedRepairPlaceCount: 0,
  usedRepairPlaceCount: 0,
  availableRepairPlaceCount: 0,
  inboundRepairPlaceAvailable: false,
  automaticRefillDelayMinutes: 5,
  repairPlacesOverCapacity: false,
  repairPlaces: [],
  current: [],
  dates: [
    {
      date: "2026-08-01",
      tasks: [firstShipment, movement, secondShipment, petrReturn],
    },
    { date: "2026-08-02", tasks: [unassignedReturn] },
  ],
  capitalRepairs: [],
}

function renderPage(currentBoard: DriverBoard = board) {
  apiMocks.getDriverBoard.mockResolvedValue(currentBoard)
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <LogisticsBoardPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  contextMocks.useAuth.mockReturnValue({
    accessToken: "logistics-token",
    currentUser: user(),
  })
  contextMocks.useWarehouse.mockReturnValue({
    selectedWarehouseId: WAREHOUSE_ID,
  })
  apiMocks.moveDriverBoardTask.mockResolvedValue({})
  apiMocks.pinDriverBoardTask.mockResolvedValue({})
  directoryMocks.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "00000000-0000-4000-8000-000000000013",
      warehouseId: WAREHOUSE_ID,
      name: "Водители",
      active: true,
      queueIds: [],
      routeQueueKinds: ["MOVEMENT"],
      members: [
        { id: IVAN_ID, name: "Иванов Иван" },
        { id: PETR_ID, name: "Петров Пётр" },
      ],
    },
  ])
  dndMocks.sortableData.clear()
  dndMocks.droppableData.clear()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("LogisticsBoardPage", () => {
  it("renders date columns with collapsible driver queues and only shipment or return cards", async () => {
    const actor = userEvent.setup()
    renderPage()

    const firstDate = await screen.findByLabelText(/^Логистика на .*1 августа/i)
    const ivanQueue = within(firstDate).getByLabelText(
      "Очередь водителя: Иванов Иван"
    )
    const petrQueue = within(firstDate).getByLabelText(
      "Очередь водителя: Петров Пётр"
    )
    expect(
      within(ivanQueue).getByTestId(
        `logistics-task-${firstShipment.externalTaskId}`
      )
    ).toBeTruthy()
    expect(within(ivanQueue).getByText("Бытовка БТ-ОТГ-1")).toBeTruthy()
    expect(
      within(petrQueue).getByTestId(
        `logistics-task-${petrReturn.externalTaskId}`
      )
    ).toBeTruthy()
    expect(screen.queryByText("БТ-ПЕР")).toBeNull()

    const secondDate = screen.getByLabelText(/^Логистика на .*2 августа/i)
    expect(
      within(secondDate).getByLabelText("Очередь водителя: Не назначено")
    ).toBeTruthy()

    await actor.click(
      within(ivanQueue).getByRole("button", {
        name: "Свернуть очередь водителя Иванов Иван",
      })
    )
    expect(
      within(ivanQueue).queryByTestId(
        `logistics-task-${firstShipment.externalTaskId}`
      )
    ).toBeNull()

    fireEvent.change(screen.getByLabelText("Дата заданий"), {
      target: { value: "2026-08-02" },
    })
    expect(screen.queryByLabelText(/^Логистика на .*1 августа/i)).toBeNull()
    expect(screen.getByLabelText(/^Логистика на .*2 августа/i)).toBeTruthy()
  })

  it("reorders only inside the same date, lane, and driver queue", async () => {
    renderPage()
    await screen.findByTestId(`logistics-task-${firstShipment.externalTaskId}`)
    const source = dndMocks.sortableData.get(
      `logistics-task:${firstShipment.externalTaskId}`
    )
    const endOfIvanQueue = dndMocks.droppableData.get(
      `logistics-slot:2026-08-01:SCHEDULED:driver:${IVAN_ID}:2`
    )

    act(() => {
      dndMocks.onDragEnd?.({
        active: { data: { current: source } },
        over: { data: { current: endOfIvanQueue } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
        accessToken: "logistics-token",
        externalTaskId: firstShipment.externalTaskId,
        command: {
          warehouseId: WAREHOUSE_ID,
          expectedTaskVersion: 7,
          expectedEntryVersion: 11,
          targetLane: "SCHEDULED",
          targetDate: "2026-08-01",
          targetIndex: 2,
        },
      })
    })
    expect(
      apiMocks.moveDriverBoardTask.mock.calls[0]?.[0].command
    ).not.toHaveProperty("targetDriverAudience")
  })

  it("renders a grouped shipment with its client and cabin list without a priority badge", async () => {
    const groupedShipment = card(
      "00000000-0000-4000-8000-000000000027",
      {
        title: "Отгрузка клиенту ООО Тест",
        taskText: "Клиент: ООО Тест\nБытовки: БТ-ОТГ-1, БТ-ОТГ-2",
        unitNumber: "2 бытовки",
        position: 0,
      }
    )
    renderPage({
      ...board,
      dates: [{ date: "2026-08-01", tasks: [groupedShipment] }],
    })

    const groupedCard = await screen.findByTestId(
      `logistics-task-${groupedShipment.externalTaskId}`
    )
    expect(
      within(groupedCard).getByText("Бытовки: 2")
    ).toBeTruthy()
    const details = within(groupedCard).getByLabelText("Детали задания")
    expect(details.textContent).toContain("Клиент: ООО Тест")
    expect(details.textContent).toContain("Бытовки: БТ-ОТГ-1, БТ-ОТГ-2")
    expect(screen.queryByText(/Приоритет/)).toBeNull()
  })

  it("ignores drops onto another driver, date, or lane", async () => {
    const currentIvan = card("00000000-0000-4000-8000-000000000026", {
      unitNumber: "БТ-ТЕК",
      lane: "CURRENT",
      workflowState: "CURRENT",
      scheduledDate: board.currentDate,
    })
    renderPage({ ...board, current: [currentIvan] })
    await screen.findByTestId(`logistics-task-${firstShipment.externalTaskId}`)
    const source = dndMocks.sortableData.get(
      `logistics-task:${firstShipment.externalTaskId}`
    )
    const anotherDriver = dndMocks.sortableData.get(
      `logistics-task:${petrReturn.externalTaskId}`
    )
    const anotherDate = dndMocks.sortableData.get(
      `logistics-task:${unassignedReturn.externalTaskId}`
    )
    const anotherLane = dndMocks.sortableData.get(
      `logistics-task:${currentIvan.externalTaskId}`
    )

    for (const target of [anotherDriver, anotherDate, anotherLane]) {
      act(() => {
        dndMocks.onDragEnd?.({
          active: { data: { current: source } },
          over: { data: { current: target } },
        })
      })
    }

    expect(apiMocks.moveDriverBoardTask).not.toHaveBeenCalled()
  })
})
