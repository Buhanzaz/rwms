import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const repairTasksApi = vi.hoisted(() => ({
  getRepairTask: vi.fn(),
  listPendingRepairAcceptance: vi.fn(),
}))
const auth = vi.hoisted(() => ({ useAuth: vi.fn() }))
const warehouse = vi.hoisted(() => ({ useWarehouse: vi.fn() }))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: auth.useAuth }))
vi.mock(
  "@/features/rental-items/dossier/actor/use-dossier-actor-displays",
  () => ({
    useDossierActorDisplays: (subjectIds: string[]) =>
      new Map(
        subjectIds.map((subjectId) => [
          subjectId,
          {
            subjectId,
            principalType: "WORKER",
            globalRole: null,
            username: "",
            firstName: subjectId.endsWith("a") ? "Анна" : "Борис",
            lastName: subjectId.endsWith("a") ? "Иванова" : "Петров",
            email: null,
          },
        ])
      ),
  })
)
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
    listPendingRepairAcceptance: repairTasksApi.listPendingRepairAcceptance,
  }
})
vi.mock("@/features/acceptance/repair-acceptance-dossier", () => ({
  RepairAcceptanceDossier: () => <div>Досье приёмки</div>,
}))

import { AcceptancePage } from "@/features/acceptance/acceptance-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

const currentUser: CurrentUser = {
  id: "22222222-2222-4222-8222-222222222222",
  username: "acceptance-user",
  displayName: "Приёмщик",
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
    acceptanceStatus: "PENDING",
    startedAt: null,
    completedAt: "2026-07-20T08:00:00Z",
    warehouseId: WAREHOUSE_ID,
    rentalItemId: `rental-${id}`,
    cabinNumber: `БЫТ-${id}`,
    actorId: `author-${id}`,
    sourceParty: `Источник ${id}`,
    dispatchDate: null,
    subtasks: [],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    readyAt: "2026-07-20T08:00:00Z",
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-20T08:00:00Z",
    ...overrides,
  }
}

function renderPage(initialEntry = "/acceptance") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <AcceptancePage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  auth.useAuth.mockReturnValue({ accessToken: "acceptance-token", currentUser })
  warehouse.useWarehouse.mockReturnValue({ selectedWarehouseId: WAREHOUSE_ID })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AcceptancePage filters", () => {
  it("filters the loaded list and preserves selected filters when hidden", async () => {
    const user = userEvent.setup()
    const firstTask = task("001", {
      sourceParty: "Клиент А",
      actorId: "worker-a",
    })
    const secondTask = task("002", {
      sourceParty: "Клиент Б",
      actorId: "worker-b",
    })
    repairTasksApi.listPendingRepairAcceptance.mockResolvedValue([
      firstTask,
      secondTask,
    ])

    renderPage()

    await screen.findAllByText("БЫТ-001")
    const search = screen.getByRole("searchbox", {
      name: "Поиск приёмок",
    })
    await user.type(search, "002")

    await waitFor(() => expect(screen.queryByText("БЫТ-001")).toBeNull())
    expect(screen.getAllByText("БЫТ-002")).toHaveLength(2)

    await user.clear(search)
    await screen.findAllByText("БЫТ-001")

    const filtersPanel = document.getElementById("acceptance-filters")
    expect(filtersPanel).not.toBeNull()
    const sourcePartyFilter = within(filtersPanel as HTMLElement).getByRole(
      "button",
      { name: "От кого" }
    )
    await user.click(sourcePartyFilter)
    await user.click(screen.getByRole("checkbox", { name: "Клиент А" }))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => expect(screen.queryByText("БЫТ-002")).toBeNull())
    expect(screen.getAllByText("БЫТ-001")).toHaveLength(2)

    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры приёмки",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe("acceptance-filters")
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    await user.click(hideFilters)

    expect(filtersPanel?.hidden).toBe(true)
    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры приёмки",
    })
    expect(showFilters.getAttribute("aria-expanded")).toBe("false")

    await user.click(showFilters)

    expect(
      within(filtersPanel as HTMLElement)
        .getByRole("button", {
          name: /От кого/,
        })
        .getAttribute("data-variant")
    ).toBe("secondary")
  })
})

describe("AcceptancePage actionable selection", () => {
  it("does not expose a direct acceptance dossier for a parent absent from the actionable projection", async () => {
    const parentRepair = task("parent-repair")
    repairTasksApi.listPendingRepairAcceptance.mockResolvedValue([])
    repairTasksApi.getRepairTask.mockResolvedValue(parentRepair)

    renderPage("/acceptance?acceptanceId=parent-repair")

    expect(await screen.findByText("Приёмка недоступна")).toBeTruthy()
    expect(
      screen.getByText("Задание больше не доступно для приёмки.")
    ).toBeTruthy()
    expect(screen.queryByText("Досье приёмки")).toBeNull()
  })

  it("opens a direct acceptance dossier only for a repair returned by the actionable projection", async () => {
    const actionableRepair = task("actionable-repair")
    repairTasksApi.listPendingRepairAcceptance.mockResolvedValue([
      actionableRepair,
    ])
    repairTasksApi.getRepairTask.mockResolvedValue(actionableRepair)

    renderPage("/acceptance?acceptanceId=actionable-repair")

    expect(await screen.findByText("Досье приёмки")).toBeTruthy()
    expect(screen.queryByText("Приёмка недоступна")).toBeNull()
  })
})
