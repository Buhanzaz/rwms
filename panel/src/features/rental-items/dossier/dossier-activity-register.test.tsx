import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import type { ReactNode } from "react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { formatDossierActorLabel } from "@/features/rental-items/dossier/actor/actor-display"
import {
  DossierActivityFiltersPanel,
  DossierActivityRegister,
} from "@/features/rental-items/dossier/dossier-activity-register"
import type {
  CabinDossierPage,
  DossierActivity,
} from "@/features/rental-items/dossier/model/dossier-service"

const viewport = vi.hoisted(() => ({ isMobile: false }))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "actor-access-token",
    currentUser: { id: "50000000-0000-0000-0000-000000000001" },
  }),
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({
    owner,
    visibleMediaIds,
    authoritativeReadyReferences,
  }: {
    owner: { ownerId: string } | null
    visibleMediaIds?: readonly string[]
    authoritativeReadyReferences?: readonly {
      mediaId: string
      generation: number
    }[]
  }) => (
    <output data-testid="task-evidence-photo-viewer">
      {owner?.ownerId ?? "no-owner"}|{visibleMediaIds?.join(",") ?? ""}|
      {authoritativeReadyReferences
        ?.map((reference) => `${reference.mediaId}:${reference.generation}`)
        .join(",") ?? ""}
    </output>
  ),
}))

vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
}))

const CABIN_ID = "10000000-0000-0000-0000-000000000001"
const ACTOR_ID = "40000000-0000-0000-0000-000000000001"
const ACTIVITY_ID = "30000000-0000-0000-0000-000000000001"
const TASK_ENTRY_ID = "60000000-0000-0000-0000-000000000001"
const TASK_EVIDENCE_MEDIA_ID = "70000000-0000-0000-0000-000000000001"
const fetchMock = vi.fn()

function activity(overrides: Partial<DossierActivity> = {}): DossierActivity {
  return {
    activityId: ACTIVITY_ID,
    cabinId: CABIN_ID,
    warehouseId: "20000000-0000-0000-0000-000000000001",
    activityCode: "CABIN_CREATED",
    occurredAt: null,
    recordedAt: "2026-07-18T12:00:00Z",
    actorRef: {
      subjectId: ACTOR_ID,
      principalType: "USER",
      profileRevision: null,
    },
    sourceRef: {
      producer: "asset-service",
      aggregateType: "RENTAL_ITEM",
      aggregateId: CABIN_ID,
    },
    media: [],
    taskEvidencePhotos: [],
    ...overrides,
  }
}

function page(overrides: Partial<CabinDossierPage> = {}): CabinDossierPage {
  return {
    cabinId: CABIN_ID,
    activities: [activity()],
    nextCursor: "next-page",
    visibility: "PARTIAL",
    ...overrides,
  }
}

