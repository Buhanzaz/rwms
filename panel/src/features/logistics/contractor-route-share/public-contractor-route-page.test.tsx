import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  PublicContractorRouteEntry,
  PublicContractorRouteShare,
  PublicContractorRouteTask,
} from "@/features/logistics/contractor-route-share/contractor-route-share-api"
import { ApiError } from "@/lib/api-client"

const api = vi.hoisted(() => ({
  action: vi.fn(),
  get: vi.fn(),
  upload: vi.fn(),
}))

vi.mock(
  "@/features/logistics/contractor-route-share/contractor-route-share-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/logistics/contractor-route-share/contractor-route-share-api")
      >()
    return {
      ...original,
      applyPublicContractorRouteAction: api.action,
      getPublicContractorRouteShare: api.get,
      uploadPublicContractorEvidence: api.upload,
    }
  }
)

import { PublicContractorRoutePage } from "@/features/logistics/contractor-route-share/public-contractor-route-page"

const waitingEntry: PublicContractorRouteEntry = {
  entryId: "30000000-0000-0000-0000-000000000001",
  version: 4,
  routeIndex: 0,
  routeStepIndex: 0,
  routeStepCount: 1,
  queueName: "Отгрузка",
  taskText: "Загрузить бытовку №172",
  status: "WAITING",
  plannedDurationMinutes: 20,
  works: [
    {
      id: "work-1",
      name: "Проверить комплектность",
      quantity: 1,
      unit: "шт.",
      durationMinutes: 10,
      comment: "Сверить две кровати",
      sourceMediaIds: [],
    },
  ],
  materials: [{ id: "material-1", name: "Кровать", quantity: 2, unit: "шт." }],
  comments: [
    {
      id: "comment-1",
      text: "Позвонить перед приездом",
      authorDisplayName: "Логист",
      createdAt: "2026-09-01T07:00:00Z",
    },
  ],
  sourceMedia: [],
  resultPhotoMinCount: 1,
  evidence: [],
  completionAllowed: false,
}

const routeTask: PublicContractorRouteTask = {
  externalTaskId: "20000000-0000-0000-0000-000000000001",
  taskId: "21000000-0000-0000-0000-000000000001",
  taskVersion: 8,
  title: "Доставка клиенту",
  description: "Передать бытовку заказчику",
  unitNumber: "172",
  scheduledDate: "2026-09-01",
  deadlineAt: "2026-09-01T14:00:00Z",
  priority: 2,
  status: "ACTIVE",
  address: "Великий Новгород, Большая Санкт-Петербургская, 1",
  latitude: 58.5215,
  longitude: 31.2755,
  contactPhone: "+7 900 000-00-00",
  logisticsComment: "Въезд с северной стороны",
  cargo: [
    {
      kind: "MATERIAL",
      name: "Бытовка BK2",
      quantity: 1,
      unit: "шт.",
      comment: "ЛДСП, линолеум",
    },
  ],
  sourceMedia: [
    {
      mediaId: "70000000-0000-0000-0000-000000000001",
      generation: 3,
      contentType: "image/webp",
      capturedAt: "2026-08-31T10:00:00Z",
      recordedAt: "2026-08-31T10:01:00Z",
      contentPath: "/api/logistics/public/route/source-large",
      thumbnailPath: "/api/logistics/public/route/source-small",
    },
  ],
  route: [waitingEntry],
}

