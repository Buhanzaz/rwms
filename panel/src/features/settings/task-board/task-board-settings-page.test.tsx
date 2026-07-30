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

vi.mock("@/features/settings/task-board/queue-order-settings", () => ({
  QueueOrderSettings: () => <p>Порядок очередей</p>,
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

function queueDefinitionFixture(): QueueDefinitionDto {
  return {
    id: "00000000-0000-4000-8000-000000000010",
    version: 1,
    name: "Ремонт",
    description: null,
    type: "REPAIR",
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
  mocks.listQueueDefinitions.mockResolvedValue([
    queueDefinitionFixture(),
    {
      ...queueDefinitionFixture(),
      id: "00000000-0000-4000-8000-000000000011",
      name: "Водители",
      type: "MOVEMENT",
    },
  ])
  mocks.listClasses.mockResolvedValue([])
  mocks.listGroups.mockResolvedValue([])
  mocks.listQueues.mockResolvedValue([queueFixture()])
  mocks.listWorkers.mockResolvedValue([])
  mocks.disableGroup.mockResolvedValue({})
  mocks.enableGroup.mockResolvedValue({})
  mocks.setWorkerCurrentGroup.mockResolvedValue({})
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("TaskBoardSettingsPage navigation", () => {
  it("uses compact section buttons and aligns the queue action to the right", async () => {
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
      "Порядок",
      "Классы",
      "Бригады",
      "Рабочие",
    ])
    expect(sectionButtons[1]?.getAttribute("aria-checked")).toBe("true")
    expect(
      navigation.firstElementChild?.classList.contains("lg:grid-cols-6")
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

    fireEvent.click(screen.getByRole("radio", { name: "Каталог очередей" }))
    expect(
      screen.getByRole("button", { name: "Создать общую очередь" })
    ).toBeTruthy()
    expect(screen.getByText("Водители")).toBeTruthy()

    fireEvent.click(screen.getByRole("radio", { name: "Порядок" }))

    expect(screen.getByText("Порядок очередей")).toBeTruthy()
    expect(
      screen
        .getByRole("radio", { name: "Порядок" })
        .getAttribute("aria-checked")
    ).toBe("true")
    expect(
      screen.queryByRole("button", { name: "Добавить из каталога" })
    ).toBeNull()

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
