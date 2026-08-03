import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  QueueDefinitionDto,
  WorkerClassDto,
  WorkerDto,
  WorkerGroupDto,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

const mocks = vi.hoisted(() => ({
  listQueueDefinitions: vi.fn(),
  listClasses: vi.fn(),
  listGroups: vi.fn(),
  listQueues: vi.fn(),
  listWorkers: vi.fn(),
  createQueue: vi.fn(),
  deleteQueue: vi.fn(),
  reorderQueues: vi.fn(),
  disableGroup: vi.fn(),
  enableGroup: vi.fn(),
  setWorkerCurrentGroup: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "task-board-token",
    currentUser: currentUser(),
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: { id: WAREHOUSE_ID },
  }),
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: {
    listQueueDefinitions: mocks.listQueueDefinitions,
    listClasses: mocks.listClasses,
    listGroups: mocks.listGroups,
    listQueues: mocks.listQueues,
    listWorkers: mocks.listWorkers,
    createQueue: mocks.createQueue,
    deleteQueue: mocks.deleteQueue,
    reorderQueues: mocks.reorderQueues,
    disableGroup: mocks.disableGroup,
    enableGroup: mocks.enableGroup,
    setWorkerCurrentGroup: mocks.setWorkerCurrentGroup,
  },
  taskBoardSettingsKeys: {
    queueDefinitions: ["task-board-settings", "queue-definitions"],
    classes: ["task-board-settings", "classes"],
    groups: (warehouseId: string) => [
      "task-board-settings",
      warehouseId,
      "groups",
    ],
    queues: (warehouseId: string) => [
      "task-board-settings",
      warehouseId,
      "queues",
    ],
    workers: (warehouseId: string) => [
      "task-board-settings",
      warehouseId,
      "workers",
    ],
  },
}))

import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"

function currentUser(): CurrentUser {
  return {
    id: "admin-1",
    username: "admin",
    displayName: "Администратор",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "SYSTEM_ADMIN",
    rentalAccess: true,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
  }
}

function queueFixture(): WorkQueueDto {
  return {
    id: "00000000-0000-4000-8000-000000000002",
    version: 1,
    warehouseId: WAREHOUSE_ID,
    definitionId: "00000000-0000-4000-8000-000000000010",
    definitionVersion: 1,
    name: "Ремонт",
    description: null,
    type: "REPAIR",
    purpose: "GENERAL",
    sortOrder: 0,
    active: true,
    hidden: false,
    collapsed: false,
    holdingPeriodMinutes: null,
    notificationThreshold: null,
    notifyWhenThresholdReached: false,
    resultPhotoMinCount: 1,
    bindings: [],
  }
}

function queueDefinitionFixture(
  overrides: Partial<QueueDefinitionDto> = {}
): QueueDefinitionDto {
  return {
    id: "00000000-0000-4000-8000-000000000010",
    version: 1,
    name: "Ремонт",
    description: null,
    type: "REPAIR",
    purpose: "GENERAL",
    ...overrides,
  }
}

function classFixture(): WorkerClassDto {
  return {
    id: "class-1",
    version: 1,
    name: "Разнорабочие",
    description: null,
    comment: null,
    sortOrder: 1,
    active: true,
    logisticsPrimary: false,
  }
}

function driverClassFixture(): WorkerClassDto {
  return {
    ...classFixture(),
    id: "driver-class",
    name: "Водители",
    logisticsPrimary: true,
  }
}

function driverQueueFixture(): WorkQueueDto {
  const driverClass = driverClassFixture()
  return {
    ...queueFixture(),
    id: "00000000-0000-4000-8000-000000000003",
    definitionId: "00000000-0000-4000-8000-000000000011",
    name: "Водители",
    type: "MOVEMENT",
    purpose: "LOGISTICS_DRIVER",
    sortOrder: 10,
    bindings: [
      {
        id: "driver-binding",
        version: 1,
        workerClass: driverClass,
        order: 0,
        primary: true,
        stopTaskOnTake: false,
        participationPolicy: "PRIMARY",
        notifyOnPrimaryTake: false,
      },
    ],
  }
}

