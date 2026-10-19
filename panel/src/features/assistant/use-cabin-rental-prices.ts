import { useQueries } from "@tanstack/react-query"
import {
  cabinRentalPricesKey,
  getCabinRentalPrices,
} from "@/features/assistant/api/rental-pricing-api"

/** Current informational prices, isolated by caller/warehouse and loaded in contract-sized batches. */
export function useCabinRentalPrices({
  accessToken,
  subjectId,
  warehouseId,
  rentalItemIds,
  enabled = true,
}: {
  accessToken: string
  subjectId: string
  warehouseId: string
  rentalItemIds: readonly string[]
  enabled?: boolean
}) {
  const batches: string[][] = []
  for (let index = 0; index < rentalItemIds.length; index += 100)
    batches.push(rentalItemIds.slice(index, index + 100))
  const queries = useQueries({
    queries: batches.map((batch) => ({
      queryKey: [...cabinRentalPricesKey, subjectId, warehouseId, batch],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        getCabinRentalPrices(accessToken, warehouseId, batch, signal),
      enabled:
        enabled && Boolean(accessToken.trim() && subjectId && warehouseId),
      retry: false,
      staleTime: 30_000,
      refetchInterval: 30_000,
      refetchOnWindowFocus: "always" as const,
    })),
  })
  const authorized = Boolean(accessToken.trim() && subjectId && warehouseId)
  return {
    pricesById: new Map(
      queries
        .flatMap((query) =>
          query.isError || !authorized ? [] : (query.data?.cabins ?? [])
        )
        .map((price) => [price.rentalItemId, price])
    ),
    failedIds: new Set(
      queries.flatMap((query, index) =>
        query.isError || !authorized ? batches[index] : []
      )
    ),
    error: queries.find((query) => query.isError)?.error,
    isFetching: queries.some((query) => query.isFetching),
    retry: () => {
      for (const query of queries)
        if (query.isError && authorized) void query.refetch()
    },
  }
}
