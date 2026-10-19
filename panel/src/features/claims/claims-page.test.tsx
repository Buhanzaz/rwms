import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ClaimsPage } from "@/features/claims/claims-page"
import type { CustomerCabinProblemClaim } from "@/features/claims/model/claim"

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  resolve: vi.fn(),
  start: vi.fn(),
  useAuth: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/features/claims/api/claims-api", () => ({
  CUSTOMER_CABIN_PROBLEMS_QUERY_KEY: ["customer-cabin-problems"],
  listCustomerCabinProblems: mocks.list,
  resolveCustomerCabinProblem: mocks.resolve,
  startCustomerCabinProblem: mocks.start,
}))

const CLAIM: CustomerCabinProblemClaim = {
  id: "00000000-0000-4000-8000-000000000001",
  orderId: "00000000-0000-4000-8000-000000000002",
  warehouseId: "00000000-0000-4000-8000-000000000003",
  bookingId: "00000000-0000-4000-8000-000000000004",
  cabinUnitId: "00000000-0000-4000-8000-000000000005",
  orderNumber: "ORD-000012",
  category: "OTHER",
  phase: "BEFORE_ACCEPTANCE",
  description: "На стене обнаружен дефект",
  reportedAt: "2026-09-02T08:00:00Z",
  clientDisplayName: "ООО «СтройМонтаж»",
  clientType: "LEGAL_ENTITY",
  clientPhone: "+79990000000",
  orderContactPhone: "+79990000001",
  deliveryAddress: "Санкт-Петербург, Тестовая улица, 1",
  status: "OPEN",
  resolutionDeadline: "2026-09-05T08:00:00Z",
  version: 2,
  resolutionKind: null,
  resolutionComment: null,
  resolvedAt: null,
  actions: [],
}

function renderClaims() {
  mocks.useAuth.mockReturnValue({ accessToken: "access-token" })
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <ClaimsPage />
    </QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("ClaimsPage", () => {
  it("shows the person-facing claim context and takes an open claim into work using its version", async () => {
    mocks.list.mockResolvedValue([CLAIM])
    mocks.start.mockResolvedValue({ ...CLAIM, status: "IN_PROGRESS" })
    const user = userEvent.setup()

    renderClaims()

    expect(await screen.findByText("ООО «СтройМонтаж»")).toBeTruthy()
    expect(
      screen
        .getByText("ООО «СтройМонтаж»")
        .parentElement?.querySelector('[data-slot="card-description"]')
        ?.textContent
    ).toContain("Юрлицо · +79990000000")
    expect(screen.getByText(/Контакт доставки: \+79990000001/)).toBeTruthy()
    expect(screen.getByText("ORD-000012")).toBeTruthy()
    expect(screen.getByText("Другое")).toBeTruthy()
    expect(screen.getByText("До приёмки")).toBeTruthy()
    expect(screen.queryByText("OTHER")).toBeNull()
    expect(screen.queryByText("BEFORE_ACCEPTANCE")).toBeNull()
    expect(screen.queryByText(CLAIM.id)).toBeNull()

    await user.click(screen.getByRole("button", { name: "Взять в работу" }))

    await waitFor(() => {
      expect(mocks.start).toHaveBeenCalledWith("access-token", CLAIM.id, 2)
    })
  })

  it("records a selected resolution with the current claim version and comment", async () => {
    const inProgress = { ...CLAIM, status: "IN_PROGRESS" as const, version: 7 }
    mocks.list.mockResolvedValue([inProgress])
    mocks.resolve.mockResolvedValue({
      ...inProgress,
      status: "RESOLVED",
      resolutionKind: "DISCOUNT",
      resolutionComment: "Согласована скидка",
      resolvedAt: "2026-09-02T09:00:00Z",
    })
    const user = userEvent.setup()

    renderClaims()

    await screen.findByText("ООО «СтройМонтаж»")
    await user.click(
      screen.getByRole("button", { name: "Зафиксировать решение" })
    )
    await user.type(
      screen.getByLabelText("Комментарий к решению"),
      "Согласована скидка"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить решение" }))

    await waitFor(() => {
      expect(mocks.resolve).toHaveBeenCalledWith("access-token", CLAIM.id, {
        expectedVersion: 7,
        resolutionKind: "DISCOUNT",
        resolutionComment: "Согласована скидка",
      })
    })
  })
})
