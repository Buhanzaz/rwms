import { DndContext } from "@dnd-kit/core"
import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { createMockTaskBoardProjection } from "@/features/task-board/domain/task-board-domain"
import {
  SPB_WAREHOUSE_ID,
  taskBoardMockClient,
} from "@/features/task-board/mock"
import { TaskBoardCard } from "@/features/task-board/task-board-card"

describe("TaskBoardCard runtime pause actions", () => {
  beforeEach(() => taskBoardMockClient.resetForTests())

  it("shows pause reasons and hides manual resume while a crew is returning", async () => {
    const snapshot = await taskBoardMockClient.getSnapshot(SPB_WAREHOUSE_ID)
    const runtimeTask = snapshot.tasks.find(
      (task) => task.status === "IN_PROGRESS"
    )!
    runtimeTask.status = "RETURNING"
    runtimeTask.pauseReasons = [
      {
        id: "interruption-1",
        type: "INTERRUPTION",
        sourceId: "interruption-1",
        label: "Возвращение после перемещения",
        createdAt: snapshot.now,
      },
    ]
    const assignment = snapshot.assignments.find(
      (candidate) => candidate.id === runtimeTask.assignmentId
    )!
    snapshot.interruptions.push({
      id: "interruption-1",
      version: 0,
      interruptedTaskId: runtimeTask.id,
      interruptingTaskId: "movement-1",
      workerGroupId: assignment.workerGroupId,
      state: "RETURNING",
      interruptedAt: snapshot.now,
      returnDeadline: new Date(
        Date.parse(snapshot.now) + 180_000
      ).toISOString(),
      resumedAt: null,
    })
    const entry = createMockTaskBoardProjection(snapshot)
      .queues.flatMap((queue) => queue.entries)
      .find((candidate) => candidate.runtimeTask?.id === runtimeTask.id)!

    render(
      <DndContext>
        <TaskBoardCard
          entry={entry}
          now={Date.parse(snapshot.now)}
          mobile={false}
          dragDisabled
          actionPending={false}
          onDetails={vi.fn()}
          onPause={vi.fn()}
          onResume={vi.fn()}
          onConfirmReturn={vi.fn()}
        />
      </DndContext>
    )

    expect(screen.getByText("Возвращение после перемещения")).not.toBeNull()
    expect(screen.queryByRole("button", { name: "Продолжить" })).toBeNull()
    expect(
      screen.getByRole("button", { name: "Бригада вернулась" })
    ).not.toBeNull()
  })
})
