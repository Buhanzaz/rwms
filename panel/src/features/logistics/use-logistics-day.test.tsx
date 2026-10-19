import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { useLogisticsDay } from "@/features/logistics/use-logistics-day"

function Harness({ timeZone }: { timeZone: string }) {
  const location = useLocation()
  const { selectedDate, setSelectedDate } = useLogisticsDay(timeZone)

  return (
    <>
      <output data-testid="selected-date">{selectedDate}</output>
      <output data-testid="location">{`${location.pathname}${location.search}`}</output>
      <button type="button" onClick={() => setSelectedDate("2026-07-23")}>
        Выбрать 23 июля
      </button>
    </>
  )
}

function renderHarness(initialEntry: string, timeZone = "Europe/Moscow") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Harness timeZone={timeZone} />
    </MemoryRouter>
  )
}

describe("useLogisticsDay", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime("2026-09-02T21:30:00.000Z")
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it("defaults to today in the selected warehouse timezone", () => {
    renderHarness("/operations")

    expect(screen.getByTestId("selected-date").textContent).toBe("2026-09-03")
  })

  it("restores the selected day from the URL and preserves other menu state", () => {
    renderHarness("/operations?date=2026-07-22&receiptId=return-17")

    expect(screen.getByTestId("selected-date").textContent).toBe("2026-07-22")

    fireEvent.click(screen.getByRole("button", { name: "Выбрать 23 июля" }))

    expect(screen.getByTestId("location").textContent).toBe(
      "/operations?date=2026-07-23&receiptId=return-17"
    )
  })

  it("does not accept an impossible calendar date from the URL", () => {
    renderHarness("/operations?date=2026-02-30")

    expect(screen.getByTestId("selected-date").textContent).toBe("2026-09-03")
  })
})
