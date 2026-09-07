import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalBookingAlert } from "@/features/assistant/api/rental-presentations-api"
import type { RentalBookingChangeAlert } from "@/features/assistant/api/rental-booking-change-alerts-api"
import { ApiError } from "@/lib/api-client"

const rentalBookingAlertsApi = vi.hoisted(() => ({
  getRentalBookingAlerts: vi.fn(),
  actOnRentalBookingAlert: vi.fn(),
}))
const toast = vi.hoisted(() => ({ error: vi.fn(), success: vi.fn() }))
const changesApi = vi.hoisted(() => ({
  getRentalBookingChangeAlerts: vi.fn(),
  acknowledgeRentalBookingChangeAlert: vi.fn(),
}))
vi.mock(
  "@/features/assistant/api/rental-booking-change-alerts-api",
  () => changesApi
)

vi.mock(
  "@/features/assistant/api/rental-presentations-api",
  () => rentalBookingAlertsApi
)

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "manager-access-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      rentalAccess: true,
      globalRole: "SYSTEM_ADMIN",
    },
  }),
}))

vi.mock("@/features/orders/api/order-command-identity", () => ({
  OrderCommandIdentityRegistry: class {
    private readonly keys = new Map<string, string>()

    keyFor(fingerprint: string) {
      const existing = this.keys.get(fingerprint)
      if (existing) return existing

      const created = "99999999-9999-4999-8999-999999999999"
      this.keys.set(fingerprint, created)
      return created
    }

    confirm(fingerprint: string) {
      this.keys.delete(fingerprint)
    }
  },
}))

vi.mock("sonner", () => ({ toast }))

import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"

const firstAlert: RentalBookingAlert = {
  bookingId: "22222222-2222-4222-8222-222222222222",
  version: 7,
  inquiryId: "33333333-3333-4333-8333-333333333333",
  orderId: "44444444-4444-4444-8444-444444444444",
  client: {
    id: "55555555-5555-4555-8555-555555555555",
    version: 3,
    type: "LEGAL_ENTITY",
    displayName: "ООО Север",
    phone: "+79990000000",
    email: null,
    createdAt: "2026-07-27T08:00:00Z",
    updatedAt: "2026-07-27T08:00:00Z",
  },
  confirmedAt: "2026-07-27T10:30:00Z",
  cabins: [
    {
      id: "66666666-6666-4666-8666-666666666666",
      number: "БК-12",
      rentalType: "Бытовка",
      dimensions: "6 × 2,4 м",
      finishing: "ДВП",
      category: "Стандарт",
    },
    {
      id: "77777777-7777-4777-8777-777777777777",
      number: "БК-18",
      rentalType: "Контейнер",
      dimensions: null,
      finishing: null,
      category: null,
    },
  ],
}

const secondAlert: RentalBookingAlert = {
  ...firstAlert,
  bookingId: "88888888-8888-4888-8888-888888888888",
  orderId: "99999999-9999-4999-8999-999999999999",
  client: { ...firstAlert.client, displayName: "АО Восток" },
  cabins: [
    {
      ...firstAlert.cabins[0],
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      number: "БК-24",
    },
  ],
}

const firstChange: RentalBookingChangeAlert = {
  mutationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  version: 3,
  bookingId: firstAlert.bookingId,
  orderId: firstAlert.orderId,
  warehouseId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  canOpenOrder: true,
  operation: "RESCHEDULE",
  occurredAt: "2026-09-05T10:00:00Z",
  previousDeliveryDate: "2026-09-07",
  newDeliveryDate: "2026-09-09",
  deliveryAddress: "Москва, ул. Лесная, 10",
  feeRubles: "1500",
  settlement: "TEST_PAID",
}

function LocationProbe() {
  const { pathname } = useLocation()
  return <output data-testid="location">{pathname}</output>
}

function renderDialog(initialPath = "/assistant") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <QueryClientProvider client={queryClient}>
        <ManagerBookingAlertDialog />
        <LocationProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  rentalBookingAlertsApi.getRentalBookingAlerts.mockReset()
  rentalBookingAlertsApi.actOnRentalBookingAlert.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
  changesApi.getRentalBookingChangeAlerts.mockReset().mockResolvedValue([])
  changesApi.acknowledgeRentalBookingChangeAlert
    .mockReset()
    .mockResolvedValue(undefined)
})

afterEach(cleanup)

