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

afterEach(cleanup)

function rentalItem(
  contentsItems: RentalItemDto["contentsItems"]
): RentalItemDto {
  return {
    id: "cabin-1",
    version: 1,
    warehouseId: "warehouse-1",
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: "Пластиковое окно",
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
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
