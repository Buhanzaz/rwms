import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  PresentationBooking,
  PublicClientPresentation,
} from "@/features/assistant/api/rental-presentations-api"
import { ApiError } from "@/lib/api-client"

const api = vi.hoisted(() => ({
  presentation: null as PublicClientPresentation | null,
  get: vi.fn(),
  confirm: vi.fn(),
  booking: vi.fn(),
}))

vi.mock("@/features/assistant/api/rental-presentations-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/assistant/api/rental-presentations-api")
  >("@/features/assistant/api/rental-presentations-api")
  return {
    ...actual,
    getPublicPresentation: api.get,
    confirmPublicPresentation: api.confirm,
    getPublicPresentationBooking: api.booking,
  }
})

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({ title }: { title: string }) => <div>{title}</div>,
}))

import {
  equipmentCapacityForCabin,
  presentationDraftIssues,
} from "@/features/assistant/pages/public-client-presentation-draft"
import { PublicClientPresentationPage } from "@/features/assistant/pages/public-client-presentation-page"

function cabin(id: string, number: string, physicalBeds = 0) {
  return {
    id,
    number,
    rentalType: "Бытовка",
    dimensions: "6 × 2,4 м",
    finishing: "ДВП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    passport: {
      legacyId: `legacy-${id}`,
      source: "old-panel-rental-items-v1",
      tenant: "ООО Строй",
    },
    tags: [],
    currentContents:
      physicalBeds > 0
        ? [
            {
              equipmentId: "bed",
              equipmentName: "Кровать",
              quantity: physicalBeds,
              locationKind: "CABIN_NON_RENTED",
            },
          ]
        : [],
    photos: [],
  }
}

function presentation(
  overrides: Partial<PublicClientPresentation> = {}
): PublicClientPresentation {
  return {
    id: "presentation-1",
    revision: 1,
    state: "ACTIVE",
    expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    viewUntil: new Date(Date.now() + 2 * 60 * 60_000).toISOString(),
    viewOnly: false,
    mode: "NORMAL",
    requiredSelectionCount: null,
    requiresDesiredDeliveryWindows: true,
    desiredDeliveryWindows: [],
    equipmentAvailability: [
      {
        equipmentId: "bed",
        equipmentName: "Кровать",
        availableQuantity: 3,
        maximumPerCabin: 4,
      },
      {
        equipmentId: "table",
        equipmentName: "Стол",
        availableQuantity: 1,
        maximumPerCabin: null,
      },
    ],
    groups: [
      {
        key: "available",
        label: "Доступные варианты",
        cabins: [cabin("cabin-1", "БЫТ-1", 1), cabin("cabin-2", "БЫТ-2")],
      },
    ],
    bookedOrderId: null,
    ...overrides,
  }
}

