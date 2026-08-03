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
  listWorkers: vi.fn(),
  createQueueDefinition: vi.fn(),
  updateQueueDefinition: vi.fn(),
  deleteQueueDefinition: vi.fn(),
  reorderQueueDefinitions: vi.fn(),
  disableGroup: vi.fn(),
  enableGroup: vi.fn(),
  setWorkerCurrentGroup: vi.fn(),
  selectedWarehouse: null as { id: string } | null,
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
    selectedWarehouse: mocks.selectedWarehouse,
  }),
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: {
    listQueueDefinitions: mocks.listQueueDefinitions,
    listClasses: mocks.listClasses,
    listGroups: mocks.listGroups,
    listWorkers: mocks.listWorkers,
    createQueueDefinition: mocks.createQueueDefinition,
    updateQueueDefinition: mocks.updateQueueDefinition,
    deleteQueueDefinition: mocks.deleteQueueDefinition,
    reorderQueueDefinitions: mocks.reorderQueueDefinitions,
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
    sortOrder: 1,
    active: true,
    hidden: false,
    collapsed: false,
    holdingPeriodMinutes: null,
    notificationThreshold: null,
    notifyWhenThresholdReached: false,
    resultPhotoMinCount: 1,
    bindings: [],
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
  mocks.selectedWarehouse = { id: WAREHOUSE_ID }
  mocks.listQueueDefinitions.mockResolvedValue([queueDefinitionFixture()])
  mocks.listClasses.mockResolvedValue([driverClassFixture()])
  mocks.listGroups.mockResolvedValue([])
  mocks.listWorkers.mockResolvedValue([])
  mocks.createQueueDefinition.mockResolvedValue(queueDefinitionFixture())
  mocks.updateQueueDefinition.mockResolvedValue(queueDefinitionFixture())
  mocks.deleteQueueDefinition.mockResolvedValue(undefined)
  mocks.reorderQueueDefinitions.mockResolvedValue([])
  mocks.disableGroup.mockResolvedValue({})
  mocks.enableGroup.mockResolvedValue({})
  mocks.setWorkerCurrentGroup.mockResolvedValue({})
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("TaskBoardSettingsPage navigation", () => {
  it("shows the global queue catalog first without a warehouse queue tab", async () => {
    renderPage()

    await screen.findByRole("radio", { name: "Каталог очередей" })

    const navigation = screen.getByRole("navigation", {
      name: "Разделы настройки доски задач",
    })
    const sectionButtons = within(navigation).getAllByRole("radio")

    expect(sectionButtons.map((button) => button.textContent)).toEqual([
      "Каталог очередей",
      "Классы",
      "Бригады",
      "Рабочие",
    ])
    expect(sectionButtons[0]?.getAttribute("aria-checked")).toBe("true")
    expect(
      navigation.firstElementChild?.classList.contains("lg:grid-cols-4")
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
      name: "Создать очередь",
    })
    expect(
      createQueueButton.parentElement?.classList.contains("justify-end")
    ).toBe(true)
    expect(screen.queryByText("Водители")).toBeNull()
    expect(screen.queryByRole("radio", { name: "Очереди склада" })).toBeNull()
    expect(screen.getByText("Классы исполнителей")).toBeTruthy()
    expect(mocks.listQueueDefinitions).toHaveBeenCalledWith("task-board-token")

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

  it("lists the global catalog without a selected warehouse", async () => {
    mocks.selectedWarehouse = null

    renderPage()

    expect(
      await screen.findByRole("button", { name: "Создать очередь" })
    ).toBeTruthy()
    expect(screen.queryByText("Выберите склад.")).toBeNull()
    expect(mocks.listQueueDefinitions).toHaveBeenCalledWith("task-board-token")

    fireEvent.click(screen.getByRole("radio", { name: "Бригады" }))
    expect(screen.getByText("Выберите склад.")).toBeTruthy()
  })

  it("shows every GENERAL definition in the global catalog", async () => {
    const movementDefinition = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000077",
      name: "Перемещения",
      description: "Старая очередь водителей",
      type: "MOVEMENT",
    })
    mocks.listQueueDefinitions.mockResolvedValue([
      queueDefinitionFixture(),
      movementDefinition,
    ])

    renderPage()

    await screen.findByRole("radio", { name: "Каталог очередей" })
    expect(screen.getByText("Перемещения")).toBeTruthy()
  })

  it("shows global worker-class bindings and their role in the catalog", async () => {
    const workerClass = classFixture()
    const definition = queueDefinitionFixture({
      bindings: [
        {
          id: "binding-1",
          version: 1,
          workerClass,
          order: 0,
          primary: true,
          stopTaskOnTake: false,
          participationPolicy: "PRIMARY",
          notifyOnPrimaryTake: false,
        },
      ],
    })
    mocks.listQueueDefinitions.mockResolvedValue([definition])

    renderPage()

    expect(await screen.findByText(workerClass.name)).toBeTruthy()
    expect(screen.getByText("Основной")).toBeTruthy()
  })

  it("reorders global definitions from the table and keeps holding queues terminal", async () => {
    const user = userEvent.setup()
    const first = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000011",
      name: "Первый ремонт",
      sortOrder: 1,
      version: 2,
    })
    const second = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000012",
      name: "Второй ремонт",
      sortOrder: 2,
      version: 3,
    })
    const holding = queueDefinitionFixture({
      id: "00000000-0000-4000-8000-000000000013",
      name: "Удержание",
      type: "HOLDING",
      sortOrder: 3,
      version: 4,
    })
    mocks.listQueueDefinitions.mockResolvedValue([first, second, holding])

    renderPage()

    await user.click(
      await screen.findByRole("button", {
        name: `Опустить очередь ${first.name}`,
      })
    )

    await waitFor(() =>
      expect(mocks.reorderQueueDefinitions).toHaveBeenCalledWith(
        "task-board-token",
        [
          { definitionId: second.id, expectedVersion: second.version },
          { definitionId: first.id, expectedVersion: first.version },
          { definitionId: holding.id, expectedVersion: holding.version },
        ]
      )
    )
    expect(
      screen.queryByRole("button", {
        name: `Поднять очередь ${holding.name}`,
      })
    ).toBeNull()
  })

  it("deletes a queue definition globally", async () => {
    const user = userEvent.setup()
    const definition = queueDefinitionFixture()

    mocks.listQueueDefinitions.mockResolvedValue([definition])
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Удалить" }))
    const dialog = await screen.findByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "Удалить" }))

    await waitFor(() =>
      expect(mocks.deleteQueueDefinition).toHaveBeenCalledWith(
        "task-board-token",
        definition.id,
        definition.version
      )
    )
  })

  it("uses the stable logistics marker without relying on a local driver queue", async () => {
    const user = userEvent.setup()
    mocks.listClasses.mockResolvedValue([driverClassFixture()])
    mocks.listGroups.mockResolvedValue([
      {
        ...groupFixture(),
        id: "driver-group",
        name: "Бригада водителей",
        workerClass: driverClassFixture(),
        members: [],
      },
    ])
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
