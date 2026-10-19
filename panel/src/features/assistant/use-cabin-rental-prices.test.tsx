import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({ getCabinRentalPrices: vi.fn() }))
vi.mock(
  "@/features/assistant/api/rental-pricing-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/assistant/api/rental-pricing-api")
    >()),
    ...api,
  })
)
import { useCabinRentalPrices } from "./use-cabin-rental-prices"
import { cabinRentalPricesKey } from "./api/rental-pricing-api"

const request = {
  accessToken: "token",
  subjectId: "manager-1",
  warehouseId: "warehouse-1",
  rentalItemIds: ["cabin-1"],
}
let price = "8000"
beforeEach(() => {
  vi.resetAllMocks()
  price = "8000"
  api.getCabinRentalPrices.mockImplementation(
    async (_token, warehouseId, ids: string[]) => ({
      warehouseId,
      pricingVersion: 3,
      cabins: ids.map((rentalItemId) => ({
        rentalItemId,
        rentalItemVersion: 1,
        rentalTypeId: "type-1",
        categoryId: "category-1",
        monthlyPriceRubles: price,
      })),
    })
  )
})
afterEach(cleanup)

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

it("isolates users and warehouses, hides cached prices without authorization and does not refetch on array identity alone", async () => {
  const client = new QueryClient()
  const hook = renderHook((props) => useCabinRentalPrices(props), {
    initialProps: request,
    wrapper: wrapper(client),
  })
  await waitFor(() =>
    expect(
      hook.result.current.pricesById.get("cabin-1")?.monthlyPriceRubles
    ).toBe("8000")
  )
  hook.rerender({ ...request, rentalItemIds: [...request.rentalItemIds] })
  expect(api.getCabinRentalPrices).toHaveBeenCalledTimes(1)
  hook.rerender({ ...request, subjectId: "manager-2" })
  await waitFor(() => expect(api.getCabinRentalPrices).toHaveBeenCalledTimes(2))
  hook.rerender({ ...request, warehouseId: "warehouse-2" })
  await waitFor(() => expect(api.getCabinRentalPrices).toHaveBeenCalledTimes(3))
  hook.rerender({ ...request, accessToken: "" })
  expect(hook.result.current.pricesById.size).toBe(0)
  expect(hook.result.current.failedIds.has("cabin-1")).toBe(true)
  expect(api.getCabinRentalPrices).toHaveBeenCalledTimes(3)
})

it("refreshes affected prices after an admin tariff invalidation without touching unrelated data", async () => {
  const client = new QueryClient()
  client.setQueryData(["unrelated"], "keep")
  const hook = renderHook(() => useCabinRentalPrices(request), {
    wrapper: wrapper(client),
  })
  await waitFor(() => expect(hook.result.current.pricesById.size).toBe(1))
  price = "10000"
  await act(async () => {
    await client.invalidateQueries({ queryKey: cabinRentalPricesKey })
  })
  await waitFor(() =>
    expect(
      hook.result.current.pricesById.get("cabin-1")?.monthlyPriceRubles
    ).toBe("10000")
  )
  expect(client.getQueryData(["unrelated"])).toBe("keep")
})

it("retains successful batches and retries only failed ones", async () => {
  const client = new QueryClient()
  api.getCabinRentalPrices.mockRejectedValueOnce(new Error("Unavailable"))
  const ids = Array.from({ length: 101 }, (_, index) => `cabin-${index + 1}`)
  const hook = renderHook(
    () => useCabinRentalPrices({ ...request, rentalItemIds: ids }),
    { wrapper: wrapper(client) }
  )
  await waitFor(() => expect(hook.result.current.error).toBeDefined())
  await waitFor(() =>
    expect(hook.result.current.pricesById.has("cabin-101")).toBe(true)
  )
  expect(hook.result.current.failedIds.size).toBe(100)
  act(() => hook.result.current.retry())
  await waitFor(() => expect(hook.result.current.pricesById.size).toBe(101))
  expect(api.getCabinRentalPrices).toHaveBeenCalledTimes(3)
  expect(api.getCabinRentalPrices.mock.calls[2][2]).toHaveLength(100)
})
