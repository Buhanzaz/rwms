import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const flow = vi.hoisted(() => ({
  conflictAfterPublish: false,
  expired: false,
  getHold: vi.fn(),
  create: vi.fn(),
  publish: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: { id: "manager-1", rentalAccess: true },
    status: "authenticated",
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
    selectedWarehouse: {
      id: "11111111-1111-4111-8111-111111111111",
      name: "СПб",
    },
  }),
}))

vi.mock("@/features/assistant/components/manager-booking-alert-dialog", () => ({
  ManagerBookingAlertDialog: () => null,
}))

vi.mock("@/features/assistant/api/assistant-api", () => ({
  ASSISTANT_QUERY_KEY: ["assistant-conversations"],
  createAssistantConversation: flow.create,
}))

vi.mock("@/features/assistant/api/rental-presentations-api", () => ({
  publishClientPresentation: flow.publish,
}))

vi.mock("@/features/booking/api/manual-booking-drafts-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/manual-booking-drafts-api")
  >("@/features/booking/api/manual-booking-drafts-api")
  return { ...actual, getManualBookingDraftHold: flow.getHold }
})

vi.mock("@/features/orders/components/order-client-chooser", () => ({
  OrderClientChooser: ({
    onChange,
  }: {
    onChange: (choice: unknown) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onChange({
          kind: "existing",
          client: {
            id: "99999999-9999-4999-8999-999999999999",
            displayName: "ООО Клиент",
          },
        })
      }
    >
      Выбрать клиента
    </button>
  ),
}))

vi.mock("@/features/booking/booking-cabin-browser", () => ({
  BookingCabinBrowser: ({
    items,
    selectedIds,
    onToggle,
    actions,
    footer,
  }: {
    items: RentalItemDto[]
    selectedIds: ReadonlySet<string>
    onToggle: (item: RentalItemDto) => void
    actions: ReactNode
    footer: ReactNode
  }) => (
    <div>
      <span>Показано бытовок: {items.length}</span>
      <span>Финально выбрано: {selectedIds.size}</span>
      {items[0] ? (
        <button type="button" onClick={() => onToggle(items[0])}>
          Изменить первую
        </button>
      ) : null}
      {actions}
      {footer}
    </div>
  ),
}))

import { BookingContinuePage } from "@/features/booking/booking-continue-page"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { BookingSelectionProvider } from "@/features/booking/booking-selection-provider"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

