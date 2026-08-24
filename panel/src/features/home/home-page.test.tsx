import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { HomePage } from "@/features/home/home-page"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const currentTime = Date.parse("2026-08-24T09:30:00Z")
const warehouse = {
  id: warehouseId,
  version: 0,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 1,
}
const viewer = {
  id: "00000000-0000-4000-8000-000000000010",
  username: "viewer",
  displayName: "Наблюдатель",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_VIEWER",
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId, level: "VIEW" }],
}

const mocks = vi.hoisted(() => ({
  auth: {
    accessToken: "access-token" as string | null,
    currentUser: null as Record<string, unknown> | null,
  },
  warehouse: {
    selectedWarehouse: null as Record<string, unknown> | null,
  },
  getTaskBoard: vi.fn(),
  getDailyBrigadeActivity: vi.fn(),
  getKpiSettings: vi.fn(),
  listKpiWorkerGroups: vi.fn(),
  listMaintenanceRepairs: vi.fn(),
}))

vi.mock(
  "@/features/home/daily-brigade-activity-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/home/daily-brigade-activity-api")
      >()
    return {
      ...original,
      getDailyBrigadeActivity: mocks.getDailyBrigadeActivity,
    }
  }
)

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => mocks.auth,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => mocks.warehouse,
}))
vi.mock("@/features/task-board/api/task-board-api", async (importOriginal) => {
  const original =
    await importOriginal<
      typeof import("@/features/task-board/api/task-board-api")
    >()
  return { ...original, getTaskBoard: mocks.getTaskBoard }
})
vi.mock(
  "@/features/settings/kpi/api/kpi-settings-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/kpi/api/kpi-settings-api")
      >()
    return { ...original, getKpiSettings: mocks.getKpiSettings }
  }
)
vi.mock("@/features/kpi/api/kpi-api", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("@/features/kpi/api/kpi-api")>()
  return { ...original, listKpiWorkerGroups: mocks.listKpiWorkerGroups }
})
vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/repair-estimates/api/http-maintenance-lifecycle-client")
      >()
    return { ...original, listMaintenanceRepairs: mocks.listMaintenanceRepairs }
  }
)

function boardEntry(patch: Partial<TaskBoardEntryDto> = {}): TaskBoardEntryDto {
  return {
    id: "entry-1",
    version: 1,
    warehouseId,
    queueKey: "repair",
    queueId: "queue-1",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: 0,
    taskId: "task-1",
    externalTaskId: null,
    source: { type: "MAINTENANCE_REPAIR", sourceId: "repair-1" },
    taskVersion: 1,
    title: "Ремонт бытовки",
    unitNumber: "БК-001",
    taskStatus: "ACTIVE",
    scheduledDate: "2026-08-24",
    priority: 2,
    pinned: false,
    status: "IN_PROGRESS",
    taskText: "Замена отделки",
    plannedDurationMinutes: 60,
    activeStartedAt: "2026-08-24T09:00:00Z",
    pausedAt: null,
    activeWorkSeconds: 1_200,
    timerSnapshot: {
      countedActiveSeconds: 1_200,
      remainingSeconds: 4_800,
      remainingPercent: 80,
      timerState: "WORKING",
      nextTransitionAt: null,
      serverTime: "2026-08-24T09:30:00Z",
    },
    assignments: [
      {
        id: "assignment-1",
        version: 1,
        workerId: "worker-1",
        workerName: "Алексей",
        workerGroupId: "group-active",
        workerGroupName: "Бригада ремонта",
        status: "ACTIVE",
        assignedAt: "2026-08-24T09:00:00Z",
        startedAt: "2026-08-24T09:00:00Z",
        pausedAt: null,
        finishedAt: null,
      },
    ],
    detailsHref: "/repairs?repairId=repair-1",
    ...patch,
  }
}

function resolveDefaultBoard(entries = [boardEntry()]) {
  return {
    warehouseId,
    queues: [
      {
        key: "repair",
        version: 1,
        label: "Внутренний ремонт",
        kind: "REPAIR",
        settingsQueueId: "queue-1",
        settingsCollapsed: false,
        workerFeedEnabled: true,
        availableTaskLimit: 6,
        entries,
      },
    ],
    totalEntries: entries.length,
    realEntries: entries.length,
    shadowEntries: 0,
  }
}

