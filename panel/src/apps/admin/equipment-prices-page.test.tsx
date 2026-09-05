import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({
  getEquipmentRentalPricing: vi.fn(),
  updateEquipmentRentalPrice: vi.fn(),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
const auth = vi.hoisted(() => ({ useAuth: vi.fn() }))
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
vi.mock("@/features/auth/use-auth", () => auth)

import {
  EquipmentPricesPage,
  EquipmentPricesSettings,
} from "./equipment-prices-page"
import {
  equipmentRentalPricingKey,
  rentalPricingSettingsKey,
  cabinRentalPricesKey,
  type EquipmentRentalPricing,
} from "@/features/assistant/api/rental-pricing-api"
import { ApiError } from "@/lib/api-client"

const id = (n: number) =>
  `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`
let current: EquipmentRentalPricing
function renderSettings(
  token = "token",
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
) {
  render(
    <QueryClientProvider client={client}>
      <EquipmentPricesSettings accessToken={token} />
    </QueryClientProvider>
  )
  return client
}
const input = (name = "Кровать") =>
  screen.getByRole("textbox", { name }) as HTMLInputElement
const save = (name = "Кровать") =>
  screen.getByRole("button", {
    name: `Сохранить цену: ${name}`,
  }) as HTMLButtonElement
beforeEach(() => {
  vi.resetAllMocks()
  current = {
    version: 3,
    updatedAt: "2026-09-05T11:00:00Z",
    items: [
      {
        equipmentId: id(1),
        name: "Кровать",
        active: true,
        monthlyPriceRubles: "500",
      },
      {
        equipmentId: id(2),
        name: "Стол",
        active: false,
        monthlyPriceRubles: "0",
      },
    ],
  }
  api.getEquipmentRentalPricing.mockImplementation(async () =>
    structuredClone(current)
  )
  api.updateEquipmentRentalPrice.mockImplementation(async (request) => {
    current = {
      ...current,
      version: current.version + 1,
      items: current.items.map((item) =>
        item.equipmentId === request.equipmentId
          ? { ...item, monthlyPriceRubles: request.monthlyPriceRubles }
          : item
      ),
    }
    return structuredClone(current)
  })
})
afterEach(cleanup)

describe("equipment monthly unit prices", () => {
  it("shows all furniture with explicit monthly unit labels and zero defaults", async () => {
    renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    expect(input().value).toBe("500")
    expect(input("Стол Неактивна").value).toBe("0")
    expect(screen.getAllByText("₽ / шт. / мес.")).toHaveLength(2)
    expect(save().disabled).toBe(true)
  })
  it("saves one exact tariff, preserves other drafts, and invalidates only affected pricing caches", async () => {
    const user = userEvent.setup()
    const client = renderSettings()
    client.setQueryData(rentalPricingSettingsKey, { version: 3 })
    client.setQueryData([...cabinRentalPricesKey, "warehouse"], {
      pricingVersion: 3,
    })
    client.setQueryData(["unrelated"], "keep")
    await screen.findByRole("textbox", { name: "Кровать" })
    await user.clear(input("Стол Неактивна"))
    await user.type(input("Стол Неактивна"), "300")
    await user.clear(input())
    await user.type(input(), "9223372036854775807")
    await user.click(save())
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(api.updateEquipmentRentalPrice).toHaveBeenCalledWith({
      accessToken: "token",
      equipmentId: id(1),
      expectedVersion: 3,
      monthlyPriceRubles: "9223372036854775807",
    })
    expect(input("Стол Неактивна").value).toBe("300")
    expect(client.getQueryState(rentalPricingSettingsKey)?.isInvalidated).toBe(
      true
    )
    expect(
      client.getQueryState([...cabinRentalPricesKey, "warehouse"])
        ?.isInvalidated
    ).toBe(true)
    expect(client.getQueryState(["unrelated"])?.isInvalidated).toBe(false)
    await user.click(save("Стол"))
    await waitFor(() =>
      expect(api.updateEquipmentRentalPrice).toHaveBeenLastCalledWith(
        expect.objectContaining({
          equipmentId: id(2),
          expectedVersion: 4,
          monthlyPriceRubles: "300",
        })
      )
    )
  })
  it.each(["", "-1", "1.5", "9223372036854775808"])(
    "rejects invalid input %s without losing it",
    async (value) => {
      const user = userEvent.setup()
      renderSettings()
      await screen.findByRole("textbox", { name: "Кровать" })
      await user.clear(input())
      if (value) await user.type(input(), value)
      await user.click(save())
      expect(input().value).toBe(value)
      expect(input().getAttribute("aria-invalid")).toBe("true")
      expect(document.activeElement).toBe(input())
      expect(api.updateEquipmentRentalPrice).not.toHaveBeenCalled()
    }
  )
  it("saves explicit zero", async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    await user.clear(input())
    await user.type(input(), "0")
    await user.click(save())
    await waitFor(() =>
      expect(api.updateEquipmentRentalPrice).toHaveBeenCalledWith(
        expect.objectContaining({ monthlyPriceRubles: "0" })
      )
    )
  })
  it("refreshes catalog renames, deletions and new zero-price identities", async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    current.items = [
      { ...current.items[0], name: "Кровать двухъярусная" },
      {
        equipmentId: id(3),
        name: "Шкаф",
        active: true,
        monthlyPriceRubles: "0",
      },
    ]
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByRole("textbox", { name: "Кровать двухъярусная" })
    expect(screen.queryByText("Стол")).toBeNull()
    expect(input("Шкаф").value).toBe("0")
  })
  it("keeps a conflicted draft through failed refresh and requires accepting the changed price", async () => {
    const user = userEvent.setup()
    api.updateEquipmentRentalPrice.mockRejectedValueOnce(
      new ApiError("Conflict", 409, "RENTAL_PRICING_VERSION_CONFLICT")
    )
    renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    await user.clear(input())
    await user.type(input(), "600")
    await user.click(save())
    await screen.findByText("Цены изменились")
    expect(input().value).toBe("600")
    expect(save().disabled).toBe(true)
    api.getEquipmentRentalPricing.mockRejectedValueOnce(new Error("Нет связи"))
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByText("Не удалось обновить стоимость наполнения")
    expect(input().value).toBe("600")
    current.version = 7
    current.items[0].monthlyPriceRubles = "700"
    await user.click(screen.getByRole("button", { name: "Обновить цены" }))
    await screen.findByText(/Эта цена уже изменена: сейчас 700/)
    expect(save().disabled).toBe(true)
    await user.click(
      screen.getByRole("button", { name: "Сбросить правку: Кровать" })
    )
    expect(input().value).toBe("700")
  })
  it("preserves drafts when another row changes and uses the latest shared revision", async () => {
    const user = userEvent.setup()
    const client = renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    await user.clear(input())
    await user.type(input(), "600")
    current.version = 7
    current.items[1].monthlyPriceRubles = "300"
    await act(async () => {
      await client.refetchQueries({ queryKey: equipmentRentalPricingKey })
    })
    expect(input().value).toBe("600")
    await user.click(save())
    await waitFor(() =>
      expect(api.updateEquipmentRentalPrice).toHaveBeenCalledWith(
        expect.objectContaining({ expectedVersion: 7 })
      )
    )
  })
  it("does not let an older in-flight read overwrite a saved tariff", async () => {
    const user = userEvent.setup()
    const client = renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    const old = structuredClone(current)
    let completeRead!: (value: EquipmentRentalPricing) => void
    api.getEquipmentRentalPricing.mockImplementationOnce(
      () =>
        new Promise<EquipmentRentalPricing>((resolve) => {
          completeRead = resolve
        })
    )
    await user.clear(input())
    await user.type(input(), "600")
    act(() => {
      void client.refetchQueries({ queryKey: equipmentRentalPricingKey })
    })
    await screen.findByRole("button", { name: "Обновляем цены…" })
    await user.click(save())
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    await act(async () => {
      completeRead(old)
    })
    expect(input().value).toBe("600")
    expect(
      client.getQueryData<EquipmentRentalPricing>(equipmentRentalPricingKey)
        ?.version
    ).toBe(4)
  })
  it("blocks repeat and other-row writes while saving", async () => {
    const user = userEvent.setup()
    let completeWrite!: (value: EquipmentRentalPricing) => void
    api.updateEquipmentRentalPrice.mockImplementationOnce(
      () =>
        new Promise<EquipmentRentalPricing>((resolve) => {
          completeWrite = resolve
        })
    )
    renderSettings()
    await screen.findByRole("textbox", { name: "Кровать" })
    await user.clear(input())
    await user.type(input(), "600")
    await user.clear(input("Стол Неактивна"))
    await user.type(input("Стол Неактивна"), "300")
    await user.click(save())
    expect(save().disabled).toBe(true)
    expect(save("Стол").disabled).toBe(true)
    await user.click(save())
    expect(api.updateEquipmentRentalPrice).toHaveBeenCalledTimes(1)
    current.version = 4
    current.items[0].monthlyPriceRubles = "600"
    await act(async () => {
      completeWrite(structuredClone(current))
    })
    await waitFor(() => expect(save("Стол").disabled).toBe(false))
    expect(input("Стол Неактивна").value).toBe("300")
  })
  it("does not expose cached prices or fetch without a token", () => {
    const client = new QueryClient()
    client.setQueryData(equipmentRentalPricingKey, current)
    renderSettings("", client)
    expect(screen.getByText("Не получен токен доступа")).toBeDefined()
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(api.getEquipmentRentalPricing).not.toHaveBeenCalled()
  })
  it("shows dependency errors without fabricated rows", async () => {
    api.getEquipmentRentalPricing.mockRejectedValue(new Error("Нет связи"))
    renderSettings()
    await screen.findByText("Не удалось обновить стоимость наполнения")
    expect(screen.queryByRole("textbox")).toBeNull()
  })
  it("does not mount tariff queries for a non-admin", () => {
    auth.useAuth.mockReturnValue({
      accessToken: "token",
      currentUser: { globalRole: "RENTAL_MANAGER" },
    })
    render(<EquipmentPricesPage />)
    expect(screen.getByText("Нет доступа к ценам наполнения")).toBeDefined()
    expect(api.getEquipmentRentalPricing).not.toHaveBeenCalled()
  })
})