function renderWithQueryClient(children: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  viewport.isMobile = false
  fetchMock.mockResolvedValue(
    new Response(
      JSON.stringify([
        {
          subjectId: ACTOR_ID,
          principalType: "USER",
          globalRole: "WMS_ADMIN",
          username: "ivanov",
          firstName: "Иван",
          lastName: "Иванов",
          email: "ivanov@example.test",
        },
      ]),
      { status: 200, headers: { "Content-Type": "application/json" } }
    )
  )
  vi.stubGlobal("fetch", fetchMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("DossierActivityRegister", () => {
  it("applies the compact period filter without losing its timezone and resets it", async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    render(<DossierActivityFiltersPanel value={{}} onApply={onApply} />)

    expect(screen.queryByLabelText("События с")).toBeNull()
    await user.click(screen.getByRole("button", { name: "Период" }))
    await user.type(
      screen.getByLabelText("События с"),
      "2026-09-05T09:00:00+03:00"
    )
    await user.type(
      screen.getByLabelText("События до"),
      "2026-09-06T09:00:00+03:00"
    )
    await user.keyboard("{Enter}")

    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({
        occurredFrom: "2026-09-05T09:00:00+03:00",
        occurredBefore: "2026-09-06T09:00:00+03:00",
      })
    )
    expect(screen.queryByRole("dialog", { name: "Период истории" })).toBeNull()
    expect(screen.getByRole("button", { name: "Период выбран" })).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Сбросить" }))
    expect(onApply).toHaveBeenLastCalledWith({})
    expect(screen.getByRole("button", { name: "Период" })).toBeTruthy()
  })

  it("keeps activity filters collapsed by default on mobile", () => {
    viewport.isMobile = true
    render(<DossierActivityFiltersPanel value={{}} onApply={vi.fn()} />)

    const toggle = screen.getByRole("button", {
      name: "Показать фильтры истории",
    })
    const filters = document.getElementById("dossier-activity-filters")

    expect(toggle.getAttribute("aria-expanded")).toBe("false")
    expect(filters).not.toBeNull()
    expect(filters?.hasAttribute("hidden")).toBe(true)

    fireEvent.click(toggle)

    expect(
      screen
        .getByRole("button", { name: "Скрыть фильтры истории" })
        .getAttribute("aria-expanded")
    ).toBe("true")
    expect(filters?.hasAttribute("hidden")).toBe(false)
  })

  it("resolves a human actor without exposing technical identifiers", async () => {
    const onLoadMore = vi.fn()
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[page()]}
        error={null}
        isLoading={false}
        hasNextPage
        isFetchingNextPage={false}
        onLoadMore={onLoadMore}
        showTechnicalActorDetails
      />
    )

    expect(screen.getByText("PARTIAL")).toBeTruthy()
    expect(screen.getByText("Загружено: 1")).toBeTruthy()
    expect(screen.getByText("Подтверждено dossier-service")).toBeTruthy()
    expect(screen.getByText("asset-service · RENTAL_ITEM")).toBeTruthy()
    expect(screen.queryByText(new RegExp(ACTOR_ID))).toBeNull()
    expect(screen.queryByText(new RegExp(CABIN_ID))).toBeNull()
    expect(
      await screen.findByText("Администратор WMS — Иванов Иван")
    ).toBeTruthy()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(input).toBe(`/auth/api/users/actor-displays?subjectId=${ACTOR_ID}`)
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer actor-access-token"
    )

    fireEvent.click(screen.getByRole("button", { name: "Загрузить ещё" }))
    expect(onLoadMore).toHaveBeenCalledTimes(1)
  })

  it("renders the canonical repair transfer activity label", () => {
    const transfer = page()
    transfer.activities[0]!.activityCode = "REPAIR_TRANSFERRED"

    renderWithQueryClient(
      <DossierActivityRegister
        pages={[transfer]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
        showTechnicalActorDetails={false}
      />
    )

    expect(screen.getByText("Ремонт передан на другой склад")).toBeTruthy()
  })

  it("hides technical actor details by default and allows a permission-ready show flag", async () => {
    const { rerender } = renderWithQueryClient(
      <DossierActivityRegister
        pages={[page()]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.queryByTestId(`technical-actor-${ACTIVITY_ID}`)).toBeNull()
    await screen.findByText("Администратор WMS — Иванов Иван")

    rerender(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <DossierActivityRegister
          pages={[page()]}
          error={null}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          showTechnicalActorDetails
        />
      </QueryClientProvider>
    )

    fireEvent.click(screen.getByRole("button", { name: /Бытовка создана/ }))
    expect(screen.getByTestId(`technical-actor-${ACTIVITY_ID}`)).toBeTruthy()
  })

  it("renders past groups in green, the latest group in blue, and orders them chronologically", () => {
    const repairId = "60000000-0000-0000-0000-000000000001"
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[
          page({
            activities: [
              activity({ recordedAt: "2026-07-18T12:00:00Z" }),
              activity({
                activityId: "30000000-0000-0000-0000-000000000002",
                activityCode: "REPAIR_CREATED",
                recordedAt: "2026-07-19T12:00:00Z",
                sourceRef: {
                  producer: "maintenance-service",
                  aggregateType: "REPAIR",
                  aggregateId: repairId,
                },
              }),
            ],
          }),
        ]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    const timeline = screen.getByRole("list", {
      name: "Хронология операций бытовки",
    })
    const triggers = within(timeline).getAllByRole("button")
    expect(triggers).toHaveLength(2)
    expect(triggers[0]?.textContent).toContain("Бытовка создана")
    expect(triggers[1]?.textContent).toContain("Ремонт создан")
    expect(
      [...timeline.querySelectorAll("[data-timeline-state]")].map((node) =>
        node.getAttribute("data-timeline-state")
      )
    ).toEqual(["past", "latest"])
    expect(
      timeline
        .querySelector('[data-timeline-state="past"]')
        ?.classList.contains("bg-history-past")
    ).toBe(true)
    expect(
      timeline
        .querySelector('[data-timeline-state="latest"]')
        ?.classList.contains("bg-primary")
    ).toBe(true)
  })

  it("groups ready photos from one canonical folder into one compact operation", () => {
    const folderId = "70000000-0000-0000-0000-000000000001"
    const firstMediaId = "71000000-0000-0000-0000-000000000001"
    const secondMediaId = "71000000-0000-0000-0000-000000000002"
    const findingId = "72000000-0000-0000-0000-000000000001"
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[
          page({
            activities: [
              activity({
                activityId: "73000000-0000-0000-0000-000000000001",
                activityCode: "MEDIA_READY",
                recordedAt: "2026-07-18T12:00:00Z",
                sourceRef: {
                  producer: "media-service",
                  aggregateType: "MEDIA",
                  aggregateId: firstMediaId,
                  secondaryId: CABIN_ID,
                },
                media: [
                  {
                    mediaId: firstMediaId,
                    folderId,
                    findingId,
                    generation: 1,
                    state: "READY",
                  },
                ],
              }),
              activity({
                activityId: "73000000-0000-0000-0000-000000000002",
                activityCode: "MEDIA_READY",
                recordedAt: "2026-07-18T12:01:00Z",
                sourceRef: {
                  producer: "media-service",
                  aggregateType: "MEDIA",
                  aggregateId: secondMediaId,
                  secondaryId: CABIN_ID,
                },
                media: [
                  {
                    mediaId: secondMediaId,
                    folderId,
                    findingId,
                    generation: 1,
                    state: "READY",
                  },
                ],
              }),
            ],
          }),
        ]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText("Добавлены 2 фотографии")).toBeTruthy()
    expect(screen.getByText("Групп: 1")).toBeTruthy()
    const timeline = screen.getByRole("list", {
      name: "Хронология операций бытовки",
    })
    expect(within(timeline).getAllByRole("button")).toHaveLength(1)

    fireEvent.click(
      screen.getByRole("button", { name: /Добавлены 2 фотографии/ })
    )
    expect(screen.getByText("2 события, 2 фото")).toBeTruthy()
    expect(screen.getAllByText("Готово: 1")).toHaveLength(2)
  })

  it("opens task evidence with its task-entry media owner proof", () => {
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[
          page({
            activities: [
              activity({
                activityCode: "MEDIA_TASK_EVIDENCE_ATTACHED",
                sourceRef: {
                  producer: "media-service",
                  aggregateType: "CABIN_PHOTO_LIBRARY",
                  aggregateId: "80000000-0000-0000-0000-000000000001",
                  secondaryId: CABIN_ID,
                },
                taskEvidencePhotos: [
                  {
                    mediaId: TASK_EVIDENCE_MEDIA_ID,
                    generation: 2,
                    taskBoardEntryId: TASK_ENTRY_ID,
                  },
                ],
              }),
            ],
          }),
        ]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    fireEvent.click(
      screen.getByRole("button", {
        name: /Фотография задания добавлена в историю/,
      })
    )
    fireEvent.click(
      screen.getByRole("button", { name: "Открыть фото задания" })
    )

    expect(screen.getByRole("dialog")).toBeTruthy()
    expect(screen.getByText("Фотография задания")).toBeTruthy()
    expect(screen.getByTestId("task-evidence-photo-viewer").textContent).toBe(
      `${TASK_ENTRY_ID}|${TASK_EVIDENCE_MEDIA_ID}|${TASK_EVIDENCE_MEDIA_ID}:2`
    )
  })

  it("groups a repair lifecycle and reveals its details and link on click", () => {
    const repairId = "60000000-0000-0000-0000-000000000001"
    const repairSource = {
      producer: "maintenance-service" as const,
      aggregateType: "REPAIR",
      aggregateId: repairId,
    }
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[
          page({
            activities: [
              activity({
                activityId: "61000000-0000-0000-0000-000000000001",
                activityCode: "REPAIR_CREATED",
                recordedAt: "2026-07-18T12:00:00Z",
                sourceRef: repairSource,
              }),
              activity({
                activityId: "61000000-0000-0000-0000-000000000002",
                activityCode: "REPAIR_PENDING_ACCEPTANCE",
                recordedAt: "2026-07-19T12:00:00Z",
                sourceRef: repairSource,
              }),
            ],
          }),
        ]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText("Групп: 1")).toBeTruthy()
    const repairTrigger = screen.getByRole("button", {
      name: /Ремонт ожидает приёмки/,
    })
    expect(repairTrigger.getAttribute("aria-expanded")).toBe("false")

    fireEvent.click(repairTrigger)

    expect(repairTrigger.getAttribute("aria-expanded")).toBe("true")
    expect(screen.getByText("Ремонт создан")).toBeTruthy()
    expect(screen.getAllByText("Ремонт ожидает приёмки")).toHaveLength(2)
    expect(
      screen.getByRole("link", { name: /Открыть ремонт/ }).getAttribute("href")
    ).toBe(`/repairs?repairId=${repairId}`)
  })

  it("formats last, first and optional patronymic before email and login without an ID fallback", () => {
    const actor = page().activities[0].actorRef!
    const baseDisplay = {
      subjectId: ACTOR_ID,
      principalType: "USER" as const,
      globalRole: "WMS_ADMIN" as const,
      username: "ivanov",
      firstName: "Иван",
      lastName: "Иванов",
      email: "ivanov@example.test",
    }

    expect(
      formatDossierActorLabel(actor, {
        ...baseDisplay,
        middleName: "Иванович",
      })
    ).toBe("Администратор WMS — Иванов Иван Иванович")
    expect(
      formatDossierActorLabel(actor, {
        ...baseDisplay,
        firstName: null,
        lastName: null,
      })
    ).toBe("Администратор WMS — ivanov@example.test")
    expect(
      formatDossierActorLabel(actor, {
        ...baseDisplay,
        firstName: null,
        lastName: null,
        email: null,
      })
    ).toBe("Администратор WMS — ivanov")
    expect(formatDossierActorLabel(actor, undefined)).toBe("Пользователь")
    expect(
      formatDossierActorLabel(actor, {
        ...baseDisplay,
        globalRole: "CUSTOMER",
      })
    ).toBe("Клиент — Иванов Иван")
  })

  it("keeps PARTIAL visible for an empty filtered page", () => {
    renderWithQueryClient(
      <DossierActivityRegister
        pages={[page({ activities: [], nextCursor: null })]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText("PARTIAL")).toBeTruthy()
    expect(screen.getByText("Загружено: 0")).toBeTruthy()
    expect(screen.getByText("Событий нет")).toBeTruthy()
  })

  it.each([
    [404, "Досье ещё не сформировано"],
    [403, "Нет доступа к досье"],
  ])("renders HTTP %s without fallback rows", (status, title) => {
    renderWithQueryClient(
      <DossierActivityRegister
        pages={undefined}
        error={new ApiError("upstream", status)}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText(title)).toBeTruthy()
    expect(screen.queryByRole("table")).toBeNull()
  })
})
