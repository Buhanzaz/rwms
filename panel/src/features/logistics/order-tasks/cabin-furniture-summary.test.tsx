import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { CabinFurnitureSummary } from "./cabin-furniture-summary"
import { classifyCabinFurniture } from "./cabin-furniture-status"

const TABLE = {
  equipmentId: "11111111-1111-4111-8111-111111111111",
  equipmentName: "Стол",
  name: "Стол",
  quantity: 2,
  reservationState: "ACTIVE" as const,
}
const BED = {
  equipmentId: "22222222-2222-4222-8222-222222222222",
  equipmentName: "Кровать",
  name: "Кровать",
  quantity: 1,
  reservationState: "ACTIVE" as const,
}

afterEach(cleanup)

describe("cabin furniture status", () => {
  it.each([
    {
      label: "Требуется наполнение",
      desired: [TABLE],
      actual: [{ ...TABLE, quantity: 1 }],
      expected: "required",
    },
    {
      label: "Требуются действия",
      desired: [TABLE],
      actual: [{ ...TABLE, quantity: 1 }, BED],
      expected: "action",
    },
    {
      label: "Наполнение загружено",
      desired: [TABLE],
      actual: [TABLE],
      expected: "loaded",
    },
    {
      label: "Нет наполнения",
      desired: [],
      actual: [],
      expected: "none",
    },
  ] as const)("shows $label", ({ label, desired, actual, expected }) => {
    expect(classifyCabinFurniture(desired, actual)).toBe(expected)
    render(<CabinFurnitureSummary desired={desired} actual={actual} />)
    expect(screen.getByText(label)).toBeTruthy()
  })

  it("keeps unavailable owner data neutral", () => {
    expect(classifyCabinFurniture([TABLE], [], true)).toBe("unavailable")
    render(<CabinFurnitureSummary desired={[TABLE]} actual={[]} unavailable />)
    expect(screen.getByText("Наполнение недоступно")).toBeTruthy()
    expect(screen.getAllByText("данные недоступны")).toHaveLength(2)
  })
})
