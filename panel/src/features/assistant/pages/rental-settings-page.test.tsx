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
  () => rentalSettingsApi
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
      })
    )
  })
})
