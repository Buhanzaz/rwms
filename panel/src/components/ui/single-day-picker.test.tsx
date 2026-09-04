import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import {
  calendarDateValue,
  parseCalendarDate,
  SingleDayPicker,
} from "@/components/ui/single-day-picker"

describe("SingleDayPicker", () => {
  it("keeps a strict date-only value without UTC conversion", () => {
    expect(calendarDateValue(new Date(2026, 8, 2))).toBe("2026-09-02")
    expect(parseCalendarDate("2026-09-02")).toEqual(new Date(2026, 8, 2))
    expect(parseCalendarDate("2026-02-31")).toBeUndefined()
  })

  it("renders the project calendar under a Russian date trigger", async () => {
    const interaction = userEvent.setup()
    render(
      <SingleDayPicker
        label="Операционный день"
        value="2026-09-02"
        onValueChange={vi.fn()}
      />
    )

    expect(
      screen.getByRole("button", {
        name: "Операционный день: 2 сентября 2026 г.",
      })
    ).not.toBeNull()
    expect(document.querySelector('input[type="date"]')).toBeNull()

    await interaction.click(
      screen.getByRole("button", {
        name: "Операционный день: 2 сентября 2026 г.",
      })
    )

    expect(
      screen.getByLabelText("Операционный день: календарь")
    ).not.toBeNull()
    expect(screen.getByRole("dialog").getAttribute("data-align")).toBe("start")
    expect(
      screen.getByRole("button", { name: "Предыдущий месяц" })
    ).not.toBeNull()
    expect(
      screen.getByRole("button", { name: "Следующий месяц" })
    ).not.toBeNull()
  })

  it("emits the selected calendar day and closes the popover", async () => {
    const interaction = userEvent.setup()
    const onValueChange = vi.fn()
    render(
      <SingleDayPicker
        label="Дата"
        value="2026-09-02"
        onValueChange={onValueChange}
      />
    )

    await interaction.click(
      screen.getByRole("button", { name: "Дата: 2 сентября 2026 г." })
    )
    await interaction.click(
      screen.getByRole("button", { name: /15 сентября 2026 г\./i })
    )

    expect(onValueChange).toHaveBeenCalledWith("2026-09-15")
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("allows choosing both past and future calendar days", async () => {
    const interaction = userEvent.setup()
    const onValueChange = vi.fn()
    render(
      <SingleDayPicker
        label="Дата операции"
        value="2026-09-02"
        onValueChange={onValueChange}
      />
    )

    await interaction.click(
      screen.getByRole("button", {
        name: "Дата операции: 2 сентября 2026 г.",
      })
    )
    await interaction.click(
      screen.getByRole("button", { name: "Предыдущий месяц" })
    )
    await interaction.click(
      screen.getByRole("button", { name: /15 августа 2026 г\./i })
    )

    expect(onValueChange).toHaveBeenLastCalledWith("2026-08-15")

    await interaction.click(
      screen.getByRole("button", {
        name: "Дата операции: 2 сентября 2026 г.",
      })
    )
    await interaction.click(
      screen.getByRole("button", { name: "Следующий месяц" })
    )
    await interaction.click(
      screen.getByRole("button", { name: "Следующий месяц" })
    )
    await interaction.click(
      screen.getByRole("button", { name: /15 октября 2026 г\./i })
    )

    expect(onValueChange).toHaveBeenLastCalledWith("2026-10-15")
  })
})
