import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { Button } from "@/components/ui/button"

afterEach(() => {
  cleanup()
})

describe("Button", () => {
  it("distinguishes primary, outline and secondary actions from destructive actions", () => {
    render(
      <>
        <Button>Тёмная</Button>
        <Button variant="outline">Светлая</Button>
        <Button variant="secondary">Вторичная</Button>
        <Button variant="destructive">Опасная</Button>
      </>
    )

    expect(screen.getByRole("button", { name: "Тёмная" }).className).toContain(
      "rwms-button-dark"
    )
    expect(screen.getByRole("button", { name: "Светлая" }).className).toContain(
      "rwms-button-outline"
    )
    expect(
      screen.getByRole("button", { name: "Вторичная" }).className
    ).toContain("rwms-button-secondary")
    expect(
      screen.getByRole("button", { name: "Опасная" }).className
    ).not.toContain("rwms-button-dark")
  })
})
