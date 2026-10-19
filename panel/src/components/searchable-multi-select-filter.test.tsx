import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"

const OPTIONS = [
  { value: "first", label: "Первое значение" },
  { value: "second", label: "Второе значение" },
  { value: "third", label: "Третье значение" },
]

describe("SearchableMultiSelectFilter", () => {
  it("searches options and applies a draft selection", () => {
    const onApply = vi.fn()

    render(
      <SearchableMultiSelectFilter
        label="Статус"
        options={OPTIONS}
        selected={[]}
        onApply={onApply}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Статус" }))
    fireEvent.change(
      screen.getByRole("textbox", {
        name: "Поиск значений фильтра Статус",
      }),
      { target: { value: "втор" } }
    )

    expect(screen.queryByText("Первое значение")).toBeNull()
    expect(screen.getByText("Второе значение")).toBeTruthy()

    fireEvent.click(screen.getByText("Второе значение"))
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    expect(onApply).toHaveBeenCalledWith(["second"])
  })

  it("supports selecting all, clearing the draft and clearing the filter", () => {
    const onApply = vi.fn()

    render(
      <SearchableMultiSelectFilter
        label="Склад"
        options={OPTIONS}
        selected={["first"]}
        onApply={onApply}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Склад: выбрано 1" }))
    fireEvent.click(screen.getByRole("button", { name: "Выбрать все" }))
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    expect(onApply).toHaveBeenLastCalledWith(["first", "second", "third"])

    fireEvent.click(screen.getByRole("button", { name: "Склад: выбрано 1" }))
    fireEvent.click(screen.getByRole("button", { name: "Снять" }))
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    expect(onApply).toHaveBeenLastCalledWith([])

    fireEvent.click(screen.getByRole("button", { name: "Склад: выбрано 1" }))
    fireEvent.click(screen.getByRole("button", { name: "Очистить" }))

    expect(onApply).toHaveBeenLastCalledWith([])
  })
})