function cabin(index: number): RentalItemDto {
  return {
    id: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: `БЫТ-${String(index).padStart(3, "0")}`,
    rentalTypeId: "33333333-3333-4333-8333-333333333333",
    dimensionId: "44444444-4444-4444-8444-444444444444",
    finishingId: "55555555-5555-4555-8555-555555555555",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Новая",
    characteristics: [],
    linoleum: true,
    status: "FREE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

function SeedSelection({ items }: { items: RentalItemDto[] }) {
  const selection = useBookingSelection()
  const navigate = useNavigate()
  return (
    <button
      type="button"
      onClick={() => {
        items.forEach(selection.toggleChecked)
        selection.addCheckedToStaged()
        selection.setActiveHold({
          draftId: selection.draftId,
          warehouseId: WAREHOUSE_ID,
          expiresAt: flow.expired
            ? "2020-08-04T12:00:00Z"
            : "2099-08-04T12:00:00Z",
          rentalItemIds: items.map((item) => item.id),
        })
        navigate("/booking/continue")
      }}
    >
      Подготовить {items.length}
    </button>
  )
}

function renderFlow(items: RentalItemDto[]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <MemoryRouter initialEntries={["/seed"]}>
      <QueryClientProvider client={queryClient}>
        <BookingSelectionProvider>
          <Routes>
            <Route path="/seed" element={<SeedSelection items={items} />} />
            <Route path="/booking/continue" element={<BookingContinuePage />} />
          </Routes>
        </BookingSelectionProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function configureHold(items: RentalItemDto[]) {
  flow.getHold.mockImplementation(async (params) => ({
    draftId: params.draftId,
    warehouseId: params.warehouseId,
    expiresAt: flow.expired ? "2020-08-04T12:00:00Z" : "2099-08-04T12:00:00Z",
    rentalItemIds: flow.conflictAfterPublish
      ? items.slice(1).map((item) => item.id)
      : items.map((item) => item.id),
  }))
}

afterEach(() => {
  cleanup()
  flow.conflictAfterPublish = false
  flow.expired = false
  vi.clearAllMocks()
})

describe("BookingContinuePage", () => {
  it("publishes only the final checked cabins through the owned manual draft", async () => {
    const user = userEvent.setup()
    const items = Array.from({ length: 31 }, (_, index) => cabin(index + 1))
    configureHold(items)
    flow.create.mockResolvedValue({
      conversation: { id: "conversation-1" },
      inquiry: {
        id: "88888888-8888-4888-8888-888888888888",
        status: "OPEN",
      },
      client: { id: "client-1" },
    })
    flow.publish.mockResolvedValue({
      id: "77777777-7777-4777-8777-777777777777",
      revision: 1,
      state: "ACTIVE",
      expiresAt: "2099-08-04T12:00:00Z",
      viewUntil: "2099-08-05T12:00:00Z",
      publicPath: "/offer/public-token",
      bookedOrderId: null,
      groups: [],
    })
    renderFlow(items)

    await user.click(screen.getByRole("button", { name: "Подготовить 31" }))
    expect(await screen.findByText("Показано бытовок: 31")).toBeTruthy()
    expect(screen.getByText(/Резерв действует до/)).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Изменить первую" }))
    expect(screen.getByText("Финально выбрано: 30")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Выбрать клиента" }))
    await user.click(
      screen.getByRole("button", {
        name: "Создать представление для клиента",
      })
    )

    expect(
      await screen.findByRole("heading", { name: "Представление готово" })
    ).toBeTruthy()
    expect(flow.create.mock.invocationCallOrder[0]).toBeLessThan(
      flow.publish.mock.invocationCallOrder[0]
    )
    const publishInput = flow.publish.mock.calls[0][0]
    expect(publishInput.manualBookingDraftId).toMatch(/^[0-9a-f-]{36}$/)
    expect(
      publishInput.groups.map(
        (group: { rentalItemIds: string[] }) => group.rentalItemIds.length
      )
    ).toEqual([30])
    expect(
      publishInput.groups.flatMap(
        (group: { rentalItemIds: string[] }) => group.rentalItemIds
      )
    ).not.toContain(items[0].id)
  })

  it("rechecks its own hold on conflict and removes a lost staged cabin", async () => {
    const user = userEvent.setup()
    const items = [cabin(1), cabin(2)]
    configureHold(items)
    flow.create.mockResolvedValue({
      conversation: { id: "conversation-1" },
      inquiry: {
        id: "88888888-8888-4888-8888-888888888888",
        status: "OPEN",
      },
      client: { id: "client-1" },
    })
    flow.publish.mockImplementation(async () => {
      flow.conflictAfterPublish = true
      throw new ApiError("Бытовка занята", 409)
    })
    renderFlow(items)

    await user.click(screen.getByRole("button", { name: "Подготовить 2" }))
    await user.click(
      await screen.findByRole("button", { name: "Выбрать клиента" })
    )
    await user.click(
      screen.getByRole("button", {
        name: "Создать представление для клиента",
      })
    )

    expect(
      await screen.findByRole("heading", { name: "Бытовка уже недоступна" })
    ).toBeTruthy()
    expect(
      screen.getByRole("list", { name: "Недоступные бытовки" }).textContent
    ).toContain("БЫТ-001 — БК-1 — Новая")
    expect(flow.getHold.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it("shows the hold expiry and disables publishing", async () => {
    const user = userEvent.setup()
    const items = [cabin(1)]
    flow.expired = true
    configureHold(items)
    renderFlow(items)

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    expect(await screen.findByText("Срок резерва истёк")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Выбрать клиента" }))
    expect(
      screen.getByRole("button", {
        name: "Создать представление для клиента",
      })
    ).toHaveProperty("disabled", true)
    expect(flow.publish).not.toHaveBeenCalled()
  })
})
