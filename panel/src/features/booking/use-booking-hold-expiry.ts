import { useEffect, useState } from "react"

import type { ManualBookingDraftHold } from "@/features/booking/api/manual-booking-drafts-api"

/**
 * Returns whether a manual-booking hold is absent, malformed, or past its
 * server-provided expiration time.
 */
export function isBookingHoldExpired(
  hold:
    Pick<ManualBookingDraftHold, "draftId" | "expiresAt"> | null | undefined,
  now = Date.now()
) {
  if (!hold?.expiresAt) return true

  const expiresAt = Date.parse(hold.expiresAt)
  return !Number.isFinite(expiresAt) || expiresAt <= now
}

/**
 * Changes state once at a manual-booking hold deadline instead of polling the
 * clock and re-rendering the whole booking screen every second.
 */
export function useBookingHoldExpiry(
  hold: Pick<ManualBookingDraftHold, "draftId" | "expiresAt"> | null | undefined
) {
  const draftId = hold?.draftId ?? null
  const expiresAt = hold?.expiresAt ?? null
  const [, setExpiryTick] = useState(0)

  useEffect(() => {
    const expiresAtTimestamp = expiresAt ? Date.parse(expiresAt) : Number.NaN

    if (
      !draftId ||
      !Number.isFinite(expiresAtTimestamp) ||
      expiresAtTimestamp <= Date.now()
    ) {
      return
    }

    const timeout = window.setTimeout(
      () => setExpiryTick((current) => current + 1),
      Math.max(0, expiresAtTimestamp - Date.now())
    )

    return () => window.clearTimeout(timeout)
  }, [draftId, expiresAt])

  return isBookingHoldExpired(hold)
}
