import { fireEvent, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"

const months = [
  "январь",
  "февраль",
  "март",
  "апрель",
  "май",
  "июнь",
  "июль",
  "август",
  "сентябрь",
  "октябрь",
  "ноябрь",
  "декабрь",
]

/** Chooses a date through the visible calendar, then the time when supplied. */
export async function selectCalendarDate(label: string, value: string) {
  const user = userEvent.setup()
  await user.click(screen.getByLabelText(label))
  const calendar = () => screen.getByLabelText(`${label}: календарь`)
  const date = new Date(`${value.slice(0, 10)}T12:00:00`)
  const caption = within(calendar()).getByText(
    new RegExp(`^(${months.join("|")}) \\d{4}$`, "i")
  ).textContent!
  const [month, year] = caption.toLocaleLowerCase("ru").split(" ")
  const offset =
    (date.getFullYear() - Number(year)) * 12 +
    date.getMonth() -
    months.indexOf(month)
  if (Math.abs(offset) > 120)
    throw new Error(
      `Calendar test date is too far from its starting month: ${value}`
    )
  for (let step = 0; step < Math.abs(offset); step++) {
    await user.click(
      within(calendar()).getByRole("button", {
        name: offset < 0 ? "Предыдущий месяц" : "Следующий месяц",
      })
    )
  }
  const formatted = new Intl.DateTimeFormat("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
  }).format(date)
  await user.click(
    within(calendar()).getByRole("button", {
      name: new RegExp(
        `\\b${formatted.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`,
        "i"
      ),
    })
  )
  if (value.includes("T")) {
    fireEvent.change(screen.getByLabelText(`Время: ${label}`), {
      target: { value: value.slice(11, 16) },
    })
  }
}