function workerFixture(): WorkerDto {
  return {
    id: "worker-1",
    version: 4,
    warehouseId: WAREHOUSE_ID,
    displayName: "Иван Петров",
    firstName: "Иван",
    lastName: "Петров",
    middleName: null,
    active: true,
    comment: null,
    appLogin: null,
    credentialStatus: "NOT_CONFIGURED",
    credentialError: null,
    currentGroupId: null,
    currentGroupName: null,
    operationalAvailability: "AVAILABLE",
    qualifications: [],
  }
}

function driverWorkerFixture(): WorkerDto {
  const driverClass = driverClassFixture()
  return {
    ...workerFixture(),
    id: "driver-worker",
    displayName: "Алексей Водитель",
    qualifications: [
      {
        id: "driver-qualification",
        version: 1,
        workerClass: driverClass,
        active: true,
        comment: null,
      },
    ],
  }
}

function groupFixture(
  operationalStatus: "AVAILABLE" | "DISABLED" = "AVAILABLE"
): WorkerGroupDto {
  const worker = workerFixture()
  return {
    id: "group-1",
    version: operationalStatus === "AVAILABLE" ? 8 : 9,
    warehouseId: WAREHOUSE_ID,
    workerClass: classFixture(),
    name: "Бригада 1",
    description: null,
    active: true,
    operationalStatus,
    unavailableSince:
      operationalStatus === "DISABLED" ? "2026-07-30T08:00:00Z" : null,
    unavailabilityReason:
      operationalStatus === "DISABLED" ? "Пересменка" : null,
    members: [
      {
        id: "member-1",
        version: 1,
        workerId: worker.id,
        workerName: worker.displayName,
        active: true,
      },
    ],
  }
}

function driverGroupFixture(): WorkerGroupDto {
  return {
    ...groupFixture(),
    id: "driver-group",
    name: "Бригада водителей",
    workerClass: driverClassFixture(),
    members: [],
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <TaskBoardSettingsPage />
    </QueryClientProvider>
  )
}

beforeAll(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: {
      configurable: true,
      value: () => false,
    },
    setPointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    releasePointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    scrollIntoView: {
      configurable: true,
      value: () => undefined,
    },
  })
})

