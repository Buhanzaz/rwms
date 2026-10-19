import { useState } from "react"
import { fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"
import { DateTimePicker } from "@/components/ui/date-time-picker"
import { selectCalendarDate } from "@/test/calendar"

describe("DateTimePicker", () => {
  it("keeps time when changing the day without applying the browser timezone", async () => {
    const changed = vi.fn()
    function Form() {
      const [value, setValue] = useState("2026-09-12T10:30")
      return (
        <DateTimePicker
          id="departure"
          label="Отправление"
          value={value}
          onValueChange={(next) => {
            setValue(next)
            changed(next)
          }}
        />
      )
    }
    render(<Form />)
    await selectCalendarDate("Отправление", "2026-09-15")
    expect(changed).toHaveBeenLastCalledWith("2026-09-15T10:30")
    fireEvent.change(screen.getByLabelText("Время: Отправление"), {
      target: { value: "11:45" },
    })
    expect(changed).toHaveBeenLastCalledWith("2026-09-15T11:45")
    fireEvent.change(screen.getByLabelText("Время: Отправление"), {
      target: { value: "" },
    })
    expect(changed).toHaveBeenLastCalledWith("")
    expect(
      screen.getByRole("button", { name: "Отправление: 15 сентября 2026 г." })
    ).toBeTruthy()
    fireEvent.change(screen.getByLabelText("Время: Отправление"), {
      target: { value: "12:00" },
    })
    expect(changed).toHaveBeenLastCalledWith("2026-09-15T12:00")
  })

  it("requires a complete date and time before form submission", async () => {
    const user = userEvent.setup()
    const submit = vi.fn((event: React.FormEvent) => event.preventDefault())
    render(
      <form onSubmit={submit}>
        <DateTimePicker label="Резерв до" value="" required />
        <button type="submit">Сохранить</button>
      </form>
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(submit).not.toHaveBeenCalled()
    expect(screen.getByLabelText("Резерв до: календарь")).toBeTruthy()
  })
})
