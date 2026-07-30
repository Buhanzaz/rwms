import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import { RepairsPage } from "@/features/repairs/repairs-page"
import {
  REPAIRS_TABLE_TEST_DATES,
  REPAIRS_TABLE_TEST_FIXTURES,
} from "@/features/repairs/repairs-table.test-fixtures"
import {
  buildRepairsTableFilterDefinitions,
  createEmptyRepairsTableFilters,
  filterRepairsTable,
  getRepairDate,
  getRepairOperationalStatusLabel,
} from "@/features/repairs/repairs-table-model"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"
import { ApiError } from "@/lib/api-client"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"

const api = vi.hoisted(() => ({
  getRepairTask: vi.fn(),
  listRepairTasks: vi.fn(),
  listMaintenanceRepairs: vi.fn(),
  getTaskBoardsForAvailableDates: vi.fn(),
  moveTaskBoardEntry: vi.fn(),
  pinTaskBoardEntry: vi.fn(),
}))
const viewport = vi.hoisted(() => ({ isMobile: false }))

const currentUser: CurrentUser = {
  id: "repairs-table-test-user",
  username: "repairs-table-test",
  displayName: "Repairs table test",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  rentalAccess: false,
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "EDIT" }],
}

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    status: "authenticated",
    accessToken: "repairs-table-token",
    currentUser,
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      name: "Москва",
    },
    selectedWarehouseId: WAREHOUSE_ID,
  }),
}))

vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
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

vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  () => ({
    listMaintenanceRepairs: api.listMaintenanceRepairs,
  })
)

vi.mock("@/features/repairs/repairs-queue-view", () => ({
  RepairsQueueView: ({
    onPin,
  }: {
    onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  }) => (
    <>
      <p>Очередь ремонтов</p>
      <button
        type="button"
        onClick={() => onPin({ id: "stale-entry" } as TaskBoardEntryDto, true)}
      >
        Закрепить тестовый ремонт
      </button>
    </>
  ),
}))

vi.mock("@/features/repair-tasks/repair-task-detail-workspace", () => ({
  RepairTaskDetailWorkspace: () => null,
}))

vi.mock("@/features/repair-tasks/repair-task-editor-workspace", () => ({
  RepairTaskEditorWorkspace: () => null,
}))

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
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  viewport.isMobile = false
  api.listRepairTasks.mockResolvedValue(REPAIRS_TABLE_TEST_FIXTURES)
  api.listMaintenanceRepairs.mockResolvedValue({
    items: REPAIRS_TABLE_TEST_FIXTURES.map((repair) => ({
      id: repair.id,
      complexity: {
        type: "MEDIUM",
        name: "Средний ремонт",
        color: "#F59E0B",
        plannedMinutes: "120",
        forcedCapital: false,
      },
    })),
    page: 0,
    size: 200,
    totalElements: REPAIRS_TABLE_TEST_FIXTURES.length,
  })
  api.getTaskBoardsForAvailableDates.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("repairs table test fixtures", () => {
  it("contains 18 repairs split into six repairs on each of three dates", () => {
    expect(REPAIRS_TABLE_TEST_FIXTURES).toHaveLength(18)

    for (const date of REPAIRS_TABLE_TEST_DATES) {
      const filters = createEmptyRepairsTableFilters()
      filters.date = [date]
      const filtered = filterRepairsTable(
        REPAIRS_TABLE_TEST_FIXTURES,
        "",
        filters
      )

      expect(filtered).toHaveLength(6)
      expect(filtered.every((repair) => getRepairDate(repair) === date)).toBe(
        true
      )
    }
  })

  it("keeps a maintenance draft labelled as a draft even with planned stages", () => {
    const source = REPAIRS_TABLE_TEST_FIXTURES[0]!
    const draft = {
      ...source,
      status: "DRAFT" as const,
      subtasks: source.subtasks.map((subtask) => ({
        ...subtask,
        status: "WAITING" as const,
      })),
    }

    expect(getRepairOperationalStatusLabel(draft)).toBe("Черновик")
  })
})

