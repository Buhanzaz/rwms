import { useRef, useState } from "react"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  WarehouseTransferCabinSelection,
  type WarehouseTransferCabinSlot,
} from "@/features/logistics/warehouse-transfers/warehouse-transfers-page"

const candidates: RentalItemDto[] = [
  {
    id: "cabin-1",
    version: 3,
    warehouseId: "spb",
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: "Стол — 2",
    contentsItems: [{ name: "Стол", quantity: 2 }],
    shipmentDate: null,
    tenant: null,
    price: null,
  },
  {
    id: "cabin-2",
    version: 1,
    warehouseId: "spb",
    number: "БЫТ-002",
    type: "БК-2",
    dimensions: "2.4x6",
    finishing: "ПВХ",
    category: "Стандарт",
    characteristics: null,
    linoleum: false,
    status: "FREE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
  },
]

function Harness() {
  const portalContainer = useRef<HTMLDivElement>(null)
  const [slots, setSlots] = useState<WarehouseTransferCabinSlot[]>([
    { slotId: "slot-1", rentalItemId: "cabin-1" },
  ])
  return (
    <div ref={portalContainer} data-testid="dialog-content">
      <WarehouseTransferCabinSelection
        candidates={candidates}
        isLoading={false}
        slots={slots}
        portalContainer={portalContainer}
        onChange={setSlots}
      />
    </div>
  )
}

describe("warehouse transfer cabin selection", () => {
  it("renders one full-width vertical cabin card with contents and adds cards below", async () => {
    const user = userEvent.setup()
    render(<Harness />)

    expect(screen.getByText("Бытовки и наполнение")).toBeTruthy()
    expect(screen.getByText("Бытовка 1")).toBeTruthy()
    expect(screen.getByText("Стол")).toBeTruthy()
    expect(screen.getByText("2 шт.")).toBeTruthy()
    expect(
      screen.queryByText(
        "Доступны свободные бытовки, склад и собственные нужды."
      )
    ).toBeNull()
    expect(screen.queryByText("Машина")).toBeNull()

    const list = screen.getByTestId("transfer-cabin-list")
    expect(list.className).toContain("flex-col")
    expect(list.className).not.toContain("grid-cols")
    const firstInput = screen.getByRole("combobox", {
      name: "Номер бытовки",
    })
    expect(
      firstInput.closest("[data-slot='input-group']")?.className
    ).toContain("w-full")

    await user.click(firstInput)
    const secondCabinOption = await screen.findByRole("option", {
      name: "БЫТ-002",
    })
    expect(
      screen.getByTestId("dialog-content").contains(secondCabinOption)
    ).toBe(true)
    await user.click(secondCabinOption)
    expect((firstInput as HTMLInputElement).value).toBe("БЫТ-002")

    await user.click(
      screen.getByRole("button", { name: "Добавить ещё бытовку" })
    )
    expect(screen.getByText("Бытовка 2")).toBeTruthy()
    expect(
      screen.getAllByRole("combobox", { name: "Номер бытовки" })
    ).toHaveLength(2)
  })
})
