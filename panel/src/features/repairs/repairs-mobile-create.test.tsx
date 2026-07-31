import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { MANAGER_MOBILE_APP_DOWNLOAD_PATH } from "@/components/mobile-app-required-dialog"
import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairsPage } from "@/features/repairs/repairs-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const REPAIR_ID = "33333333-3333-4333-8333-333333333333"

const deviceState = vi.hoisted(() => ({ isMobile: true }))
const api = vi.hoisted(() => ({
  getRepairTask: vi.fn(),
  getTaskBoardsForAvailableDates: vi.fn(),
  listRepairTasks: vi.fn(),
  moveTaskBoardEntry: vi.fn(),
  pinTaskBoardEntry: vi.fn(),
}))

vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => deviceState.isMobile,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    status: "authenticated",
    accessToken: "repairs-mobile-token",
    currentUser: {
      id: "operator-1",
      username: "operator",
      displayName: "Operator",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      rentalAccess: false,
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: WAREHOUSE_ID,
          level: "EDIT" satisfies WarehouseAccessLevel,
        },
      ],
    } satisfies CurrentUser,
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: { id: WAREHOUSE_ID, name: "Москва" },
    selectedWarehouseId: WAREHOUSE_ID,
  }),
}))

vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  getRepairTask: api.getRepairTask,
  listRepairTasks: api.listRepairTasks,
  repairTaskDetailQueryKey: (warehouseId: string, repairId: string | null) => [
    "repair-tasks",
    "detail",
    warehouseId,
    repairId,
  ],
  repairTasksListQueryKey: (warehouseId: string) => [
    "repair-tasks",
    "list",
    warehouseId,
  ],
}))

vi.mock("@/features/task-board/api/task-board-api", () => ({
  TASK_BOARD_QUERY_KEY: ["task-board"],
  getTaskBoardsForAvailableDates: api.getTaskBoardsForAvailableDates,
  moveTaskBoardEntry: api.moveTaskBoardEntry,
  pinTaskBoardEntry: api.pinTaskBoardEntry,
}))

vi.mock("@/features/repairs/repairs-queue-view", () => ({
  RepairsQueueView: () => null,
}))

vi.mock("@/features/repair-tasks/repair-task-detail-workspace", () => ({
  RepairTaskDetailWorkspace: () => <output data-testid="repair-detail" />,
}))

vi.mock("@/features/repair-tasks/repair-task-editor-workspace", () => ({
  RepairTaskEditorWorkspace: ({ task }: { task: RepairTaskDto | null }) => (
    <output data-testid="repair-editor">
      {task ? "existing repair" : "new repair"}
    </output>
  ),
}))

const repair: RepairTaskDto = {
  id: REPAIR_ID,
  version: 1,
  status: "DRAFT",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "NOT_READY",
  startedAt: null,
  completedAt: null,
  warehouseId: WAREHOUSE_ID,
  rentalItemId: "44444444-4444-4444-8444-444444444444",
  cabinNumber: "БЫТ-001",
  actorId: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18",
  subtasks: [],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-07-18T10:00:00Z",
  updatedAt: "2026-07-18T10:00:00Z",
}

function LocationProbe() {
  const location = useLocation()

  return (
    <output data-testid="repairs-location">
      {location.pathname}
      {location.search}
    </output>
  )
}

function renderPage(path = "/repairs") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <RepairsPage />
        <LocationProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  deviceState.isMobile = true
  api.getRepairTask.mockResolvedValue(repair)
  api.getTaskBoardsForAvailableDates.mockResolvedValue([])
  api.listRepairTasks.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairsPage mobile creation", () => {
  it("opens the mobile app dialog from the list without adding create to the URL", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    const dialog = await screen.findByRole("dialog")
    expect(
      within(dialog).getByRole("heading", {
        name: "Создание ремонта доступно в мобильном приложении",
      })
    ).toBeTruthy()
    expect(
      within(dialog)
        .getByRole("link", { name: "Скачать приложение" })
        .getAttribute("href")
    ).toBe(MANAGER_MOBILE_APP_DOWNLOAD_PATH)
    expect(screen.queryByTestId("repair-editor")).toBeNull()
    expect(screen.getByTestId("repairs-location").textContent).toBe("/repairs")
  })

  it("blocks a direct mobile creation route and returns to the list when dismissed", async () => {
    const user = userEvent.setup()
    renderPage("/repairs?create=1")

    const dialog = await screen.findByRole("dialog")
    expect(screen.queryByTestId("repair-editor")).toBeNull()

    await user.click(within(dialog).getByRole("button", { name: "Понятно" }))

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).toBeNull()
      expect(screen.getByTestId("repairs-location").textContent).toBe(
        "/repairs"
      )
    })
    expect(screen.getByRole("button", { name: "Создать задание" })).toBeTruthy()
  })

  it("keeps an existing repair editor available on mobile", async () => {
    renderPage(`/repairs?repairId=${REPAIR_ID}`)

    await waitFor(() => {
      expect(screen.getByTestId("repair-editor").textContent).toBe(
        "existing repair"
      )
    })
    expect(screen.queryByRole("dialog")).toBeNull()
  })
})
