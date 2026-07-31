import type { ReactNode } from "react"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  DriverBoard,
  DriverBoardCard,
} from "@/features/logistics/driver-board/driver-board-model"
import { DriverBoardPage } from "@/features/logistics/driver-board/driver-board-page"

const apiMocks = vi.hoisted(() => ({
  getDriverBoard: vi.fn(),
  moveDriverBoardTask: vi.fn(),
  promoteCapitalRepair: vi.fn(),
}))

const contextMocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
}))

const dndMocks = vi.hoisted(() => ({
  onDragStart: null as ((event: unknown) => void) | null,
  onDragEnd: null as ((event: unknown) => void) | null,
  sortableData: new Map<string, unknown>(),
  draggableData: new Map<string, unknown>(),
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
    promoteCapitalRepair: apiMocks.promoteCapitalRepair,
  }
})
vi.mock("@dnd-kit/core", () => ({
  closestCenter: vi.fn(),
  DndContext: ({
    children,
    onDragStart,
    onDragEnd,
  }: {
    children: ReactNode
    onDragStart: (event: unknown) => void
    onDragEnd: (event: unknown) => void
  }) => {
    dndMocks.onDragStart = onDragStart
    dndMocks.onDragEnd = onDragEnd
    return children
  },
  DragOverlay: ({ children }: { children: ReactNode }) => children,
  KeyboardSensor: class KeyboardSensor {},
  PointerSensor: class PointerSensor {},
  TouchSensor: class TouchSensor {},
  useDraggable: ({ id, data }: { id: string; data: unknown }) => {
    dndMocks.draggableData.set(id, data)
    return {
      attributes: {},
      listeners: {},
      setNodeRef: vi.fn(),
      transform: null,
      isDragging: false,
    }
  },
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
const CURRENT_EXTERNAL_ID = "00000000-0000-4000-8000-000000000010"
const SCHEDULED_EXTERNAL_ID = "00000000-0000-4000-8000-000000000020"
const SECOND_EXTERNAL_ID = "00000000-0000-4000-8000-000000000021"
const CAPITAL_REPAIR_ID = "00000000-0000-4000-8000-000000000030"

function user(): CurrentUser {
  return {
    id: "driver-board-manager",
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
    driverTaskId: "00000000-0000-4000-8000-000000000100",
    externalTaskId,
    taskBoardTaskId: "00000000-0000-4000-8000-000000000101",
    taskBoardTaskVersion: 7,
    taskBoardEntryId: "00000000-0000-4000-8000-000000000102",
    taskBoardEntryVersion: 11,
    title: "Доставить бытовку",
    taskText: null,
    unitNumber: "БТ-100",
    kind: "DELIVER_TO_REPAIR",
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

const board: DriverBoard = {
  warehouseId: WAREHOUSE_ID,
  queueId: "00000000-0000-4000-8000-000000000200",
  queueVersion: 4,
  repairPlaceCount: 3,
  occupiedRepairPlaceCount: 2,
  availableRepairPlaceCount: 1,
  repairPlacesOverCapacity: false,
  current: [
    card(CURRENT_EXTERNAL_ID, {
      title: "Вывезти готовую бытовку",
      unitNumber: "БТ-001",
      kind: "REMOVE_FROM_REPAIR",
      workflowState: "CURRENT",
      entryStatus: "IN_PROGRESS",
      lane: "CURRENT",
    }),
  ],
  dates: [
    {
      date: "2026-08-01",
      tasks: [card(SCHEDULED_EXTERNAL_ID)],
    },
    {
      date: "2026-08-02",
      tasks: [
        card(SECOND_EXTERNAL_ID, {
          scheduledDate: "2026-08-02",
          unitNumber: "БТ-101",
        }),
      ],
    },
    {
      date: "2026-08-03",
      tasks: [],
    },
  ],
  capitalRepairs: [
    {
      repairId: CAPITAL_REPAIR_ID,
      repairVersion: 2,
      cabinId: "00000000-0000-4000-8000-000000000031",
      unitNumber: "БТ-КАП",
      priority: 1,
      complexityName: "Капитальный ремонт",
      complexityColor: "#7C3AED",
      plannedMinutes: "540.000",
      forcedCapital: true,
    },
  ],
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
      <DriverBoardPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  contextMocks.useAuth.mockReturnValue({
    accessToken: "driver-token",
    currentUser: user(),
  })
  contextMocks.useWarehouse.mockReturnValue({
    selectedWarehouseId: WAREHOUSE_ID,
  })
  apiMocks.moveDriverBoardTask.mockResolvedValue({})
  apiMocks.promoteCapitalRepair.mockResolvedValue({})
  dndMocks.sortableData.clear()
  dndMocks.draggableData.clear()
  dndMocks.droppableData.clear()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("DriverBoardPage", () => {
  it("renders fixed current and capital blocks around finite non-empty dates", async () => {
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Текущее задание" })
    ).toBeTruthy()
    expect(
      screen.getByRole("heading", { name: "Капитальные ремонты" })
    ).toBeTruthy()
    expect(
      screen.getByLabelText("Ремонтные места: 2 из 3 занято").textContent
    ).toContain("2/3")
    expect(
      screen.getByLabelText("Запланированные задания по датам")
    ).toBeTruthy()
    expect(screen.getByLabelText(/Задания на .*1 августа/i)).toBeTruthy()
    expect(screen.getByLabelText(/Задания на .*2 августа/i)).toBeTruthy()
    expect(screen.queryByLabelText(/Задания на .*3 августа/i)).toBeNull()
    expect(
      screen.queryByLabelText("Переместить задание бытовки БТ-001")
    ).toBeNull()
  })

  it("opens a date-only dialog after dropping into the empty date area", async () => {
    const actor = userEvent.setup()
    renderPage()
    await screen.findByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${SCHEDULED_EXTERNAL_ID}`
    )
    expect(dragData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: { type: "new-date" } } },
      })
    })

    const dateInput = screen.getByLabelText("Дата")
    expect(dateInput.getAttribute("type")).toBe("date")
    fireEvent.change(dateInput, { target: { value: "2026-08-05" } })
    await actor.click(screen.getByRole("button", { name: "Переместить" }))

    expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
      accessToken: "driver-token",
      externalTaskId: SCHEDULED_EXTERNAL_ID,
      command: {
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
        expectedEntryVersion: 11,
        targetDate: "2026-08-05",
        targetIndex: 0,
      },
    })
  })

  it("promotes one capital repair when its card is dropped into CURRENT", async () => {
    renderPage()
    await screen.findByTestId(`capital-repair-${CAPITAL_REPAIR_ID}`)

    const dragData = dndMocks.draggableData.get(
      `capital-repair:${CAPITAL_REPAIR_ID}`
    )
    expect(dragData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: { type: "current" } } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.promoteCapitalRepair).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "driver-token",
          repairId: CAPITAL_REPAIR_ID,
          warehouseId: WAREHOUSE_ID,
          idempotencyKey: expect.any(String),
        })
      )
    })
  })
})