const routeShare: PublicContractorRouteShare = {
  id: "10000000-0000-0000-0000-000000000001",
  expiresAt: "2026-09-02T18:00:00Z",
  tasks: [routeTask],
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <MemoryRouter initialEntries={["/contractor-routes/route-token"]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/contractor-routes/:token"
            element={<PublicContractorRoutePage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  api.get.mockResolvedValue(routeShare)
  vi.stubGlobal("crypto", {
    randomUUID: vi.fn().mockReturnValue("90000000-0000-0000-0000-000000000001"),
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe("public contractor route page", () => {
  it("shows the exact task, cargo, contact, media and ordered work", async () => {
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Задания на рейс" })
    ).toBeTruthy()
    expect(api.get).toHaveBeenCalledWith("route-token")
    expect(screen.getByText("Доставка клиенту")).toBeTruthy()
    expect(screen.getByText("Бытовка №172")).toBeTruthy()
    expect(screen.getByText("Бытовка BK2")).toBeTruthy()
    expect(screen.getByText("Загрузить бытовку №172")).toBeTruthy()
    expect(screen.getByText(/Проверить комплектность/)).toBeTruthy()
    expect(screen.getByText("Позвонить перед приездом")).toBeTruthy()
    expect(
      screen
        .getByRole("link", { name: "+7 900 000-00-00" })
        .getAttribute("href")
    ).toBe("tel:+7 900 000-00-00")
    expect(
      screen.getByRole("link", { name: "Открыть маршрут" }).getAttribute("href")
    ).toContain("destination=58.5215%2C31.2755")
    expect(screen.getByRole("img").getAttribute("src")).toBe(
      "/api/logistics/public/route/source-small"
    )
    expect(screen.getByRole("main").className).toContain("overflow-y-auto")
    expect(screen.getByRole("main").className).toContain("h-svh")
  })

  it("starts an exact route entry with its current version and stable command id", async () => {
    const user = userEvent.setup()
    api.action.mockResolvedValue({
      currentVersion: 5,
      task: {
        ...routeTask,
        route: [{ ...waitingEntry, version: 5, status: "IN_PROGRESS" }],
      },
    })
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Начать этап" }))

    await waitFor(() =>
      expect(api.action).toHaveBeenCalledWith({
        token: "route-token",
        externalTaskId: routeTask.externalTaskId,
        entryId: waitingEntry.entryId,
        expectedVersion: 4,
        action: "START",
        evidenceId: null,
        idempotencyKey: "90000000-0000-0000-0000-000000000001",
      })
    )
    expect(await screen.findByText("В работе")).toBeTruthy()
  })

  it("uses a ready result photo to complete an in-progress entry", async () => {
    const user = userEvent.setup()
    const inProgressEntry: PublicContractorRouteEntry = {
      ...waitingEntry,
      version: 9,
      status: "IN_PROGRESS",
      completionAllowed: true,
      evidence: [
        {
          evidenceId: "50000000-0000-0000-0000-000000000001",
          version: 2,
          capturedAt: "2026-09-01T10:00:00Z",
          recordedAt: "2026-09-01T10:01:00Z",
          state: "READY",
          mediaId: "60000000-0000-0000-0000-000000000001",
          mediaGeneration: 1,
          contentType: "image/jpeg",
          contentPath: "/api/logistics/public/route/result-large",
          thumbnailPath: "/api/logistics/public/route/result-small",
        },
      ],
    }
    api.get.mockResolvedValue({
      ...routeShare,
      tasks: [{ ...routeTask, route: [inProgressEntry] }],
    })
    api.action.mockResolvedValue({
      currentVersion: 10,
      task: {
        ...routeTask,
        status: "DONE",
        route: [{ ...inProgressEntry, version: 10, status: "DONE" }],
      },
    })
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Завершить этап" })
    )

    await waitFor(() =>
      expect(api.action).toHaveBeenCalledWith(
        expect.objectContaining({
          action: "COMPLETE",
          expectedVersion: 9,
          evidenceId: "50000000-0000-0000-0000-000000000001",
        })
      )
    )
    expect(await screen.findByText("Этап подтверждён")).toBeTruthy()
  })

  it("uploads a selected result photo only for an in-progress entry", async () => {
    const user = userEvent.setup()
    api.get.mockResolvedValue({
      ...routeShare,
      tasks: [
        {
          ...routeTask,
          route: [
            {
              ...waitingEntry,
              status: "IN_PROGRESS",
              completionAllowed: false,
            },
          ],
        },
      ],
    })
    api.upload.mockResolvedValue({ state: "UPLOADING" })
    renderPage()
    const file = new File(["photo"], "result.jpg", {
      type: "image/jpeg",
      lastModified: Date.now() - 1_000,
    })

    await user.upload(await screen.findByLabelText("Добавить фото"), file)

    await waitFor(() =>
      expect(api.upload).toHaveBeenCalledWith(
        expect.objectContaining({
          token: "route-token",
          externalTaskId: routeTask.externalTaskId,
          entryId: waitingEntry.entryId,
          evidenceId: "90000000-0000-0000-0000-000000000001",
          file,
        })
      )
    )
  })

  it("keeps the same evidence id when an ambiguous upload is retried", async () => {
    const user = userEvent.setup()
    api.get.mockResolvedValue({
      ...routeShare,
      tasks: [
        {
          ...routeTask,
          route: [
            {
              ...waitingEntry,
              status: "IN_PROGRESS",
              completionAllowed: false,
            },
          ],
        },
      ],
    })
    api.upload.mockRejectedValue(new ApiError("network timeout", 0))
    renderPage()
    const file = new File(["photo"], "result.jpg", {
      type: "image/jpeg",
      lastModified: Date.now() - 1_000,
    })
    const input = await screen.findByLabelText("Добавить фото")

    await user.upload(input, file)
    await screen.findByText(
      "Не удалось выполнить действие. Проверьте соединение и повторите попытку."
    )
    await user.upload(input, file)

    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(2))
    const firstEvidenceId = api.upload.mock.calls[0]?.[0].evidenceId
    expect(api.upload.mock.calls[1]?.[0].evidenceId).toBe(firstEvidenceId)
  })

  it("does not let a contractor skip an unfinished route entry", async () => {
    const secondEntry: PublicContractorRouteEntry = {
      ...waitingEntry,
      entryId: "30000000-0000-0000-0000-000000000002",
      routeStepIndex: 1,
      routeStepCount: 2,
      taskText: "Выгрузить бытовку №172",
    }
    api.get.mockResolvedValue({
      ...routeShare,
      tasks: [
        {
          ...routeTask,
          route: [{ ...waitingEntry, routeStepCount: 2 }, secondEntry],
        },
      ],
    })
    renderPage()

    const buttons = await screen.findAllByRole("button", {
      name: "Начать этап",
    })
    expect(buttons).toHaveLength(2)
    expect((buttons[0] as HTMLButtonElement).disabled).toBe(false)
    expect((buttons[1] as HTMLButtonElement).disabled).toBe(true)
    expect(buttons[1]?.getAttribute("title")).toBe(
      "Сначала завершите предыдущий этап маршрута"
    )
  })

  it("does not let a contractor start a later task before the whole route reaches it", async () => {
    const secondTask: PublicContractorRouteTask = {
      ...routeTask,
      externalTaskId: "20000000-0000-0000-0000-000000000002",
      taskId: "21000000-0000-0000-0000-000000000002",
      title: "Вывоз от клиента",
      route: [
        {
          ...waitingEntry,
          entryId: "30000000-0000-0000-0000-000000000002",
          taskText: "Забрать бытовку №510",
        },
      ],
    }
    api.get.mockResolvedValue({
      ...routeShare,
      tasks: [routeTask, secondTask],
    })
    renderPage()

    const buttons = await screen.findAllByRole("button", {
      name: "Начать этап",
    })
    expect(buttons).toHaveLength(2)
    expect((buttons[0] as HTMLButtonElement).disabled).toBe(false)
    expect((buttons[1] as HTMLButtonElement).disabled).toBe(true)
    expect(buttons[1]?.getAttribute("title")).toBe(
      "Сначала завершите предыдущий этап маршрута"
    )
  })

  it("shows a safe expired-link state without upstream diagnostics", async () => {
    api.get.mockRejectedValue(
      new ApiError("private object key and upstream stack", 404)
    )
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Маршрут не найден" })
    ).toBeTruthy()
    expect(screen.getByText(/Ссылка истекла/)).toBeTruthy()
    expect(screen.queryByText(/private|upstream|stack/i)).toBeNull()
  })
})
