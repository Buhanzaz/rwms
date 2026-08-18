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
import { DriverBoardPage } from "@/features/logistics/driver-board/driver-board-page"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { ApiError } from "@/lib/api-client"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  releasePointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
  setPointerCapture: { configurable: true, value: () => undefined },
})

const apiMocks = vi.hoisted(() => ({
  createManualMovement: vi.fn(),
  getDriverBoard: vi.fn(),
  moveDriverBoardTask: vi.fn(),
  pinDriverBoardTask: vi.fn(),
  promoteCapitalRepair: vi.fn(),
  returnCapitalRepair: vi.fn(),
  scheduleCapitalRepair: vi.fn(),
}))

const assetApiMocks = vi.hoisted(() => ({
  listAssetRentalItems: vi.fn(),
}))

const repairTaskApiMocks = vi.hoisted(() => ({
  getRepairTask: vi.fn(),
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
  capitalPointerDown: vi.fn(),
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
    createManualMovement: apiMocks.createManualMovement,
    getDriverBoard: apiMocks.getDriverBoard,
    moveDriverBoardTask: apiMocks.moveDriverBoardTask,
    pinDriverBoardTask: apiMocks.pinDriverBoardTask,
    promoteCapitalRepair: apiMocks.promoteCapitalRepair,
    returnCapitalRepair: apiMocks.returnCapitalRepair,
    scheduleCapitalRepair: apiMocks.scheduleCapitalRepair,
  }
})
vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: assetApiMocks.listAssetRentalItems,
}))
vi.mock("@/features/repair-tasks/api/repair-tasks-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/repair-tasks/api/repair-tasks-api")
  >("@/features/repair-tasks/api/repair-tasks-api")

  return {
    ...actual,
    getRepairTask: repairTaskApiMocks.getRepairTask,
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
      listeners: { onPointerDown: dndMocks.capitalPointerDown },
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

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

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
    driverAudience: {
      mode: "UNASSIGNED",
      workerId: null,
      workerName: null,
    },
    workflowState: "SCHEDULED",
    taskStatus: "ACTIVE",
    entryStatus: "WAITING",
    scheduledDate: "2026-08-01",
    lane: "SCHEDULED",
    priority: 3,
    pinned: false,
    position: 0,
    tripDetails: null,
    ...params,
  }
}

