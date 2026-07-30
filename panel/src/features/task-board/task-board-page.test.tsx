import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  TaskBoardEntryDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardPage } from "@/features/task-board/task-board-page"

const mocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
  getTaskBoard: vi.fn(),
  getKpiSettings: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/hooks/use-mobile", () => ({ useIsMobile: () => false }))
vi.mock("@/features/task-board/api/task-board-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/task-board/api/task-board-api")
  >("@/features/task-board/api/task-board-api")

  return { ...actual, getTaskBoard: mocks.getTaskBoard }
})
vi.mock("@/features/settings/kpi/api/kpi-settings-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/settings/kpi/api/kpi-settings-api")
  >("@/features/settings/kpi/api/kpi-settings-api")

  return { ...actual, getKpiSettings: mocks.getKpiSettings }
})
vi.mock("@/features/task-board/task-board-column", () => ({
  TaskBoardColumn: ({
    dragDisabled,
    actionPending,
    queueActionsDisabled,
    visibleEntries,
    onEdit,
    palette,
  }: {
    dragDisabled: boolean
    actionPending: boolean
    queueActionsDisabled: boolean
    visibleEntries: TaskBoardEntryDto[]
    onEdit: (entry: TaskBoardEntryDto) => void
    palette: { ranges: { color: string }[] } | null
  }) => (
    <div
      data-testid="task-board-command-state"
      data-palette-color={palette?.ranges[0]?.color}
    >
      {dragDisabled && actionPending && queueActionsDisabled
        ? "read-only"
        : "editable"}
      <span data-testid="visible-task-external-ids">
        {visibleEntries
          .map((entry) => entry.externalTaskId ?? entry.taskId)
          .join(",")}
      </span>
      {visibleEntries[0] ? (
        <button type="button" onClick={() => onEdit(visibleEntries[0]!)}>
          Редактировать тестовый ремонт
        </button>
      ) : null}
    </div>
  ),
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const board: TaskBoardSnapshotDto = {
  warehouseId: WAREHOUSE_ID,
  selectedDate: "2026-07-18",
  availableDates: ["2026-07-18"],
  queues: [
    {
      key: "repair",
      label: "Ремонт",
      kind: "REPAIR",
      settingsQueueId: "00000000-0000-4000-8000-000000000002",
      settingsCollapsed: false,
      entries: [],
    },
  ],
  totalEntries: 0,
  realEntries: 0,
  shadowEntries: 0,
}

function user(level: "VIEW" | "EDIT"): CurrentUser {
  return {
    id: "user-1",
    username: "operator",
    displayName: "Operator",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function taskEntry(externalTaskId: string, title: string): TaskBoardEntryDto {
  return {
    id: `entry-${externalTaskId}`,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    queueKey: "repair",
    queueId: "00000000-0000-4000-8000-000000000002",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: 1,
    taskId: `task-${externalTaskId}`,
    externalTaskId,
    source: null,
    taskVersion: 1,
    title,
    unitNumber: "БЫТ-001",
    taskStatus: "ACTIVE",
    scheduledDate: "2026-07-18",
    priority: 3,
    pinned: false,
    status: "WAITING",
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: null,
    pausedAt: null,
    activeWorkSeconds: 0,
    timerSnapshot: null,
    assignments: [],
    detailsHref: null,
  }
}

function renderPage(
  level: "VIEW" | "EDIT",
  {
    currentBoard = board,
    initialEntry = "/",
  }: { currentBoard?: TaskBoardSnapshotDto; initialEntry?: string } = {}
) {
  mocks.useAuth.mockReturnValue({
    accessToken: "task-board-token",
    currentUser: user(level),
  })
  mocks.useWarehouse.mockReturnValue({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      version: 0,
      name: "СПБ",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      active: true,
      sortOrder: 0,
    },
  })
  mocks.getTaskBoard.mockResolvedValue(currentBoard)
  mocks.getKpiSettings.mockResolvedValue({
    warehouseId: WAREHOUSE_ID,
    timeZone: "Europe/Moscow",
    status: "ACTIVE",
    version: 2,
    dataAvailableFrom: "2026-07-18",
    palette: {
      version: 1,
      ranges: [{ fromPercent: 0, toPercent: 100, color: "#123456" }],
      overdueColor: "#654321",
    },
    activeSchedule: null,
    pendingSchedule: null,
  })

  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <TaskBoardPage />
        <LocationProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function LocationProbe() {
  const location = useLocation()
  return (
    <p data-testid="task-board-location">
      {location.pathname}
      {location.search}
    </p>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("task board warehouse access", () => {
  it("keeps VIEW users read-only", async () => {
    renderPage("VIEW")

    const search = screen.getByLabelText("Поиск по доске задач")
    expect(
      search.closest('[data-slot="page-toolbar-content"]')?.className
    ).toContain("max-w-xl")

    const dateSelector = await screen.findByRole("region", {
      name: "Дата очереди",
    })
    for (const label of ["Предыдущие даты", "Следующие даты"]) {
      const button = screen.getByRole("button", { name: label })
      expect(button.getAttribute("data-size")).toBe("icon")
      expect(button.classList.contains("size-9")).toBe(true)
    }
    const dateButtons = dateSelector.querySelectorAll("button[aria-pressed]")
    expect(dateButtons).toHaveLength(7)
    for (const button of dateButtons) {
      expect(button.getAttribute("data-size")).toBe("default")
      expect(button.classList.contains("h-9")).toBe(true)
    }

    const createButton = screen.getByRole("button", {
      name: "Создать задание",
    }) as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
    expect(
      (await screen.findByTestId("task-board-command-state")).textContent
    ).toBe("read-only")
    expect(
      screen.getByTestId("task-board-command-state").dataset.paletteColor
    ).toBe("#123456")
  })

  it("enables commands for EDIT users", async () => {
    renderPage("EDIT")

    expect(screen.getByRole("link", { name: "Создать задание" })).toBeTruthy()
    await waitFor(() => {
      expect(screen.getByTestId("task-board-command-state").textContent).toBe(
        "editable"
      )
    })
  })

  it("shows only the task opened from a shipment furniture blocker", async () => {
    const furnitureTaskId = "furniture-task-1"
    const otherTaskId = "other-task-2"
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: 2,
      realEntries: 2,
      queues: [
        {
          ...board.queues[0]!,
          entries: [
            taskEntry(furnitureTaskId, "Переместить мебель"),
            taskEntry(otherTaskId, "Другое задание"),
          ],
        },
      ],
    }

    renderPage("EDIT", {
      currentBoard,
      initialEntry: `/?externalTaskId=${furnitureTaskId}`,
    })

    expect(
      await screen.findByText("Открыто связанное логистическое задание.")
    ).toBeTruthy()
    expect(
      (await screen.findByTestId("visible-task-external-ids")).textContent
    ).toBe(furnitureTaskId)
  })

  it("opens a waiting maintenance repair in edit mode", async () => {
    const sourceId = "repair id/with space"
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: 1,
      realEntries: 1,
      queues: [
        {
          ...board.queues[0]!,
          entries: [
            {
              ...taskEntry("repair-stage-1", "Замена панели"),
              source: { type: "MAINTENANCE_REPAIR", sourceId },
            },
          ],
        },
      ],
    }

    renderPage("EDIT", { currentBoard })

    await userEvent.setup().click(
      await screen.findByRole("button", {
        name: "Редактировать тестовый ремонт",
      })
    )

    expect(screen.getByTestId("task-board-location").textContent).toBe(
      "/repairs?repairId=repair%20id%2Fwith%20space&edit=1"
    )
  })
})
