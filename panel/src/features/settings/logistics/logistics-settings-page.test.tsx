import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
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
  WorkerClassDto,
  WorkerDto,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const DRIVER_QUEUE_ID = "00000000-0000-4000-8000-000000000002"
const DRIVER_CLASS_ID = "00000000-0000-4000-8000-000000000003"
const SLINGER_CLASS_ID = "00000000-0000-4000-8000-000000000004"

const mocks = vi.hoisted(() => ({
  listClasses: vi.fn(),
  listQueues: vi.fn(),
  listWorkers: vi.fn(),
  updateQueue: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "logistics-settings-token",
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
    listClasses: mocks.listClasses,
    listQueues: mocks.listQueues,
    listWorkers: mocks.listWorkers,
    updateQueue: mocks.updateQueue,
  },
  taskBoardSettingsKeys: {
    classes: ["task-board-settings", "classes"],
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

import { LogisticsSettingsPage } from "@/features/settings/logistics"

function currentUser(): CurrentUser {
  return {
    id: "admin-1",
    username: "admin",
    displayName: "Администратор",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
  }
}

function workerClass(id: string, name: string): WorkerClassDto {
  return {
    id,
    version: 1,
    name,
    description: null,
    comment: null,
    sortOrder: 1,
    active: true,
  }
}

const driverClass = workerClass(DRIVER_CLASS_ID, "Водители")
const slingerClass = workerClass(SLINGER_CLASS_ID, "Стропальщики")

function driverQueue(id = DRIVER_QUEUE_ID): WorkQueueDto {
  return {
    id,
    version: 7,
    warehouseId: WAREHOUSE_ID,
    definitionId: "00000000-0000-4000-8000-000000000005",
    definitionVersion: 3,
    name: "Водители",
    description: null,
    type: "MOVEMENT",
    purpose: "LOGISTICS_DRIVER",
    sortOrder: 10,
    active: true,
    hidden: false,
    collapsed: false,
    holdingPeriodMinutes: null,
    notificationThreshold: null,
    notifyWhenThresholdReached: false,
    resultPhotoMinCount: 1,
    bindings: [
      {
        id: "00000000-0000-4000-8000-000000000006",
        version: 2,
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

function driverWorker(): WorkerDto {
  return {
    id: "00000000-0000-4000-8000-000000000007",
    version: 4,
    warehouseId: WAREHOUSE_ID,
    displayName: "Алексей Водитель",
    firstName: "Алексей",
    lastName: "Водитель",
    middleName: null,
    active: true,
    comment: null,
    appLogin: "driver.alexey",
    credentialStatus: "ACTIVE",
    credentialError: null,
    currentGroupId: null,
    currentGroupName: null,
    operationalAvailability: "AVAILABLE",
    qualifications: [
      {
        id: "00000000-0000-4000-8000-000000000008",
        version: 1,
        workerClass: driverClass,
        active: true,
        comment: null,
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
      <LogisticsSettingsPage />
    </QueryClientProvider>
  )
}

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
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
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
  mocks.listQueues.mockResolvedValue([driverQueue()])
  mocks.listClasses.mockResolvedValue([driverClass, slingerClass])
  mocks.listWorkers.mockResolvedValue([driverWorker()])
  mocks.updateQueue.mockResolvedValue(driverQueue())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("LogisticsSettingsPage", () => {
  it("shows the existing primary class users without requiring a group", async () => {
    renderPage()

    expect(await screen.findByText("Алексей Водитель")).toBeTruthy()
    expect(screen.getByText("driver.alexey")).toBeTruthy()
    expect(screen.getAllByText("Водители").length).toBeGreaterThan(0)
    expect(
      screen.getByText(/Для водителей бригада не требуется\./)
    ).toBeTruthy()
  })

  it("attaches the same worker class and maps the notification flag", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("combobox", { name: "Класс рабочих" })
    )
    await user.click(screen.getByRole("option", { name: slingerClass.name }))
    await user.click(
      screen.getByRole("button", {
        name: "Прикрепить дополнительный класс",
      })
    )

    const attachedClass = screen
      .getByText(slingerClass.name)
      .closest('[data-slot="field"]') as HTMLElement
    await user.click(
      within(attachedClass).getByRole("checkbox", {
        name: "Отправлять задание прикреплённому классу после принятия задания водителем",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить настройки логистики" })
    )

    await waitFor(() =>
      expect(mocks.updateQueue).toHaveBeenCalledWith(
        "logistics-settings-token",
        WAREHOUSE_ID,
        DRIVER_QUEUE_ID,
        expect.objectContaining({
          version: 7,
          definitionId: driverQueue().definitionId,
          bindings: [
            {
              workerClassId: DRIVER_CLASS_ID,
              order: 0,
              stopTaskOnTake: false,
              participationPolicy: "PRIMARY",
              notifyOnPrimaryTake: false,
            },
            {
              workerClassId: SLINGER_CLASS_ID,
              order: 1,
              stopTaskOnTake: false,
              participationPolicy: "OPTIONAL",
              notifyOnPrimaryTake: true,
            },
          ],
        })
      )
    )
  })

  it("reports a clear error instead of selecting an ambiguous driver queue", async () => {
    mocks.listQueues.mockResolvedValue([
      driverQueue(),
      driverQueue("00000000-0000-4000-8000-000000000099"),
    ])

    renderPage()

    expect(
      await screen.findByText("Неверная конфигурация очереди водителей")
    ).toBeTruthy()
    expect(
      screen.getByText(
        "К складу подключено несколько очередей водителей. Должна остаться ровно одна."
      )
    ).toBeTruthy()
    expect(mocks.updateQueue).not.toHaveBeenCalled()
  })
})