afterAll(() => {
  vi.unstubAllGlobals()
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

beforeEach(() => {
  mocks.listQueueDefinitions.mockResolvedValue([queueDefinitionFixture()])
  mocks.listClasses.mockResolvedValue([driverClassFixture()])
  mocks.listGroups.mockResolvedValue([])
  mocks.listQueues.mockResolvedValue([queueFixture(), driverQueueFixture()])
  mocks.listWorkers.mockResolvedValue([])
  mocks.createQueue.mockResolvedValue(queueFixture())
  mocks.deleteQueue.mockResolvedValue(undefined)
  mocks.reorderQueues.mockResolvedValue([])
  mocks.disableGroup.mockResolvedValue({})
  mocks.enableGroup.mockResolvedValue({})
  mocks.setWorkerCurrentGroup.mockResolvedValue({})
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("TaskBoardSettingsPage navigation", () => {
  it("separates the global catalog, warehouse queues, and warehouse order", async () => {
    renderPage()

    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "Очереди склада" })).toBeTruthy()
    )

    const navigation = screen.getByRole("navigation", {
      name: "Разделы настройки доски задач",
    })
    const sectionButtons = within(navigation).getAllByRole("radio")

    expect(sectionButtons.map((button) => button.textContent)).toEqual([
      "Каталог очередей",
      "Очереди склада",
      "Классы",
      "Бригады",
      "Рабочие",
    ])
    expect(sectionButtons[1]?.getAttribute("aria-checked")).toBe("true")
    expect(
      navigation.firstElementChild?.classList.contains("lg:grid-cols-5")
    ).toBe(true)
    expect(
      sectionButtons.every((button) => button.classList.contains("h-9"))
    ).toBe(true)
    expect(
      sectionButtons.every((button) =>
        button.classList.contains("justify-center")
      )
    ).toBe(true)

    const createQueueButton = screen.getByRole("button", {
      name: "Добавить из каталога",
    })
    expect(
      createQueueButton.parentElement?.classList.contains("justify-end")
    ).toBe(true)
    expect(screen.queryByText("Водители")).toBeNull()
    expect(
      screen.getByRole("button", { name: "Сохранить порядок" })
    ).toBeTruthy()

    fireEvent.click(screen.getByRole("radio", { name: "Каталог очередей" }))
    expect(
      screen.getByRole("button", { name: "Создать общую очередь" })
    ).toBeTruthy()
    expect(screen.queryByText("Классы исполнителей")).toBeNull()

    fireEvent.click(screen.getByRole("radio", { name: "Классы" }))
    expect(screen.queryByText("Водители")).toBeNull()
    for (const [section, action] of [
      ["Классы", "Создать класс"],
      ["Бригады", "Создать бригаду"],
      ["Рабочие", "Создать рабочего"],
    ]) {
      fireEvent.click(screen.getByRole("radio", { name: section }))

      const createButton = screen.getByRole("button", { name: action })
      expect(
        createButton.parentElement?.classList.contains("justify-end")
      ).toBe(true)
    }
  })

  it("removes the legacy general movement definition and description from repair settings", async () => {
    const movementDefinition = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000077",
      name: "Перемещения",
      description: "Старая очередь водителей",
      type: "MOVEMENT",
    })
    const movementQueue: WorkQueueDto = {
      ...queueFixture(),
      id: "00000000-0000-4000-8000-000000000078",
      definitionId: movementDefinition.id,
      name: movementDefinition.name,
      description: movementDefinition.description,
      type: "MOVEMENT",
      sortOrder: 8,
    }
    mocks.listQueueDefinitions.mockResolvedValue([
      queueDefinitionFixture(),
      movementDefinition,
    ])
    mocks.listQueues.mockResolvedValue([
      queueFixture(),
      movementQueue,
      driverQueueFixture(),
    ])

    renderPage()

    await screen.findByRole("radio", { name: "Каталог очередей" })
    expect(screen.queryByText("Перемещения")).toBeNull()
    fireEvent.click(screen.getByRole("radio", { name: "Каталог очередей" }))
    expect(screen.queryByText("Перемещения")).toBeNull()
    expect(screen.queryByText("Описание")).toBeNull()
  })

  it("connects a definition to only the selected warehouse with its stable id", async () => {
    const user = userEvent.setup()
    const connected = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000011",
      name: "Внешний ремонт",
    })
    const available = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000012",
      name: "Внутренний ремонт",
    })
    const connectedQueue = {
      ...queueFixture(),
      definitionId: connected.id,
      name: connected.name,
    }
    mocks.listQueueDefinitions.mockResolvedValue([connected, available])
    mocks.listQueues.mockResolvedValue([connectedQueue, driverQueueFixture()])

    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Добавить из каталога" })
    )
    await user.click(screen.getByRole("combobox", { name: "Общая очередь" }))
    await user.click(
      screen.getByRole("option", { name: "Внутренний ремонт · Ремонт" })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.createQueue).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        expect.objectContaining({ definitionId: available.id, version: 0 })
      )
    )
  })

  it("reorders general queues while preserving the warehouse driver connection", async () => {
    const first = queueFixture()
    const second = {
      ...queueFixture(),
      id: "00000000-0000-4000-8000-000000000099",
      definitionId: "00000000-0000-4000-8000-000000000088",
      name: "Внутренний ремонт",
      sortOrder: 20,
      version: 4,
    }
    mocks.listQueues.mockResolvedValue([first, driverQueueFixture(), second])

    renderPage()
    fireEvent.click(
      await screen.findByRole("button", { name: "Сохранить порядок" })
    )

    await waitFor(() =>
      expect(mocks.reorderQueues).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        [
          { queueId: first.id, expectedVersion: first.version },
          {
            queueId: driverQueueFixture().id,
            expectedVersion: driverQueueFixture().version,
          },
          { queueId: second.id, expectedVersion: second.version },
        ]
      )
    )
  })

  it("preserves a legacy movement position while ordering visible repair queues", async () => {
    const first = queueFixture()
    const movement = {
      ...queueFixture(),
      id: "00000000-0000-4000-8000-000000000066",
      definitionId: "00000000-0000-4000-8000-000000000067",
      name: "Перемещения",
      type: "MOVEMENT" as const,
      sortOrder: 5,
      version: 2,
    }
    const second = {
      ...queueFixture(),
      id: "00000000-0000-4000-8000-000000000099",
      definitionId: "00000000-0000-4000-8000-000000000088",
      name: "Внутренний ремонт",
      sortOrder: 20,
      version: 4,
    }
    mocks.listQueues.mockResolvedValue([first, movement, second])

    renderPage()
    fireEvent.click(
      await screen.findByRole("button", { name: "Сохранить порядок" })
    )

    await waitFor(() =>
      expect(mocks.reorderQueues).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        [
          { queueId: first.id, expectedVersion: first.version },
          { queueId: movement.id, expectedVersion: movement.version },
          { queueId: second.id, expectedVersion: second.version },
        ]
      )
    )
  })

  it("disconnects a general queue only from the selected warehouse", async () => {
    const user = userEvent.setup()
    const queue = queueFixture()
    mocks.listQueues.mockResolvedValue([queue, driverQueueFixture()])

    renderPage()

    await user.click(await screen.findByRole("button", { name: "Удалить" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "Удалить" }))

    await waitFor(() =>
      expect(mocks.deleteQueue).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        queue.id,
        queue.version
      )
    )
  })

  it("uses the stable logistics marker even before a local driver queue exists", async () => {
    const user = userEvent.setup()
    mocks.listQueues.mockResolvedValue([queueFixture()])
    mocks.listClasses.mockResolvedValue([driverClassFixture()])
    mocks.listGroups.mockResolvedValue([driverGroupFixture()])
    mocks.listWorkers.mockResolvedValue([driverWorkerFixture()])

    renderPage()

    await user.click(await screen.findByRole("radio", { name: "Классы" }))
    expect(screen.queryByText("Водители")).toBeNull()

    await user.click(screen.getByRole("radio", { name: "Бригады" }))
    expect(screen.queryByText("Бригада водителей")).toBeNull()

    await user.click(screen.getByRole("radio", { name: "Рабочие" }))
    expect(screen.queryByText("Алексей Водитель")).toBeNull()
  })
})

