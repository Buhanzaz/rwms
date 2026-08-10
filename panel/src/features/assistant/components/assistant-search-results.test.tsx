import * as React from "react"
import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  AvailableCabin,
  CabinSearchResult,
} from "@/features/assistant/api/assistant-api"

const coverQueryFixture = vi.hoisted(() => ({
  items: [] as unknown[],
  queryOptions: [] as {
    placeholderData?: (previousData: unknown) => unknown
  }[],
}))

vi.mock("@tanstack/react-query", () => ({
  keepPreviousData: (previousData: unknown) => previousData,
  useQuery: (options: {
    placeholderData?: (previousData: unknown) => unknown
  }) => {
    coverQueryFixture.queryOptions.push(options)
    return {
      data: { items: coverQueryFixture.items },
      isPending: false,
      isError: false,
    }
  },
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({
    title,
    photos,
    placeholder,
  }: {
    title: string
    photos: readonly unknown[]
    placeholder?: React.ReactNode
  }) => (
    <div data-testid={`photo-carousel-${title}`}>
      {photos.length > 0 ? (
        "Фото загружено"
      ) : (
        <>
          {placeholder}
          <span>Фото не загружены.</span>
        </>
      )}
    </div>
  ),
}))

vi.mock("@/components/ui/carousel", () => ({
  Carousel: ({
    children,
    className,
    ...props
  }: React.PropsWithChildren<React.ComponentProps<"div">>) => (
    <div
      data-testid="assistant-carousel"
      role="region"
      className={className}
      {...props}
    >
      {children}
    </div>
  ),
  CarouselContent: ({
    children,
    className,
  }: React.PropsWithChildren<{ className?: string }>) => (
    <div className={className}>{children}</div>
  ),
  CarouselItem: ({
    children,
    className,
  }: React.PropsWithChildren<{ className?: string }>) => (
    <div className={className}>{children}</div>
  ),
  CarouselPrevious: () => (
    <button type="button" aria-label="Предыдущий слайд" />
  ),
  CarouselNext: () => <button type="button" aria-label="Следующий слайд" />,
  CarouselDots: ({ "aria-label": ariaLabel }: { "aria-label": string }) => (
    <div role="group" aria-label={ariaLabel} />
  ),
}))

vi.mock("@/features/rental-items/use-rental-item-covers", () => ({
  loadRentalItemCoverPage: vi.fn(),
  useRentalItemCardPhotos: ({
    projection,
  }: {
    projection?: { previews?: readonly unknown[] }
  }) => ({
    photos:
      projection?.previews?.length === 1
        ? [{ id: "photo-1", url: "blob:photo-1" }]
        : [],
    availability: "available",
  }),
}))

import { AssistantSearchResults } from "@/features/assistant/components/assistant-search-results"

afterEach(() => {
  coverQueryFixture.items = []
  coverQueryFixture.queryOptions = []
  cleanup()
})

function cabin(index: number, category = "Обычная"): AvailableCabin {
  return {
    id: `cabin-${index}`,
    version: 1,
    warehouseId: "warehouse-1",
    number: `БЫТ-${index}`,
    status: "FREE",
    rentalType: `БК-${index}`,
    dimensions: "2x2",
    finishing: "ДВП",
    category,
    characteristics: null,
    linoleum: null,
    passport: {},
    tags: [],
    updatedAt: "2026-07-27T12:00:00Z",
  }
}

function resultWithCabins(count: number): CabinSearchResult {
  return {
    warehouseId: "warehouse-1",
    expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    groups: [
      {
        group: {
          cabinType: null,
          finish: null,
          dimensions: null,
          category: null,
          quantity: count,
        },
        cabins: Array.from({ length: count }, (_, index) => cabin(index + 1)),
      },
    ],
  }
}

function resultWithGroups(): CabinSearchResult {
  return {
    warehouseId: "warehouse-1",
    expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    groups: [
      {
        group: {
          cabinType: "БК-1",
          finish: "ЛДСП",
          dimensions: null,
          category: null,
          quantity: 1,
        },
        cabins: [
          {
            ...cabin(1),
            rentalType: "БК-1",
            finishing: "ЛДСП",
          },
        ],
      },
      {
        group: {
          cabinType: "БК-2",
          finish: "ОСБ",
          dimensions: null,
          category: null,
          quantity: 1,
        },
        cabins: [
          {
            ...cabin(2),
            rentalType: "БК-2",
            finishing: "ОСБ",
          },
        ],
      },
    ],
  }
}

