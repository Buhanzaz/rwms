import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { TaskProblemReportsBell } from "@/features/task-board/task-problem-reports-bell"

const { listTaskProblemReports, markTaskProblemReportRead } = vi.hoisted(
  () => ({
    listTaskProblemReports: vi.fn(),
    markTaskProblemReportRead: vi.fn(),
  })
)

vi.mock("@/features/task-board/api/task-problem-reports-api", () => ({
  listTaskProblemReports,
  markTaskProblemReportRead,
  taskProblemReportsQueryKey: (warehouseId: string, userId: string) => [
    "task-board",
    "problem-reports",
    warehouseId,
    userId,
  ],
}))

vi.mock("@/components/media/fullscreen-photo-viewer", () => ({
  FullscreenPhotoViewer: ({
    open,
    loading,
    error,
    onRetry,
  }: {
    open: boolean
    loading: boolean
    error?: unknown
    onRetry?: () => void
  }) =>
    open ? (
      <div>
        {loading ? "Загрузка полного фото" : null}
        {error ? (
          <>
            <p role="alert">Ошибка полного фото</p>
            <button onClick={onRetry}>Повторить загрузку фото</button>
          </>
        ) : null}
      </div>
    ) : null,
}))

const report = {
  reportId: "00000000-0000-4000-8000-000000000001",
  entryId: "00000000-0000-4000-8000-000000000002",
  taskId: "00000000-0000-4000-8000-000000000003",
  routeIndex: 1,
  entryTitle: "Проверить замок",
  workerName: "Иван Петров",
  comment: "Не закрывается.",
  occurredAt: "2026-09-09T10:00:00Z",
  recordedAt: "2026-09-09T10:01:00Z",
  readAt: null,
  attachments: [],
}

function renderBell() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <TaskProblemReportsBell
        accessToken="token"
        warehouseId="warehouse-1"
        userId="user-1"
      />
    </QueryClientProvider>
  )
}

describe("TaskProblemReportsBell", () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it("shows worker details and records the explicit read receipt", async () => {
    listTaskProblemReports.mockResolvedValue({
      reports: [report],
      nextCursor: null,
      unreadCount: 1,
    })
    markTaskProblemReportRead.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderBell()

    await user.click(
      await screen.findByRole("button", { name: /1 непрочитанных/ })
    )
    expect(await screen.findByText("Проверить замок")).not.toBeNull()
    expect(screen.getByText("Не закрывается.")).not.toBeNull()
    expect(screen.getByText(/Иван Петров/)).not.toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Отметить прочитанным" })
    )
    await waitFor(() =>
      expect(markTaskProblemReportRead).toHaveBeenCalledWith(
        "token",
        "warehouse-1",
        report.reportId
      )
    )
    await waitFor(() => expect(listTaskProblemReports).toHaveBeenCalledTimes(2))
  })

  it("explains that reports require a selected warehouse", async () => {
    const client = new QueryClient()
    const user = userEvent.setup()
    render(
      <QueryClientProvider client={client}>
        <TaskProblemReportsBell
          accessToken="token"
          warehouseId={null}
          userId="user-1"
        />
      </QueryClientProvider>
    )
    await user.click(screen.getByRole("button", { name: "Сообщения рабочих" }))
    expect(
      screen.getByText("Выберите склад, чтобы увидеть сообщения.")
    ).not.toBeNull()
    expect(listTaskProblemReports).not.toHaveBeenCalled()
  })

  it("shows a failed read receipt and lets the user retry it", async () => {
    listTaskProblemReports.mockResolvedValue({
      reports: [report],
      nextCursor: null,
      unreadCount: 1,
    })
    markTaskProblemReportRead
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(undefined)
    const user = userEvent.setup()
    renderBell()
    await user.click(
      await screen.findByRole("button", { name: /1 непрочитанных/ })
    )
    await user.click(
      screen.getByRole("button", { name: "Отметить прочитанным" })
    )
    expect(await screen.findByRole("alert")).not.toBeNull()
    await user.click(screen.getByRole("button", { name: "Повторить отметку" }))
    await waitFor(() =>
      expect(markTaskProblemReportRead).toHaveBeenCalledTimes(2)
    )
  })

  it("opens a ready thumbnail and exposes full-photo failure retry", async () => {
    const attached = {
      ...report,
      attachments: [
        {
          evidenceId: "evidence-1",
          state: "READY" as const,
          capturedAt: report.occurredAt,
          recordedAt: report.recordedAt,
          mediaId: "media-1",
          mediaGeneration: 1,
          reviewReason: null,
          contentType: "image/jpeg",
          thumbnailPath: "/api/media/thumbnail",
          readPath: "/api/media/original",
        },
      ],
    }
    listTaskProblemReports.mockResolvedValue({
      reports: [attached],
      nextCursor: null,
      unreadCount: 1,
    })
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response("thumb", {
            status: 200,
            headers: { "Content-Type": "image/jpeg" },
          })
        )
        .mockResolvedValueOnce(new Response(null, { status: 500 }))
        .mockResolvedValueOnce(
          new Response("full", {
            status: 200,
            headers: { "Content-Type": "image/jpeg" },
          })
        )
    )
    Object.defineProperty(URL, "createObjectURL", {
      value: vi.fn(() => "blob:photo"),
      configurable: true,
    })
    Object.defineProperty(URL, "revokeObjectURL", {
      value: vi.fn(),
      configurable: true,
    })
    const user = userEvent.setup()
    renderBell()
    await user.click(
      await screen.findByRole("button", { name: /1 непрочитанных/ })
    )
    await user.click(
      await screen.findByRole("button", { name: "Открыть фото проблемы" })
    )
    expect(await screen.findByRole("alert")).not.toBeNull()
    await user.click(
      screen.getByRole("button", { name: "Повторить загрузку фото" })
    )
    await waitFor(() =>
      expect(screen.queryByText("Ошибка полного фото")).toBeNull()
    )
  })
})
