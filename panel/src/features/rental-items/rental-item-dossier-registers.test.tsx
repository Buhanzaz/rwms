import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  CabinActivityDto,
  CabinPhotoGroupDto,
} from "@/features/rental-items/dossier/model/rental-item-dossier"
import {
  CharacteristicTags,
  dossierLocalDateKey,
  EmptyDossierRegister,
  HistoryRegister,
  InspectionsRegister,
  PhotosRegister,
} from "@/features/rental-items/rental-item-dossier-registers"
import {
  getEffectiveRentalItemsGridFormat,
  getRentalItemsGridFormatMax,
} from "@/features/rental-items/rental-items-grid-format"
import { DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY } from "@/features/rental-items/rental-items-grid-preferences"
import { smartLocalSearch } from "@/features/rental-items/smart-local-search"

afterEach(() => {
  cleanup()
  window.localStorage.removeItem(DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY)
})

function setViewport(width: number, height = 900) {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: width,
  })
  Object.defineProperty(window, "innerHeight", {
    configurable: true,
    value: height,
  })
  fireEvent(window, new Event("resize"))
}

function photoGroup(
  id: string,
  sourceLabel: string,
  occurredAt: string,
  actor = "Иван Петров"
): CabinPhotoGroupDto {
  return {
    id,
    rentalItemId: "cabin-1",
    activityId: null,
    occurredAt,
    actor: { id: `user-${id}`, displayName: actor },
    sourceType: "MANUAL",
    sourceId: id,
    sourceLabel,
    stage: "GENERAL",
    photos: [
      {
        id: `${id}-photo`,
        occurredAt,
        order: 0,
        stage: "GENERAL",
        processingStatus: "READY",
        variants: {
          thumb: {
            url: `/${id}-thumb.jpg`,
            width: 160,
            height: 160,
            mimeType: "image/jpeg",
          },
          preview: {
            url: `/${id}-preview.jpg`,
            width: 640,
            height: 640,
            mimeType: "image/jpeg",
          },
        },
        originalAvailable: false,
        provenance: {
          sourceType: "MANUAL",
          sourceId: id,
          sourceLabel,
        },
      },
    ],
  }
}

