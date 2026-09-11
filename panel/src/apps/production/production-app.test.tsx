import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ThemeProvider } from "@/components/theme-provider"

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ logout: vi.fn() }),
}))

import { ProductionApp } from "./production-app"

function LocationProbe() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function renderProduction(initialEntry = "/production") {
  return render(
    <ThemeProvider defaultTheme="light" storageKey="production-test-theme">
      <MemoryRouter initialEntries={[initialEntry]}>
        <ProductionApp />
        <LocationProbe />
      </MemoryRouter>
    </ThemeProvider>
  )
}

afterEach(() => {
  cleanup()
  localStorage.removeItem("production-test-theme")
  vi.unstubAllGlobals()
})

describe("ProductionApp", () => {
  it("renders the exact three production navigation groups and empty page bodies", () => {
    renderProduction("/production/")

    const navigation = screen.getByRole("navigation", {
      name: "Разделы производства",
    })
    expect(
      within(navigation)
        .getAllByText(/^(Обзор|Производство|Имущество)$/)
        .map((element) => element.textContent)
    ).toEqual(["Обзор", "Производство", "Имущество"])
    expect(
      within(navigation)
        .getAllByRole("link")
        .map((link) => link.textContent?.trim())
    ).toEqual([
      "Главная",
      "Работа с претензиями",
      "СAD online",
      "Заказы",
      "Кап. ремонты",
      "Доска задач",
      "Склад",
    ])
    expect(
      screen.getByLabelText("Содержимое раздела производства").childElementCount
    ).toBe(0)
    expect(screen.getAllByText("Главная")).toHaveLength(2)
  })

  it("navigates within the production prefix while keeping bodies empty", async () => {
    const user = userEvent.setup()
    renderProduction()

    await user.click(screen.getByRole("link", { name: "Кап. ремонты" }))

    expect(screen.getByTestId("location").textContent).toBe(
      "/production/capital-repairs"
    )
    expect(screen.getAllByText("Кап. ремонты")).toHaveLength(2)
    expect(
      screen.getByLabelText("Содержимое раздела производства").childElementCount
    ).toBe(0)
  })

  it("keeps the main-panel shell surface and motion-safe video background", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }))
    )
    renderProduction()

    const video = document.querySelector("video")!
    expect(video.querySelector("source")?.getAttribute("src")).toBe(
      "/admin/background.webm"
    )
    expect(video.className).toContain("motion-reduce:hidden")
    expect(video.className).toContain("object-cover")
    expect(
      document.querySelector('[data-slot="sidebar-inset"]')?.className
    ).toContain("rwms-shell-surface")
    expect(screen.getByLabelText("BLOCKBOX: Производство")).toBeTruthy()
  })
})
