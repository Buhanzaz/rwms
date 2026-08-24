import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"

const dnd = vi.hoisted(() => ({
  onDragEnd: null as
    | null
    | ((event: {
        active: { id: string }
        over: { id: string } | null
      }) => void),
}))

vi.mock("@dnd-kit/core", async () => {
  const actual =
    await vi.importActual<typeof import("@dnd-kit/core")>("@dnd-kit/core")
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
    }: {
      children: ReactNode
      onDragEnd: typeof dnd.onDragEnd
    }) => {
      dnd.onDragEnd = onDragEnd
      return children
    },
    useSensor: () => ({}),
    useSensors: () => [],
  }
})

vi.mock("@dnd-kit/sortable", async () => {
  const actual =
    await vi.importActual<typeof import("@dnd-kit/sortable")>(
      "@dnd-kit/sortable"
    )
  return {
    ...actual,
    SortableContext: ({ children }: { children: ReactNode }) => children,
    useSortable: () => ({
      attributes: {},
      isDragging: false,
      listeners: {},
      setNodeRef: vi.fn(),
      transform: null,
      transition: null,
    }),
  }
})

afterEach(cleanup)

function entry(id: string, status: TaskBoardEntryDto["status"]) {
  return {
    id,
    version: 1,
    warehouseId: "warehouse-1",
    queueKey: "queue-1",
    queueId: "queue-1",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: id === "first" ? 0 : 1,
    taskId: `task-${id}`,
    externalTaskId: null,
    source: null,
    taskVersion: 1,
    title: `Task ${id}`,
    unitNumber: id,
    taskStatus: "ACTIVE",
    scheduledDate: "2026-08-21",
    priority: 1,
    pinned: false,
    status,
    taskText: null,
    plannedDurationMinutes: null,
    activeStartedAt: status === "IN_PROGRESS" ? "2026-08-21T08:00:00Z" : null,
    pausedAt: null,
    activeWorkSeconds: 0,
    timerSnapshot: null,
    assignments: [],
    detailsHref: null,
  } satisfies TaskBoardEntryDto
}

function queue(entries: TaskBoardEntryDto[]) {
  return {
    key: "queue-1",
    version: 3,
    label: "Электрика",
    kind: "REPAIR",
    settingsQueueId: "queue-1",
    settingsCollapsed: false,
    workerFeedEnabled: true,
    availableTaskLimit: 6,
    entries,
  } satisfies TaskBoardQueueDto
}

function renderColumn(params: {
  entries: TaskBoardEntryDto[]
  visibleEntries: TaskBoardEntryDto[]
  onTake?: (entry: TaskBoardEntryDto) => void
  onComplete?: (entry: TaskBoardEntryDto) => void
  onUpdateWorkerPlan?: (
    queue: TaskBoardQueueDto,
    workerFeedEnabled: boolean,
    availableTaskLimit: number
  ) => void
  onReorder?: (entry: TaskBoardEntryDto, targetIndex: number) => void
  highlightedTaskId?: string | null
  canManage?: boolean
}) {
  const renderTaskBoardColumn = (
    highlightedTaskId = params.highlightedTaskId ?? null
  ) => (
    <TaskBoardColumn
      queue={queue(params.entries)}
      visibleEntries={params.visibleEntries}
      now={Date.parse("2026-08-21T09:00:00Z")}
      mobile={false}
      canEdit
      canManage={params.canManage ?? true}
      collapsed={false}
      actionPending={false}
      configPending={false}
      queueActionsDisabled={false}
      reorderDisabled={false}
      dailyPlanEntryIds={
        new Set(params.visibleEntries.slice(0, 1).map((item) => item.id))
      }
      highlightedTaskId={highlightedTaskId}
      onToggleCollapsed={vi.fn()}
      onUpdateWorkerPlan={params.onUpdateWorkerPlan ?? vi.fn()}
      onReorder={params.onReorder ?? vi.fn()}
      onDetails={vi.fn()}
      onEdit={vi.fn()}
      onTake={params.onTake ?? vi.fn()}
      onPause={vi.fn()}
      onResume={vi.fn()}
      onPin={vi.fn()}
      onShowFullRoute={vi.fn()}
      isEntryCollapsed={() => false}
      onToggleEntryCollapsed={vi.fn()}
      onComplete={params.onComplete ?? vi.fn()}
      palette={null}
      repairComplexitiesByRepairId={new Map()}
    />
  )
  const view = render(renderTaskBoardColumn())
  return {
    ...view,
    rerenderHighlightedTaskId: (highlightedTaskId: string | null) =>
      view.rerender(renderTaskBoardColumn(highlightedTaskId)),
  }
}

