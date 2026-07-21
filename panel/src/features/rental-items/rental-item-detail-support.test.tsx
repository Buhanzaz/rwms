import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import {
  CharacteristicTags,
  EmptyDossierRegister,
} from "@/features/rental-items/rental-item-detail-support"

afterEach(cleanup)

describe("rental item detail support", () => {
  it("keeps compound characteristics and highlights sanitary characteristics", () => {
    render(
      <CharacteristicTags value="Электрика КК, Душевая, Металлическая дверь, кондиционер" />
    )

    expect(screen.getByText("Электрика КК").className).toContain("bg-secondary")
    expect(screen.getByText("Душевая").className).toContain("bg-primary")
    expect(
      screen.getByText("Металлическая дверь, кондиционер").className
    ).toContain("bg-secondary")
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
