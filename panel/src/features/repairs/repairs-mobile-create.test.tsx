import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { MANAGER_MOBILE_APP_DOWNLOAD_PATH } from "@/components/mobile-app-required-dialog"
import { resolveHeaderBreadcrumbs } from "@/components/site-header-breadcrumbs"
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
  repairTaskDetailQueryKey: (warehouseId: string, repairId: string | null) => [
    "repair-tasks",
    "detail",
    warehouseId,
    repairId,
  ],
}))

vi.mock("@/features/repair-tasks/repair-task-detail-workspace", () => ({
  RepairTaskDetailWorkspace: () => <output data-testid="repair-detail" />,
}))

vi.mock("@/features/repair-tasks/repair-task-editor-workspace", () => ({
  RepairTaskEditorWorkspace: ({
    task,
    onBack,
    onClose,
    onSaved,
  }: {
    task: RepairTaskDto | null
    onBack: () => void
    onClose: () => void
    onSaved: () => void
  }) => (
    <div>
      <output data-testid="repair-editor">
        {task ? "existing repair" : "new repair"}
      </output>
      <button type="button" onClick={onBack}>
        Назад из редактора
      </button>
      <button type="button" onClick={onClose}>
        Закрыть редактор
      </button>
      <button type="button" onClick={onSaved}>
        Ремонт сохранён
      </button>
    </div>
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
  movementToRepair: false,
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

function renderPage(
  path = "/repairs",
  options?: { previousPath?: string; state?: unknown }
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const [pathname, search = ""] = path.split("?", 2)
  const workspaceEntry = {
    pathname,
    search: search ? `?${search}` : "",
    state: options?.state,
  }
  const initialEntries = options?.previousPath
    ? [options.previousPath, workspaceEntry]
    : [workspaceEntry]

  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/repairs" element={<RepairsPage />} />
          <Route path="*" element={null} />
        </Routes>
        <LocationProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  deviceState.isMobile = true
  api.getRepairTask.mockResolvedValue(repair)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairsPage mobile creation", () => {
  it.each(["/repairs", "/repairs?view=queue"])(
    "redirects the obsolete list route %s to the task board",
    async (path) => {
      renderPage(path)

      await waitFor(() => {
        expect(screen.getByTestId("repairs-location").textContent).toBe(
          "/task-board"
        )
      })
      expect(api.getRepairTask).not.toHaveBeenCalled()
    }
  )

  it("blocks a direct mobile creation route and returns to the task board when dismissed", async () => {
    const user = userEvent.setup()
    renderPage("/repairs?create=1")

    const dialog = await screen.findByRole("dialog")
    expect(screen.queryByTestId("repair-editor")).toBeNull()
    expect(
      within(dialog)
        .getByRole("link", { name: "Скачать приложение" })
        .getAttribute("href")
    ).toBe(MANAGER_MOBILE_APP_DOWNLOAD_PATH)

    await user.click(within(dialog).getByRole("button", { name: "Понятно" }))

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).toBeNull()
      expect(screen.getByTestId("repairs-location").textContent).toBe(
        "/task-board"
      )
    })
    expect(api.getRepairTask).not.toHaveBeenCalled()
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

  it("uses the task board as the fallback after closing a direct desktop link", async () => {
    deviceState.isMobile = false
    const user = userEvent.setup()
    renderPage(`/repairs?repairId=${REPAIR_ID}`)

    await user.click(
      await screen.findByRole("button", { name: "Закрыть редактор" })
    )

    await waitFor(() => {
      expect(screen.getByTestId("repairs-location").textContent).toBe(
        "/task-board"
      )
    })
  })

  it("returns to the recorded in-panel origin after saving", async () => {
    deviceState.isMobile = false
    const user = userEvent.setup()
    renderPage(`/repairs?repairId=${REPAIR_ID}`, {
      previousPath: "/acceptance",
      state: { workspaceEntry: true },
    })

    await user.click(
      await screen.findByRole("button", { name: "Ремонт сохранён" })
    )

    await waitFor(() => {
      expect(screen.getByTestId("repairs-location").textContent).toBe(
        "/acceptance"
      )
    })
  })
})

describe("repair workspace breadcrumbs", () => {
  it.each([
    ["?repairId=repair-1", "Задание"],
    ["?create=1", "Новое задание"],
  ])("keeps %s under the task board", (search, currentTitle) => {
    expect(resolveHeaderBreadcrumbs("/repairs", search, null, null)).toEqual([
      { title: "Доска задач", to: "/task-board" },
      { title: currentTitle },
    ])
  })
})