describe("rental item dossier registers", () => {
  it("uses gray tags normally and primary tags for sanitary characteristics", () => {
    render(
      <CharacteristicTags value="Электрика КК, Душевая, Металлическая дверь, кондиционер" />
    )

    expect(screen.getByText("Электрика КК").className).toContain("bg-secondary")
    expect(screen.getByText("Душевая").className).toContain("bg-primary")
    expect(
      screen.getByText("Металлическая дверь, кондиционер").className
    ).toContain("bg-secondary")
  })

  it("shares the warehouse 1/3/5 density caps", () => {
    expect(getRentalItemsGridFormatMax({ width: 390, height: 844 })).toBe(1)
    expect(getRentalItemsGridFormatMax({ width: 900, height: 1000 })).toBe(3)
    expect(getRentalItemsGridFormatMax({ width: 1440, height: 900 })).toBe(5)
    expect(
      getEffectiveRentalItemsGridFormat(5, { width: 900, height: 1000 })
    ).toEqual({ columns: 3, rows: 3 })
    expect(
      getEffectiveRentalItemsGridFormat(5, { width: 390, height: 844 })
    ).toEqual({ columns: 1, rows: 1 })
  })

  it("offers the density picker and displays photo folders below it", () => {
    setViewport(1440)
    const firstRender = render(
      <PhotosRegister
        values={[
          photoGroup("first", "Ручная загрузка", "2026-07-12T08:00:00Z"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    const folders = screen.getByTestId("photo-folder-grid")
    expect(folders.getAttribute("data-columns")).toBe("5")
    expect(screen.getByText("Ручная загрузка")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: /до 5x5/i }))
    const density = screen.getByRole("slider", { name: "Формат сетки" })
    expect(density.getAttribute("aria-valuenow")).toBe("5")
    expect(screen.queryByRole("button", { name: "2 x 2" })).toBeNull()
    fireEvent.keyDown(density, { key: "Home" })
    fireEvent.keyDown(density, { key: "ArrowRight" })
    fireEvent.click(screen.getByRole("button", { name: "Готово" }))
    expect(folders.getAttribute("data-columns")).toBe("2")

    firstRender.unmount()
    render(
      <PhotosRegister
        values={[
          photoGroup("first", "Ручная загрузка", "2026-07-12T08:00:00Z"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )
    expect(
      screen.getByTestId("photo-folder-grid").getAttribute("data-columns")
    ).toBe("2")
  })

  it("fixes phone photos to one full-width column", () => {
    setViewport(390, 844)
    render(
      <PhotosRegister
        values={[
          photoGroup("first", "Ручная загрузка", "2026-07-12T08:00:00Z"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    expect(
      screen.getByTestId("photo-folder-grid").getAttribute("data-columns")
    ).toBe("1")
    expect(screen.queryByRole("button", { name: /до \dx\d/i })).toBeNull()
  })

  it("collapses mobile register controls while keeping search beside the funnel", () => {
    setViewport(390, 844)
    render(
      <InspectionsRegister
        values={[]}
        photoGroups={[]}
        onOpenPhotoGroup={vi.fn()}
      />
    )

    const toggle = screen.getByRole("button", {
      name: "Показать поиск и фильтры",
    })
    const search = screen.getByLabelText("Умный поиск")
    const searchGroup = search.closest('[data-slot="input-group"]')

    expect(toggle.getAttribute("aria-expanded")).toBe("false")
    expect(searchGroup?.className).toContain("hidden")

    fireEvent.click(toggle)

    expect(
      screen
        .getByRole("button", { name: "Скрыть поиск и фильтры" })
        .getAttribute("aria-expanded")
    ).toBe("true")
    expect(searchGroup?.className).not.toContain("hidden")
  })

  it("uses a search row and compact chip filter bar with a date range", () => {
    const { container } = render(
      <PhotosRegister
        values={[
          photoGroup("first", "Ручная загрузка", "2026-07-12T08:00:00.000Z"),
          photoGroup("second", "Осмотр", "2026-07-11T08:00:00.000Z"),
          photoGroup("third", "Ремонт", "2026-07-10T08:00:00.000Z"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    expect(screen.getByLabelText("Умный поиск")).toBeTruthy()
    expect(screen.getByTestId("dossier-filter-bar").className).toContain(
      "border"
    )
    expect(
      screen
        .getByRole("button", { name: "Фильтр Событие: не выбрано" })
        .getAttribute("aria-pressed")
    ).toBe("false")
    expect(
      screen
        .getByRole("button", { name: "Фильтр Автор: не выбрано" })
        .getAttribute("aria-pressed")
    ).toBe("false")

    fireEvent.click(
      screen.getByRole("button", { name: "Фильтр по дате: не выбрана" })
    )
    fireEvent.change(screen.getByLabelText("Дата с"), {
      target: { value: "2026-07-11" },
    })
    expect(screen.queryByText("Ручная загрузка")).toBeNull()
    expect(screen.getAllByText("Осмотр").length).toBeGreaterThan(0)
    expect(screen.queryByText("Ремонт")).toBeNull()

    fireEvent.change(screen.getByLabelText("Дата по"), {
      target: { value: "2026-07-12" },
    })
    expect(screen.getAllByText("Ручная загрузка").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Осмотр").length).toBeGreaterThan(0)
    expect(screen.queryByText("Ремонт")).toBeNull()
    expect(screen.getByLabelText("Дата с").getAttribute("max")).toBe(
      "2026-07-12"
    )
    expect(screen.getByLabelText("Дата по").getAttribute("min")).toBe(
      "2026-07-11"
    )

    const dateFields = container.querySelectorAll('[data-slot="input-group"]')
    expect(
      Array.from(dateFields).some((field) => field.querySelector("svg"))
    ).toBe(true)
  })

  it("smart-searches author, local date and event together", () => {
    render(
      <PhotosRegister
        values={[
          photoGroup(
            "first",
            "Ручная загрузка",
            "2026-07-12T08:00:00Z",
            "Анна Соколова"
          ),
          photoGroup("second", "Осмотр", "2026-07-11T08:00:00Z", "Иван Петров"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    fireEvent.change(screen.getByLabelText("Умный поиск"), {
      target: { value: "петр осмот 11 07 2026" },
    })

    expect(screen.getAllByText("Осмотр").length).toBeGreaterThan(0)
    expect(screen.queryByText("Ручная загрузка")).toBeNull()
  })

  it("combines smart search with explicit multi-value chips", () => {
    render(
      <PhotosRegister
        values={[
          photoGroup(
            "first",
            "Ручная загрузка",
            "2026-07-12T08:00:00Z",
            "Анна Соколова"
          ),
          photoGroup("second", "Осмотр", "2026-07-11T08:00:00Z", "Иван Петров"),
        ]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    fireEvent.change(screen.getByLabelText("Умный поиск"), {
      target: { value: "иван" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "Фильтр Событие: не выбрано" })
    )
    fireEvent.click(screen.getByRole("checkbox", { name: "Ручная загрузка" }))
    fireEvent.click(screen.getByRole("button", { name: "Применить" }))

    expect(
      screen
        .getByRole("button", {
          name: "Фильтр Событие: выбрано 1: Ручная загрузка",
        })
        .getAttribute("aria-pressed")
    ).toBe("true")

    expect(screen.queryByText("Осмотр")).toBeNull()
    expect(screen.getByText("Фотографии не найдены")).toBeTruthy()
  })

  it("normalizes ё/е, allows one typo and keeps equal matches stable", () => {
    const rows = [
      { id: "first", text: "Алёна осмотр" },
      { id: "second", text: "Алена осмотр" },
      { id: "third", text: "Иван ремонт" },
    ]

    expect(
      smartLocalSearch(rows, "алена", (row) => [row.text]).map((row) => row.id)
    ).toEqual(["first", "second"])
    expect(
      smartLocalSearch(rows, "осморт", (row) => [row.text]).map((row) => row.id)
    ).toEqual(["first", "second"])
  })

  it("filters a near-midnight event by the local date shown to the user", () => {
    const occurredAt = "2026-07-11T22:30:00.000Z"
    const visibleLocalDate = dossierLocalDateKey(occurredAt)!
    expect(visibleLocalDate).toMatch(/^2026-07-(11|12)$/)
    expect(dossierLocalDateKey("not-a-date")).toBeNull()

    render(
      <PhotosRegister
        values={[photoGroup("near-midnight", "Ночной осмотр", occurredAt)]}
        onOpen={vi.fn()}
        onAddPhoto={vi.fn()}
      />
    )

    fireEvent.click(
      screen.getByRole("button", { name: "Фильтр по дате: не выбрана" })
    )
    fireEvent.change(screen.getByLabelText("Дата с"), {
      target: { value: visibleLocalDate },
    })

    expect(screen.getAllByText("Ночной осмотр").length).toBeGreaterThan(0)
  })

  it("keeps desktop empty grids and uses only muted text on mobile", () => {
    const { container } = render(
      <EmptyDossierRegister
        title="Резервы"
        description="Раздел пока не реализован."
        columns={["Клиент", "Статус", "Создан"]}
      />
    )

    expect(screen.getByLabelText("Умный поиск")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Фильтр по дате: не выбрана" })
    ).toBeTruthy()
    expect(screen.getByText("Клиент")).toBeTruthy()
    const mobileEmpty = screen.getByText("Резервы не найдены")
    expect(mobileEmpty.className).toContain("text-muted-foreground")
    expect(mobileEmpty.closest('[data-slot="card"]')).toBeNull()
    expect(screen.queryByText("Раздел пока не реализован.")).toBeNull()

    const grid = container.querySelector('[data-slot="operations-list-grid"]')
    expect(grid?.className).toContain("hidden")
    expect(grid?.className).toContain("lg:block")
  })

  it("uses the same text-only mobile empty state for projected registers", () => {
    render(
      <InspectionsRegister
        values={[]}
        photoGroups={[]}
        onOpenPhotoGroup={vi.fn()}
      />
    )

    const empty = screen.getByText("Осмотры не найдены")
    expect(empty.className).toContain("text-muted-foreground")
    expect(empty.closest('[data-slot="card"]')).toBeNull()
    expect(
      screen.queryByText("Подтверждённые осмотры появятся здесь.")
    ).toBeNull()
  })

  it("describes a cleared general comment truthfully in history", () => {
    const activity: CabinActivityDto = {
      id: "comment-cleared",
      rentalItemId: "cabin-1",
      occurredAt: "2026-07-12T08:00:00Z",
      actor: { id: "user-1", displayName: "Иван Петров" },
      type: "GENERAL_COMMENT_UPDATED",
      sourceType: "MANUAL",
      sourceId: "comment-cleared",
      sourceLabel: "Общий комментарий",
      comment: null,
      statusTransition: null,
      parentActivityId: null,
      photoGroupId: null,
      links: [],
    }

    render(
      <HistoryRegister
        values={[activity]}
        photoGroups={[]}
        onOpenPhotoGroup={vi.fn()}
      />
    )

    expect(screen.getAllByText("Комментарий очищен").length).toBeGreaterThan(0)
  })
})
