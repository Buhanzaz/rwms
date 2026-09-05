import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({
  getRentalPricingSettings: vi.fn(),
  updateRentalPrice: vi.fn(),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock(
  "@/features/assistant/api/rental-pricing-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/assistant/api/rental-pricing-api")
    >()),
    ...api,
  })
)
vi.mock("sonner", () => ({ toast }))

import { RentalPricesSettings } from "./rental-prices-settings"
import {
  rentalPricingSettingsKey,
  type RentalPricingSettings,
} from "@/features/assistant/api/rental-pricing-api"
import { ApiError } from "@/lib/api-client"

const id = (n: number) =>
  `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`
function table(): RentalPricingSettings {
  return {
    version: 3,
    updatedAt: "2026-09-05T11:00:00Z",
    types: [
      {
        rentalTypeId: id(1),
        name: "БК-1",
        active: true,
        categories: [
          {
            categoryId: id(3),
            name: "Обычная",
            active: true,
            monthlyPriceRubles: "8000",
          },
          {
            categoryId: id(4),
            name: "Новая",
            active: false,
            monthlyPriceRubles: "12000",
          },
        ],
      },
      {
        rentalTypeId: id(2),
        name: "БК-2",
        active: false,
        categories: [
          {
            categoryId: id(3),
            name: "Обычная",
            active: true,
            monthlyPriceRubles: "0",
          },
          {
            categoryId: id(4),
            name: "Новая",
            active: false,
            monthlyPriceRubles: "0",
          },
        ],
      },
    ],
  }
}
let current: RentalPricingSettings
function renderSettings(
  token = "token",
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
) {
  render(
    <QueryClientProvider client={queryClient}>
      <RentalPricesSettings accessToken={token} />
    </QueryClientProvider>
  )
  return queryClient
}
function input(type = "БК-1", category = "Обычная") {
  return screen.getByRole("textbox", {
    name: `${type} — ${category}${category === "Новая" ? " Неактивна" : ""}`,
  }) as HTMLInputElement
}
function save(type = "БК-1", category = "Обычная") {
  return screen.getByRole("button", {
    name: `Сохранить цену: ${type} — ${category}`,
  }) as HTMLButtonElement
}
beforeEach(() => {
  vi.resetAllMocks()
  current = table()
  api.getRentalPricingSettings.mockImplementation(async () =>
    structuredClone(current)
  )
  api.updateRentalPrice.mockImplementation(async (request) => {
    current = {
      ...current,
      version: current.version + 1,
      types: current.types.map((type) => ({
        ...type,
        categories: type.categories.map((category) =>
          type.rentalTypeId === request.rentalTypeId &&
          category.categoryId === request.categoryId
            ? { ...category, monthlyPriceRubles: request.monthlyPriceRubles }
            : category
        ),
      })),
    }
    return structuredClone(current)
  })
})
afterEach(cleanup)