describe("RepairsPage table view", () => {
  it("renders filter triggers at the shared 36px button height", async () => {
    renderPage()

    await screen.findAllByRole("button", {
      name: "Открыть ремонт бытовки ТЕСТ-001",
    })

    for (const label of [
      "Тип ремонта",
      "Задание",
      "Дата",
      "Приоритет",
      "Статус",
    ]) {
      const trigger = screen.getByRole("button", {
        name: label,
        expanded: false,
      })
      expect(trigger.getAttribute("data-size")).toBe("default")
      expect(trigger.classList.contains("h-9")).toBe(true)
    }
  })

  it("uses the full repairs workspace for both rows and the empty state", async () => {
    const rendered = renderPage()

    await screen.findAllByRole("button", {
      name: "Открыть ремонт бытовки ТЕСТ-001",
    })

    const populatedWorkspace = screen.getByTestId("repairs-table-workspace")
    expect(populatedWorkspace.classList.contains("h-full")).toBe(true)
    expect(populatedWorkspace.classList.contains("w-full")).toBe(true)

    rendered.unmount()
    api.listRepairTasks.mockResolvedValueOnce([])
    renderPage()

    const emptyState = await screen.findByRole("status")
    const emptyWorkspace = screen.getByTestId("repairs-table-workspace")
    expect(emptyState.textContent).toContain(
      "Ремонты по заданным условиям не найдены."
    )
    expect(emptyState.parentElement).toBe(emptyWorkspace)
    expect(emptyWorkspace.children).toHaveLength(1)
    expect(emptyState.classList.contains("min-h-full")).toBe(true)
    expect(emptyState.classList.contains("w-full")).toBe(true)
  })

  it("does not load boards or show pin controls", async () => {
    renderPage()

    expect(
      await screen.findAllByRole("button", {
        name: "Открыть ремонт бытовки ТЕСТ-001",
      })
    ).toHaveLength(2)
    expect(api.getTaskBoardsForAvailableDates).not.toHaveBeenCalled()
    expect(screen.queryByText("Закрепление")).toBeNull()
    expect(
      screen.queryByRole("button", { name: /Закрепить|Открепить/ })
    ).toBeNull()
  })

  it("does not show or filter by queue position", async () => {
    renderPage()

    await screen.findAllByRole("button", {
      name: "Открыть ремонт бытовки ТЕСТ-001",
    })

    expect(
      buildRepairsTableFilterDefinitions(REPAIRS_TABLE_TEST_FIXTURES).map(
        (definition) => definition.id
      )
    ).not.toContain("queuePosition")
    expect(screen.queryByRole("button", { name: "В очереди" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Позиция в очереди" })
    ).toBeNull()
    expect(screen.queryByText("Очередь")).toBeNull()
  })

  it("applies cabin search and date filters to desktop and mobile rows", async () => {
    renderPage()

    const search = await screen.findByRole("textbox", {
      name: "Поиск по ремонтам",
    })
    fireEvent.change(search, { target: { value: "ТЕСТ-018" } })

    expect(
      screen.getAllByRole("button", {
        name: "Открыть ремонт бытовки ТЕСТ-018",
      })
    ).toHaveLength(2)
    expect(
      screen.queryByRole("button", {
        name: "Открыть ремонт бытовки ТЕСТ-001",
      })
    ).toBeNull()

    fireEvent.change(search, { target: { value: "" } })
    fireEvent.click(
      screen.getByRole("button", { name: "Дата", expanded: false })
    )
    fireEvent.click(
      screen.getByRole("checkbox", {
        name: /24.*2026/,
      })
    )
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => {
      expect(
        screen.getAllByRole("button", {
          name: /Открыть ремонт бытовки ТЕСТ-/,
        })
      ).toHaveLength(12)
    })
    expect(
      screen.getAllByRole("button", {
        name: "Открыть ремонт бытовки ТЕСТ-001",
      })
    ).toHaveLength(2)
    expect(
      screen.queryByRole("button", {
        name: "Открыть ремонт бытовки ТЕСТ-007",
      })
    ).toBeNull()
  })

  it("collapses repair filters without resetting their selected values", async () => {
    renderPage()

    await screen.findAllByRole("button", {
      name: "Открыть ремонт бытовки ТЕСТ-001",
    })
    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры ремонтов",
    })
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")
    expect(hideFilters.getAttribute("aria-controls")).toBe(
      "repairs-table-filters"
    )

    fireEvent.click(
      screen.getByRole("button", { name: "Дата", expanded: false })
    )
    fireEvent.click(
      screen.getByRole("checkbox", {
        name: /24.*2026/,
      })
    )
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => {
      expect(
        screen.getAllByRole("button", {
          name: /Открыть ремонт бытовки ТЕСТ-/,
        })
      ).toHaveLength(12)
    })

    fireEvent.click(hideFilters)
    expect(document.getElementById("repairs-table-filters")?.hidden).toBe(true)
    expect(
      screen
        .getByRole("button", { name: "Показать фильтры ремонтов" })
        .getAttribute("aria-expanded")
    ).toBe("false")
    expect(
      screen.getAllByRole("button", {
        name: /Открыть ремонт бытовки ТЕСТ-/,
      })
    ).toHaveLength(12)

    fireEvent.click(
      screen.getByRole("button", { name: "Показать фильтры ремонтов" })
    )
    expect(document.getElementById("repairs-table-filters")?.hidden).toBe(false)
    const filtersPanel = document.getElementById("repairs-table-filters")
    expect(filtersPanel).not.toBeNull()
    expect(
      within(filtersPanel!).getByRole("button", { name: /Дата/ }).textContent
    ).toContain("1")
  })

  it("keeps repair filters closed on mobile and puts the view switcher on the next row", async () => {
    viewport.isMobile = true
    renderPage()

    await screen.findAllByRole("button", {
      name: "Открыть ремонт бытовки ТЕСТ-001",
    })

    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры ремонтов",
    })
    const create = screen.getByRole("button", { name: "Создать задание" })
    const viewSwitcher = screen.getByRole("radiogroup", {
      name: "Вид ремонтов",
    })

    expect(showFilters.getAttribute("aria-expanded")).toBe("false")
    expect(document.getElementById("repairs-table-filters")?.hidden).toBe(true)
    expect(showFilters.parentElement?.className).toContain("order-1")
    expect(showFilters.parentElement?.contains(create)).toBe(true)
    expect(viewSwitcher.parentElement?.className).toContain("order-2")
  })
})

describe("RepairsPage queue conflicts", () => {
  it("refreshes board and repairs after a version conflict", async () => {
    api.pinTaskBoardEntry.mockRejectedValueOnce(
      new ApiError("Версия очереди устарела", 409)
    )
    renderPage("/repairs?view=queue")

    await screen.findByText("Очередь ремонтов")
    expect(api.getTaskBoardsForAvailableDates).toHaveBeenCalledTimes(1)
    expect(api.listRepairTasks).toHaveBeenCalledTimes(1)

    fireEvent.click(
      screen.getByRole("button", { name: "Закрепить тестовый ремонт" })
    )

    expect(
      await screen.findByText(
        "Очередь уже изменилась. Данные обновлены — повторите действие."
      )
    ).not.toBeNull()
    await waitFor(() => {
      expect(api.getTaskBoardsForAvailableDates).toHaveBeenCalledTimes(2)
      expect(api.listRepairTasks).toHaveBeenCalledTimes(2)
    })
  })
})
