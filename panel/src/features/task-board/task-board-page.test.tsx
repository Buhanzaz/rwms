import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import type { TaskBoardRepairComplexity } from "@/features/task-board/task-board-card"
import { TaskBoardPage } from "@/features/task-board/task-board-page"
import { readTaskBoardViewPreferences } from "@/features/task-board/task-board-view-preferences"

const mocks = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
  getTaskBoard: vi.fn(),
  getKpiSettings: vi.fn(),
  listMaintenanceRepairs: vi.fn(),
  updateTaskBoardWorkerPlan: vi.fn(),
  reorderTaskBoardEntry: vi.fn(),
  restoreTaskBoardTask: vi.fn(),
  setFutureTaskBoardEntryAvailability: vi.fn(),
  suspendTaskBoardTask: vi.fn(),
  getTaskRequirements: vi.fn(),
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

  return {
    ...actual,
    getTaskBoard: mocks.getTaskBoard,
    updateTaskBoardWorkerPlan: mocks.updateTaskBoardWorkerPlan,
    reorderTaskBoardEntry: mocks.reorderTaskBoardEntry,
    restoreTaskBoardTask: mocks.restoreTaskBoardTask,
    setFutureTaskBoardEntryAvailability:
      mocks.setFutureTaskBoardEntryAvailability,
    suspendTaskBoardTask: mocks.suspendTaskBoardTask,
  }
})
vi.mock("@/features/settings/kpi/api/kpi-settings-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/settings/kpi/api/kpi-settings-api")
  >("@/features/settings/kpi/api/kpi-settings-api")

  return { ...actual, getKpiSettings: mocks.getKpiSettings }
})
vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  () => ({ listMaintenanceRepairs: mocks.listMaintenanceRepairs })
)
vi.mock("@/features/task-board/api/task-requirements-api", () => ({
  getTaskRequirements: mocks.getTaskRequirements,
  taskRequirementsQueryKey: (warehouseId: string, taskId: string) => [
    "task-board",
    warehouseId,
    "requirements",
    taskId,
  ],
}))
vi.mock("@/features/task-board/task-board-column", () => ({
  TaskBoardColumn: ({
    actionPending,
    queueActionsDisabled,
    visibleEntries,
    onEdit,
    onShowFullRoute,
    dailyPlanEntryIds,
    highlightedTaskId,
    palette,
    repairComplexitiesByRepairId,
    queue,
    canManage,
    reorderDisabled,
    onUpdateWorkerPlan,
    onReorder,
    collapsed,
    futureEntryIds,
    initialScrollTop,
    onFutureAvailabilityChange,
    onSuspend,
    onRestore,
    onRequirements,
    onToggleCollapsed,
    onScrollTopChange,
  }: {
    actionPending: boolean
    queueActionsDisabled: boolean
    visibleEntries: TaskBoardEntryDto[]
    onEdit: (entry: TaskBoardEntryDto) => void
    onShowFullRoute: (entry: TaskBoardEntryDto) => void
    dailyPlanEntryIds: ReadonlySet<string>
    highlightedTaskId: string | null
    palette: { ranges: { color: string }[] } | null
    repairComplexitiesByRepairId: ReadonlyMap<
      string,
      { name: string; type: string; color: string }
    >
    queue: TaskBoardQueueDto
    canManage: boolean
    reorderDisabled: boolean
    onUpdateWorkerPlan: (
      queue: TaskBoardQueueDto,
      workerFeedEnabled: boolean,
      availableTaskLimit: number
    ) => void
    onReorder: (entry: TaskBoardEntryDto, targetIndex: number) => void
    collapsed: boolean
    futureEntryIds: ReadonlySet<string>
    initialScrollTop: number
    onFutureAvailabilityChange: (
      entry: TaskBoardEntryDto,
      available: boolean
    ) => void
    onSuspend: (entry: TaskBoardEntryDto) => void
    onRestore: (entry: TaskBoardEntryDto) => void
    onRequirements: (entry: TaskBoardEntryDto) => void
    onToggleCollapsed: (queueKey: string) => void
    onScrollTopChange: (queueKey: string, scrollTop: number) => void
  }) => (
    <div
      data-testid="task-board-command-state"
      data-command-state={
        actionPending && queueActionsDisabled ? "read-only" : "editable"
      }
      data-palette-color={palette?.ranges[0]?.color}
      data-can-manage={canManage}
      data-reorder-disabled={reorderDisabled}
      data-collapsed={collapsed}
      data-initial-scroll-top={initialScrollTop}
    >
      {actionPending && queueActionsDisabled ? "read-only" : "editable"}
      <span data-testid="visible-task-external-ids">
        {visibleEntries
          .map((entry) => entry.externalTaskId ?? entry.taskId)
          .join(",")}
      </span>
      <span data-testid="first-visible-task-suspended">
        {String(visibleEntries[0]?.suspended ?? false)}
      </span>
      <span
        data-testid="visible-repair-complexities"
        data-colors={[...repairComplexitiesByRepairId.values()]
          .map((item) => item.color)
          .join(",")}
      >
        {visibleEntries
          .map((entry) =>
            entry.source?.type === "MAINTENANCE_REPAIR"
              ? (repairComplexitiesByRepairId.get(entry.source.sourceId)
                  ?.name ?? "")
              : ""
          )
          .join(",")}
      </span>
      <span data-testid="daily-plan-entry-ids">
        {visibleEntries
          .filter((entry) => dailyPlanEntryIds.has(entry.id))
          .map((entry) => entry.id)
          .join(",")}
      </span>
      <span data-testid="highlighted-route-entry-ids">
        {visibleEntries
          .filter((entry) => entry.taskId === highlightedTaskId)
          .map((entry) => entry.id)
          .join(",")}
      </span>
      {visibleEntries
        .filter((entry) => entry.entryType === "REAL")
        .map((entry) => (
          <button
            key={`route-${entry.id}`}
            type="button"
            onClick={() => onShowFullRoute(entry)}
          >
            Полный путь {entry.id}
          </button>
        ))}
      {visibleEntries[0] ? (
        <button type="button" onClick={() => onEdit(visibleEntries[0]!)}>
          Редактировать тестовый ремонт
        </button>
      ) : null}
      {visibleEntries
        .filter((entry) => futureEntryIds.has(entry.id))
        .map((entry) => (
          <button
            key={`availability-${entry.id}`}
            type="button"
            onClick={() =>
              onFutureAvailabilityChange(entry, entry.entryType !== "REAL")
            }
          >
            Доступность {entry.id}
          </button>
        ))}
      <button type="button" onClick={() => onToggleCollapsed(queue.key)}>
        Переключить очередь {queue.key}
      </button>
      <button type="button" onClick={() => onScrollTopChange(queue.key, 275)}>
        Прокрутить очередь {queue.key}
      </button>
      <button type="button" onClick={() => onUpdateWorkerPlan(queue, false, 3)}>
        Настроить WorkerApp {queue.key}
      </button>
      {visibleEntries[0] ? (
        <button type="button" onClick={() => onReorder(visibleEntries[0]!, 1)}>
          Переместить тестовое задание
        </button>
      ) : null}
      {visibleEntries[0] ? (
        <button type="button" onClick={() => onSuspend(visibleEntries[0]!)}>
          Отключить тестовое задание
        </button>
      ) : null}
      {visibleEntries[0] ? (
        <button type="button" onClick={() => onRestore(visibleEntries[0]!)}>
          Восстановить тестовое задание
        </button>
      ) : null}
      {visibleEntries
        .filter(
          (entry) =>
            entry.source?.type === "MAINTENANCE_REPAIR" && !entry.suspended
        )
        .map((entry) => (
          <button
            key={`requirements-${entry.id}`}
            type="button"
            onClick={() => onRequirements(entry)}
          >
            Требования
          </button>
        ))}
    </div>
  ),
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const board: TaskBoardSnapshotDto = {
  warehouseId: WAREHOUSE_ID,
  queues: [
    {
      key: "repair",
      version: 4,
      label: "Ремонт",
      kind: "REPAIR",
      settingsQueueId: "00000000-0000-4000-8000-000000000002",
      settingsCollapsed: false,
      workerFeedEnabled: true,
      availableTaskLimit: 6,
      linkedQueueId: null,
      linkedQueueName: null,
      entries: [],
    },
  ],
  totalEntries: 0,
  realEntries: 0,
  shadowEntries: 0,
}

function user(level: "VIEW" | "EDIT" | "MANAGE"): CurrentUser {
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
    suspended: false,
    status: "WAITING",
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: null,
    pausedAt: null,
    activeWorkSeconds: 0,
    timerSnapshot: null,
    assignments: [],
    detailsHref: null,
    hasProblem: false,
    incomplete: false,
    completedWorkPercent: 100,
  }
}

type RepairComplexityFixture = {
  id: string
  complexity: TaskBoardRepairComplexity
}

function renderPage(
  level: "VIEW" | "EDIT" | "MANAGE",
  {
    currentBoard = board,
    initialEntry = "/",
    maintenanceRepairs = [],
    maintenancePage,
    requirements,
  }: {
    currentBoard?: TaskBoardSnapshotDto
    initialEntry?: string
    maintenanceRepairs?: RepairComplexityFixture[]
    maintenancePage?: (filters: {
      executionState?: string
      repairIds?: readonly string[]
      page?: number
      size?: number
    }) => Promise<{
      items: RepairComplexityFixture[]
      page: number
      size: number
      totalElements: number
    }>
    requirements?: unknown
  } = {}
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
  mocks.updateTaskBoardWorkerPlan.mockResolvedValue({
    id: currentBoard.queues[0]?.settingsQueueId ?? "queue-1",
    version: (currentBoard.queues[0]?.version ?? 0) + 1,
    workerFeedEnabled: false,
    availableTaskLimit: 3,
  })
  mocks.reorderTaskBoardEntry.mockResolvedValue(currentBoard)
  mocks.suspendTaskBoardTask.mockResolvedValue(currentBoard)
  mocks.restoreTaskBoardTask.mockResolvedValue(currentBoard)
  mocks.getTaskRequirements.mockResolvedValue(
    requirements ?? {
      taskId: "task-default",
      taskVersion: 1,
      hasProblem: true,
      incomplete: true,
      completedWorkPercent: 80,
      items: [],
    }
  )
  mocks.setFutureTaskBoardEntryAvailability.mockResolvedValue(currentBoard)
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
  if (maintenancePage) {
    mocks.listMaintenanceRepairs.mockImplementation(
      (_token, _warehouse, filters) => maintenancePage(filters)
    )
  } else {
    mocks.listMaintenanceRepairs.mockResolvedValue({
      items: maintenanceRepairs,
      page: 0,
      size: 200,
      totalElements: maintenanceRepairs.length,
    })
  }

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
  vi.useRealTimers()
  vi.clearAllMocks()
  window.localStorage.clear()
})

describe("task board warehouse access", () => {
  it("refreshes worker requirement changes in the open board and drops restored selections", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const entry = { ...taskEntry("restore", "Восстановление"), suspended: true }
    const currentBoard = {
      ...board,
      queues: [{ ...board.queues[0]!, entries: [entry] }],
    }
    const requirements = {
      taskId: entry.taskId,
      taskVersion: 9,
      hasProblem: true,
      incomplete: true,
      completedWorkPercent: 80,
      items: [
        {
          itemId: "work-1",
          kind: "WORK",
          name: "Установка вешалки",
          state: "MISSING",
          linkedItemIds: ["material-1"],
        },
        {
          itemId: "material-1",
          kind: "MATERIAL",
          name: "Вешалка",
          state: "MISSING",
          linkedItemIds: ["work-1"],
        },
        {
          itemId: "other",
          kind: "MATERIAL",
          name: "Краска",
          state: "AVAILABLE",
          linkedItemIds: [],
        },
      ],
    }
    const userEventApi = userEvent.setup()
    renderPage("EDIT", { currentBoard, requirements })
    await userEventApi.click(
      await screen.findByRole("button", {
        name: "Восстановить тестовое задание",
      })
    )
    const work = await screen.findByRole("checkbox", {
      name: /Работа: Установка вешалки/,
    })
    const material = screen.getByRole("checkbox", { name: /Материал: Вешалка/ })
    const other = screen.getByRole("checkbox", { name: /Материал: Краска/ })
    await userEventApi.click(material)
    expect(work.getAttribute("aria-checked")).toBe("true")
    expect(material.getAttribute("aria-checked")).toBe("true")
    expect(other.closest("label")?.className).not.toContain("bg-destructive")
    await userEventApi.click(work)
    expect(work.getAttribute("aria-checked")).toBe("false")
    expect(material.getAttribute("aria-checked")).toBe("false")
    await userEventApi.click(material)

    mocks.getTaskBoard.mockResolvedValue({
      ...currentBoard,
      queues: [
        {
          ...currentBoard.queues[0]!,
          entries: [{ ...entry, suspended: false }],
        },
      ],
    })
    mocks.getTaskRequirements.mockResolvedValue({
      ...requirements,
      taskVersion: 10,
      items: requirements.items.map((item) =>
        item.state === "MISSING" ? { ...item, state: "RESTORED" } : item
      ),
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_001)
    })
    expect(screen.getByTestId("first-visible-task-suspended").textContent).toBe(
      "false"
    )
    expect(work.getAttribute("aria-checked")).toBe("false")
    expect(material.getAttribute("aria-checked")).toBe("false")
    expect(material.closest("label")?.style.borderColor).toBe(
      "rgb(35, 134, 54)"
    )
    expect(material.closest("label")?.className).not.toContain("bg-destructive")
    expect(other.closest("label")?.className).not.toContain("bg-green-500")
    await userEventApi.click(
      screen.getByRole("button", { name: "Восстановить" })
    )
    await waitFor(() =>
      expect(mocks.restoreTaskBoardTask).toHaveBeenCalledWith(
        "task-board-token",
        expect.objectContaining({ taskVersion: 10 }),
        []
      )
    )
  })

  it("restores exactly the transitive missing group with the fresh requirements version", async () => {
    const entry = {
      ...taskEntry("restore", "Восстановление"),
      suspended: true,
      taskVersion: 2,
      source: { type: "MAINTENANCE_REPAIR" as const, sourceId: "repair-1" },
    }
    const currentBoard = {
      ...board,
      queues: [{ ...board.queues[0]!, entries: [entry] }],
      totalEntries: 1,
      realEntries: 1,
    }
    const requirements = {
      taskId: entry.taskId,
      taskVersion: 9,
      hasProblem: true,
      incomplete: true,
      completedWorkPercent: 80,
      items: [
        {
          itemId: "work-1",
          kind: "WORK",
          name: "Покраска",
          state: "MISSING",
          linkedItemIds: ["material-1"],
        },
        {
          itemId: "material-1",
          kind: "MATERIAL",
          name: "Краска",
          state: "MISSING",
          linkedItemIds: ["work-1", "available-1"],
        },
        {
          itemId: "available-1",
          kind: "MATERIAL",
          name: "Грунтовка",
          state: "AVAILABLE",
          linkedItemIds: ["material-1"],
        },
        {
          itemId: "unrelated-missing",
          kind: "MATERIAL",
          name: "Шурупы",
          state: "MISSING",
          linkedItemIds: [],
        },
      ],
    }
    const restoredRequirements = {
      ...requirements,
      hasProblem: true,
      incomplete: false,
      items: requirements.items.map((item) =>
        item.itemId === "work-1" || item.itemId === "material-1"
          ? { ...item, state: "RESTORED" as const }
          : item
      ),
    }
    const restoredBoard = {
      ...currentBoard,
      queues: [
        {
          ...currentBoard.queues[0]!,
          entries: [
            {
              ...entry,
              suspended: false,
              incomplete: false,
              hasProblem: true,
            },
          ],
        },
      ],
    }
    const userEventApi = userEvent.setup()
    renderPage("EDIT", { currentBoard, requirements })
    mocks.getTaskRequirements
      .mockResolvedValueOnce(requirements)
      .mockResolvedValue(restoredRequirements)
    mocks.restoreTaskBoardTask.mockResolvedValue(restoredBoard)

    await userEventApi.click(
      await screen.findByRole("button", {
        name: "Восстановить тестовое задание",
      })
    )
    await userEventApi.click(
      await screen.findByRole("checkbox", { name: /Работа: Покраска/ })
    )
    await userEventApi.click(
      screen.getByRole("button", { name: "Восстановить" })
    )

    await waitFor(() =>
      expect(mocks.restoreTaskBoardTask).toHaveBeenCalledTimes(1)
    )
    expect(mocks.restoreTaskBoardTask).toHaveBeenCalledWith(
      "task-board-token",
      expect.objectContaining({ taskVersion: 9 }),
      expect.arrayContaining(["work-1", "material-1"])
    )
    expect(mocks.restoreTaskBoardTask.mock.calls[0]![2]).not.toContain(
      "available-1"
    )
    await userEventApi.click(
      await screen.findByRole("button", { name: "Требования" })
    )
    const restoredWork = await screen.findByText("Покраска")
    const restoredMaterial = screen.getByText("Краска")
    const availableItem = screen.getByText("Грунтовка")
    const unrelatedMissing = screen.getByText("Шурупы")
    expect(restoredWork.closest("div")?.style.borderColor).toBe(
      "rgb(35, 134, 54)"
    )
    expect(restoredMaterial.closest("div")?.style.borderColor).toBe(
      "rgb(35, 134, 54)"
    )
    expect(unrelatedMissing.closest("div")?.style.borderColor).toBe(
      "rgb(255, 59, 48)"
    )
    expect(availableItem.closest("div")?.style.backgroundColor).toBe("")
    expect(availableItem.closest("div")?.className).not.toContain(
      "bg-destructive"
    )
  })

  it("keeps VIEW users read-only", async () => {
    renderPage("VIEW")

    const search = screen.getByLabelText("Поиск по доске задач")
    expect(
      search.closest('[data-slot="page-toolbar-content"]')?.className
    ).toContain("max-w-xl")

    expect(screen.queryByRole("region", { name: "Дата очереди" })).toBeNull()
    expect(mocks.getTaskBoard).toHaveBeenCalledWith(
      "task-board-token",
      WAREHOUSE_ID
    )

    const createButton = screen.getByRole("button", {
      name: "Создать задание",
    }) as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
    expect(
      (await screen.findByTestId("task-board-command-state")).dataset
        .commandState
    ).toBe("read-only")
    expect(
      screen.getByTestId("task-board-command-state").dataset.paletteColor
    ).toBe("#123456")
  })

  it("enables commands for EDIT users", async () => {
    renderPage("EDIT")

    expect(screen.getByRole("link", { name: "Создать задание" })).toBeTruthy()
    await waitFor(() => {
      expect(
        screen.getByTestId("task-board-command-state").dataset.commandState
      ).toBe("editable")
    })
    expect(
      screen.getByTestId("task-board-command-state").dataset.canManage
    ).toBe("false")
  })

  it("saves WorkerApp queue settings only for MANAGE users", async () => {
    renderPage("MANAGE")

    const commandState = await screen.findByTestId("task-board-command-state")
    expect(commandState.dataset.canManage).toBe("true")
    await userEvent.setup().click(
      screen.getByRole("button", {
        name: "Настроить WorkerApp repair",
      })
    )

    await waitFor(() => {
      expect(mocks.updateTaskBoardWorkerPlan).toHaveBeenCalledWith({
        accessToken: "task-board-token",
        warehouseId: WAREHOUSE_ID,
        queue: expect.objectContaining({ key: "repair", version: 4 }),
        workerFeedEnabled: false,
        availableTaskLimit: 3,
      })
    })
    expect(
      await screen.findByText("Настройки очереди для WorkerApp сохранены.")
    ).toBeTruthy()
  })

  it("reorders a complete unfiltered queue and disables sorting during search", async () => {
    const first = taskEntry("first", "Первая задача")
    const second = taskEntry("second", "Вторая задача")
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: 2,
      realEntries: 2,
      queues: [{ ...board.queues[0]!, entries: [first, second] }],
    }
    mocks.reorderTaskBoardEntry.mockResolvedValue({
      ...currentBoard,
      queues: [{ ...currentBoard.queues[0]!, entries: [second, first] }],
    })
    renderPage("EDIT", { currentBoard })

    const commandState = await screen.findByTestId("task-board-command-state")
    expect(commandState.dataset.reorderDisabled).toBe("false")
    await userEvent
      .setup()
      .click(
        screen.getByRole("button", { name: "Переместить тестовое задание" })
      )
    await waitFor(() => {
      expect(mocks.reorderTaskBoardEntry).toHaveBeenCalledWith({
        accessToken: "task-board-token",
        queue: expect.objectContaining({ key: "repair", version: 4 }),
        entry: first,
        targetEntryId: "entry-second",
        targetIndex: 1,
      })
    })

    await userEvent
      .setup()
      .type(screen.getByLabelText("Поиск по доске задач"), "вторая")
    expect(commandState.dataset.reorderDisabled).toBe("true")
  })

  it("refreshes global KPI and repair colors in an already open board", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const repairId = "repair-1"
    renderPage("EDIT", {
      currentBoard: {
        ...board,
        queues: [
          {
            ...board.queues[0]!,
            entries: [
              {
                ...taskEntry("repair-stage", "Работа"),
                source: { type: "MAINTENANCE_REPAIR", sourceId: repairId },
              },
            ],
          },
        ],
      },
      maintenanceRepairs: [
        {
          id: repairId,
          complexity: {
            type: "LIGHT",
            name: "Лёгкий ремонт",
            color: "#16A34A",
          },
        },
      ],
    })
    await waitFor(() =>
      expect(
        screen.getByTestId("visible-repair-complexities").dataset.colors
      ).toBe("#16A34A")
    )
    const settings = await mocks.getKpiSettings.mock.results[0]!.value
    mocks.getKpiSettings.mockResolvedValue({
      ...settings,
      palette: {
        ...settings.palette,
        ranges: [{ fromPercent: 0, toPercent: 100, color: "#112233" }],
      },
    })
    mocks.listMaintenanceRepairs.mockResolvedValue({
      items: [
        {
          id: repairId,
          complexity: {
            type: "LIGHT",
            name: "Лёгкий ремонт",
            color: "#445566",
          },
        },
      ],
      page: 0,
      size: 200,
      totalElements: 1,
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_001)
    })
    expect(mocks.getKpiSettings).toHaveBeenCalledTimes(2)
    expect(mocks.listMaintenanceRepairs).toHaveBeenCalledTimes(2)
    expect(
      screen.getByTestId("task-board-command-state").dataset.paletteColor
    ).toBe("#112233")
    expect(
      screen.getByTestId("visible-repair-complexities").dataset.colors
    ).toBe("#445566")
  })

  it("loads service-issued complexities in bounded active-repair requests", async () => {
    const firstRepairId = "repair-1"
    const secondRepairId = "repair-2"
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: 2,
      realEntries: 2,
      queues: [
        {
          ...board.queues[0]!,
          entries: [
            {
              ...taskEntry("repair-stage-1", "Замена панели"),
              source: {
                type: "MAINTENANCE_REPAIR",
                sourceId: firstRepairId,
              },
            },
            {
              ...taskEntry("repair-stage-2", "Замена двери"),
              source: {
                type: "MAINTENANCE_REPAIR",
                sourceId: secondRepairId,
              },
            },
          ],
        },
      ],
    }

    renderPage("EDIT", {
      currentBoard,
      maintenanceRepairs: [
        {
          id: firstRepairId,
          complexity: {
            type: "LIGHT",
            name: "Лёгкий ремонт",
            color: "#16A34A",
          },
        },
        {
          id: secondRepairId,
          complexity: {
            type: "COMPLEX",
            name: "Тяжёлый ремонт",
            color: "#0E7490",
          },
        },
      ],
    })

    await waitFor(() => {
      expect(
        screen.getByTestId("visible-repair-complexities").textContent
      ).toBe("Лёгкий ремонт,Тяжёлый ремонт")
    })
    expect(mocks.listMaintenanceRepairs).toHaveBeenCalledTimes(1)
    expect(mocks.listMaintenanceRepairs).toHaveBeenCalledWith(
      "task-board-token",
      WAREHOUSE_ID,
      { repairIds: [firstRepairId, secondRepairId], page: 0, size: 200 }
    )
  })

  it("chunks more than two hundred visible repair complexity IDs", async () => {
    const repairId = "repair-after-first-page"
    const repairIds = [
      ...Array.from({ length: 200 }, (_, index) => `repair-${index}`),
      repairId,
    ]
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: repairIds.length,
      realEntries: repairIds.length,
      queues: [
        {
          ...board.queues[0]!,
          entries: repairIds.map((id) => ({
            ...taskEntry(id, "Ремонт"),
            source: { type: "MAINTENANCE_REPAIR" as const, sourceId: id },
          })),
        },
      ],
    }
    const repair: RepairComplexityFixture = {
      id: repairId,
      complexity: {
        type: "COMPLEX",
        name: "Тяжёлый ремонт",
        color: "#dc2626",
      },
    }

    renderPage("EDIT", {
      currentBoard,
      maintenancePage: (filters) => {
        const includesLastRepair =
          filters.repairIds?.includes(repairId) ?? false
        return Promise.resolve({
          items: includesLastRepair ? [repair] : [],
          page: 0,
          size: 200,
          totalElements: includesLastRepair ? 1 : 0,
        })
      },
    })

    await waitFor(() => {
      const renderedComplexities = screen
        .getByTestId("visible-repair-complexities")
        .textContent?.split(",")
        .filter(Boolean)

      expect(renderedComplexities).toEqual(["Тяжёлый ремонт"])
    })
    expect(mocks.listMaintenanceRepairs).toHaveBeenNthCalledWith(
      2,
      "task-board-token",
      WAREHOUSE_ID,
      { repairIds: [repairId], page: 0, size: 200 }
    )
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

    expect(await screen.findByText("Открыто связанное задание.")).toBeTruthy()
    expect(
      (await screen.findByTestId("visible-task-external-ids")).textContent
    ).toBe(furnitureTaskId)
  })

  it("hides future subtasks by default and reveals either all futures or one full route", async () => {
    const routeReal = {
      ...taskEntry("route-real", "Внешние работы"),
      id: "entry-route-real",
      taskId: "route-task",
      routeIndex: 0,
      routeLength: 2,
    }
    const secondReal = {
      ...taskEntry("second-real", "Другая бытовка"),
      id: "entry-second-real",
    }
    const routeShadow = {
      ...taskEntry("route-shadow", "Электрика"),
      id: "entry-route-shadow",
      taskId: "route-task",
      entryType: "SHADOW" as const,
      routeIndex: 1,
      routeLength: 2,
      queueKey: "electricity",
      queueId: "00000000-0000-4000-8000-000000000003",
    }
    const unrelatedShadow = {
      ...taskEntry("other-shadow", "Будущая сантехника"),
      id: "entry-other-shadow",
      entryType: "SHADOW" as const,
      routeIndex: 1,
      routeLength: 2,
      queueKey: "electricity",
      queueId: "00000000-0000-4000-8000-000000000003",
    }
    const currentBoard: TaskBoardSnapshotDto = {
      ...board,
      totalEntries: 4,
      realEntries: 2,
      shadowEntries: 2,
      queues: [
        {
          ...board.queues[0]!,
          availableTaskLimit: 1,
          entries: [routeReal, secondReal],
        },
        {
          ...board.queues[0]!,
          key: "electricity",
          label: "Электрика",
          settingsQueueId: "00000000-0000-4000-8000-000000000003",
          settingsCollapsed: true,
          availableTaskLimit: 1,
          entries: [routeShadow, unrelatedShadow],
        },
      ],
    }
    const renderedIds = (testId: string) =>
      screen
        .getAllByTestId(testId)
        .flatMap((element) => element.textContent?.split(",") ?? [])
        .filter(Boolean)

    renderPage("EDIT", { currentBoard })
    await screen.findAllByTestId("visible-task-external-ids")

    const electricityColumn = screen.getAllByTestId(
      "task-board-command-state"
    )[1]!
    await waitFor(() => {
      expect(electricityColumn.dataset.collapsed).toBe("true")
    })

    expect(renderedIds("visible-task-external-ids")).toEqual([
      "route-real",
      "second-real",
    ])
    expect(renderedIds("daily-plan-entry-ids")).toEqual(["entry-route-real"])

    const user = userEvent.setup()
    const futureToggle = screen.getByRole("checkbox", {
      name: "Отобразить будущие подзадачи",
    })
    await user.click(futureToggle)
    expect(renderedIds("visible-task-external-ids")).toEqual([
      "route-real",
      "second-real",
      "route-shadow",
      "other-shadow",
    ])

    await user.click(futureToggle)
    await user.click(
      screen.getByRole("button", {
        name: "Полный путь entry-route-real",
      })
    )

    expect(renderedIds("visible-task-external-ids")).toEqual([
      "route-real",
      "second-real",
      "route-shadow",
    ])
    expect(renderedIds("highlighted-route-entry-ids")).toEqual([
      "entry-route-real",
      "entry-route-shadow",
    ])
    expect(screen.getByText("Полный путь: БЫТ-001.")).toBeTruthy()
    expect(electricityColumn.dataset.collapsed).toBe("false")

    await user.click(
      screen.getByRole("button", {
        name: "Полный путь entry-route-real",
      })
    )

    expect(renderedIds("visible-task-external-ids")).toEqual([
      "route-real",
      "second-real",
    ])
    expect(renderedIds("highlighted-route-entry-ids")).toEqual([])
    expect(screen.queryByText("Полный путь: БЫТ-001.")).toBeNull()
    expect(electricityColumn.dataset.collapsed).toBe("true")
  })

  it("changes availability only for a future route entry", async () => {
    const routeReal = {
      ...taskEntry("route-real", "Внешние работы"),
      taskId: "route-task",
      routeIndex: 0,
      routeLength: 2,
    }
    const routeShadow = {
      ...taskEntry("route-shadow", "Электрика"),
      taskId: "route-task",
      routeIndex: 1,
      routeLength: 2,
      entryType: "SHADOW" as const,
    }
    const currentBoard = {
      ...board,
      totalEntries: 2,
      realEntries: 1,
      shadowEntries: 1,
      queues: [{ ...board.queues[0]!, entries: [routeReal, routeShadow] }],
    }
    renderPage("EDIT", { currentBoard })
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Отобразить будущие подзадачи",
      })
    )
    await user.click(
      screen.getByRole("button", {
        name: `Доступность ${routeShadow.id}`,
      })
    )

    await waitFor(() => {
      expect(mocks.setFutureTaskBoardEntryAvailability).toHaveBeenCalledWith({
        accessToken: "task-board-token",
        entry: routeShadow,
        available: true,
      })
    })
    expect(
      screen.queryByRole("button", {
        name: `Доступность ${routeReal.id}`,
      })
    ).toBeNull()
  })

  it("restores future visibility, collapsed queues and scroll positions per warehouse", async () => {
    const routeReal = {
      ...taskEntry("route-real", "Внешние работы"),
      taskId: "route-task",
      routeIndex: 0,
      routeLength: 2,
    }
    const routeShadow = {
      ...taskEntry("route-shadow", "Электрика"),
      taskId: "route-task",
      routeIndex: 1,
      routeLength: 2,
      entryType: "SHADOW" as const,
    }
    const currentBoard = {
      ...board,
      totalEntries: 2,
      realEntries: 1,
      shadowEntries: 1,
      queues: [{ ...board.queues[0]!, entries: [routeReal, routeShadow] }],
    }
    const firstView = renderPage("EDIT", { currentBoard })
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Отобразить будущие подзадачи",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Переключить очередь repair" })
    )
    await user.click(
      screen.getByRole("button", { name: "Прокрутить очередь repair" })
    )
    const boardScroll = document.querySelector<HTMLElement>(
      '[data-slot="task-board-scroll"]'
    )!
    boardScroll.scrollLeft = 310
    boardScroll.scrollTop = 12
    fireEvent.scroll(boardScroll)

    await waitFor(() => {
      expect(readTaskBoardViewPreferences(WAREHOUSE_ID)).toEqual({
        showFutureSubtasks: true,
        collapsedQueueKeys: ["repair"],
        boardScrollLeft: 310,
        boardScrollTop: 12,
        queueScrollTops: { repair: 275 },
      })
    })
    firstView.unmount()

    renderPage("EDIT", { currentBoard })
    const futureToggle = await screen.findByRole("checkbox", {
      name: "Отобразить будущие подзадачи",
    })
    await waitFor(() =>
      expect(futureToggle.getAttribute("data-state")).toBe("checked")
    )
    expect(
      (await screen.findByTestId("visible-task-external-ids")).textContent
    ).toBe("route-real,route-shadow")
    const commandState = screen.getByTestId("task-board-command-state")
    expect(commandState.dataset.collapsed).toBe("true")
    expect(commandState.dataset.initialScrollTop).toBe("275")
    await waitFor(() => {
      const restoredScroll = document.querySelector<HTMLElement>(
        '[data-slot="task-board-scroll"]'
      )!
      expect(restoredScroll.scrollLeft).toBe(310)
      expect(restoredScroll.scrollTop).toBe(12)
    })
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

  it("sends task-level suspend and restore commands for editable entries", async () => {
    const taken = {
      ...taskEntry("taken-stage", "Замена панели"),
      status: "IN_PROGRESS" as const,
    }
    const suspended = {
      ...taken,
      suspended: true,
      hasProblem: true,
      incomplete: false,
      status: "WAITING" as const,
      taskVersion: 2,
    }
    const restored = { ...suspended, suspended: false, taskVersion: 3 }
    const currentBoard = {
      ...board,
      queues: [{ ...board.queues[0]!, entries: [taken] }],
    }
    renderPage("EDIT", { currentBoard })
    const user = userEvent.setup()
    const suspendedBoard = {
      ...currentBoard,
      queues: [{ ...currentBoard.queues[0]!, entries: [suspended] }],
    }
    const restoredBoard = {
      ...currentBoard,
      queues: [{ ...currentBoard.queues[0]!, entries: [restored] }],
    }
    mocks.suspendTaskBoardTask.mockResolvedValue(suspendedBoard)
    mocks.restoreTaskBoardTask.mockResolvedValue(restoredBoard)

    await user.click(
      await screen.findByRole("button", { name: "Отключить тестовое задание" })
    )
    await waitFor(() =>
      expect(mocks.suspendTaskBoardTask).toHaveBeenCalledWith(
        "task-board-token",
        taken
      )
    )
    await waitFor(() =>
      expect(
        screen.getByTestId("first-visible-task-suspended").textContent
      ).toBe("true")
    )
    expect(
      await screen.findByRole("button", {
        name: "Восстановить тестовое задание",
      })
    ).toBeTruthy()
    await user.click(
      await screen.findByRole("button", {
        name: "Восстановить тестовое задание",
      })
    )
    await user.click(
      await screen.findByRole("button", { name: "Восстановить" })
    )
    await waitFor(() =>
      expect(mocks.restoreTaskBoardTask).toHaveBeenCalledWith(
        "task-board-token",
        { ...suspended, taskVersion: 1 },
        []
      )
    )
    await waitFor(() =>
      expect(
        screen.getByTestId("first-visible-task-suspended").textContent
      ).toBe("false")
    )
  })
})
