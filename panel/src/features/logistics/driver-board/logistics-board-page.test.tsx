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
  DriverTripDetails,
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
  getDriverTask: vi.fn(),
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
    getDriverTask: apiMocks.getDriverTask,
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
    tripDetails: tripDetails(),
    ...params,
  }
}

function tripDetails(
  params: Partial<DriverTripDetails> = {}
): DriverTripDetails {
  return {
    taskNumber: "123",
    tripNumber: 1,
    operationType: "SHIPMENT",
    clientName: "ООО Тест",
    address: "Санкт-Петербург, Невский проспект, 1",
    latitude: 59.9343,
    longitude: 30.3351,
    primaryContactName: "Анна",
    primaryContactPhone: "+79990000001",
    additionalContacts: [
      { name: "Пётр", phone: "+79990000002" },
      { name: "Пётр", phone: "+79990000002" },
    ],
    comment: "Позвонить за час",
    desiredDeliveryWindows: [
      {
        startDate: "2026-08-01",
        endDate: "2026-08-02",
      },
    ],
    scheduledDate: "2026-08-03",
    cabins: [
      {
        cabinId: "00000000-0000-4000-8000-000000000301",
        unitNumber: "БТ-ОТГ-1",
        desiredContents: [
          {
            equipmentId: "00000000-0000-4000-8000-000000000401",
            equipmentName: "Кровать",
            quantity: 4,
          },
        ],
        actualContents: null,
        movementTaskCreated: true,
        movementTaskCompleted: false,
        contentReady: null,
      },
      {
        cabinId: "00000000-0000-4000-8000-000000000302",
        unitNumber: "БТ-ОТГ-2",
        desiredContents: [],
        actualContents: [],
        movementTaskCreated: false,
        movementTaskCompleted: false,
        contentReady: true,
      },
    ],
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
  apiMocks.getDriverTask.mockResolvedValue({
    id: "driver-task",
    tripDetails: null,
  })
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
  it("renders date columns with collapsible driver queues and only grouped shipment or return cards", async () => {
    const actor = userEvent.setup()
    renderPage()

    const firstDate = await screen.findByLabelText(/^Логистика на .*1 августа/i)
    const ivanQueue = within(firstDate).getByLabelText(
      "Очередь водителя: Иванов Иван"
    )
    const petrQueue = within(firstDate).getByLabelText(
      "Очередь водителя: Петров Пётр"
    )
    const firstCard = within(ivanQueue).getByTestId(
      `logistics-task-${firstShipment.externalTaskId}`
    )
    expect(firstCard).toBeTruthy()
    expect(within(firstCard).getByText("Бытовка - БТ-ОТГ-1")).toBeTruthy()
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

  it("renders only grouped shipment or return cards and never exposes legacy task text", async () => {
    const legacyShipment = card("00000000-0000-4000-8000-000000000026", {
      title: "Устаревшее задание №77",
      taskText: "Устаревший текст задания",
      tripDetails: null,
    })
    const groupedShipment = card("00000000-0000-4000-8000-000000000027", {
      title: "Старый заголовок отгрузки",
      taskText: "Старый текст отгрузки",
      unitNumber: "2 бытовки",
      position: 1,
    })
    renderPage({
      ...board,
      dates: [{ date: "2026-08-01", tasks: [legacyShipment, groupedShipment] }],
    })

    const groupedCard = await screen.findByTestId(
      `logistics-task-${groupedShipment.externalTaskId}`
    )
    expect(within(groupedCard).getByText("Отгрузить бытовку")).toBeTruthy()
    expect(within(groupedCard).getByText("ООО Тест · Бытовки: 2")).toBeTruthy()
    expect(
      screen.queryByTestId(`logistics-task-${legacyShipment.externalTaskId}`)
    ).toBeNull()
    expect(screen.queryByText("Устаревшее задание №77")).toBeNull()
    expect(screen.queryByText("Устаревший текст задания")).toBeNull()
    expect(screen.queryByText("Старый заголовок отгрузки")).toBeNull()
    expect(screen.queryByText("Старый текст отгрузки")).toBeNull()
    expect(screen.queryByText(/Задание №/)).toBeNull()
    expect(screen.queryByText(/Ходка №/)).toBeNull()
  })

  it("uses compact trip details and loads current actual filling in the dialog", async () => {
    const actor = userEvent.setup()
    const summary = tripDetails()
    const groupedShipment = card("00000000-0000-4000-8000-000000000028", {
      driverTaskId: "00000000-0000-4000-8000-000000000128",
      title: "Отгрузка клиенту",
      unitNumber: "2 бытовки",
      tripDetails: summary,
    })
    const liveDetails = tripDetails({
      cabins: summary.cabins.map((cabin, index) => ({
        ...cabin,
        actualContents: [
          {
            equipmentId: "00000000-0000-4000-8000-000000000402",
            equipmentName: index === 0 ? "Стол" : "Стул",
            quantity: index === 0 ? 4 : 2,
            locationKind: "RENTAL_ITEM",
          },
        ],
        movementTaskCompleted: index === 0,
        contentReady: index === 0,
      })),
    })
    apiMocks.getDriverTask.mockResolvedValue({
      id: groupedShipment.driverTaskId,
      tripDetails: liveDetails,
    })
    renderPage({
      ...board,
      dates: [{ date: "2026-08-03", tasks: [groupedShipment] }],
    })

    const groupedCard = await screen.findByTestId(
      `logistics-task-${groupedShipment.externalTaskId}`
    )
    expect(within(groupedCard).getByText("Отгрузить бытовку")).toBeTruthy()
    expect(within(groupedCard).queryByText(/Задание №/)).toBeNull()
    expect(within(groupedCard).queryByText(/Ходка №/)).toBeNull()
    expect(within(groupedCard).queryByText("Отгрузка")).toBeNull()
    expect(within(groupedCard).getByText("ООО Тест · Бытовки: 2")).toBeTruthy()
    expect(within(groupedCard).getByText("Водитель: Иванов Иван")).toBeTruthy()
    expect(
      within(groupedCard).getByText("Санкт-Петербург, Невский проспект, 1")
    ).toBeTruthy()
    expect(
      within(groupedCard).getByText("Координаты: 59.9343, 30.3351")
    ).toBeTruthy()
    expect(
      within(groupedCard).getAllByText("Пётр · +79990000002")
    ).toHaveLength(2)
    expect(within(groupedCard).getByText("Бытовка - БТ-ОТГ-1")).toBeTruthy()
    expect(within(groupedCard).getByText("Бытовка - БТ-ОТГ-2")).toBeTruthy()
    expect(
      within(groupedCard).getByText(/Дата выполнения задания:/)
    ).toBeTruthy()
    expect(within(groupedCard).queryByText("Желаемые даты клиента")).toBeNull()
    expect(within(groupedCard).queryByText(/Фактически назначено/)).toBeNull()
    expect(within(groupedCard).queryByText(/Нужно по заказу/)).toBeNull()
    expect(within(groupedCard).queryByText(/Фактически в бытовке/)).toBeNull()
    expect(within(groupedCard).queryByText(/Перемещение мебели/)).toBeNull()
    expect(
      within(groupedCard).queryByText(/Недоступно в карточке доски/i)
    ).toBeNull()
    expect(
      within(groupedCard).getAllByLabelText(/^Статус наполнения:/)
    ).toHaveLength(2)
    expect(within(groupedCard).getByText("Ожидает наполнения")).toBeTruthy()
    expect(within(groupedCard).getByText("Нет наполнения")).toBeTruthy()
    expect(
      [...dndMocks.sortableData.keys()].filter((key) =>
        key.startsWith("logistics-task:")
      )
    ).toEqual([`logistics-task:${groupedShipment.externalTaskId}`])

    await actor.click(
      within(groupedCard).getByRole("button", { name: "Подробности ходки" })
    )
    const dialog = await screen.findByRole("dialog")
    await waitFor(() => {
      expect(apiMocks.getDriverTask).toHaveBeenCalledWith(
        "logistics-token",
        groupedShipment.driverTaskId
      )
    })
    expect(within(dialog).getByText("Стол: 4шт")).toBeTruthy()
    expect(within(dialog).getByText("Стул: 2шт")).toBeTruthy()
    expect(within(dialog).getByText("Ожидает наполнения")).toBeTruthy()
    expect(within(dialog).getByText("Ожидает выноса наполнения")).toBeTruthy()
    expect(within(dialog).queryByText(/Задание №/)).toBeNull()
    expect(within(dialog).queryByText(/Ходка №/)).toBeNull()
  })

  it("shows exactly one filling status for every cabin", async () => {
    const statusTrip = tripDetails({
      cabins: [
        {
          cabinId: "00000000-0000-4000-8000-000000000311",
          unitNumber: "БТ-ПУСТО",
          desiredContents: [],
          actualContents: [],
          movementTaskCreated: false,
          movementTaskCompleted: false,
          contentReady: false,
        },
        {
          cabinId: "00000000-0000-4000-8000-000000000312",
          unitNumber: "БТ-ЖДЕТ",
          desiredContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000411",
              equipmentName: "Стол",
              quantity: 4,
            },
          ],
          actualContents: [],
          movementTaskCreated: false,
          movementTaskCompleted: false,
          contentReady: true,
        },
        {
          cabinId: "00000000-0000-4000-8000-000000000313",
          unitNumber: "БТ-ВЫНОС",
          desiredContents: [],
          actualContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000412",
              equipmentName: "Стул",
              quantity: 2,
              locationKind: "RENTAL_ITEM",
            },
          ],
          movementTaskCreated: false,
          movementTaskCompleted: true,
          contentReady: true,
        },
        {
          cabinId: "00000000-0000-4000-8000-000000000314",
          unitNumber: "БТ-ГОТОВО",
          desiredContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000413",
              equipmentName: "Стол",
              quantity: 4,
            },
          ],
          actualContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000413",
              equipmentName: "Стол",
              quantity: 4,
              locationKind: "RENTAL_ITEM",
            },
          ],
          movementTaskCreated: true,
          movementTaskCompleted: true,
          contentReady: false,
        },
        {
          cabinId: "00000000-0000-4000-8000-000000000315",
          unitNumber: "БТ-НЕДОСТУПНО",
          desiredContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000414",
              equipmentName: "Кровать",
              quantity: 1,
            },
          ],
          actualContents: null,
          movementTaskCreated: false,
          movementTaskCompleted: false,
          contentReady: true,
        },
        {
          cabinId: "00000000-0000-4000-8000-000000000316",
          unitNumber: "БТ-В-РАБОТЕ",
          desiredContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000415",
              equipmentName: "Стол",
              quantity: 1,
            },
          ],
          actualContents: [
            {
              equipmentId: "00000000-0000-4000-8000-000000000415",
              equipmentName: "Стол",
              quantity: 1,
              locationKind: "RENTAL_ITEM",
            },
          ],
          movementTaskCreated: true,
          movementTaskCompleted: false,
          contentReady: true,
        },
      ],
    })
    const groupedShipment = card("00000000-0000-4000-8000-000000000034", {
      tripDetails: statusTrip,
    })
    renderPage({
      ...board,
      dates: [{ date: "2026-08-01", tasks: [groupedShipment] }],
    })

    const groupedCard = await screen.findByTestId(
      `logistics-task-${groupedShipment.externalTaskId}`
    )
    const statuses =
      within(groupedCard).getAllByLabelText(/^Статус наполнения:/)
    expect(statuses).toHaveLength(statusTrip.cabins.length)
    expect(statuses.map((status) => status.textContent)).toEqual([
      "Нет наполнения",
      "Ожидает наполнения",
      "Ожидает выноса наполнения",
      "Наполнение готово",
      "Ожидает наполнения",
      "Ожидает наполнения",
    ])
    expect(within(groupedCard).getByText("Стол: 4шт")).toBeTruthy()
    expect(within(groupedCard).getByText("Стул: 2шт")).toBeTruthy()
  })

  it("moves a grouped trip with two cabin cards through one board command", async () => {
    const groupedShipment = card("00000000-0000-4000-8000-000000000029", {
      position: 0,
      tripDetails: tripDetails(),
    })
    const nextTrip = card("00000000-0000-4000-8000-000000000030", {
      position: 1,
      tripDetails: tripDetails({ taskNumber: "124", tripNumber: 2 }),
    })
    renderPage({
      ...board,
      dates: [{ date: "2026-08-01", tasks: [groupedShipment, nextTrip] }],
    })
    await screen.findByTestId(
      `logistics-task-${groupedShipment.externalTaskId}`
    )
    const source = dndMocks.sortableData.get(
      `logistics-task:${groupedShipment.externalTaskId}`
    )
    const target = dndMocks.sortableData.get(
      `logistics-task:${nextTrip.externalTaskId}`
    )

    act(() => {
      dndMocks.onDragEnd?.({
        active: { data: { current: source } },
        over: { data: { current: target } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledTimes(1)
    })
    expect(apiMocks.moveDriverBoardTask.mock.calls[0]?.[0].externalTaskId).toBe(
      groupedShipment.externalTaskId
    )
  })

  it("shows grouped shipment and return trips while hiding transfers", async () => {
    const shipment = card("00000000-0000-4000-8000-000000000031", {
      tripDetails: tripDetails(),
    })
    const pickup = card("00000000-0000-4000-8000-000000000032", {
      kind: "RETURN",
      driverAudience: {
        mode: "ASSIGNED_DRIVER",
        workerId: PETR_ID,
        workerName: "Петров Пётр",
      },
      scheduledDate: "2026-08-02",
      tripDetails: tripDetails({
        taskNumber: "124",
        tripNumber: 2,
        operationType: "RETURN",
        clientName: "ИП Клиент",
        scheduledDate: "2026-08-02",
      }),
    })
    const transfer = card("00000000-0000-4000-8000-000000000033", {
      kind: "TRANSFER",
      scheduledDate: "2026-08-03",
      tripDetails: tripDetails({
        taskNumber: "125",
        tripNumber: 3,
        operationType: "TRANSFER",
        clientName: "Склад назначения",
        scheduledDate: "2026-08-03",
      }),
    })
    renderPage({
      ...board,
      dates: [
        { date: "2026-08-01", tasks: [shipment] },
        { date: "2026-08-02", tasks: [pickup] },
        { date: "2026-08-03", tasks: [transfer] },
      ],
    })

    expect(await screen.findByText("ООО Тест · Бытовки: 2")).toBeTruthy()
    expect(screen.getByText("ИП Клиент · Бытовки: 2")).toBeTruthy()
    expect(screen.getByText("Отгрузить бытовку")).toBeTruthy()
    expect(screen.getByText("Вернуть бытовку")).toBeTruthy()
    expect(screen.queryByText("Склад назначения · Бытовки: 2")).toBeNull()
    expect(screen.queryByText("Перемещение")).toBeNull()
    expect(screen.getAllByLabelText(/^Логистика на /i)).toHaveLength(2)
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
