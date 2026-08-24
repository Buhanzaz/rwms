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
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

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

const REQUESTABLE_DELIVERY_DATES = [
  "2026-08-25",
  "2026-08-26",
  "2026-08-27",
  "2026-08-28",
]

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

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
    requestableDeliveryDates: REQUESTABLE_DELIVERY_DATES,
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

function calendarDateFromButton(day: HTMLButtonElement) {
  const localized = day.dataset.day
  if (!localized) throw new Error("У дня календаря нет локальной даты.")
  const [dayValue, monthValue, yearValue] = localized.split(".")
  return `${yearValue}-${monthValue}-${dayValue}`
}

function calendarDayButtons() {
  const calendar = screen.getByLabelText(
    "Календарь выбора желаемой даты получения"
  )
  return Array.from(
    calendar.querySelectorAll<HTMLButtonElement>("button[data-day]")
  )
}

async function chooseCalendarDates(
  user: ReturnType<typeof userEvent.setup>,
  indexes: number[] = [0]
) {
  const selectedDates = indexes.map((index) => {
    const date = api.presentation?.requestableDeliveryDates[index]
    if (!date) throw new Error("Сервер не предложил нужную тестовую дату.")
    return date
  })
  for (const date of selectedDates) {
    const day = calendarDayButtons().find(
      (candidate) => calendarDateFromButton(candidate) === date
    )
    if (!day) throw new Error("Выбранный день исчез из календаря.")
    await user.click(day)
  }
  return selectedDates
}

async function openNormalDetails(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    await screen.findByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-1",
    })
  )
  await user.click(
    screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
  )
}

