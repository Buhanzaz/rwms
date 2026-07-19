import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import {
  CharacteristicTags,
  UnavailableDossierSection,
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

  it("shows a truthful unavailable section without a fake register", () => {
    render(
      <UnavailableDossierSection
        title="Осмотры"
        description="Документы доступны только через inventory-service."
      />
    )

    expect(screen.getByText("Осмотры")).toBeTruthy()
    expect(
      screen.getByText("Документы доступны только через inventory-service.")
    ).toBeTruthy()
    expect(screen.queryByRole("table")).toBeNull()
    expect(screen.queryByRole("textbox")).toBeNull()
  })
})
