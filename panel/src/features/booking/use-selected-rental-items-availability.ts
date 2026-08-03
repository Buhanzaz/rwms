import { useEffect, useMemo, useRef } from "react"
import { useQuery } from "@tanstack/react-query"

import {
  checkRentalItemsAvailability,
  RENTAL_BOOKING_SELECTED_AVAILABILITY_QUERY_KEY,
  unavailableRentalItemIds,
} from "@/features/booking/api/booking-availability-api"

const BOOKING_QUERY_CACHE_TIME_MS = 2 * 60 * 60 * 1_000
const AVAILABILITY_REFETCH_INTERVAL_MS = 15_000

export function useSelectedRentalItemsAvailability(params: {
  accessToken: string | null
  subjectId: string | undefined
  warehouseId: string | null
  rentalItemIds: readonly string[]
  onUnavailable: (rentalItemIds: string[]) => void
}) {
  const { accessToken, subjectId, warehouseId, rentalItemIds, onUnavailable } =
    params
  const sortedIds = useMemo(
    () => [...new Set(rentalItemIds)].sort(),
    [rentalItemIds]
  )
  const enabled = Boolean(
    accessToken &&
    warehouseId &&
    sortedIds.length > 0 &&
    sortedIds.length <= 100
  )
  const lastNotification = useRef("")
  const query = useQuery({
    queryKey: [
      ...RENTAL_BOOKING_SELECTED_AVAILABILITY_QUERY_KEY,
      subjectId ?? "unknown-user",
      warehouseId ?? "no-warehouse",
      sortedIds,
    ],
    queryFn: () =>
      checkRentalItemsAvailability({
        accessToken: accessToken!,
        warehouseId: warehouseId!,
        rentalItemIds: sortedIds,
      }),
    enabled,
    staleTime: Infinity,
    gcTime: BOOKING_QUERY_CACHE_TIME_MS,
    refetchInterval: enabled ? AVAILABILITY_REFETCH_INTERVAL_MS : false,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })

  useEffect(() => {
    if (!query.data) return

    const unavailable = unavailableRentalItemIds(query.data, sortedIds)
    if (unavailable.length === 0) {
      lastNotification.current = ""
      return
    }

    const signature = unavailable.join(":")
    if (signature === lastNotification.current) return
    lastNotification.current = signature
    onUnavailable(unavailable)
  }, [onUnavailable, query.data, sortedIds])

  return query
}
