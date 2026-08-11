import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import { DesiredTripScheduleFields } from "@/features/logistics/desired-trip-schedule-fields"

describe("DesiredTripScheduleFields", () => {
  it("marks desired windows but keeps another actual date selectable", async () => {
    const onDateChange = vi.fn()
    const user = userEvent.setup()

    render(
      <DesiredTripScheduleFields
        dateLabel="Фактическая дата ходки"
        scheduledDate=""
        desiredDeliveryWindows={[
          {
            startDate: "2026-08-15",
            endDate: "2026-08-16",
          },
        ]}
        onDateChange={onDateChange}
      />
    )

    const desiredDay = document.querySelector<HTMLButtonElement>(
      '[data-day="15.08.2026"][data-desired-window="true"]'
    )
    const anotherDay = document.querySelector<HTMLButtonElement>(
      '[data-day="18.08.2026"]'
    )
    expect(desiredDay).toBeTruthy()
    expect(desiredDay?.disabled).toBe(false)
    expect(anotherDay).toBeTruthy()
    expect(anotherDay?.hasAttribute("data-desired-window")).toBe(false)

    await user.click(anotherDay!)
    expect(onDateChange).toHaveBeenCalledWith("2026-08-18")
    expect(screen.queryByLabelText("Фактическое время ходки")).toBeNull()
    expect(
      screen.getByText(/пожелания не ограничивают календарь логиста/i)
    ).toBeTruthy()
  })
})