describe("ManagerBookingAlertDialog", () => {
  it("shows the actual reschedule dates and server settlement without customer PII", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([firstChange])
    renderDialog()
    expect(
      await screen.findByRole("heading", { name: "Клиент перенёс доставку" })
    ).toBeTruthy()
    expect(screen.getByText(firstChange.deliveryAddress)).toBeTruthy()
    expect(screen.getByText("7 сент. 2026 г.")).toBeTruthy()
    expect(screen.getByText("9 сент. 2026 г.")).toBeTruthy()
    expect(screen.getByText("Подтверждена тестовая оплата")).toBeTruthy()
    expect(screen.queryByText(firstAlert.client.displayName)).toBeNull()
  })

  it("shows cancellation and keeps zero different from an unspecified fee", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([
      {
        ...firstChange,
        operation: "CANCEL",
        newDeliveryDate: null,
        feeRubles: "0",
        settlement: "NOT_REQUIRED",
        canOpenOrder: false,
      },
    ])
    renderDialog()
    expect(
      await screen.findByRole("heading", { name: "Клиент отменил заказ" })
    ).toBeTruthy()
    expect(screen.getByText("Отменённая дата")).toBeTruthy()
    expect(screen.getByText("0 ₽")).toBeTruthy()
    expect(screen.queryByText("Не указана")).toBeNull()
    expect(screen.queryByRole("button", { name: "Открыть заказ" })).toBeNull()
    expect(screen.getByRole("button", { name: "Понятно" })).toBeTruthy()
  })

  it("does not interpret an unconfigured policy as a free or paid change", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([
      { ...firstChange, feeRubles: null, settlement: "POLICY_UNCONFIGURED" },
    ])
    renderDialog()
    expect(await screen.findByText("Не указана")).toBeTruthy()
    expect(screen.getByText("Правило неустойки не настроено")).toBeTruthy()
    expect(screen.queryByText("Оплата не требуется")).toBeNull()
  })

  it("formats the full ruble range without rounding", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([
      { ...firstChange, feeRubles: "9223372036854775807" },
    ])
    renderDialog()
    const dialog = await screen.findByRole("alertdialog")
    expect(dialog.textContent?.replace(/\s/g, "")).toContain(
      "9223372036854775807₽"
    )
  })

  it("acknowledges each mutation independently even when they share one booking", async () => {
    const user = userEvent.setup()
    const secondChange = {
      ...firstChange,
      mutationId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      operation: "CANCEL" as const,
      newDeliveryDate: null,
    }
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([
      firstAlert,
    ])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([
      firstChange,
      secondChange,
    ])
    renderDialog()
    await user.click(await screen.findByRole("button", { name: "Понятно" }))
    await waitFor(() =>
      expect(
        changesApi.acknowledgeRentalBookingChangeAlert
      ).toHaveBeenCalledWith({
        accessToken: "manager-access-token",
        mutationId: firstChange.mutationId,
        expectedVersion: 3,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(
      await screen.findByRole("heading", { name: "Клиент отменил заказ" })
    ).toBeTruthy()
    expect(screen.getAllByRole("alertdialog")).toHaveLength(1)
    await user.click(screen.getByRole("button", { name: "Понятно" }))
    expect(
      await screen.findByRole("heading", {
        name: "Клиент «ООО Север» подтвердил выбор",
      })
    ).toBeTruthy()
    expect(
      changesApi.acknowledgeRentalBookingChangeAlert
    ).toHaveBeenLastCalledWith(
      expect.objectContaining({ mutationId: secondChange.mutationId })
    )
    expect(
      rentalBookingAlertsApi.actOnRentalBookingAlert
    ).not.toHaveBeenCalled()
  })

  it("retains failed acknowledgement, reuses its identity, and opens only after success", async () => {
    const user = userEvent.setup()
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([firstChange])
    changesApi.acknowledgeRentalBookingChangeAlert.mockRejectedValueOnce(
      new ApiError("Unavailable", 503)
    )
    renderDialog()
    await user.click(
      await screen.findByRole("button", { name: "Открыть заказ" })
    )
    expect(await screen.findByText("Уведомление не подтверждено")).toBeTruthy()
    expect(screen.getByTestId("location").textContent).toBe("/assistant")
    await user.click(screen.getByRole("button", { name: "Открыть заказ" }))
    await waitFor(() =>
      expect(screen.getByTestId("location").textContent).toBe(
        `/orders/${firstChange.orderId}`
      )
    )
    expect(
      changesApi.acknowledgeRentalBookingChangeAlert.mock.calls[0]
    ).toEqual(changesApi.acknowledgeRentalBookingChangeAlert.mock.calls[1])
  })

  it("keeps both acknowledgement actions disabled while pending", async () => {
    const user = userEvent.setup()
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts.mockResolvedValue([firstChange])
    let resolve: (() => void) | undefined
    changesApi.acknowledgeRentalBookingChangeAlert.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        })
    )
    renderDialog()
    await user.click(await screen.findByRole("button", { name: "Понятно" }))
    expect(
      (screen.getByRole("button", { name: "Понятно" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
    expect(
      (
        screen.getByRole("button", {
          name: "Открыть заказ",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    resolve?.()
  })

  it("shows feed errors instead of reporting that there are no changes", async () => {
    const user = userEvent.setup()
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])
    changesApi.getRentalBookingChangeAlerts
      .mockRejectedValueOnce(new Error("Feed unavailable"))
      .mockResolvedValue([firstChange])
    renderDialog()
    expect(
      await screen.findByText("Не удалось проверить изменения заказов")
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Повторить" }))
    expect(
      await screen.findByRole("heading", { name: "Клиент перенёс доставку" })
    ).toBeTruthy()
  })

  it("stays hidden when the manager has no pending alerts", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([])

    renderDialog()

    await waitFor(() =>
      expect(
        rentalBookingAlertsApi.getRentalBookingAlerts
      ).toHaveBeenCalledWith("manager-access-token")
    )
    expect(screen.queryByRole("alertdialog")).toBeNull()
  })

  it("shows the confirmed client, selected cabins, time and pending count", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([
      firstAlert,
      secondAlert,
    ])

    renderDialog()

    expect(
      await screen.findByRole("heading", {
        name: "Клиент «ООО Север» подтвердил выбор",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("list", { name: "Выбранные бытовки" }).textContent
    ).toContain("БК-12БытовкаБК-18Контейнер")
    expect(screen.getByText("Подтверждено")).toBeTruthy()
    expect(screen.getByText("Других ожидающих подтверждений")).toBeTruthy()
    expect(screen.getByText("1")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Сохранить черновик" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Продолжить" })).toBeTruthy()
  })

  it("disables both actions while a booking action is pending", async () => {
    let resolveAction: (() => void) | undefined
    rentalBookingAlertsApi.getRentalBookingAlerts.mockResolvedValue([
      firstAlert,
    ])
    rentalBookingAlertsApi.actOnRentalBookingAlert.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveAction = resolve
        })
    )
    const user = userEvent.setup()

    renderDialog()

    await user.click(
      await screen.findByRole("button", { name: "Сохранить черновик" })
    )

    await waitFor(() => {
      expect(
        (
          screen.getByRole("button", {
            name: "Сохранить черновик",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(true)
      expect(
        (
          screen.getByRole("button", {
            name: "Продолжить",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(true)
    })

    resolveAction?.()
  })

  it("keeps the draft through the alert action and then shows the next alert", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts
      .mockResolvedValueOnce([firstAlert, secondAlert])
      .mockResolvedValue([secondAlert])
    rentalBookingAlertsApi.actOnRentalBookingAlert.mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderDialog()

    await user.click(
      await screen.findByRole("button", { name: "Сохранить черновик" })
    )

    await waitFor(() =>
      expect(
        rentalBookingAlertsApi.actOnRentalBookingAlert
      ).toHaveBeenCalledWith({
        accessToken: "manager-access-token",
        bookingId: firstAlert.bookingId,
        expectedVersion: firstAlert.version,
        action: "KEEP_DRAFT",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(
      await screen.findByRole("heading", {
        name: "Клиент «АО Восток» подтвердил выбор",
      })
    ).toBeTruthy()
    expect(screen.getByTestId("location").textContent).toBe("/assistant")
  })

  it("continues through the alert action and opens the matching draft order", async () => {
    rentalBookingAlertsApi.getRentalBookingAlerts
      .mockResolvedValueOnce([firstAlert])
      .mockResolvedValue([])
    rentalBookingAlertsApi.actOnRentalBookingAlert.mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderDialog()

    await user.click(await screen.findByRole("button", { name: "Продолжить" }))

    await waitFor(() =>
      expect(
        rentalBookingAlertsApi.actOnRentalBookingAlert
      ).toHaveBeenCalledWith({
        accessToken: "manager-access-token",
        bookingId: firstAlert.bookingId,
        expectedVersion: firstAlert.version,
        action: "CONTINUE",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    await waitFor(() =>
      expect(screen.getByTestId("location").textContent).toBe(
        `/orders/${firstAlert.orderId}`
      )
    )
  })
})