describe("TaskBoardSettingsPage workforce operations", () => {
  it("shows and changes the manager-selected current group", async () => {
    const user = userEvent.setup()
    const group = groupFixture()
    mocks.listClasses.mockResolvedValue([classFixture()])
    mocks.listGroups.mockResolvedValue([group])
    mocks.listWorkers.mockResolvedValue([workerFixture()])

    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Рабочие" }))

    expect(screen.getByText("Не выбрана")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Назначить бригаду" }))
    await user.click(screen.getByRole("combobox", { name: "Текущая бригада" }))
    await user.click(screen.getByRole("option", { name: group.name }))
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.setWorkerCurrentGroup).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        "worker-1",
        4,
        group.id
      )
    )
  })

  it("keeps a disable conflict visible and never invents success", async () => {
    const user = userEvent.setup()
    const group = groupFixture()
    const conflict = Object.assign(
      new Error("У бригады есть активное задание."),
      { status: 409 }
    )
    mocks.listGroups.mockResolvedValue([group])
    mocks.disableGroup.mockRejectedValue(conflict)

    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Бригады" }))
    await user.click(screen.getByRole("button", { name: "Отключить работу" }))
    await user.type(
      screen.getByRole("textbox", { name: "Причина" }),
      "Пересменка"
    )
    await user.click(screen.getByRole("button", { name: "Отключить" }))

    expect(
      await screen.findByText("У бригады есть активное задание.")
    ).toBeTruthy()
    expect(mocks.disableGroup).toHaveBeenCalledWith(
      "task-board-token",
      WAREHOUSE_ID,
      group.id,
      group.version,
      "Пересменка"
    )
    expect(screen.getByRole("dialog")).toBeTruthy()
  })

  it("shows the disabled reason and enables the same group", async () => {
    const user = userEvent.setup()
    const group = groupFixture("DISABLED")
    mocks.listGroups.mockResolvedValue([group])

    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Бригады" }))

    expect(screen.getByText("Пересменка")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Включить работу" }))

    await waitFor(() =>
      expect(mocks.enableGroup).toHaveBeenCalledWith(
        "task-board-token",
        WAREHOUSE_ID,
        group.id,
        group.version,
        null
      )
    )
  })
})
