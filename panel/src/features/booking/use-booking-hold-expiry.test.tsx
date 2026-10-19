import { act, cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  isBookingHoldExpired,
  useBookingHoldExpiry,
} from "@/features/booking/use-booking-hold-expiry"

const hold = {
  draftId: "11111111-1111-4111-8111-111111111111",
  expiresAt: "2026-08-09T12:00:10.000Z",
}

function ExpiryState({ value }: { value: typeof hold | null }) {
  return (
    <output data-testid="booking-hold-expiry">
      {useBookingHoldExpiry(value) ? "expired" : "active"}
    </output>
  )
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date("2026-08-09T12:00:00.000Z"))
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe("useBookingHoldExpiry", () => {
  it("marks a hold expired precisely at its deadline without a one-second poll", async () => {
    render(<ExpiryState value={hold} />)

    expect(screen.getByTestId("booking-hold-expiry").textContent).toBe("active")
    expect(vi.getTimerCount()).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(9_999)
    })
    expect(screen.getByTestId("booking-hold-expiry").textContent).toBe("active")

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(screen.getByTestId("booking-hold-expiry").textContent).toBe(
      "expired"
    )
  })

  it("treats an absent or invalid hold as expired", () => {
    expect(isBookingHoldExpired(null)).toBe(true)
    expect(
      isBookingHoldExpired({
        draftId: hold.draftId,
        expiresAt: "not-a-date",
      })
    ).toBe(true)
  })
})