const board: DriverBoard = {
  warehouseId: WAREHOUSE_ID,
  currentDate: "2026-08-01",
  queueId: "00000000-0000-4000-8000-000000000200",
  queueVersion: 4,
  repairPlaceCount: 3,
  occupiedRepairPlaceCount: 1,
  usedRepairPlaceCount: 2,
  availableRepairPlaceCount: 1,
  inboundRepairPlaceAvailable: true,
  automaticRefillDelayMinutes: 5,
  repairPlacesOverCapacity: false,
  repairPlaces: [
    {
      repairId: "00000000-0000-4000-8000-000000000062",
      cabinId: "00000000-0000-4000-8000-000000000063",
      unitNumber: "БТ-062",
      allocationState: "OCCUPIED",
      repairStageName: "Электрика",
      repairStageState: "IN_PROGRESS",
      priority: 2,
    },
  ],
  current: [
    card(CURRENT_EXTERNAL_ID, {
      title: "Вывезти готовую бытовку",
      unitNumber: "БТ-001",
      kind: "REMOVE_FROM_REPAIR",
      workflowState: "CURRENT",
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

function repairLine(
  id: string,
  params: Partial<RepairEstimateLineDto> = {}
): RepairEstimateLineDto {
  return {
    id,
    sourceLineKey: id,
    lineType: "WORK",
    description: "Работа без названия",
    lineComment: "",
    unit: "шт.",
    quantity: 1,
    normativeMinutes: 30,
    unitPrice: "0.00",
    lineTotal: "0.00",
    catalogSnapshot: null,
    customQueueBinding: null,
    maintenanceMediaReferences: [],
    rework: null,
    ...params,
  }
}

function repairStage({
  id,
  sortOrder,
  queueName,
  workLines = [],
  materialLines = [],
}: {
  id: string
  sortOrder: number
  queueName: string
  workLines?: RepairEstimateLineDto[]
  materialLines?: RepairEstimateLineDto[]
}): RepairTaskDto["subtasks"][number] {
  return {
    id,
    taskBoardEntryId: null,
    externalTaskId: null,
    taskTitle: null,
    taskText: null,
    kind: "REPAIR_WORK",
    status: "WAITING",
    workLines,
    materialLines,
    primaryLineId: workLines[0]?.id ?? null,
    groupComment: "",
    evidence: [],
    queueName,
    queueId: null,
    routeQueueKind: "REPAIR",
    sortOrder,
    entryType: "REAL",
    queuePosition: sortOrder,
    priority: 1,
    pinned: false,
    plannedDurationMinutes: null,
    startedAt: null,
    completedAt: null,
    activeStartedAt: null,
    activeWorkSeconds: 0,
    workerGroup: null,
    assignments: [],
  }
}

function repairTaskDetails(): RepairTaskDto {
  return {
    id: CAPITAL_REPAIR_ID,
    version: 2,
    status: "QUEUED",
    kind: "REPAIR",
    origin: "ESTIMATE",
    acceptanceStatus: "NOT_READY",
    startedAt: null,
    completedAt: null,
    warehouseId: WAREHOUSE_ID,
    rentalItemId: "00000000-0000-4000-8000-000000000031",
    cabinNumber: "БТ-КАП",
    actorId: "driver-board-manager",
    sourceParty: null,
    dispatchDate: null,
    priority: 1,
    maintenanceMediaReferences: [],
    coverMediaId: null,
    subtasks: [
      repairStage({
        id: "00000000-0000-4000-8000-000000000082",
        sortOrder: 1,
        queueName: "Малярный цех",
      }),
      repairStage({
        id: "00000000-0000-4000-8000-000000000081",
        sortOrder: 0,
        queueName: "Столярный цех",
        workLines: [
          repairLine("00000000-0000-4000-8000-000000000083", {
            description: "Заменить оконный блок",
            quantity: 2,
            unit: "шт.",
            catalogSnapshot: {
              nodeId: "00000000-0000-4000-8000-000000000084",
              name: "Замена окна",
              nodeType: "WORK",
              furnitureEquipment: null,
              characteristic: null,
            },
          }),
        ],
        materialLines: [
          repairLine("00000000-0000-4000-8000-000000000085", {
            lineType: "MATERIAL",
            description: "Пена монтажная",
            quantity: 3.5,
            unit: "л",
            normativeMinutes: 0,
          }),
        ],
      }),
    ],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    readyAt: null,
    writtenOffAt: null,
    decisionActorId: null,
    taskBoardAvailable: true,
    awaitingMovement: false,
    movementToRepair: true,
    forceCapitalRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    createdAt: "2026-08-01T08:00:00Z",
    updatedAt: "2026-08-01T08:00:00Z",
  }
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
  apiMocks.returnCapitalRepair.mockResolvedValue(undefined)
  apiMocks.scheduleCapitalRepair.mockResolvedValue(
    card("00000000-0000-4000-8000-000000000099", {
      title: "Переместить бытовку на производство",
      kind: "CAPITAL_TO_PRODUCTION",
      workflowState: "SCHEDULED",
      lane: "SCHEDULED",
    })
  )
  apiMocks.createManualMovement.mockResolvedValue({})
  apiMocks.pinDriverBoardTask.mockResolvedValue({})
  assetApiMocks.listAssetRentalItems.mockResolvedValue({
    content: [
      {
        id: "00000000-0000-4000-8000-000000000040",
        number: "БТ-040",
      },
    ],
    page: 0,
    size: 200,
    totalElements: 1,
    totalPages: 1,
  })
  repairTaskApiMocks.getRepairTask.mockResolvedValue(repairTaskDetails())
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
      await screen.findByRole("heading", { name: "Текущие задания" })
    ).toBeTruthy()
    expect(
      within(
        screen.getByLabelText(/Текущие задания на .*01 августа/i)
      ).getByText(/сб.*, 01 августа/i)
    ).toBeTruthy()
    expect(screen.queryByRole("heading", { name: "Перемещение" })).toBeNull()
    expect(
      screen.queryByText(
        "Текущие перемещения, запланированные даты и капитальные ремонты."
      )
    ).toBeNull()
    expect(
      screen.getByRole("heading", { name: "Капитальные ремонты" })
    ).toBeTruthy()
    expect(
      screen.getByLabelText("Ремонтные места: 1 из 3 занято").textContent
    ).toContain("1 из 3")
    expect(
      screen.getByLabelText("Запланированные задания по датам")
    ).toBeTruthy()
    const newDateDropZone = screen.getByTestId("driver-new-date-drop-zone")
    expect(newDateDropZone.classList.contains("flex-1")).toBe(true)
    expect(newDateDropZone.classList.contains("self-stretch")).toBe(true)
    expect(newDateDropZone.classList.contains("h-full")).toBe(true)
    expect(newDateDropZone.classList.contains("min-h-full")).toBe(true)
    expect(screen.getByLabelText(/^Задания на .*1 августа/i)).toBeTruthy()
    expect(screen.getByLabelText(/^Задания на .*2 августа/i)).toBeTruthy()
    expect(screen.queryByLabelText(/^Задания на .*3 августа/i)).toBeNull()
    expect(
      screen.getByLabelText("Переместить задание бытовки БТ-001")
    ).toBeTruthy()
  })

  it("shows one shared movement queue without logistics cards or driver sections", async () => {
    const movement = card("00000000-0000-4000-8000-000000000044", {
      title: "Переставить бытовку",
      unitNumber: "БТ-ПЕР",
      driverAudience: {
        mode: "ASSIGNED_DRIVER",
        workerId: "00000000-0000-4000-8000-000000000045",
        workerName: "Иванов Иван",
      },
    })
    const shipment = card("00000000-0000-4000-8000-000000000046", {
      title: "Отгрузить бытовку",
      unitNumber: "БТ-ОТГ",
      kind: "SHIPMENT",
      position: 1,
    })
    const returnTask = card("00000000-0000-4000-8000-000000000047", {
      title: "Вернуть бытовку",
      unitNumber: "БТ-ВОЗ",
      kind: "RETURN",
      lane: "CURRENT",
      workflowState: "CURRENT",
    })
    renderPage({
      ...board,
      current: [returnTask],
      dates: [{ date: board.currentDate, tasks: [movement, shipment] }],
    })

    expect(
      await screen.findByTestId(`scheduled-task-${movement.externalTaskId}`)
    ).toBeTruthy()
    expect(
      screen.queryByTestId(`scheduled-task-${shipment.externalTaskId}`)
    ).toBeNull()
    expect(
      screen.queryByTestId(`current-task-${returnTask.externalTaskId}`)
    ).toBeNull()
    expect(screen.queryByText("Иванов Иван")).toBeNull()
    expect(screen.queryByLabelText(/Очередь водителя:/)).toBeNull()
  })

  it("filters scheduled columns by a chosen date and restores all dates", async () => {
    const actor = userEvent.setup()
    renderPage()

    await screen.findByLabelText(/^Задания на .*1 августа/i)
    fireEvent.change(screen.getByLabelText("Дата заданий"), {
      target: { value: "2026-08-02" },
    })

    expect(screen.queryByLabelText(/^Задания на .*1 августа/i)).toBeNull()
    expect(screen.getByLabelText(/^Задания на .*2 августа/i)).toBeTruthy()
    expect(screen.getByTestId("driver-new-date-drop-zone")).toBeTruthy()

    await actor.click(screen.getByRole("button", { name: "Все даты" }))

    expect(screen.getByLabelText(/^Задания на .*1 августа/i)).toBeTruthy()
    expect(screen.getByLabelText(/^Задания на .*2 августа/i)).toBeTruthy()
  })

  it("loads an ordered capital plan only after expansion and shows both line columns", async () => {
    const actor = userEvent.setup()
    renderPage()

    const expandButton = await screen.findByRole("button", {
      name: "Развернуть план капитального ремонта бытовки БТ-КАП",
    })
    expect(repairTaskApiMocks.getRepairTask).not.toHaveBeenCalled()

    fireEvent.pointerDown(expandButton)
    expect(dndMocks.capitalPointerDown).not.toHaveBeenCalled()
    await actor.click(expandButton)

    await waitFor(() => {
      expect(repairTaskApiMocks.getRepairTask).toHaveBeenCalledWith(
        CAPITAL_REPAIR_ID,
        WAREHOUSE_ID
      )
    })
    expect(
      screen
        .getByRole("button", {
          name: "Свернуть план капитального ремонта бытовки БТ-КАП",
        })
        .getAttribute("aria-expanded")
    ).toBe("true")
    const stages = await screen.findAllByRole("region", {
      name: /^Этап \d+: очередь /,
    })
    expect(stages).toHaveLength(2)
    expect(stages[0]?.getAttribute("aria-label")).toBe(
      "Этап 1: очередь Столярный цех"
    )
    expect(stages[1]?.getAttribute("aria-label")).toBe(
      "Этап 2: очередь Малярный цех"
    )

    const firstStage = stages[0]!
    expect(within(firstStage).getAllByRole("region")).toHaveLength(2)
    expect(
      within(firstStage).getByRole("heading", { name: "Работы" })
    ).toBeTruthy()
    expect(
      within(firstStage).getByRole("heading", { name: "Материалы" })
    ).toBeTruthy()
    expect(within(firstStage).getByText("Замена окна")).toBeTruthy()
    expect(within(firstStage).getByText("2 шт.")).toBeTruthy()
    expect(within(firstStage).getByText("Пена монтажная")).toBeTruthy()
    expect(within(firstStage).getByText("3,5 л")).toBeTruthy()
    fireEvent.pointerDown(firstStage)
    expect(dndMocks.capitalPointerDown).not.toHaveBeenCalled()

    const secondStage = stages[1]!
    expect(within(secondStage).getAllByRole("region")).toHaveLength(2)
    expect(
      within(secondStage).getByText("Работы не запланированы.")
    ).toBeTruthy()
    expect(
      within(secondStage).getByText("Материалы не запланированы.")
    ).toBeTruthy()
  })

  it("shows an honest capital-plan loading state", async () => {
    const pendingPlan = deferred<RepairTaskDto | null>()
    repairTaskApiMocks.getRepairTask.mockReturnValueOnce(pendingPlan.promise)
    const actor = userEvent.setup()
    renderPage()

    await actor.click(
      await screen.findByRole("button", {
        name: "Развернуть план капитального ремонта бытовки БТ-КАП",
      })
    )

    expect(
      await screen.findByRole("status", {
        name: "Загрузка плана капитального ремонта бытовки БТ-КАП",
      })
    ).toBeTruthy()

    await act(async () => {
      pendingPlan.resolve(repairTaskDetails())
      await pendingPlan.promise
    })
    expect(await screen.findByText("Замена окна")).toBeTruthy()
  })

  it("shows a capital-plan read error and retries without starting a drag", async () => {
    repairTaskApiMocks.getRepairTask.mockRejectedValueOnce(
      new ApiError("Сервис ремонта временно недоступен", 503, null)
    )
    const actor = userEvent.setup()
    renderPage()

    await actor.click(
      await screen.findByRole("button", {
        name: "Развернуть план капитального ремонта бытовки БТ-КАП",
      })
    )

    const capitalCard = screen.getByTestId(
      `capital-repair-${CAPITAL_REPAIR_ID}`
    )
    const errorAlert = await within(capitalCard).findByRole("alert")
    expect(
      within(errorAlert).getByText("Не удалось загрузить план ремонта")
    ).toBeTruthy()
    expect(
      within(errorAlert).getByText("Сервис ремонта временно недоступен")
    ).toBeTruthy()

    const retryButton = within(errorAlert).getByRole("button", {
      name: "Повторить",
    })
    fireEvent.pointerDown(retryButton)
    expect(dndMocks.capitalPointerDown).not.toHaveBeenCalled()
    await actor.click(retryButton)

    expect(await within(capitalCard).findByText("Замена окна")).toBeTruthy()
    expect(repairTaskApiMocks.getRepairTask).toHaveBeenCalledTimes(2)
  })

  it("keeps a scheduled repair delivery out of the empty repair-place list", async () => {
    const actor = userEvent.setup()
    renderPage({
      ...board,
      repairPlaceCount: 6,
      occupiedRepairPlaceCount: 0,
      usedRepairPlaceCount: 1,
      availableRepairPlaceCount: 5,
      repairPlaces: [],
      dates: [
        {
          ...board.dates[0],
          tasks: [
            card(SCHEDULED_EXTERNAL_ID, {
              unitNumber: "БТ-064",
              kind: "DELIVER_TO_REPAIR",
            }),
          ],
        },
        ...board.dates.slice(1),
      ],
    })

    const repairPlaces = await screen.findByLabelText(
      "Ремонтные места: 0 из 6 занято"
    )
    expect(within(repairPlaces).getByText("0 из 6")).toBeTruthy()
    expect(within(repairPlaces).getByText("Физически свободно: 6")).toBeTruthy()
    expect(
      screen.getByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`).textContent
    ).toContain("БТ-064")

    await actor.click(
      screen.getByRole("button", { name: "Развернуть список ремонтных мест" })
    )

    const repairPlaceList = screen.getByLabelText("Бытовки в ремонтных местах")
    expect(within(repairPlaceList).getByRole("status").textContent).toBe(
      "В ремонтных местах пока нет бытовок."
    )
    expect(within(repairPlaceList).queryByText("Бытовка БТ-064")).toBeNull()

    await actor.click(
      screen.getByRole("button", { name: "Свернуть список ремонтных мест" })
    )
    expect(screen.queryByLabelText("Бытовки в ремонтных местах")).toBeNull()
  })

  it("expands repair places with their truthful repair stage states", async () => {
    const actor = userEvent.setup()
    renderPage({
      ...board,
      occupiedRepairPlaceCount: 3,
      repairPlaces: [
        {
          repairId: "00000000-0000-4000-8000-000000000066",
          cabinId: "00000000-0000-4000-8000-000000000067",
          unitNumber: "БТ-066",
          allocationState: "OCCUPIED",
          repairStageName: "Электрика",
          repairStageState: "QUEUED",
          priority: 2,
        },
        {
          repairId: "00000000-0000-4000-8000-000000000068",
          cabinId: "00000000-0000-4000-8000-000000000069",
          unitNumber: "БТ-068",
          allocationState: "OCCUPIED",
          repairStageName: "Сантехника",
          repairStageState: "IN_PROGRESS",
          priority: 1,
        },
        {
          repairId: "00000000-0000-4000-8000-000000000070",
          cabinId: "00000000-0000-4000-8000-000000000071",
          unitNumber: "БТ-070",
          allocationState: "READY_TO_RELEASE",
          repairStageName: null,
          repairStageState: "DONE",
          priority: 3,
        },
      ],
    })

    await actor.click(
      await screen.findByRole("button", {
        name: "Развернуть список ремонтных мест",
      })
    )

    const repairPlaceList = screen.getByLabelText("Бытовки в ремонтных местах")
    expect(within(repairPlaceList).getByText("Бытовка БТ-066")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Ожидает")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Этап: Электрика")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Бытовка БТ-068")).toBeTruthy()
    expect(within(repairPlaceList).getByText("В работе")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Этап: Сантехника")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Бытовка БТ-070")).toBeTruthy()
    expect(within(repairPlaceList).getByText("Ожидает вывоза")).toBeTruthy()
    expect(
      screen
        .getByRole("button", { name: "Свернуть список ремонтных мест" })
        .getAttribute("aria-expanded")
    ).toBe("true")
  })

  it("shows an accessible empty repair-place message after expansion", async () => {
    const actor = userEvent.setup()
    renderPage({
      ...board,
      repairPlaceCount: 6,
      occupiedRepairPlaceCount: 0,
      usedRepairPlaceCount: 1,
      availableRepairPlaceCount: 5,
      repairPlaces: [],
    })

    await actor.click(
      await screen.findByRole("button", {
        name: "Развернуть список ремонтных мест",
      })
    )

    expect(screen.getByRole("status").textContent).toBe(
      "В ремонтных местах пока нет бытовок."
    )
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
    expect(dateInput.getAttribute("min")).toBe("2026-08-01")
    fireEvent.change(dateInput, { target: { value: "2026-08-05" } })
    await actor.click(screen.getByRole("button", { name: "Переместить" }))

    expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
      accessToken: "driver-token",
      externalTaskId: SCHEDULED_EXTERNAL_ID,
      command: {
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
        expectedEntryVersion: 11,
        targetLane: "SCHEDULED",
        targetDate: "2026-08-05",
        targetIndex: 0,
      },
    })
  })

  it("moves a planned card between date columns when it is dropped on another card", async () => {
    renderPage()
    await screen.findByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`)

    const sourceData = dndMocks.sortableData.get(
      `driver-task:${SCHEDULED_EXTERNAL_ID}`
    )
    const targetData = dndMocks.sortableData.get(
      `driver-task:${SECOND_EXTERNAL_ID}`
    )
    expect(sourceData).toBeDefined()
    expect(targetData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: sourceData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: sourceData } },
        over: { data: { current: targetData } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
        accessToken: "driver-token",
        externalTaskId: SCHEDULED_EXTERNAL_ID,
        command: {
          warehouseId: WAREHOUSE_ID,
          expectedTaskVersion: 7,
          expectedEntryVersion: 11,
          targetLane: "SCHEDULED",
          targetDate: "2026-08-02",
          targetIndex: 0,
        },
      })
    })
  })

  it("promotes one capital repair when its card is dropped into CURRENT", async () => {
    renderPage({
      ...board,
      current: [],
      usedRepairPlaceCount: board.repairPlaceCount,
      availableRepairPlaceCount: 0,
      inboundRepairPlaceAvailable: false,
    })
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

  it("places a capital repair directly onto the selected calendar date", async () => {
    renderPage()
    await screen.findByTestId(`capital-repair-${CAPITAL_REPAIR_ID}`)

    const dragData = dndMocks.draggableData.get(
      `capital-repair:${CAPITAL_REPAIR_ID}`
    )
    const targetData = dndMocks.sortableData.get(
      `driver-task:${SECOND_EXTERNAL_ID}`
    )
    expect(dragData).toBeDefined()
    expect(targetData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: targetData } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.scheduleCapitalRepair).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "driver-token",
          repairId: CAPITAL_REPAIR_ID,
          warehouseId: WAREHOUSE_ID,
          targetDate: "2026-08-02",
          targetIndex: 0,
          idempotencyKey: expect.any(String),
        })
      )
    })
  })

  it("promotes a capital repair through the explicit current-queue action", async () => {
    const userEventInstance = userEvent.setup()
    renderPage()

    await userEventInstance.click(
      await screen.findByRole("button", {
        name: "Добавить капитальный ремонт бытовки БТ-КАП в текущие задания",
      })
    )

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

  it("returns a current capital movement to the capital-repair column", async () => {
    const capitalMovement = card(CURRENT_EXTERNAL_ID, {
      title: "Переместить бытовку на производство",
      kind: "CAPITAL_TO_PRODUCTION",
      workflowState: "CURRENT",
      lane: "CURRENT",
      unitNumber: "БТ-КАП",
    })
    renderPage({ ...board, current: [capitalMovement] })
    await screen.findByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${CURRENT_EXTERNAL_ID}`
    )
    const capitalTarget = dndMocks.droppableData.get("driver-capital-repairs")
    expect(dragData).toBeDefined()
    expect(capitalTarget).toEqual({ type: "capital-target" })

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: capitalTarget } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.returnCapitalRepair).toHaveBeenCalledWith({
        accessToken: "driver-token",
        externalTaskId: CURRENT_EXTERNAL_ID,
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
      })
    })
  })

  it("returns a scheduled capital movement from a date column", async () => {
    const scheduledCapital = card(SCHEDULED_EXTERNAL_ID, {
      title: "Переместить бытовку на производство",
      kind: "CAPITAL_TO_PRODUCTION",
      workflowState: "SCHEDULED",
      lane: "SCHEDULED",
      unitNumber: "БТ-КАП-2",
    })
    renderPage({
      ...board,
      dates: [{ date: board.currentDate, tasks: [scheduledCapital] }],
    })
    await screen.findByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${SCHEDULED_EXTERNAL_ID}`
    )
    const capitalTarget = dndMocks.droppableData.get("driver-capital-repairs")
    act(() => {
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: capitalTarget } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.returnCapitalRepair).toHaveBeenCalledWith({
        accessToken: "driver-token",
        externalTaskId: SCHEDULED_EXTERNAL_ID,
        warehouseId: WAREHOUSE_ID,
        expectedTaskVersion: 7,
      })
    })
  })

  it("reorders a queue in the UI before the move request resolves", async () => {
    const pendingMove = deferred<DriverBoardCard>()
    apiMocks.moveDriverBoardTask.mockReturnValueOnce(pendingMove.promise)
    const anotherCurrent = card("00000000-0000-4000-8000-000000000011", {
      driverTaskId: "00000000-0000-4000-8000-000000000110",
      unitNumber: "БТ-002",
      kind: "DELIVER_TO_REPAIR",
      workflowState: "CURRENT",
      lane: "CURRENT",
      position: 1,
    })
    renderPage({ ...board, current: [...board.current, anotherCurrent] })
    await screen.findByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${CURRENT_EXTERNAL_ID}`
    )
    const targetData = dndMocks.sortableData.get(
      `driver-task:${anotherCurrent.externalTaskId}`
    )
    expect(dragData).toBeDefined()
    expect(targetData).toBeDefined()

    act(() => {
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: targetData } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledTimes(1)
      const currentTaskIds = within(
        screen.getByLabelText(/^Текущие задания на /i)
      )
        .getAllByTestId(/^current-task-/)
        .map((element) => element.getAttribute("data-testid"))
      expect(currentTaskIds).toEqual([
        `current-task-${anotherCurrent.externalTaskId}`,
        `current-task-${CURRENT_EXTERNAL_ID}`,
      ])
    })
  })

  it("rolls a move back and shows an error when the server rejects it", async () => {
    const pendingMove = deferred<DriverBoardCard>()
    apiMocks.moveDriverBoardTask.mockReturnValueOnce(pendingMove.promise)
    renderPage()
    await screen.findByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${CURRENT_EXTERNAL_ID}`
    )
    const targetData = dndMocks.sortableData.get(
      `driver-task:${SECOND_EXTERNAL_ID}`
    )
    act(() => {
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: targetData } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledTimes(1)
      expect(
        screen.queryByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)
      ).toBeNull()
      expect(
        within(
          screen.getByLabelText(/^Задания на .*2 августа/i)
        ).getAllByTestId(/^scheduled-task-/)
      ).toHaveLength(2)
    })

    await act(async () => {
      pendingMove.reject(
        new ApiError("Очередь перемещений уже изменилась", 409, null)
      )
      await Promise.resolve()
    })

    await waitFor(() => {
      expect(apiMocks.getDriverBoard).toHaveBeenCalledTimes(2)
    })
    expect(
      screen.getByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)
    ).toBeTruthy()
    expect(
      within(screen.getByLabelText(/^Задания на .*2 августа/i)).getAllByTestId(
        /^scheduled-task-/
      )
    ).toHaveLength(1)
    expect(screen.getByText("Команда не выполнена")).toBeTruthy()
    expect(screen.getByText("Очередь перемещений уже изменилась")).toBeTruthy()
  })

  it("does not move a scheduled ordinary task into current assignments", async () => {
    renderPage()
    await screen.findByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${SCHEDULED_EXTERNAL_ID}`
    )
    const currentDropData = dndMocks.droppableData.get("driver-current")

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: currentDropData } },
      })
    })

    expect(apiMocks.moveDriverBoardTask).not.toHaveBeenCalled()
    expect(
      screen.getByText(
        /в текущие задания можно добавить только капитальный ремонт/i
      )
    ).toBeTruthy()
    expect(
      screen.getByTestId(`scheduled-task-${SCHEDULED_EXTERNAL_ID}`)
    ).toBeTruthy()
  })

  it("reorders current tasks by dropping the whole card on another card", async () => {
    const anotherCurrent = card("00000000-0000-4000-8000-000000000011", {
      driverTaskId: "00000000-0000-4000-8000-000000000110",
      unitNumber: "БТ-002",
      kind: "DELIVER_TO_REPAIR",
      workflowState: "CURRENT",
      lane: "CURRENT",
      position: 1,
    })
    renderPage({ ...board, current: [...board.current, anotherCurrent] })
    await screen.findByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)

    const dragData = dndMocks.sortableData.get(
      `driver-task:${CURRENT_EXTERNAL_ID}`
    )
    const targetData = dndMocks.sortableData.get(
      `driver-task:${anotherCurrent.externalTaskId}`
    )
    expect(dragData).toBeDefined()
    expect(targetData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: { data: { current: targetData } },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
        accessToken: "driver-token",
        externalTaskId: CURRENT_EXTERNAL_ID,
        command: {
          warehouseId: WAREHOUSE_ID,
          expectedTaskVersion: 7,
          expectedEntryVersion: 11,
          targetLane: "CURRENT",
          targetDate: "2026-08-01",
          targetIndex: 1,
        },
      })
    })
  })

  it("moves a current card back to a planned date without promoting another card in the UI command", async () => {
    renderPage()
    const currentCard = await screen.findByTestId(
      `current-task-${CURRENT_EXTERNAL_ID}`
    )
    expect(currentCard.getAttribute("aria-label")).toBe(
      "Переместить задание бытовки БТ-001"
    )

    const dragData = dndMocks.sortableData.get(
      `driver-task:${CURRENT_EXTERNAL_ID}`
    )
    expect(dragData).toBeDefined()

    act(() => {
      dndMocks.onDragStart?.({ active: { data: { current: dragData } } })
      dndMocks.onDragEnd?.({
        active: { data: { current: dragData } },
        over: {
          data: {
            current: { type: "scheduled-date", date: "2026-08-02" },
          },
        },
      })
    })

    await waitFor(() => {
      expect(apiMocks.moveDriverBoardTask).toHaveBeenCalledWith({
        accessToken: "driver-token",
        externalTaskId: CURRENT_EXTERNAL_ID,
        command: {
          warehouseId: WAREHOUSE_ID,
          expectedTaskVersion: 7,
          expectedEntryVersion: 11,
          targetLane: "SCHEDULED",
          targetDate: "2026-08-02",
          targetIndex: 1,
        },
      })
    })
  })

  it("renders every server-provided current movement instead of truncating the lane", async () => {
    const anotherCurrent = card("00000000-0000-4000-8000-000000000011", {
      driverTaskId: "00000000-0000-4000-8000-000000000110",
      unitNumber: "БТ-002",
      kind: "DELIVER_TO_REPAIR",
      workflowState: "CURRENT",
      lane: "CURRENT",
    })
    renderPage({ ...board, current: [...board.current, anotherCurrent] })

    expect(
      await screen.findByTestId(`current-task-${CURRENT_EXTERNAL_ID}`)
    ).toBeTruthy()
    expect(
      screen.getByTestId(`current-task-${anotherCurrent.externalTaskId}`)
    ).toBeTruthy()
  })

  it("pins a movement through the shared task-board rule", async () => {
    const actor = userEvent.setup()
    renderPage()

    await actor.click(
      await screen.findByRole("button", {
        name: "Закрепить задание бытовки БТ-001",
      })
    )

    await waitFor(() => {
      expect(apiMocks.pinDriverBoardTask).toHaveBeenCalledWith({
        accessToken: "driver-token",
        warehouseId: WAREHOUSE_ID,
        taskId: board.current[0]!.taskBoardTaskId,
        expectedTaskVersion: 7,
        pinned: true,
      })
    })
  })

  it("creates a general movement from a warehouse cabin and comment", async () => {
    const actor = userEvent.setup()
    renderPage()

    await actor.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )
    const cabinSelect = await screen.findByRole("combobox", {
      name: "Бытовка",
    })
    await actor.click(cabinSelect)
    await actor.click(await screen.findByRole("option", { name: "БТ-040" }))
    await actor.type(
      screen.getByLabelText("Комментарий"),
      "Переставить к зоне отгрузки"
    )
    await actor.click(screen.getByRole("button", { name: "Создать" }))

    await waitFor(() => {
      expect(apiMocks.createManualMovement).toHaveBeenCalledWith({
        accessToken: "driver-token",
        command: {
          warehouseId: WAREHOUSE_ID,
          cabinId: "00000000-0000-4000-8000-000000000040",
          comment: "Переставить к зоне отгрузки",
          priority: 3,
          idempotencyKey: expect.any(String),
        },
      })
    })
  })
})
