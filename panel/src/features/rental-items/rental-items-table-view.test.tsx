import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  ContentsCell,
  ExpandableTextCell,
  RentalItemsTableView,
} from "@/features/rental-items/rental-items-table-view"
import type {
  RentalItemDto,
  RentalItemsColumnConfig,
  RentalItemsTableSchema,
} from "@/features/rental-items/model/rental-item"
import {
  buildRentalItemsTableSchema,
  normalizeRentalItemsColumnConfig,
} from "@/features/rental-items/model/rental-item"

afterEach(cleanup)

function rentalItem(
  contentsItems: RentalItemDto["contentsItems"]
): RentalItemDto {
  return {
    id: "cabin-1",
    version: 1,
    warehouseId: "warehouse-1",
    number: "БЫТ-001",
    rentalTypeId: "type-1",
    dimensionId: "dimension-1",
    finishingId: "finishing-1",
    type: "БК-1",
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: [
      { id: "characteristic-window", name: "Пластиковое окно" },
    ],
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    contents: null,
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

const numberColumn: RentalItemsColumnConfig = {
  id: "number",
  label: "Номер",
  visible: true,
  locked: true,
  searchable: true,
  dataType: "text",
}

const tableSchema: RentalItemsTableSchema = {
  columns: [numberColumn],
  filters: [],
  searchableFieldIds: ["number"],
}

const photosColumn: RentalItemsColumnConfig = {
  id: "hasPhotos",
  label: "Фото",
  visible: true,
  locked: false,
  searchable: false,
  dataType: "photos",
}

const photosTableSchema: RentalItemsTableSchema = {
  columns: [photosColumn],
  filters: [],
  searchableFieldIds: [],
}

const characteristicsColumn: RentalItemsColumnConfig = {
  id: "characteristics",
  label: "Характеристики",
  visible: true,
  locked: false,
  searchable: true,
  dataType: "text",
}

const characteristicsTableSchema: RentalItemsTableSchema = {
  columns: [characteristicsColumn],
  filters: [],
  searchableFieldIds: ["characteristics"],
}

describe("rental items table cells", () => {
  it("opens the full text by click and keyboard without bubbling to the row", async () => {
    const user = userEvent.setup()
    const onRowDoubleClick = vi.fn()
    const value =
      "Пластиковое окно, металлическая дверь и длинное описание комплектации"

    render(
      <div onDoubleClick={onRowDoubleClick}>
        <ExpandableTextCell value={value} label="Характеристики" />
      </div>
    )

    const trigger = screen.getByRole("button", {
      name: `Характеристики: ${value}. Показать полностью`,
    })
    expect(trigger.className).toContain("truncate")

    await user.click(trigger)
    expect(
      document.querySelector('[data-slot="popover-content"]')?.textContent
    ).toBe(value)

    await user.keyboard("{Escape}")
    expect(document.querySelector('[data-slot="popover-content"]')).toBeNull()

    trigger.focus()
    await user.keyboard("{Enter}")
    expect(
      document.querySelector('[data-slot="popover-content"]')?.textContent
    ).toBe(value)

    fireEvent.doubleClick(trigger)
    expect(onRowDoubleClick).not.toHaveBeenCalled()
  })

  it("keeps an empty value as a noninteractive dash", () => {
    render(<ExpandableTextCell value="—" label="Комментарий" />)

    expect(screen.getByText("—")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("keeps empty contents read-only when the user cannot manage balances", () => {
    render(<ContentsCell item={rentalItem([])} />)

    expect(screen.getByText("Не указано")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("shows contents read-only when the user cannot manage balances", async () => {
    const user = userEvent.setup()

    render(<ContentsCell item={rentalItem([{ name: "Стол", quantity: 2 }])} />)

    await user.click(screen.getByRole("button", { name: "Стол 2 шт." }))

    expect(screen.queryByText(/Изменение наполнения будет доступно/)).toBeNull()
    expect(screen.queryByRole("button", { name: /^Переместить/ })).toBeNull()
  })

  it("opens the transferred add and move actions for MANAGE access", async () => {
    const user = userEvent.setup()
    const onAddContents = vi.fn()
    const onMoveToRentalItem = vi.fn()
    const onMoveToStock = vi.fn()
    const { rerender } = render(
      <ContentsCell
        item={rentalItem([])}
        canManageContents
        onAddContents={onAddContents}
      />
    )

    await user.click(screen.getByRole("button", { name: "Добавить" }))
    expect(onAddContents).toHaveBeenCalledOnce()

    rerender(
      <ContentsCell
        item={rentalItem([{ name: "Стол", quantity: 2 }])}
        canManageContents
        onMoveToRentalItem={onMoveToRentalItem}
        onMoveToStock={onMoveToStock}
      />
    )
    await user.click(screen.getByRole("button", { name: "Стол 2 шт." }))
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )
    expect(onMoveToStock).toHaveBeenCalledOnce()
  })
})

describe("rental items table actions", () => {
  it("keeps characteristics default-visible when the asset response contains an array", () => {
    const schema = buildRentalItemsTableSchema([rentalItem([])])
    const columns = normalizeRentalItemsColumnConfig(schema.columns, [])

    expect(schema.columns).toContainEqual(
      expect.objectContaining({
        id: "characteristics",
        label: "Характеристики",
        visible: true,
      })
    )
    expect(columns).toContainEqual(
      expect.objectContaining({ id: "characteristics", visible: true })
    )
  })

  it("keeps the full-height table grid and its header when there are no cabins", () => {
    const { container } = render(
      <RentalItemsTableView
        schema={tableSchema}
        items={[]}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenPhotos={vi.fn()}
        onOpenItem={vi.fn()}
        columnsConfig={[numberColumn]}
        mediaCovers={new Map()}
      />
    )

    const grid = container.querySelector<HTMLElement>(
      '[data-slot="rental-items-table-grid"]'
    )
    expect(grid).not.toBeNull()
    expect(screen.getByRole("columnheader", { name: "Номер" })).toBeTruthy()
    expect(grid?.className).toContain("flex-1")
    expect(grid?.querySelector("table")?.className).not.toContain("min-h-full")
    expect(screen.queryByText("Бытовки не найдены.")).toBeNull()
    expect(grid?.querySelector("tbody")?.children).toHaveLength(0)
  })

  it("keeps a single filtered cabin at the standard row height", () => {
    const { container } = render(
      <RentalItemsTableView
        schema={tableSchema}
        items={[rentalItem([])]}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenPhotos={vi.fn()}
        onOpenItem={vi.fn()}
        columnsConfig={[numberColumn]}
        mediaCovers={new Map()}
      />
    )

    const grid = container.querySelector<HTMLElement>(
      '[data-slot="rental-items-table-grid"]'
    )
    const resultRow = grid?.querySelector("tbody tr")

    expect(grid?.className).toContain("flex-1")
    expect(grid?.querySelector("table")?.className).not.toContain("min-h-full")
    expect(resultRow?.className).toContain("h-[49px]")
    expect(screen.getByText("БЫТ-001")).toBeTruthy()
  })

  it("renders characteristics as neutral wrapping tags instead of one text line", () => {
    render(
      <RentalItemsTableView
        schema={characteristicsTableSchema}
        items={[
          {
            ...rentalItem([]),
            characteristics: [
              { id: "characteristic-window", name: "Пластиковое окно" },
              {
                id: "characteristic-electricity-uzo",
                name: "Электрика КК + УЗО",
              },
              {
                id: "characteristic-electricity-meter",
                name: "Электрика КК + счетчик",
              },
            ],
          },
        ]}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenPhotos={vi.fn()}
        onOpenItem={vi.fn()}
        columnsConfig={[characteristicsColumn]}
        mediaCovers={new Map()}
      />
    )

    const plasticWindow = screen.getByText("Пластиковое окно")
    const characteristics = plasticWindow.parentElement

    expect(plasticWindow.className).toContain("rounded-full")
    expect(plasticWindow.className).toContain("bg-secondary")
    expect(characteristics?.className).toContain("flex-wrap")
    expect(characteristics?.parentElement?.className).toContain("items-center")
    expect(characteristics?.parentElement?.className).toContain("min-h-[33px]")
    expect(plasticWindow.closest("td")?.className).toContain("align-middle")
    expect(screen.getByText("Электрика КК + УЗО")).toBeTruthy()
    expect(screen.getByText("Электрика КК + счетчик")).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: /Показать полностью/ })
    ).toBeNull()
  })

  it("distinguishes an unavailable photo service from an empty archive", () => {
    const commonProps = {
      schema: photosTableSchema,
      items: [rentalItem([])],
      sorting: [],
      onSortingChange: vi.fn(),
      onOpenPhotos: vi.fn(),
      onOpenItem: vi.fn(),
      columnsConfig: [photosColumn],
      mediaCovers: new Map(),
    }
    const view = render(
      <RentalItemsTableView {...commonProps} coverAvailability="unavailable" />
    )

    expect(screen.getByText("Сервис фото недоступен")).toBeTruthy()

    view.rerender(
      <RentalItemsTableView {...commonProps} coverAvailability="available" />
    )
    expect(screen.getByText("Нет фото")).toBeTruthy()
  })

  it("keeps the warehouse table unchanged when no item actions are provided", () => {
    render(
      <RentalItemsTableView
        schema={tableSchema}
        items={[rentalItem([])]}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenPhotos={vi.fn()}
        onOpenItem={vi.fn()}
        columnsConfig={[numberColumn]}
        mediaCovers={new Map()}
      />
    )

    expect(screen.getByRole("columnheader", { name: "Номер" })).toBeTruthy()
    expect(screen.queryByRole("columnheader", { name: "Действия" })).toBeNull()
  })

  it("renders optional row actions without opening the warehouse item", async () => {
    const user = userEvent.setup()
    const onOpenItem = vi.fn()

    render(
      <RentalItemsTableView
        schema={tableSchema}
        items={[rentalItem([])]}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenPhotos={vi.fn()}
        onOpenItem={onOpenItem}
        columnsConfig={[numberColumn]}
        mediaCovers={new Map()}
        renderItemActions={(item) => (
          <button type="button">Добавить {item.number}</button>
        )}
      />
    )

    expect(screen.getByRole("columnheader", { name: "Действия" })).toBeTruthy()

    await user.dblClick(
      screen.getByRole("button", { name: "Добавить БЫТ-001" })
    )

    expect(onOpenItem).not.toHaveBeenCalled()
  })
})
