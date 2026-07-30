import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: { id: "user-1", globalRole: "RENTAL_MANAGER" },
    warehouses: [],
  }),
}))

vi.mock("@/features/rental-items/use-rental-item-covers", () => ({
  RENTAL_ITEM_COVERS_QUERY_KEY: ["rental-item-covers"],
  loadRentalItemCoverPage: vi.fn().mockResolvedValue({ items: [] }),
}))

vi.mock("@/features/rental-items/rental-items-grid-view", () => ({
  RentalItemsGridView: ({
    items,
    renderItemActions,
    gridFormat,
  }: {
    items: Array<{ id: string; number: string }>
    renderItemActions: (item: { id: string; number: string }) => React.ReactNode
    gridFormat: { columns: number; rows: number }
  }) => (
    <div
      data-testid="order-unit-grid-view"
      data-grid-format={`${gridFormat.columns}x${gridFormat.rows}`}
    >
      {items.map((item) => (
        <div key={item.id}>
          <span data-testid="candidate-number">{item.number}</span>
          {renderItemActions(item)}
        </div>
      ))}
    </div>
  ),
}))

vi.mock("@/features/rental-items/rental-items-table-view", () => ({
  RentalItemsTableView: ({
    items,
    renderItemActions,
    sorting,
    onSortingChange,
  }: {
    items: Array<{ id: string; number: string }>
    renderItemActions: (item: { id: string; number: string }) => React.ReactNode
    sorting: Array<{ id: string; desc: boolean }>
    onSortingChange: (sorting: Array<{ id: string; desc: boolean }>) => void
  }) => (
    <div data-testid="order-unit-table-view">
      <span data-testid="order-unit-table-sorting">
        {sorting[0]?.desc ? "descending" : "default"}
      </span>
      <button
        type="button"
        onClick={() => onSortingChange([{ id: "number", desc: true }])}
      >
        Сначала больший номер
      </button>
      {items.map((item) => (
        <div key={item.id}>
          <span data-testid="candidate-number">{item.number}</span>
          {renderItemActions(item)}
        </div>
      ))}
    </div>
  ),
}))

import { OrderWarehouseUnitSelection } from "@/features/orders/components/order-warehouse-unit-selection"
import type { OrderUnitCandidate } from "@/features/orders/domain/orders"

const candidate: OrderUnitCandidate = {
  reservationId: null,
  added: false,
  desiredContents: [],
  unit: {
    id: "11111111-1111-4111-8111-111111111111",
    version: 1,
    warehouseId: "22222222-2222-4222-8222-222222222222",
    number: "БЫТ-001",
    status: "FREE",
    rentalType: "БК-1",
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: null,
    linoleum: null,
    tags: [],
    contents: [],
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T08:00:00Z",
  },
}

function View({
  currentCandidate = candidate,
  currentCandidates,
  canEdit = true,
  conflicting = new Set<string>(),
  onAdd = vi.fn(),
}: {
  currentCandidate?: OrderUnitCandidate
  currentCandidates?: OrderUnitCandidate[]
  canEdit?: boolean
  conflicting?: ReadonlySet<string>
  onAdd?: (candidate: OrderUnitCandidate) => void
}) {
  return (
    <OrderWarehouseUnitSelection
      accessToken="orders-token"
      warehouseId={candidate.unit.warehouseId}
      candidates={currentCandidates ?? [currentCandidate]}
      canEdit={canEdit}
      pendingUnitIds={new Set()}
      conflictingUnitIds={conflicting}
      onAdd={onAdd}
      onEditContents={vi.fn()}
    />
  )
}

