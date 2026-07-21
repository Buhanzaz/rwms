import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { formatDossierActorLabel } from "@/features/rental-items/dossier/actor/actor-display"
import { DossierActivityRegister } from "@/features/rental-items/dossier/dossier-activity-register"
import type { CabinDossierPage } from "@/features/rental-items/dossier/model/dossier-service"

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "actor-access-token",
    currentUser: { id: "50000000-0000-0000-0000-000000000001" },
  }),
}))

const CABIN_ID = "10000000-0000-0000-0000-000000000001"
const ACTOR_ID = "40000000-0000-0000-0000-000000000001"
const ACTIVITY_ID = "30000000-0000-0000-0000-000000000001"
const fetchMock = vi.fn()

function page(overrides: Partial<CabinDossierPage> = {}): CabinDossierPage {
  return {
    cabinId: CABIN_ID,
    activities: [
      {
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
      },
    ],
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
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

beforeEach(() => {
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
  it("resolves a human actor and preserves technical actor/source refs", async () => {
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
    expect(screen.getByText("CABIN_CREATED")).toBeTruthy()
    expect(screen.getByText(`USER · ${ACTOR_ID}`)).toBeTruthy()
    expect(screen.getByText(`subjectId: ${CABIN_ID}`)).toBeTruthy()
    expect(
      screen.getByText(`asset-service · RENTAL_ITEM · ${CABIN_ID}`)
    ).toBeTruthy()
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

    expect(screen.getByTestId(`technical-actor-${ACTIVITY_ID}`)).toBeTruthy()
  })

  it("formats last, first and optional patronymic before email, login and technical ID fallbacks", () => {
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
    expect(formatDossierActorLabel(actor, undefined)).toBe(
      `Пользователь — ${ACTOR_ID}`
    )
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
