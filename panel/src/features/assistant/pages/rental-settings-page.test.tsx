import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const rentalSettingsApi = vi.hoisted(() => ({
  getRentalSettings: vi.fn(),
  updateRentalSettings: vi.fn(),
}))
const toast = vi.hoisted(() => ({ error: vi.fn(), success: vi.fn() }))

vi.mock(
  "@/features/assistant/api/rental-presentations-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/assistant/api/rental-presentations-api")
    >()),
    ...rentalSettingsApi,
  })
)

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "admin-access-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      globalRole: "SYSTEM_ADMIN",
    },
  }),
}))

vi.mock("sonner", () => ({ toast }))

import { RentalSettingsPage } from "@/features/assistant/pages/rental-settings-page"
import { ApiError } from "@/lib/api-client"

const initialSettings = {
  version: 4,
  chatSelectionHoldMinutes: 10,
  manualBookingHoldMinutes: 60,
  presentationHoldMinutes: 90,
  draftReservationHoldMinutes: 1440,
  lateChangeNoticeDays: 2,
  lateChangeFeeMode: null,
  lateChangeFeeValue: null,
  rentalSupportPhone: null,
  updatedBy: "11111111-1111-4111-8111-111111111111",
  updatedAt: "2026-08-04T08:00:00Z",
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RentalSettingsPage />
    </QueryClientProvider>
  )
}