const completedBooking: PresentationBooking = {
  bookingId: "booking-1",
  state: "COMPLETED",
  orderId: "order-1",
  statusPath: "/status",
  errorCode: null,
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={["/client-presentations/example-token"]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/client-presentations/:token"
            element={<PublicClientPresentationPage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

async function chooseCalendarDate(user: ReturnType<typeof userEvent.setup>) {
  const calendar = screen.getByLabelText(
    "Календарь выбора желаемой даты получения"
  )
  const day = Array.from(
    calendar.querySelectorAll<HTMLButtonElement>("button[data-day]")
  )[0]
  if (!day) throw new Error("Календарь не показал ни одного дня.")
  const localized = day.dataset.day
  if (!localized) throw new Error("У дня календаря нет локальной даты.")
  const [dayValue, monthValue, yearValue] = localized.split(".")
  await user.click(day)
  return `${yearValue}-${monthValue}-${dayValue}`
}

async function openNormalDetails(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    (await screen.findAllByRole("button", { name: "Выбрать" }))[0]
  )
  await user.click(
    screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
  )
}

async function fillNormalDetails(user: ReturnType<typeof userEvent.setup>) {
  const date = await chooseCalendarDate(user)
  await user.type(
    screen.getByLabelText("Адрес доставки"),
    "Санкт-Петербург, Невский проспект, 1"
  )
  return date
}

beforeEach(() => {
  api.presentation = presentation()
  api.get.mockImplementation(async () => api.presentation)
  api.confirm.mockResolvedValue(completedBooking)
  api.booking.mockResolvedValue(completedBooking)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("public client presentation", () => {
  it("shows the exact cabin actions and keeps the sticky safe-area layout", async () => {
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Бытовка БЫТ-1" })
    ).toBeTruthy()
    expect(screen.getAllByRole("button", { name: "Выбрать" })).toHaveLength(2)
    expect(
      screen.getAllByRole("button", { name: "Добавить мебель" })
    ).toHaveLength(2)
    expect(screen.getAllByText("tenant")).toHaveLength(2)
    expect(screen.queryByText("legacyId")).toBeNull()
    expect(screen.queryByText("source")).toBeNull()

    const page = screen.getByRole("main")
    expect(page.className).toContain("h-svh")
    expect(page.className).toContain("overflow-y-auto")
    const content = Array.from(page.children).find((element) =>
      element.classList.contains("max-w-6xl")
    )
    const actionBar = Array.from(page.children).find((element) =>
      element.classList.contains("bottom-0")
    )
    expect(content?.className).toContain(
      "pb-[calc(10rem+env(safe-area-inset-bottom))]"
    )
    expect(actionBar?.className).toContain(
      "pb-[calc(0.75rem+env(safe-area-inset-bottom))]"
    )
  })

  it("shares free and held physical furniture while enforcing the per-cabin maximum", () => {
    const value = presentation()
    const selectedIds = ["cabin-1", "cabin-2"]
    const validDraft = { "cabin-1": { bed: 4 }, "cabin-2": { bed: 0 } }

    expect(
      equipmentCapacityForCabin({
        presentation: value,
        selectedIds,
        draft: validDraft,
        cabinId: "cabin-1",
        equipmentId: "bed",
      })
    ).toBe(4)
    expect(
      equipmentCapacityForCabin({
        presentation: value,
        selectedIds,
        draft: validDraft,
        cabinId: "cabin-2",
        equipmentId: "bed",
      })
    ).toBe(0)
    expect(
      presentationDraftIssues({
        presentation: value,
        selectedIds,
        draft: validDraft,
      })
    ).toEqual([])
    expect(
      presentationDraftIssues({
        presentation: value,
        selectedIds,
        draft: { "cabin-1": { bed: 4 }, "cabin-2": { bed: 1 } },
      })
    ).toEqual(expect.arrayContaining([expect.stringContaining("Кровать")]))

    expect(
      equipmentCapacityForCabin({
        presentation: presentation({
          equipmentAvailability: [
            {
              equipmentId: "bed",
              equipmentName: "Кровать",
              availableQuantity: 10,
              maximumPerCabin: 4,
            },
          ],
          groups: [
            {
              key: "available",
              label: "Доступные варианты",
              cabins: [cabin("cabin-3", "БЫТ-3")],
            },
          ],
        }),
        selectedIds: ["cabin-3"],
        draft: {},
        cabinId: "cabin-3",
        equipmentId: "bed",
      })
    ).toBe(4)
    expect(
      equipmentCapacityForCabin({
        presentation: presentation({
          equipmentAvailability: [
            {
              equipmentId: "bed",
              equipmentName: "Кровать",
              availableQuantity: 3,
              maximumPerCabin: 4,
            },
          ],
          groups: [
            {
              key: "available",
              label: "Доступные варианты",
              cabins: [cabin("cabin-3", "БЫТ-3")],
            },
          ],
        }),
        selectedIds: ["cabin-3"],
        draft: {},
        cabinId: "cabin-3",
        equipmentId: "bed",
      })
    ).toBe(3)
  })

  it("keeps furniture compact and capacity-bound per cabin", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Выбрать" }))[0]
    )
    const addFurniture = screen.getAllByRole("button", {
      name: "Добавить мебель",
    })
    expect(addFurniture[0]).toHaveProperty("disabled", false)
    expect(addFurniture[1]).toHaveProperty("disabled", true)
    await user.click(addFurniture[0])
    const dialog = screen.getByRole("dialog", {
      name: "Добавить мебель в бытовку БЫТ-1",
    })
    expect(
      within(dialog).getByText(
        "Свободно сейчас: 3 · в этой бытовке: 4 · лимит: 4"
      )
    ).toBeTruthy()
    expect(
      within(dialog).getByText(
        "Свободно сейчас: 1 · в этой бытовке: 1 · лимит: нет"
      )
    ).toBeTruthy()
    const increment = within(dialog).getByRole("button", {
      name: "Увеличить количество: Кровать",
    })
    await user.click(increment)
    expect(
      within(dialog).getByLabelText("Количество: Кровать").textContent
    ).toBe("1")
    expect(increment).toHaveProperty("disabled", false)
    const tableIncrement = within(dialog).getByRole("button", {
      name: "Увеличить количество: Стол",
    })
    await user.click(tableIncrement)
    expect(tableIncrement).toHaveProperty("disabled", true)
    await user.click(within(dialog).getByRole("button", { name: "Готово" }))
  })

  it("requires two replacements and preserves the client click order", async () => {
    api.presentation = presentation({
      mode: "REPLACEMENT",
      requiredSelectionCount: 2,
      requiresDesiredDeliveryWindows: false,
      desiredDeliveryWindows: [
        {
          startDate: "2026-08-11",
          endDate: "2026-08-11",
        },
      ],
      groups: [
        {
          key: "replacement",
          label: "Замены",
          cabins: [
            cabin("cabin-1", "БЫТ-1"),
            cabin("cabin-2", "БЫТ-2"),
            cabin("cabin-3", "БЫТ-3"),
          ],
        },
      ],
    })
    const user = userEvent.setup()
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Бытовка БЫТ-1" })
    ).toBeTruthy()
    expect(screen.queryByText("Желаемая дата получения")).toBeNull()
    expect(screen.queryByText("Срок аренды")).toBeNull()
    expect(screen.queryByLabelText("Адрес доставки")).toBeNull()
    expect(screen.queryByLabelText("Координаты")).toBeNull()
    expect(screen.queryByText("Дополнительные контакты заказа")).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Далее: дата, срок и доставка" })
    ).toBeNull()
    expect(screen.getByText("Условия текущего заказа")).toBeTruthy()
    expect(screen.getByText("2026-08-11")).toBeTruthy()

    const choose = screen.getAllByRole("button", { name: "Выбрать" })
    await user.click(choose[1])
    await user.click(choose[0])
    await user.click(choose[2])
    expect(screen.getByText(/можно выбрать ровно 2/)).toBeTruthy()
    expect(
      screen.getAllByRole("button", { name: "Добавить мебель" })[0]
    ).toHaveProperty("disabled", true)

    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))
    await waitFor(() => expect(api.confirm).toHaveBeenCalledOnce())
    expect(api.confirm.mock.calls[0][0].selections).toEqual([
      { rentalItemId: "cabin-2", equipment: [] },
      { rentalItemId: "cabin-1", equipment: [] },
    ])
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty(
      "desiredDeliveryWindows"
    )
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty("rentalMonths")
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty("deliveryAddress")
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty("latitude")
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty("longitude")
    expect(api.confirm.mock.calls[0][0]).not.toHaveProperty(
      "additionalContacts"
    )
  })

  it("moves normal selection to a date-only details step and submits its exact payload", async () => {
    const user = userEvent.setup()
    renderPage()

    expect(
      await screen.findByRole("button", {
        name: "Далее: дата, срок и доставка",
      })
    ).toHaveProperty("disabled", true)
    expect(
      screen.queryByRole("button", { name: "Подтвердить выбор" })
    ).toBeNull()
    expect(
      screen.queryByLabelText("Календарь выбора желаемой даты получения")
    ).toBeNull()
    expect(screen.queryByLabelText("Адрес доставки")).toBeNull()

    await openNormalDetails(user)

    expect(screen.getByText("Выбранные бытовки и наполнение")).toBeTruthy()
    expect(screen.getByText("Бытовка БЫТ-1")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Добавить мебель" })).toBeNull()
    expect(document.querySelector('input[type="time"]')).toBeNull()
    expect(document.querySelector('input[type="number"]')).toBeNull()
    expect(screen.queryByText(/время|циферблат|час/i)).toBeNull()
    expect(
      screen.getByRole("button", { name: "Подтвердить выбор" })
    ).toHaveProperty("disabled", true)

    const desiredDate = await fillNormalDetails(user)
    expect(
      screen.getByRole("button", { name: "Уменьшить срок аренды" })
    ).toHaveProperty("disabled", true)
    expect(
      screen.getByRole("button", { name: "Подтвердить выбор" })
    ).toHaveProperty("disabled", false)
    expect(screen.getByText("1 месяц")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Увеличить срок аренды" })
    )
    expect(screen.getByText("2 месяца")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))

    await waitFor(() => expect(api.confirm).toHaveBeenCalledOnce())
    expect(api.confirm.mock.calls[0][0]).toMatchObject({
      desiredDeliveryWindows: [
        {
          startDate: desiredDate,
          endDate: desiredDate,
        },
      ],
      rentalMonths: 2,
      deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
      additionalContacts: [],
    })
    expect(
      api.confirm.mock.calls[0][0].desiredDeliveryWindows[0]
    ).not.toHaveProperty("timeFrom")
    expect(api.confirm.mock.calls[0][0].idempotencyKey).toEqual(
      expect.any(String)
    )
  }, 15_000)

  it("accepts a complete coordinate pair and maps optional contacts from details", async () => {
    const user = userEvent.setup()
    renderPage()

    await openNormalDetails(user)
    await fillNormalDetails(user)
    const coordinates = screen.getByLabelText("Координаты")
    await user.type(coordinates, "59.9343")
    expect(
      screen.getByText("Укажите широту и долготу в формате «55.75, 37.61».")
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Подтвердить выбор" })
    ).toHaveProperty("disabled", true)
    await user.clear(coordinates)
    await user.type(coordinates, "59.9343, 30.3351")

    await user.click(screen.getByRole("button", { name: "Добавить контакт" }))
    await user.type(screen.getByLabelText("Имя"), "  Иван Петров  ")
    await user.type(screen.getByLabelText("Телефон"), "+79990000000")
    expect(
      screen.getByRole("button", { name: "Подтвердить выбор" })
    ).toHaveProperty("disabled", false)

    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))
    await waitFor(() => expect(api.confirm).toHaveBeenCalledOnce())
    expect(api.confirm.mock.calls[0][0]).toMatchObject({
      latitude: 59.9343,
      longitude: 30.3351,
      additionalContacts: [{ name: "Иван Петров", phone: "+79990000000" }],
    })
  }, 15_000)

  it("preserves date, duration, selection and furniture when returning to step one", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Выбрать" }))[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Добавить мебель" })[0]
    )
    const furnitureDialog = screen.getByRole("dialog", {
      name: "Добавить мебель в бытовку БЫТ-1",
    })
    await user.click(
      within(furnitureDialog).getByRole("button", {
        name: "Увеличить количество: Кровать",
      })
    )
    await user.click(
      within(furnitureDialog).getByRole("button", { name: "Готово" })
    )
    await user.click(
      screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
    )
    await fillNormalDetails(user)
    await user.click(
      screen.getByRole("button", { name: "Увеличить срок аренды" })
    )

    await user.click(screen.getByRole("button", { name: "Назад к выбору" }))
    expect(screen.getByRole("button", { name: "Убрать выбор" })).toBeTruthy()
    await user.click(
      screen.getAllByRole("button", { name: "Добавить мебель" })[0]
    )
    expect(screen.getByLabelText("Количество: Кровать").textContent).toBe("1")
    await user.click(screen.getByRole("button", { name: "Готово" }))

    await user.click(
      screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
    )
    expect(screen.getAllByText(/^Выбрано:/)).toHaveLength(2)
    expect(screen.getByText("2 месяца")).toBeTruthy()
    expect(screen.getByLabelText("Адрес доставки")).toHaveProperty(
      "value",
      "Санкт-Петербург, Невский проспект, 1"
    )
  }, 15_000)

  it("changes confirmation idempotency when the selected rental duration changes", async () => {
    api.confirm
      .mockRejectedValueOnce(new ApiError("Повторите подтверждение", 500))
      .mockResolvedValueOnce(completedBooking)
    const user = userEvent.setup()
    renderPage()

    await openNormalDetails(user)
    await fillNormalDetails(user)
    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))
    expect(await screen.findByText("Повторите подтверждение")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Вернуться" }))

    await user.click(
      screen.getByRole("button", { name: "Увеличить срок аренды" })
    )
    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))

    await waitFor(() => expect(api.confirm).toHaveBeenCalledTimes(2))
    expect(api.confirm.mock.calls[0][0]).toMatchObject({ rentalMonths: 1 })
    expect(api.confirm.mock.calls[1][0]).toMatchObject({ rentalMonths: 2 })
    expect(api.confirm.mock.calls[1][0].idempotencyKey).not.toBe(
      api.confirm.mock.calls[0][0].idempotencyKey
    )
  }, 15_000)

  it("refetches a 409 and keeps the furniture draft visible", async () => {
    api.confirm.mockImplementation(async () => {
      api.presentation = presentation({
        equipmentAvailability: [
          {
            equipmentId: "bed",
            equipmentName: "Кровать",
            availableQuantity: 0,
            maximumPerCabin: 4,
          },
        ],
      })
      throw new ApiError("Остаток изменился", 409)
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Выбрать" }))[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Добавить мебель" })[0]
    )
    const furnitureDialog = screen.getByRole("dialog", {
      name: "Добавить мебель в бытовку БЫТ-1",
    })
    const increment = within(furnitureDialog).getByRole("button", {
      name: "Увеличить количество: Кровать",
    })
    await user.click(increment)
    await user.click(increment)
    await user.click(increment)
    await user.click(
      within(furnitureDialog).getByRole("button", { name: "Готово" })
    )
    await user.click(
      screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
    )
    await fillNormalDetails(user)
    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))

    expect(await screen.findByText("Остаток изменился")).toBeTruthy()
    await waitFor(() => expect(api.get.mock.calls.length).toBeGreaterThan(1))
    expect(
      await screen.findByText("Доступность мебели изменилась")
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Вернуться" }))
    await user.click(screen.getByRole("button", { name: "Назад к выбору" }))
    expect(
      screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
    ).toHaveProperty("disabled", true)
    await user.click(
      screen.getAllByRole("button", { name: "Добавить мебель" })[0]
    )
    expect(screen.getByLabelText("Количество: Кровать").textContent).toBe("3")
  }, 20_000)

  it("treats a permanent rejection as terminal and asks for an updated link", async () => {
    api.confirm.mockResolvedValue({
      bookingId: "booking-rejected",
      state: "REJECTED",
      orderId: null,
      statusPath: "/status",
      errorCode: "EQUIPMENT_UNAVAILABLE",
    })
    const user = userEvent.setup()
    renderPage()

    await openNormalDetails(user)
    await fillNormalDetails(user)
    await user.click(screen.getByRole("button", { name: "Подтвердить выбор" }))
    await user.click(screen.getByRole("button", { name: "Подтвердить" }))

    expect(
      await screen.findByText(
        "Выбор больше нельзя подтвердить. Попросите менеджера отправить обновлённую ссылку."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Подтвердить выбор" })
    ).toBeNull()
  }, 15_000)
})
