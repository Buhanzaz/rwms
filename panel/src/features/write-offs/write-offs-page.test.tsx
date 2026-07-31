import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const repairTasksApi = vi.hoisted(() => ({
  getRepairTask: vi.fn(),
  listRepairWriteOffs: vi.fn(),
}))
const actorApi = vi.hoisted(() => ({ listDossierActorDisplays: vi.fn() }))
const auth = vi.hoisted(() => ({ useAuth: vi.fn() }))
const warehouse = vi.hoisted(() => ({ useWarehouse: vi.fn() }))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: auth.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: warehouse.useWarehouse,
}))
vi.mock("@/features/repair-tasks/api/repair-tasks-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/repair-tasks/api/repair-tasks-api")
  >("@/features/repair-tasks/api/repair-tasks-api")

  return {
    ...actual,
    getRepairTask: repairTasksApi.getRepairTask,
    listRepairWriteOffs: repairTasksApi.listRepairWriteOffs,
  }
})
vi.mock("@/features/rental-items/dossier/actor/actor-display-api", () => ({
  listDossierActorDisplays: actorApi.listDossierActorDisplays,
}))
vi.mock("@/features/acceptance/repair-acceptance-dossier", () => ({
  RepairAcceptanceDossier: () => <div>Досье списания</div>,
}))

import { WriteOffsPage } from "@/features/write-offs/write-offs-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const AUTHOR_ID = "22222222-2222-4222-8222-222222222222"

const currentUser: CurrentUser = {
  id: "33333333-3333-4333-8333-333333333333",
  username: "operator",
  displayName: "Оператор",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  rentalAccess: false,
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
}

function task(
  id: string,
  overrides: Partial<RepairTaskDto> = {}
): RepairTaskDto {
  return {
    id,
    version: 1,
    status: "COMPLETED",
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "WRITTEN_OFF",
    startedAt: null,
    completedAt: "2026-07-20T10:00:00Z",
    warehouseId: WAREHOUSE_ID,
    rentalItemId: `rental-${id}`,
    cabinNumber: `БЫТ-${id}`,
    actorId: "task-author",
    sourceParty: null,
    dispatchDate: null,
    subtasks: [],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    writtenOffAt: "2026-07-20T12:00:00Z",
    decisionActorId: AUTHOR_ID,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-20T12:00:00Z",
    ...overrides,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={["/write-offs"]}>
      <QueryClientProvider client={queryClient}>
        <WriteOffsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  auth.useAuth.mockReturnValue({
    accessToken: "write-offs-token",
    currentUser,
  })
  warehouse.useWarehouse.mockReturnValue({ selectedWarehouseId: WAREHOUSE_ID })
  repairTasksApi.getRepairTask.mockResolvedValue(null)
  actorApi.listDossierActorDisplays.mockResolvedValue([
    {
      subjectId: AUTHOR_ID,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      username: "ivanov",
      firstName: "Иван",
      lastName: "Иванов",
      email: "ivanov@example.test",
    },
  ])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("WriteOffsPage filters", () => {
  it("keeps a full-height grid with headers when there are no written-off cabins", async () => {
    repairTasksApi.listRepairWriteOffs.mockResolvedValue([])
    const { container } = renderPage()

    expect(
      await screen.findByRole("button", { name: "Номер бытовки" })
    ).toBeTruthy()
    const grid = container.querySelector<HTMLElement>(
      '[data-slot="operations-list-grid"]'
    )
    expect(grid).not.toBeNull()
    expect(grid?.className).toContain("min-h-full")
    expect(grid?.parentElement?.className).toContain("flex-1")
    expect(grid?.parentElement?.className).not.toContain("hidden")
    expect(screen.queryByText("Списанных бытовок нет.")).toBeNull()
  })

  it("filters the grid and replaces the raw author identifier with a name", async () => {
    const user = userEvent.setup()
    repairTasksApi.listRepairWriteOffs.mockResolvedValue([
      task("001"),
      task("002", { decisionActorId: null }),
    ])

    renderPage()

    await screen.findAllByText("Иванов Иван")
    expect(document.body.textContent).not.toContain(AUTHOR_ID)

    const search = screen.getByRole("searchbox", { name: "Поиск списаний" })
    await user.type(search, "Иванов")
    await waitFor(() => expect(screen.queryByText("БЫТ-002")).toBeNull())
    expect(screen.getAllByText("БЫТ-001")).toHaveLength(2)

    const filtersPanel = document.getElementById("write-off-filters")
    expect(filtersPanel).not.toBeNull()
    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры списаний",
    })
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")
    await user.click(hideFilters)

    expect(filtersPanel?.hidden).toBe(true)
    expect((search as HTMLInputElement).value).toBe("Иванов")
  })
})