function renderView(element: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>{element}</QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderWarehouseUnitSelection", () => {
  it("keeps all free cabin categories in the booking candidates", () => {
    const candidates: OrderUnitCandidate[] = [
      {
        ...candidate,
        unit: {
          ...candidate.unit,
          category: "ИТР",
          number: "БЫТ-ИТР-001",
        },
      },
      {
        ...candidate,
        unit: {
          ...candidate.unit,
          id: "33333333-3333-4333-8333-333333333333",
          category: "Обычная",
          number: "БЫТ-ОБЫЧ-001",
        },
      },
      {
        ...candidate,
        unit: {
          ...candidate.unit,
          id: "44444444-4444-4444-8444-444444444444",
          category: "Новая",
          number: "БЫТ-НОВ-001",
        },
      },
    ]

    renderView(<View currentCandidates={candidates} />)

    expect(screen.getByText("БЫТ-ИТР-001")).toBeTruthy()
    expect(screen.getByText("БЫТ-ОБЫЧ-001")).toBeTruthy()
    expect(screen.getByText("БЫТ-НОВ-001")).toBeTruthy()
    expect(screen.getAllByRole("button", { name: "Добавить" })).toHaveLength(
      3
    )
  })

  it("uses the same candidate action in grid and table without exposing creation", async () => {
    const user = userEvent.setup()
    const onAdd = vi.fn()
    renderView(<View onAdd={onAdd} />)

    expect(screen.getByTestId("order-unit-grid-view")).toBeTruthy()
    expect(screen.queryByTestId("order-unit-table-view")).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Добавить новую бытовку" })
    ).toBeNull()

    await user.click(screen.getByRole("radio", { name: "Список" }))

    expect(screen.queryByTestId("order-unit-grid-view")).toBeNull()
    expect(screen.getByTestId("order-unit-table-view")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Добавить" }))

    await user.click(screen.getByRole("radio", { name: "Сетка" }))

    expect(screen.getByTestId("order-unit-grid-view")).toBeTruthy()
    expect(screen.queryByTestId("order-unit-table-view")).toBeNull()
    await user.click(screen.getByRole("button", { name: "Добавить" }))

    expect(onAdd).toHaveBeenNthCalledWith(1, candidate)
    expect(onAdd).toHaveBeenNthCalledWith(2, candidate)
  })

  it("preserves warehouse sorting while switching back to the grid", async () => {
    const user = userEvent.setup()
    const secondCandidate: OrderUnitCandidate = {
      ...candidate,
      unit: {
        ...candidate.unit,
        id: "44444444-4444-4444-8444-444444444444",
        number: "БЫТ-002",
      },
    }
    renderView(<View currentCandidates={[candidate, secondCandidate]} />)

    await user.click(screen.getByRole("radio", { name: "Список" }))
    await user.click(
      screen.getByRole("button", { name: "Сначала больший номер" })
    )

    expect(screen.getByTestId("order-unit-table-sorting").textContent).toBe(
      "descending"
    )

    await user.click(screen.getByRole("radio", { name: "Сетка" }))

    expect(
      screen
        .getAllByTestId("candidate-number")
        .map((element) => element.textContent)
    ).toEqual(["БЫТ-002", "БЫТ-001"])
  })

  it("changes the grid density from 2x2 to 3x3 and keeps it after a view round-trip", async () => {
    const user = userEvent.setup()
    renderView(<View />)

    expect(
      screen
        .getByTestId("order-unit-grid-view")
        .getAttribute("data-grid-format")
    ).toBe("2x2")

    await user.click(screen.getByRole("button", { name: "до 2x2" }))
    const gridSize = screen.getByRole("slider", { name: "Формат сетки" })
    gridSize.focus()
    await user.keyboard("{ArrowRight}")
    await user.click(screen.getByRole("button", { name: "Готово" }))

    expect(
      screen
        .getByTestId("order-unit-grid-view")
        .getAttribute("data-grid-format")
    ).toBe("3x3")

    await user.click(screen.getByRole("radio", { name: "Список" }))
    await user.click(screen.getByRole("radio", { name: "Сетка" }))

    expect(
      screen
        .getByTestId("order-unit-grid-view")
        .getAttribute("data-grid-format")
    ).toBe("3x3")
  })

  it("reuses warehouse filters to narrow the authorized server page", async () => {
    const user = userEvent.setup()
    const secondCandidate: OrderUnitCandidate = {
      ...candidate,
      unit: {
        ...candidate.unit,
        id: "44444444-4444-4444-8444-444444444444",
        number: "БЫТ-002",
        rentalType: "БК-2",
      },
    }
    renderView(<View currentCandidates={[candidate, secondCandidate]} />)

    expect(screen.getByRole("button", { name: "Тип" })).toBeTruthy()
    expect(screen.getByText("БЫТ-001")).toBeTruthy()
    expect(screen.getByText("БЫТ-002")).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Тип" }))
    await user.click(screen.getByText("БК-2"))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    expect(screen.queryByText("БЫТ-001")).toBeNull()
    expect(screen.getByText("БЫТ-002")).toBeTruthy()

    await user.click(screen.getByRole("radio", { name: "Список" }))
    await user.click(screen.getByRole("radio", { name: "Сетка" }))

    expect(screen.queryByText("БЫТ-001")).toBeNull()
    expect(screen.getByText("БЫТ-002")).toBeTruthy()
  })

  it("keeps Add until the backend projection confirms the reservation", async () => {
    const user = userEvent.setup()
    const onAdd = vi.fn()
    const rendered = renderView(<View onAdd={onAdd} />)

    const addButton = screen.getByRole("button", { name: "Добавить" })
    await user.click(addButton)
    expect(onAdd).toHaveBeenCalledWith(candidate)
    expect(screen.getByRole("button", { name: "Добавить" })).toBeTruthy()

    rendered.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <View
          currentCandidate={{
            ...candidate,
            added: true,
            reservationId: "33333333-3333-4333-8333-333333333333",
          }}
        />
      </QueryClientProvider>
    )

    expect(
      (screen.getByRole("button", { name: "Добавлено" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
    expect(
      (
        screen.getByRole("button", {
          name: "Изменить наполнение БЫТ-001",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })

  it("rolls the card into a non-repeatable reserved state after conflict", () => {
    renderView(
      <View conflicting={new Set([candidate.unit.id])} onAdd={vi.fn()} />
    )

    expect(
      (screen.getByRole("button", { name: "Уже занята" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
  })

  it("disables both commands for a read-only order", () => {
    const rendered = renderView(<View canEdit={false} />)
    expect(
      (screen.getByRole("button", { name: "Добавить" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)

    rendered.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <View
          canEdit={false}
          currentCandidate={{
            ...candidate,
            added: true,
            reservationId: "33333333-3333-4333-8333-333333333333",
          }}
        />
      </QueryClientProvider>
    )
    expect(
      (
        screen.getByRole("button", {
          name: "Изменить наполнение БЫТ-001",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
  })
})
