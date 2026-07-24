import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
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
vi.mock("@/features/task-board/task-board-column", () => ({
  TaskBoardColumn: ({
    dragDisabled,
    actionPending,
    queueActionsDisabled,
    visibleEntries,
  }: {
    dragDisabled: boolean
    actionPending: boolean
    queueActionsDisabled: boolean
    visibleEntries: TaskBoardEntryDto[]
  }) => (
    <div data-testid="task-board-command-state">
      {dragDisabled && actionPending && queueActionsDisabled
        ? "read-only"
        : "editable"}
      <span data-testid="visible-task-external-ids">
        {visibleEntries
          .map((entry) => entry.externalTaskId ?? entry.taskId)
          .join(",")}
      </span>
    </div>
  ),
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const board: TaskBoardSnapshotDto = {
  warehouseId: WAREHOUSE_ID,
  queues: [
    {
      key: "repair",
      label: "Ремонт",
      kind: "REPAIR",
      queueCode: "REPAIR",
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
    queueCode: "REPAIR",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: 1,
    taskId: `task-${externalTaskId}`,
    externalTaskId,
    taskVersion: 1,
    title,
    unitNumber: "БЫТ-001",
    taskStatus: "ACTIVE",
    status: "WAITING",
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: null,
    pausedAt: null,
    activeWorkSeconds: 0,
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
      serviceId: WAREHOUSE_ID,
      version: 0,
      code: "SPB",
      name: "СПБ",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      active: true,
      sortOrder: 0,
    },
  })
  mocks.getTaskBoard.mockResolvedValue(currentBoard)

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
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("task board warehouse access", () => {
  it("keeps VIEW users read-only", async () => {
    renderPage("VIEW")

    const createButton = screen.getByRole("button", {
      name: "Создать задание",
    }) as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
    expect(
      (await screen.findByTestId("task-board-command-state")).textContent
    ).toBe("read-only")
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
      await screen.findByText("Открыто задание, связанное с отгрузкой.")
    ).toBeTruthy()
    expect(
      (await screen.findByTestId("visible-task-external-ids")).textContent
    ).toBe(furnitureTaskId)
  })
})