describe("TaskBoardColumn visible command targets", () => {
  it("stacks cards vertically inside a fixed desktop queue column", () => {
    const first = entry("first", "WAITING")
    const second = entry("second", "WAITING")
    renderColumn({
      entries: [first, second],
      visibleEntries: [first, second],
    })

    const section = screen.getByRole("region", { name: "Очередь Электрика" })
    expect(section.className).toContain("h-full")
    expect(section.className).toContain("w-80")
    expect(section.querySelector("header")?.className).toContain("h-32")

    const cardStack = section.lastElementChild
    const scrollBody = section.querySelector(
      '[data-slot="task-board-column-scroll-body"]'
    )
    expect(scrollBody?.className).toContain("flex-col")
    expect(scrollBody?.className).toContain("overflow-y-auto")
    expect(scrollBody?.className).not.toContain("overflow-x-auto")
    expect(
      section.querySelector('[data-slot="task-board-column-config"]')?.className
    ).toContain("whitespace-nowrap")
    expect(screen.getByText("План на день: 6")).toBeTruthy()
    expect(screen.getByText("План на день")).toBeTruthy()
    expect(cardStack).toBeTruthy()
  })

  it("starts the visible waiting card and keeps the same action on its card", async () => {
    const first = entry("first", "WAITING")
    const focused = entry("focused", "WAITING")
    const onTake = vi.fn()
    renderColumn({
      entries: [first, focused],
      visibleEntries: [focused],
      onTake,
    })

    const user = userEvent.setup()
    await user.click(screen.getByRole("button", { name: "Начать" }))
    await user.click(screen.getByRole("button", { name: "Взять в работу" }))

    expect(onTake).toHaveBeenNthCalledWith(1, focused)
    expect(onTake).toHaveBeenNthCalledWith(2, focused)
  })

  it("completes only the visible active card", async () => {
    const first = entry("first", "IN_PROGRESS")
    const focused = entry("focused", "IN_PROGRESS")
    const onComplete = vi.fn()
    renderColumn({
      entries: [first, focused],
      visibleEntries: [focused],
      onComplete,
    })

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Завершить" }))

    expect(onComplete).toHaveBeenCalledWith(focused)
  })

  it("reorders only between waiting real cards in the same column", () => {
    const first = entry("first", "WAITING")
    const second = entry("second", "WAITING")
    const onReorder = vi.fn()
    renderColumn({
      entries: [first, second],
      visibleEntries: [first, second],
      onReorder,
    })

    dnd.onDragEnd?.({ active: { id: "first" }, over: { id: "second" } })

    expect(onReorder).toHaveBeenCalledWith(first, 1)
  })

  it("changes the WorkerApp task limit and queue visibility only with MANAGE access", async () => {
    const first = entry("first", "WAITING")
    const onUpdateWorkerPlan = vi.fn()
    renderColumn({
      entries: [first],
      visibleEntries: [first],
      onUpdateWorkerPlan,
    })

    const user = userEvent.setup()
    await user.click(
      screen.getByRole("checkbox", {
        name: "Показывать очередь Электрика в WorkerApp",
      })
    )
    expect(onUpdateWorkerPlan).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ key: "queue-1", version: 3 }),
      false,
      6
    )

    await user.click(
      screen.getByRole("button", {
        name: "Изменить план на день для очереди Электрика",
      })
    )
    const limit = screen.getByRole("spinbutton", {
      name: "Количество задач в WorkerApp",
    })
    await user.clear(limit)
    await user.type(limit, "3")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(onUpdateWorkerPlan).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ key: "queue-1", version: 3 }),
      true,
      3
    )
  })

  it("keeps WorkerApp controls disabled without MANAGE access", () => {
    const first = entry("first", "WAITING")
    renderColumn({
      entries: [first],
      visibleEntries: [first],
      canManage: false,
    })

    const planButton = screen.getByRole("button", {
      name: "Изменить план на день для очереди Электрика",
    }) as HTMLButtonElement
    const workerToggle = screen.getByRole("checkbox", {
      name: "Показывать очередь Электрика в WorkerApp",
    }) as HTMLButtonElement
    expect(planButton.disabled).toBe(true)
    expect(workerToggle.disabled).toBe(true)
  })

  it("scrolls its own body to the highlighted route without reordering cards", () => {
    const scrollTo = vi.fn()
    Object.defineProperty(HTMLElement.prototype, "scrollTo", {
      configurable: true,
      value: scrollTo,
    })
    const first = entry("first", "WAITING")
    const second = entry("second", "WAITING")

    const { rerenderHighlightedTaskId } = renderColumn({
      entries: [first, second],
      visibleEntries: [first, second],
      highlightedTaskId: second.taskId,
    })

    expect(scrollTo).toHaveBeenCalledWith(
      expect.objectContaining({ behavior: "smooth" })
    )
    expect(
      Array.from(document.querySelectorAll("[data-entry-id]")).map(
        (element) => (element as HTMLElement).dataset.entryId
      )
    ).toEqual(["first", "second"])

    rerenderHighlightedTaskId(null)
    expect(scrollTo).toHaveBeenLastCalledWith(
      expect.objectContaining({ top: 0, behavior: "smooth" })
    )
    delete (HTMLElement.prototype as { scrollTo?: unknown }).scrollTo
  })
})