async function fillNormalDetails(
  user: ReturnType<typeof userEvent.setup>,
  calendarDayIndexes: number[] = [0]
) {
  const dates = await chooseCalendarDates(user, calendarDayIndexes)
  await user.type(
    screen.getByLabelText("Адрес доставки"),
    "Санкт-Петербург, Невский проспект, 1"
  )
  return dates
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
  it("uses labelled cabin checkboxes and keeps the sticky safe-area layout", async () => {
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Бытовка БЫТ-1" })
    ).toBeTruthy()
    expect(
      screen.getAllByRole("checkbox", { name: /Выбрать бытовку/ })
    ).toHaveLength(2)
    expect(screen.queryByRole("button", { name: "Выбрать" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Добавить наполнение" })
    ).toBeNull()
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

  it("adds unique filling positions in a compact capacity-bound dialog", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-1",
      })
    )
    const addFilling = screen.getAllByRole("button", {
      name: "Добавить наполнение",
    })
    expect(addFilling).toHaveLength(1)
    expect(addFilling[0]).toHaveProperty("disabled", false)
    await user.click(addFilling[0])
    const dialog = screen.getByRole("dialog", {
      name: "Добавить наполнение в бытовку БЫТ-1",
    })
    expect(dialog.className).toContain("max-h-[calc(100svh-2rem)]")
    expect(dialog.className).toContain("overflow-y-auto")
    expect(dialog.className).toContain("sm:max-w-xl")

    const type = within(dialog).getByLabelText("Тип наполнения")
    await user.click(type)
    expect(
      await screen.findByRole("option", { name: "Кровать: доступно — 4" })
    ).toBeTruthy()
    expect(
      await screen.findByRole("option", { name: "Стол: доступно — 1" })
    ).toBeTruthy()
    await user.click(
      await screen.findByRole("option", { name: "Кровать: доступно — 4" })
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Добавить наполнение" })
    )
    const increment = within(dialog).getByRole("button", {
      name: "Увеличить количество: Кровать",
    })
    await user.click(increment)
    await user.click(increment)
    await user.click(increment)
    expect(
      within(dialog).getByLabelText("Количество: Кровать").textContent
    ).toBe("4")
    expect(within(dialog).getByText("Доступно: 4 шт.")).toBeTruthy()
    expect(increment).toHaveProperty("disabled", true)

    await user.click(type)
    expect(
      screen.queryByRole("option", { name: "Кровать: доступно — 4" })
    ).toBeNull()
    await user.click(
      await screen.findByRole("option", { name: "Стол: доступно — 1" })
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Добавить наполнение" })
    )
    expect(within(dialog).getByLabelText("Количество: Стол").textContent).toBe(
      "1"
    )
    await user.click(
      within(dialog).getByRole("button", {
        name: "Уменьшить количество: Стол",
      })
    )
    expect(within(dialog).queryByLabelText("Количество: Стол")).toBeNull()
    expect(increment).toHaveProperty("disabled", true)
    await user.click(within(dialog).getByRole("button", { name: "Готово" }))
  })

  it("requires two replacements and preserves the client click order", async () => {
    api.presentation = presentation({
      mode: "REPLACEMENT",
      requiredSelectionCount: 2,
      requiresDesiredDeliveryWindows: false,
      requestableDeliveryDates: [],
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

    const choose = screen.getAllByRole("checkbox", {
      name: /Выбрать бытовку/,
    })
    await user.click(choose[1])
    await user.click(choose[0])
    await user.click(choose[2])
    expect(screen.getByText(/можно выбрать ровно 2/)).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Добавить наполнение" })
    ).toBeNull()

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

  it("moves normal selection to a multi-date details step and submits sorted date-only payload", async () => {
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
    expect(
      screen.queryByRole("button", { name: "Добавить наполнение" })
    ).toBeNull()
    expect(document.querySelector('input[type="time"]')).toBeNull()
    expect(document.querySelector('input[type="number"]')).toBeNull()
    expect(screen.queryByText(/время|циферблат|час/i)).toBeNull()
    expect(
      screen.getByRole("button", { name: "Подтвердить выбор" })
    ).toHaveProperty("disabled", true)

    const selectedDates = await fillNormalDetails(user, [2, 0])
    expect(screen.getByText("Выбрано дней: 2 из 4.")).toBeTruthy()
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
          startDate: [...selectedDates].sort()[0],
          endDate: [...selectedDates].sort()[0],
        },
        {
          startDate: [...selectedDates].sort()[1],
          endDate: [...selectedDates].sort()[1],
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

  it("enables only the four server-requestable dates and permits deselection", async () => {
    const user = userEvent.setup()
    renderPage()

    await openNormalDetails(user)
    expect(
      screen.getByText(
        "Доступны для запроса и согласования: 25 авг. 2026 г., 26 авг. 2026 г., 27 авг. 2026 г., 28 авг. 2026 г."
      )
    ).toBeTruthy()
    expect(
      screen.getByText(
        "Выбор даты не резервирует логистическую мощность до согласования с логистом."
      )
    ).toBeTruthy()

    const tomorrow = calendarDayButtons().find(
      (day) => calendarDateFromButton(day) === "2026-08-24"
    )
    if (!tomorrow) throw new Error("Календарь не показал завтрашний день.")
    expect(tomorrow).toHaveProperty("disabled", true)
    for (const requestableDate of REQUESTABLE_DELIVERY_DATES) {
      const day = calendarDayButtons().find(
        (candidate) => calendarDateFromButton(candidate) === requestableDate
      )
      if (!day) throw new Error("Календарь не показал разрешённую дату.")
      expect(day).toHaveProperty("disabled", false)
    }
    const outsideWindow = calendarDayButtons().find(
      (day) => calendarDateFromButton(day) === "2026-08-29"
    )
    if (!outsideWindow) throw new Error("Календарь не показал дату вне окна.")
    expect(outsideWindow).toHaveProperty("disabled", true)

    const selectedDates = await chooseCalendarDates(user, [0, 1, 2, 3])
    expect(screen.getByText("Выбрано дней: 4 из 4.")).toBeTruthy()

    const selectedDay = calendarDayButtons().find(
      (day) => calendarDateFromButton(day) === selectedDates[0]
    )
    if (!selectedDay) throw new Error("Календарь не сохранил выбранный день.")
    await user.click(selectedDay)
    expect(screen.getByText("Выбрано дней: 3 из 4.")).toBeTruthy()
    expect(outsideWindow).toHaveProperty("disabled", true)
  })

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

  it("preserves dates, duration, selection and filling when returning to step one", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-1",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Добавить наполнение" })
    )
    const furnitureDialog = screen.getByRole("dialog", {
      name: "Добавить наполнение в бытовку БЫТ-1",
    })
    await user.click(within(furnitureDialog).getByLabelText("Тип наполнения"))
    await user.click(
      await screen.findByRole("option", { name: "Кровать: доступно — 4" })
    )
    await user.click(
      within(furnitureDialog).getByRole("button", {
        name: "Добавить наполнение",
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
    expect(
      screen
        .getByRole("checkbox", { name: "Выбрать бытовку БЫТ-1" })
        .getAttribute("data-state")
    ).toBe("checked")
    await user.click(
      screen.getByRole("button", { name: "Добавить наполнение" })
    )
    expect(screen.getByLabelText("Количество: Кровать").textContent).toBe("1")
    await user.click(screen.getByRole("button", { name: "Готово" }))

    await user.click(
      screen.getByRole("button", { name: "Далее: дата, срок и доставка" })
    )
    expect(screen.getByText("Выбрано дней: 1 из 4.")).toBeTruthy()
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

  it("refetches a 409 and keeps the filling draft visible", async () => {
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
      await screen.findByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-1",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Добавить наполнение" })
    )
    const furnitureDialog = screen.getByRole("dialog", {
      name: "Добавить наполнение в бытовку БЫТ-1",
    })
    await user.click(within(furnitureDialog).getByLabelText("Тип наполнения"))
    await user.click(
      await screen.findByRole("option", { name: "Кровать: доступно — 4" })
    )
    await user.click(
      within(furnitureDialog).getByRole("button", {
        name: "Добавить наполнение",
      })
    )
    const increment = within(furnitureDialog).getByRole("button", {
      name: "Увеличить количество: Кровать",
    })
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
      screen.getByRole("button", { name: "Добавить наполнение" })
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
