import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"

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
    label: "Электрика",
    kind: "REPAIR",
    settingsQueueId: "queue-1",
    settingsCollapsed: false,
    availableTaskLimit: 6,
    entries,
  } satisfies TaskBoardQueueDto
}

function renderColumn(params: {
  entries: TaskBoardEntryDto[]
  visibleEntries: TaskBoardEntryDto[]
  onTake?: (entry: TaskBoardEntryDto) => void
  onComplete?: (entry: TaskBoardEntryDto) => void
}) {
  render(
    <TaskBoardColumn
      queue={queue(params.entries)}
      visibleEntries={params.visibleEntries}
      now={Date.parse("2026-08-21T09:00:00Z")}
      mobile={false}
      canEdit
      collapsed={false}
      actionPending={false}
      queueActionsDisabled={false}
      onToggleCollapsed={vi.fn()}
      onDetails={vi.fn()}
      onEdit={vi.fn()}
      onTake={params.onTake ?? vi.fn()}
      onPause={vi.fn()}
      onResume={vi.fn()}
      onPin={vi.fn()}
      isEntryCollapsed={() => false}
      onToggleEntryCollapsed={vi.fn()}
      onComplete={params.onComplete ?? vi.fn()}
      palette={null}
      repairComplexitiesByRepairId={new Map()}
    />
  )
}

describe("TaskBoardColumn visible command targets", () => {
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
})