const exactFilterSuggestions = {
  cabinTypes: ["БК-1"],
  finishes: ["ЛДСП"],
  dimensions: ["6x2.4"],
  categories: ["ИТР"],
  characteristics: ["Пластиковое окно"],
}

function renderResults(count: number) {
  return render(
    <MemoryRouter>
      <AssistantSearchResults
        accessToken="token"
        result={resultWithCabins(count)}
        selectedIds={new Set()}
        onSelectionChange={vi.fn()}
      />
    </MemoryRouter>
  )
}

describe("AssistantSearchResults layout", () => {
  it.each([
    [1, "max-w-sm"],
    [2, "max-w-2xl"],
    [3, "md:grid-cols-3"],
    [4, "md:grid-cols-4"],
  ])(
    "shows %i cabins in a centered grid without carousel navigation",
    (count, expectedClass) => {
      renderResults(count)

      const cards = screen.getByRole("list", {
        name: "Карточки найденных бытовок",
      })
      expect(cards.children).toHaveLength(count)
      expect(cards.className).toContain("grid")
      expect(cards.className).toContain(expectedClass)
      expect(
        screen.queryByRole("region", {
          name: "Карусель найденных бытовок",
        })
      ).toBeNull()
      expect(
        screen.queryByRole("button", { name: "Предыдущий слайд" })
      ).toBeNull()
      expect(
        screen.queryByRole("button", { name: "Следующий слайд" })
      ).toBeNull()
      expect(
        screen.queryByRole("group", {
          name: "Навигация по найденным бытовкам",
        })
      ).toBeNull()
    }
  )

  it("shows carousel navigation only after the fourth cabin", () => {
    renderResults(5)

    const carousel = screen.getByRole("region", {
      name: "Карусель найденных бытовок",
    })
    expect(carousel).toBe(screen.getByTestId("assistant-carousel"))
    expect(carousel.getAttribute("data-slot")).toBe(
      "assistant-cabin-search-carousel"
    )
    expect(within(carousel).getAllByRole("article")).toHaveLength(5)
    expect(
      screen.getByRole("button", { name: "Предыдущий слайд" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Следующий слайд" })).toBeTruthy()
    expect(
      screen.getByRole("group", {
        name: "Навигация по найденным бытовкам",
      })
    ).toBeTruthy()
    expect(
      screen.queryByRole("list", {
        name: "Карточки найденных бытовок",
      })
    ).toBeNull()
  })

  it("keeps the group switcher visible and hides only cabin cards when collapsed", () => {
    const result = resultWithCabins(4)

    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          collapsed
          onCollapsedChange={vi.fn()}
        />
      </MemoryRouter>
    )

    const expand = screen.getByRole("button", {
      name: "Развернуть подбор бытовок",
    })
    const group = screen.getByRole("tab", { name: /4/ })
    expect(
      expand.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(
      screen.queryByRole("region", { name: "Карусель найденных бытовок" })
    ).toBeNull()
  })

  it("uses compact rounded group pills without a line underline", () => {
    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={resultWithGroups()}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    const tabs = screen.getByRole("tablist", {
      name: "Группы найденных бытовок",
    })
    const activeGroup = screen.getByRole("tab", { name: /БК-1 · ЛДСП/ })

    expect(tabs.getAttribute("data-variant")).toBe("default")
    expect(tabs.className).toContain("gap-1")
    expect(tabs.className).not.toContain("min-w-full")
    expect(activeGroup.className).toContain("rounded-full")
    expect(activeGroup.className).toContain("bg-muted/70")
    expect(activeGroup.className).toContain("after:hidden")
    expect(activeGroup.getAttribute("data-state")).toBe("active")
  })

  it("keeps the active logical group through a content and expiry refresh", async () => {
    const user = userEvent.setup()
    const initial = resultWithGroups()
    const { rerender } = render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={initial}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    await user.click(screen.getByRole("tab", { name: /БК-2 · ОСБ/ }))
    expect(
      screen.getByRole("tab", { name: /БК-2 · ОСБ/ }).getAttribute("data-state")
    ).toBe("active")
    expect(screen.getByText("Бытовка БЫТ-2")).toBeTruthy()

    const refreshed = resultWithGroups()
    refreshed.expiresAt = new Date(Date.now() + 20 * 60_000).toISOString()
    refreshed.groups[1] = {
      ...refreshed.groups[1],
      cabins: [
        {
          ...refreshed.groups[1].cabins[0],
          number: "БЫТ-2А",
        },
      ],
    }
    rerender(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={refreshed}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(
      screen.getByRole("tab", { name: /БК-2 · ОСБ/ }).getAttribute("data-state")
    ).toBe("active")
    expect(screen.getByText("Бытовка БЫТ-2А")).toBeTruthy()
  })

  it("keeps cards with canonical covers visible when another cabin has no cover projection", () => {
    const cover = {
      mediaId: "media-1",
      generation: 1,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath: "/api/media/v1/assets/media-1/variants/SMALL/content",
      width: 360,
      height: 240,
    }
    coverQueryFixture.items = [
      {
        cabinId: "cabin-1",
        photoCount: 1,
        cover,
        previews: [cover],
      },
    ]

    renderResults(2)

    expect(
      within(screen.getByTestId("photo-carousel-Бытовка БЫТ-1")).getByText(
        "Фото загружено"
      )
    ).toBeTruthy()
    expect(
      within(screen.getByTestId("photo-carousel-Бытовка БЫТ-2")).getByText(
        "Фото не загружены."
      )
    ).toBeTruthy()
    expect(screen.getAllByRole("article")).toHaveLength(2)
  })

  it("keeps prior cover data while loading a changed candidate list without applying it to another cabin", () => {
    const cover = {
      mediaId: "media-1",
      generation: 1,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath: "/api/media/v1/assets/media-1/variants/SMALL/content",
      width: 360,
      height: 240,
    }
    coverQueryFixture.items = [
      {
        cabinId: "cabin-1",
        photoCount: 1,
        cover,
        previews: [cover],
      },
    ]
    const initial = resultWithCabins(1)
    const { rerender } = render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={initial}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )
    const previousData = { items: coverQueryFixture.items }

    expect(
      coverQueryFixture.queryOptions.at(-1)?.placeholderData?.(previousData)
    ).toBe(previousData)
    expect(
      within(screen.getByTestId("photo-carousel-Бытовка БЫТ-1")).getByText(
        "Фото загружено"
      )
    ).toBeTruthy()

    const changedCandidates = resultWithCabins(1)
    changedCandidates.groups[0].cabins = [cabin(2)]
    rerender(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={changedCandidates}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(
      within(screen.getByTestId("photo-carousel-Бытовка БЫТ-2")).getByText(
        "Фото не загружены."
      )
    ).toBeTruthy()
  })

  it("opens the cabin from the whole information area and keeps status there", () => {
    renderResults(1)

    const detailsLink = screen.getByRole("link", {
      name: "Открыть бытовку БЫТ-1",
    })
    expect(detailsLink.getAttribute("href")).toBe("/warehouse/cabin-1")
    expect(within(detailsLink).getByText("Бытовка БЫТ-1")).toBeTruthy()
    expect(within(detailsLink).getByText("Свободна")).toBeTruthy()
  })

  it("renders a new category cabin with the free availability badge", () => {
    const result = resultWithCabins(1)
    result.groups[0].cabins = [cabin(1, "Новая")]

    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    const detailsLink = screen.getByRole("link", {
      name: "Открыть бытовку БЫТ-1",
    })
    expect(within(detailsLink).getByText(/Новая/)).toBeTruthy()
    expect(within(detailsLink).getByText("Свободна")).toBeTruthy()
  })

  it("marks a new-category selection group clearly", () => {
    const result = resultWithCabins(1)
    result.groups[0].group = {
      ...result.groups[0].group,
      category: "Новая",
    }

    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(screen.getByRole("tab", { name: /Новая1/ })).toBeTruthy()
  })

  it("shows characteristics and the linoleum requirement in a group label", () => {
    const result = resultWithCabins(1)
    result.groups[0].group = {
      ...result.groups[0].group,
      cabinType: "БК-1",
      finish: "ДВП",
      characteristics: "Пластиковое окно",
      linoleum: false,
    }

    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(
      screen.getByRole("tab", {
        name: /БК-1 · ДВП · Пластиковое окно · Без линолеума1/,
      })
    ).toBeTruthy()
  })

  it("shows exact OR categories from a structured search notice/result group", () => {
    const result = resultWithCabins(1)
    result.groups[0].group = {
      ...result.groups[0].group,
      category: null,
      categories: ["Обычная", "ИТР"],
    }

    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(screen.getByRole("tab", { name: /Обычная или ИТР1/ })).toBeTruthy()
  })

  it("puts its footer immediately after cabin results and hides it without cabins", () => {
    const { rerender } = render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={resultWithCabins(1)}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          footer={
            <>
              <span>Выбрано: 0</span>
              <button type="button" disabled>
                Создать представление для клиента
              </button>
            </>
          }
        />
      </MemoryRouter>
    )

    const cards = screen.getByRole("list", {
      name: "Карточки найденных бытовок",
    })
    const createButton = screen.getByRole("button", {
      name: "Создать представление для клиента",
    })
    const footer = createButton.closest(
      '[data-slot="assistant-search-results-footer"]'
    )
    expect(cards.compareDocumentPosition(createButton)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING
    )
    expect(footer?.className).toContain("justify-center")
    expect((createButton as HTMLButtonElement).disabled).toBe(true)

    rerender(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={{
            ...resultWithCabins(0),
            groups: [
              {
                ...resultWithCabins(0).groups[0],
                cabins: [],
              },
            ],
          }}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          footer={
            <button type="button">Создать представление для клиента</button>
          }
        />
      </MemoryRouter>
    )

    expect(
      screen.queryByRole("button", {
        name: "Создать представление для клиента",
      })
    ).toBeNull()
  })

  it("keeps exact filters hidden until the manager explicitly opens them", async () => {
    const onSuggestion = vi.fn()
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={resultWithCabins(1)}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          filterSuggestions={exactFilterSuggestions}
          onSuggestion={onSuggestion}
        />
      </MemoryRouter>
    )

    const toggle = screen.getByRole("button", {
      name: "Продолжить точный поиск",
    })
    expect(toggle.getAttribute("aria-expanded")).toBe("false")
    expect(screen.queryByRole("radio", { name: "Пластиковое окно" })).toBeNull()

    await user.click(toggle)
    expect(
      screen
        .getByRole("button", { name: "Продолжить точный поиск" })
        .getAttribute("aria-expanded")
    ).toBe("true")
    await user.click(screen.getByRole("radio", { name: "Пластиковое окно" }))
    expect(onSuggestion).toHaveBeenCalledWith(
      "Уточни выборку: характеристика «Пластиковое окно»."
    )
    await user.click(screen.getByRole("radio", { name: "Есть" }))
    expect(onSuggestion).toHaveBeenCalledWith(
      "Уточни выборку: только бытовки с линолеумом."
    )
    expect(screen.queryByText("Несуществующий тип")).toBeNull()
  })

  it("persists the manager's exact-filter open and collapsed choices through result rerenders", async () => {
    const user = userEvent.setup()
    const initial = resultWithCabins(1)
    const { rerender } = render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={initial}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          filterSuggestions={exactFilterSuggestions}
          onSuggestion={vi.fn()}
        />
      </MemoryRouter>
    )

    const toggle = screen.getByRole("button", {
      name: "Продолжить точный поиск",
    })
    await user.click(toggle)
    const refreshed = {
      ...initial,
      expiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
    }
    rerender(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={refreshed}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          filterSuggestions={exactFilterSuggestions}
          onSuggestion={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(
      screen
        .getByRole("button", { name: "Продолжить точный поиск" })
        .getAttribute("aria-expanded")
    ).toBe("true")
    expect(screen.getByRole("radio", { name: "Пластиковое окно" })).toBeTruthy()

    await user.click(toggle)
    rerender(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={{
            ...refreshed,
            expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
          }}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
          filterSuggestions={exactFilterSuggestions}
          onSuggestion={vi.fn()}
        />
      </MemoryRouter>
    )

    expect(
      screen
        .getByRole("button", { name: "Продолжить точный поиск" })
        .getAttribute("aria-expanded")
    ).toBe("false")
    expect(screen.queryByRole("radio", { name: "Пластиковое окно" })).toBeNull()
  })

  it("emits the complete remaining ID set when a selected cabin is unchecked", async () => {
    const onSelectionChange = vi.fn()
    const user = userEvent.setup()
    const result = resultWithCabins(2)
    render(
      <MemoryRouter>
        <AssistantSearchResults
          accessToken="token"
          result={result}
          selectedIds={new Set(["cabin-1", "cabin-2"])}
          onSelectionChange={onSelectionChange}
        />
      </MemoryRouter>
    )

    await user.click(
      screen.getByRole("checkbox", { name: "Выбрать бытовку БЫТ-1" })
    )
    expect(onSelectionChange).toHaveBeenCalledOnce()
    expect([...onSelectionChange.mock.calls[0][0]]).toEqual(["cabin-2"])
  })
})