describe("RentalPricesSettings", () => {
  it("opens the full live matrix including unused and inactive combinations without selectors", async () => {
    renderSettings()
    await screen.findByText("БК-2")
    expect(screen.getAllByRole("textbox")).toHaveLength(4)
    expect(input().value).toBe("8000")
    expect(input("БК-2").value).toBe("0")
    expect(screen.queryByRole("combobox")).toBeNull()
    expect(save().disabled).toBe(true)
  })
  it("saves only the selected row exactly and retains a different unsaved row", async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByText("БК-2")
    await user.clear(input("БК-2"))
    await user.type(input("БК-2"), "9000")
    await user.clear(input())
    await user.type(input(), "9223372036854775807")
    await user.click(save())
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(api.updateRentalPrice).toHaveBeenCalledWith({
      accessToken: "token",
      rentalTypeId: id(1),
      categoryId: id(3),
      expectedVersion: 3,
      monthlyPriceRubles: "9223372036854775807",
    })
    expect(input().value).toBe("9223372036854775807")
    expect(input("БК-2").value).toBe("9000")
    await user.click(save("БК-2"))
    await waitFor(() =>
      expect(api.updateRentalPrice).toHaveBeenLastCalledWith(
        expect.objectContaining({
          rentalTypeId: id(2),
          expectedVersion: 4,
          monthlyPriceRubles: "9000",
        })
      )
    )
  })
  it.each(["", "-1", "1.5", "9223372036854775808"])(
    "blocks invalid price %s and focuses the input",
    async (value) => {
      const user = userEvent.setup()
      renderSettings()
      await screen.findByText("БК-2")
      await user.clear(input())
      if (value) await user.type(input(), value)
      await user.click(save())
      expect(input().getAttribute("aria-invalid")).toBe("true")
      expect(document.activeElement).toBe(input())
      expect(api.updateRentalPrice).not.toHaveBeenCalled()
    }
  )
  it("saves explicit zero", async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByText("БК-2")
    await user.clear(input())
    await user.type(input(), "0")
    await user.click(save())
    await waitFor(() =>
      expect(api.updateRentalPrice).toHaveBeenCalledWith(
        expect.objectContaining({ monthlyPriceRubles: "0" })
      )
    )
  })
  it("reflects renamed and deleted catalog rows and newly added zero rows on refresh", async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByText("БК-2")
    current.types = [
      {
        ...current.types[0],
        name: "БК-1М",
        categories: [
          current.types[0].categories[0],
          {
            categoryId: id(5),
            name: "ИТР",
            active: true,
            monthlyPriceRubles: "0",
          },
        ],
      },
    ]
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByText("БК-1М")
    expect(screen.queryByText("БК-2")).toBeNull()
    expect(screen.queryByText("Новая")).toBeNull()
    expect(input("БК-1М").value).toBe("8000")
    expect(input("БК-1М", "ИТР").value).toBe("0")
  })
  it("preserves draft on background refresh and rebases only when that row's price is unchanged", async () => {
    const user = userEvent.setup()
    const client = renderSettings()
    await screen.findByText("БК-2")
    await user.clear(input())
    await user.type(input(), "8500")
    current.version = 7
    current.types[1].categories[0].monthlyPriceRubles = "9500"
    await act(async () => {
      await client.refetchQueries({ queryKey: rentalPricingSettingsKey })
    })
    expect(input().value).toBe("8500")
    await user.click(save())
    await waitFor(() =>
      expect(api.updateRentalPrice).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: 7,
          monthlyPriceRubles: "8500",
        })
      )
    )
  })
  it("keeps a conflicted draft through failed refresh and requires accepting a changed server price", async () => {
    const user = userEvent.setup()
    api.updateRentalPrice.mockRejectedValueOnce(
      new ApiError("Conflict", 409, "RENTAL_PRICING_VERSION_CONFLICT")
    )
    renderSettings()
    await screen.findByText("БК-2")
    await user.clear(input())
    await user.type(input(), "8500")
    await user.click(save())
    await screen.findByText("Цены изменились")
    expect(input().value).toBe("8500")
    expect(save().disabled).toBe(true)
    api.getRentalPricingSettings.mockRejectedValueOnce(new Error("Нет связи"))
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByText("Не удалось обновить цены аренды")
    expect(input().value).toBe("8500")
    current.version = 5
    current.types[0].categories[0].monthlyPriceRubles = "8700"
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByText(/Эта цена уже изменена: сейчас 8700/)
    expect(input().value).toBe("8500")
    expect(save().disabled).toBe(true)
    await user.click(
      screen.getByRole("button", { name: "Сбросить правку: БК-1 — Обычная" })
    )
    expect(input().value).toBe("8700")
    await user.clear(input())
    await user.type(input(), "9000")
    await user.click(save())
    await waitFor(() =>
      expect(api.updateRentalPrice).toHaveBeenLastCalledWith(
        expect.objectContaining({
          expectedVersion: 5,
          monthlyPriceRubles: "9000",
        })
      )
    )
  })
  it("surfaces a dependency failure without fabricating zero rows", async () => {
    api.getRentalPricingSettings.mockRejectedValue(new Error("Нет связи"))
    renderSettings()
    await screen.findByText("Не удалось обновить цены аренды")
    expect(screen.queryByRole("textbox")).toBeNull()
  })
  it("does not let an older in-flight GET overwrite the saved response", async () => {
    const user = userEvent.setup()
    const client = renderSettings()
    await screen.findByText("БК-2")
    const oldTable = structuredClone(current)
    let completeRead!: (data: RentalPricingSettings) => void
    api.getRentalPricingSettings.mockImplementationOnce(
      () =>
        new Promise<RentalPricingSettings>((resolve) => {
          completeRead = resolve
        })
    )
    await user.clear(input())
    await user.type(input(), "8500")
    act(() => {
      void client.refetchQueries({ queryKey: rentalPricingSettingsKey })
    })
    await screen.findByRole("button", { name: "Обновляем цены…" })
    await user.click(save())
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    await act(async () => {
      completeRead(oldTable)
    })
    expect(input().value).toBe("8500")
    expect(
      client.getQueryData<RentalPricingSettings>(rentalPricingSettingsKey)
        ?.version
    ).toBe(4)
  })
  it("blocks duplicate and other-row saves while a write is pending", async () => {
    const user = userEvent.setup()
    let completeWrite!: (data: RentalPricingSettings) => void
    api.updateRentalPrice.mockImplementationOnce(
      () =>
        new Promise<RentalPricingSettings>((resolve) => {
          completeWrite = resolve
        })
    )
    renderSettings()
    await screen.findByText("БК-2")
    await user.clear(input())
    await user.type(input(), "8500")
    await user.clear(input("БК-2"))
    await user.type(input("БК-2"), "9000")
    await user.click(save())
    expect(save().disabled).toBe(true)
    expect(save("БК-2").disabled).toBe(true)
    await user.click(save())
    expect(api.updateRentalPrice).toHaveBeenCalledTimes(1)
    current.version = 4
    current.types[0].categories[0].monthlyPriceRubles = "8500"
    await act(async () => {
      completeWrite(structuredClone(current))
    })
    await waitFor(() => expect(save("БК-2").disabled).toBe(false))
    expect(input("БК-2").value).toBe("9000")
  })
  it("does not expose cached prices or fetch without a token", () => {
    const client = new QueryClient()
    client.setQueryData(rentalPricingSettingsKey, table())
    renderSettings("", client)
    expect(screen.getByText("Не получен токен доступа")).toBeDefined()
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(api.getRentalPricingSettings).not.toHaveBeenCalled()
  })
})