describe("RentalSettingsPage", () => {
  beforeEach(() => {
    rentalSettingsApi.getRentalSettings.mockResolvedValue({
      ...initialSettings,
      version: 4,
      chatSelectionHoldMinutes: 10,
      manualBookingHoldMinutes: 60,
      presentationHoldMinutes: 90,
      draftReservationHoldMinutes: 1_440,
      updatedBy: "11111111-1111-4111-8111-111111111111",
      updatedAt: "2026-08-04T08:00:00Z",
    })
    rentalSettingsApi.updateRentalSettings.mockImplementation(
      async (request: Record<string, unknown>) => ({
        version: 5,
        ...request,
        updatedBy: "11111111-1111-4111-8111-111111111111",
        updatedAt: "2026-08-04T08:01:00Z",
      })
    )
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it("saves an independent manual booking hold duration", async () => {
    const user = userEvent.setup()
    renderPage()

    const manualHold = await screen.findByLabelText(
      "Удержание в ручном бронировании"
    )
    await user.clear(manualHold)
    await user.type(manualHold, "45")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenCalledWith({
        accessToken: "admin-access-token",
        expectedVersion: 4,
        chatSelectionHoldMinutes: 10,
        manualBookingHoldMinutes: 45,
        presentationHoldMinutes: 90,
        draftReservationHoldMinutes: 1_440,
        lateChangeNoticeDays: 2,
        lateChangeFeeMode: null,
        lateChangeFeeValue: null,
        rentalSupportPhone: null,
      })
    )
  })

  it("saves exact large whole-ruble fee and separate support phone", async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Сумма, ₽" }))
    await user.type(
      screen.getByLabelText("Сумма неустойки, ₽"),
      "9223372036854775807"
    )
    await user.type(
      screen.getByLabelText("Телефон поддержки аренды"),
      "+74951234567"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: 4,
          lateChangeFeeMode: "FIXED",
          lateChangeFeeValue: "9223372036854775807",
          rentalSupportPhone: "+74951234567",
          chatSelectionHoldMinutes: 10,
          manualBookingHoldMinutes: 60,
          presentationHoldMinutes: 90,
          draftReservationHoldMinutes: 1440,
        })
      )
    )
  })

  it("round-trips a percentage with comma input and uses the returned version", async () => {
    const user = userEvent.setup()
    rentalSettingsApi.getRentalSettings.mockResolvedValue({
      ...initialSettings,
      lateChangeFeeMode: "PERCENT",
      lateChangeFeeValue: "12.50",
    })
    renderPage()
    const fee = await screen.findByLabelText("Размер неустойки, %")
    expect((fee as HTMLInputElement).value).toBe("12.50")
    await user.clear(fee)
    await user.type(fee, "15,25")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenLastCalledWith(
        expect.objectContaining({
          expectedVersion: 5,
          lateChangeFeeValue: "15.25",
        })
      )
    )
  })

  it("clears a configured fee and phone explicitly as null", async () => {
    const user = userEvent.setup()
    rentalSettingsApi.getRentalSettings.mockResolvedValue({
      ...initialSettings,
      lateChangeFeeMode: "FIXED",
      lateChangeFeeValue: "1500.00",
      rentalSupportPhone: "+74951234567",
    })
    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Не настроена" }))
    await user.clear(screen.getByLabelText("Телефон поддержки аренды"))
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenCalledWith(
        expect.objectContaining({
          lateChangeFeeMode: null,
          lateChangeFeeValue: null,
          rentalSupportPhone: null,
        })
      )
    )
  })

  it.each([
    ["Сумма, ₽", "Сумма неустойки, ₽", ""],
    ["Сумма, ₽", "Сумма неустойки, ₽", "1.50"],
    ["Сумма, ₽", "Сумма неустойки, ₽", "9223372036854775808"],
    ["Процент, %", "Размер неустойки, %", "100.01"],
    ["Процент, %", "Размер неустойки, %", "1.001"],
  ])("blocks invalid fee %s / %s = %s", async (mode, label, value) => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole("radio", { name: mode }))
    const input = screen.getByLabelText(label)
    if (value) await user.type(input, value)
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(input.getAttribute("aria-invalid")).toBe("true")
    expect(document.activeElement).toBe(input)
    expect(rentalSettingsApi.updateRentalSettings).not.toHaveBeenCalled()
  })

  it("accepts zero only with an explicitly selected policy", async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole("radio", { name: "Сумма, ₽" }))
    await user.type(screen.getByLabelText("Сумма неустойки, ₽"), "0")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenCalledWith(
        expect.objectContaining({
          lateChangeFeeMode: "FIXED",
          lateChangeFeeValue: "0",
        })
      )
    )
  })

  it("keeps draft and original fence on conflict and failed explicit reload", async () => {
    const user = userEvent.setup()
    rentalSettingsApi.updateRentalSettings.mockRejectedValue(
      new ApiError("Conflict", 409, "SETTINGS_VERSION_CONFLICT")
    )
    renderPage()
    const notice = await screen.findByLabelText(
      "Предупреждение, календарные дни"
    )
    await user.clear(notice)
    await user.type(notice, "3")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    const reload = await screen.findByRole("button", {
      name: "Загрузить актуальные и сбросить правки",
    })
    rentalSettingsApi.getRentalSettings.mockRejectedValueOnce(
      new Error("Unavailable")
    )
    await user.click(reload)
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("Unavailable"))
    expect((notice as HTMLInputElement).value).toBe("3")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() =>
      expect(rentalSettingsApi.updateRentalSettings).toHaveBeenLastCalledWith(
        expect.objectContaining({ expectedVersion: 4, lateChangeNoticeDays: 3 })
      )
    )
    rentalSettingsApi.getRentalSettings.mockResolvedValueOnce({
      ...initialSettings,
      version: 8,
      lateChangeNoticeDays: 4,
    })
    await user.click(
      await screen.findByRole("button", {
        name: "Загрузить актуальные и сбросить правки",
      })
    )
    await waitFor(() => expect((notice as HTMLInputElement).value).toBe("4"))
  })

  it.each([
    ["Предупреждение, календарные дни", "-1"],
    ["Предупреждение, календарные дни", "1.5"],
    ["Телефон поддержки аренды", "Call manager"],
  ])("validates %s", async (label, value) => {
    const user = userEvent.setup()
    renderPage()
    const input = await screen.findByLabelText(label)
    await user.clear(input)
    await user.type(input, value)
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(input.getAttribute("aria-invalid")).toBe("true")
    expect(rentalSettingsApi.updateRentalSettings).not.toHaveBeenCalled()
  })
})
