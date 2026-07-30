import * as React from "react"
import { cleanup, render, screen, within } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  AvailableCabin,
  CabinSearchResult,
} from "@/features/assistant/api/assistant-api"

const coverQueryFixture = vi.hoisted(() => ({
  items: [] as unknown[],
}))

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: { items: coverQueryFixture.items },
    isPending: false,
    isError: false,
  }),
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
    const group = screen.getByRole("button", { name: /4/ })
    expect(
      expand.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(
      screen.queryByRole("region", { name: "Карусель найденных бытовок" })
    ).toBeNull()
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

    expect(screen.getByRole("button", { name: /Новая1/ })).toBeTruthy()
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
      screen.getByRole("button", {
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

    expect(
      screen.getByRole("button", { name: /Обычная или ИТР1/ })
    ).toBeTruthy()
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
})
