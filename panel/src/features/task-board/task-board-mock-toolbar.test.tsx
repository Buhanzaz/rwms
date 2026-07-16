import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { TaskBoardMockToolbar } from "@/features/task-board/task-board-mock-toolbar"

describe("TaskBoardMockToolbar", () => {
  it("shows an unread inbox and marks an opened notification", () => {
    const onMarkRead = vi.fn()
    render(
      <TaskBoardMockToolbar
        workers={[
          {
            id: "worker-1",
            version: 0,
            warehouseId: "warehouse-1",
            displayName: "Алексей Соколов",
            firstName: "Алексей",
            lastName: "Соколов",
            middleName: null,
            active: true,
            comment: null,
            appLogin: null,
            credentialStatus: "NOT_CONFIGURED",
            credentialError: null,
            qualifications: [],
          },
        ]}
        activeWorkerId="worker-1"
        notifications={[
          {
            id: "notification-1",
            version: 0,
            workerId: "worker-1",
            type: "TASK_ASSIGNED",
            message: "Назначено задание «Перемещение».",
            taskId: "task-1",
            createdAt: "2026-07-12T10:00:00.000Z",
            readAt: null,
            deduplicationKey: "assigned:1",
          },
        ]}
        onWorkerChange={vi.fn()}
        onMarkRead={onMarkRead}
        onMarkAllRead={vi.fn()}
      />
    )

    fireEvent.click(
      screen.getByRole("button", {
        name: "Уведомления рабочего, непрочитанных: 1",
      })
    )
    fireEvent.click(screen.getByRole("button", { name: /Назначено задание/ }))

    expect(onMarkRead).toHaveBeenCalledWith(
      expect.objectContaining({ id: "notification-1" })
    )
  })
})
