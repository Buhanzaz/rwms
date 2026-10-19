import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

const estimateRentalItems = vi.hoisted(() => ({
  resolve: vi.fn(),
  search: vi.fn(),
}))
const repairRentalItems = vi.hoisted(() => ({
  resolve: vi.fn(),
  search: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  ESTIMATE_RENTAL_ITEMS_QUERY_KEY: ["repair-estimates", "rental-items"],
  resolveEstimateRentalItem: estimateRentalItems.resolve,
  searchEstimateRentalItems: estimateRentalItems.search,
}))

vi.mock("@/features/repair-tasks/api/repair-tasks-api", () => ({
  REPAIR_TASK_RENTAL_ITEMS_QUERY_KEY: ["repair-tasks", "rental-items"],
  resolveRepairTaskRentalItem: repairRentalItems.resolve,
  searchRepairTaskRentalItems: repairRentalItems.search,
}))

import { RepairEstimateRentalItemPicker } from "@/features/repair-estimates/repair-estimate-rental-item-picker"

const warehouseId = "00000000-0000-4000-8000-000000000001"

function rentalItem(number: string) {
  return {
    id:
      number === "БЫТ-017"
        ? "00000000-0000-4000-8000-000000000017"
        : "00000000-0000-4000-8000-000000000042",
    warehouseId,
    number,
    counterparty: "ООО Арендатор",
    arrivalDate: "2026-07-18",
  }
}

function renderPicker(scope: "ESTIMATE" | "REPAIR" = "ESTIMATE") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RepairEstimateRentalItemPicker
        id="estimate-rental-item"
        warehouseId={warehouseId}
        value=""
        scope={scope}
        onValueChange={vi.fn()}
      />
    </QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

describe("RepairEstimateRentalItemPicker", () => {
  it("opens on focus with a blank after-rent query, then searches the visible list", async () => {
    estimateRentalItems.search.mockImplementation(
      async ({ search }: { search: string }) => ({
        items: [rentalItem(search ? "БЫТ-017" : "БЫТ-042")],
        page: 0,
        size: 40,
        totalElements: 1,
        totalPages: 1,
      })
    )
    const user = userEvent.setup()

    renderPicker()
    await user.tab()

    await waitFor(() =>
      expect(estimateRentalItems.search).toHaveBeenCalledWith({
        warehouseId,
        search: "",
        page: 0,
        size: 40,
      })
    )
    expect(await screen.findByText("БЫТ-042")).toBeTruthy()
    expect(
      screen.getByText(/Контрагент: ООО Арендатор.*Осмотр: 18\.07\.2026/)
    ).toBeTruthy()
    expect(document.querySelector(".max-h-64.overflow-y-auto")).not.toBeNull()

    await user.type(screen.getByLabelText("Поиск бытовки"), "017")

    await waitFor(() =>
      expect(estimateRentalItems.search).toHaveBeenCalledWith({
        warehouseId,
        search: "017",
        page: 0,
        size: 40,
      })
    )
    expect(await screen.findByText("БЫТ-017")).toBeTruthy()
  })

  it("keeps direct-repair cabin choices free of estimate counterparty and inspection metadata", async () => {
    repairRentalItems.search.mockResolvedValue({
      items: [rentalItem("БЫТ-042")],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    })
    const user = userEvent.setup()

    renderPicker("REPAIR")
    await user.tab()

    await waitFor(() =>
      expect(repairRentalItems.search).toHaveBeenCalledWith({
        warehouseId,
        search: "",
        page: 0,
        size: 40,
      })
    )
    expect(await screen.findByText("БЫТ-042")).toBeTruthy()
    expect(screen.queryByText(/Контрагент:/)).toBeNull()
    expect(screen.queryByText(/Осмотр:/)).toBeNull()
  })
})
