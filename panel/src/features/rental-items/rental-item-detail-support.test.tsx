import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import {
  CharacteristicTags,
  EmptyDossierRegister,
} from "@/features/rental-items/rental-item-detail-support"
import { rentalLifecycleLabel } from "@/features/rental-items/rental-item-lifecycle"

afterEach(cleanup)

describe("rental item detail support", () => {
  it("shows an imported cabin as shipped only when shipment date and tenant are both present", () => {
    expect(
      rentalLifecycleLabel(null, null, null, "2023-05-17", "ООО Партнёр")
    ).toBe("Отгружена")
    expect(rentalLifecycleLabel(null, null, null, "2023-05-17", "   ")).toBe(
      "Не отгружена"
    )
    expect(rentalLifecycleLabel(null, null, null, null, "ООО Партнёр")).toBe(
      "Не отгружена"
    )
  })

  it("keeps a live draft shipment authoritative over imported passport facts", () => {
    expect(
      rentalLifecycleLabel("DRAFT", null, null, "2023-05-17", "ООО Партнёр")
    ).toBe("Ожидает отгрузки")
  })

  it("keeps compound characteristics in neutral rounded tags", () => {
    render(
      <CharacteristicTags
        values={[
          { id: "electricity", name: "Электрика КК" },
          { id: "shower", name: "Душевая" },
          { id: "door", name: "Металлическая дверь" },
          { id: "air-conditioner", name: "кондиционер" },
        ]}
      />
    )

    expect(screen.getByText("Электрика КК").className).toContain("bg-secondary")
    expect(screen.getByText("Душевая").className).toContain("bg-secondary")
    expect(screen.getByText("Металлическая дверь").className).toContain(
      "bg-secondary"
    )
    expect(screen.getByText("кондиционер").className).toContain("bg-secondary")
  })

  it("shows an honest empty register with the transferred columns", () => {
    render(
      <EmptyDossierRegister
        title="Возвраты"
        description="Для этой бытовки нет подтверждённых возвратов."
        columns={["Дата", "От кого", "Статус", "Действия"]}
      />
    )

    expect(screen.getByText("Возвраты")).toBeTruthy()
    expect(
      screen.getByText("Для этой бытовки нет подтверждённых возвратов.")
    ).toBeTruthy()
    expect(screen.getByRole("table")).toBeTruthy()
    expect(screen.getByText("От кого")).toBeTruthy()
    expect(screen.getByText("Записей нет")).toBeTruthy()
    expect(screen.queryByRole("textbox")).toBeNull()
  })
})