function renderPage() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <HomePage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(Date, "now").mockReturnValue(currentTime)
  mocks.auth.accessToken = "access-token"
  mocks.auth.currentUser = viewer
  mocks.warehouse.selectedWarehouse = warehouse
  mocks.getTaskBoard.mockResolvedValue(resolveDefaultBoard())
  mocks.getDailyBrigadeActivity.mockResolvedValue({
    warehouseId,
    localDate: "2026-08-24",
    serverTime: "2026-08-24T09:30:00Z",
    intervals: [
      {
        workerGroupId: "group-active",
        workerGroupName: "Бригада ремонта",
        taskId: "task-1",
        entryId: "entry-1",
        queueId: "queue-1",
        queueName: "Внутренний ремонт",
        title: "Ремонт бытовки",
        unitNumber: "БК-001",
        taskText: "Замена отделки",
        priority: 2,
        startedAt: "2026-08-24T08:00:00Z",
        finishedAt: null,
        status: "ACTIVE",
        source: { type: "MAINTENANCE_REPAIR", sourceId: "repair-1" },
      },
    ],
  })
  mocks.getKpiSettings.mockResolvedValue({
    warehouseId,
    timeZone: "Europe/Moscow",
    status: "ACTIVE",
    version: 4,
    dataAvailableFrom: "2026-08-01",
    palette: {
      version: 2,
      ranges: [
        { fromPercent: 0, toPercent: 35, color: "#DC2626" },
        { fromPercent: 35, toPercent: 70, color: "#F59E0B" },
        { fromPercent: 70, toPercent: 100, color: "#16A34A" },
      ],
      overdueColor: "#7F1D1D",
    },
    activeSchedule: {
      id: "schedule-1",
      version: 1,
      effectiveFrom: "2026-08-01",
      shiftStart: "09:00",
      shiftEnd: "18:00",
      daysOff: [],
      breaks: [],
    },
    pendingSchedule: null,
  })
  mocks.listKpiWorkerGroups.mockResolvedValue([
    { id: "group-active", name: "Бригада ремонта", active: true },
    { id: "group-free", name: "Свободная бригада", active: true },
    { id: "group-old", name: "Старая бригада", active: false },
  ])
  mocks.listMaintenanceRepairs.mockResolvedValue({
    items: [
      {
        id: "repair-1",
        complexity: { name: "Средний ремонт" },
      },
    ],
    page: 0,
    size: 200,
    totalElements: 1,
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe("HomePage", () => {
  it("shows every active brigade, a live warehouse-local marker, and the configured task color", async () => {
    renderPage()

    await screen.findByText("Статистика дня по бригадам")

    const activeTrack = screen.getByTestId("daily-brigade-track-group-active")
    expect(activeTrack.getAttribute("aria-label")).toContain(
      "Текущее время 12:30"
    )
    expect(
      screen
        .getByTestId("daily-brigade-task-fill-group-active-0")
        .getAttribute("style")
    ).toContain("--daily-brigade-kpi-color: #16A34A")
    expect(
      screen
        .getByTestId("daily-brigade-task-fill-group-active-0")
        .getAttribute("style")
    ).toContain("left: 22.222")
    expect(
      screen
        .getByTestId("daily-brigade-task-fill-group-active-0")
        .getAttribute("style")
    ).toContain("width: 16.666")
    expect(screen.getByTestId("daily-brigade-track-group-free")).toBeTruthy()
    expect(screen.queryByTestId("daily-brigade-row-group-old")).toBeNull()
    expect(
      screen.queryByTestId("daily-brigade-task-fill-group-free-0")
    ).toBeNull()
  })

  it("shows the task envelope only when hovering a taken-task line", async () => {
    const user = userEvent.setup()
    renderPage()
    const taskFill = await screen.findByTestId(
      "daily-brigade-task-fill-group-active-0"
    )

    await user.hover(taskFill)

    await waitFor(() => {
      expect(
        document.querySelector<HTMLElement>('[data-slot="tooltip-content"]')
      ).not.toBeNull()
    })
    const tooltip = document.querySelector<HTMLElement>(
      '[data-slot="tooltip-content"]'
    )!
    expect(tooltip.textContent).toContain("Замена отделки")
    expect(tooltip.textContent).toContain("БК-001")
    expect(tooltip.textContent).toContain("Внутренний ремонт")
    expect(tooltip.textContent).toContain("11:00")
    expect(tooltip.textContent).toContain("сейчас, 12:30")
    expect(tooltip.textContent).toContain("Средний ремонт")
    expect(tooltip.textContent).toContain("2 из 5")
    expect(tooltip.textContent).toContain("80%")
  })

  it("does not invent a timeline when the active work schedule is absent", async () => {
    mocks.getKpiSettings.mockResolvedValue({
      warehouseId,
      timeZone: "Europe/Moscow",
      status: "DRAFT",
      version: 4,
      dataAvailableFrom: null,
      palette: null,
      activeSchedule: null,
      pendingSchedule: null,
    })

    renderPage()

    expect(await screen.findByText("Рабочий день не настроен")).toBeTruthy()
    expect(screen.queryByTestId("daily-brigade-track-group-active")).toBeNull()
  })
})
