import { render, screen, within } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { InventoryMembershipMovements } from "@/features/inventory/inventory-membership-movements"
import type { InventoryMembershipMovementDto } from "@/features/inventory/model/inventory"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

const movements: InventoryMembershipMovementDto[] = [
  {
    id: "movement-departed",
    type: "DEPARTED",
    assetId: "asset-1",
    displayCanonicalNumber: "БЫТ-001",
    origin: "EXPECTED",
    fromWarehouseId: WAREHOUSE_ID,
    toWarehouseId: null,
    status: "RENTED",
    tenantSnapshot: "ООО Арендатор",
    occurredAt: "2026-07-27T08:10:00Z",
  },
  {
    id: "movement-transferred",
    type: "TRANSFERRED",
    assetId: "asset-2",
    displayCanonicalNumber: "БЫТ-002",
    origin: "UNEXPECTED_EXISTING",
    fromWarehouseId: WAREHOUSE_ID,
    toWarehouseId: "22222222-2222-4222-8222-222222222222",
    status: "IN_TRANSFER",
    tenantSnapshot: null,
    occurredAt: "2026-07-27T09:10:00Z",
  },
  {
    id: "movement-arrived",
    type: "ARRIVED",
    assetId: "asset-3",
    displayCanonicalNumber: "БЫТ-003",
    origin: "ADDED_USED",
    fromWarehouseId: null,
    toWarehouseId: WAREHOUSE_ID,
    status: "WAREHOUSE",
    tenantSnapshot: null,
    occurredAt: "2026-07-27T10:10:00Z",
  },
]

describe("InventoryMembershipMovements", () => {
  it("shows each server-recorded change with locations, status, origin, and tenant", () => {
    render(
      <InventoryMembershipMovements
        warehouse={{
          id: WAREHOUSE_ID,
          name: "Склад СПБ",
          timeZone: "Europe/Moscow",
        }}
        movements={movements}
      />
    )

    const section = screen.getByRole("region", { name: "Изменения состава" })
    expect(within(section).getAllByText("БЫТ-001")).toHaveLength(2)
    expect(within(section).getAllByText("Уехала")).toHaveLength(2)
    expect(within(section).getAllByText("Перемещена")).toHaveLength(2)
    expect(within(section).getAllByText("Приехала")).toHaveLength(2)
    expect(within(section).getAllByText("Склад СПБ")).toHaveLength(6)
    expect(within(section).getAllByText("Другой склад")).toHaveLength(2)
    expect(within(section).getAllByText("Добавлена б/у")).toHaveLength(2)
    expect(within(section).getAllByText("ООО Арендатор")).toHaveLength(2)
    expect(within(section).getAllByText("Аренда")).toHaveLength(2)
  })

  it("shows a clear empty state when the session buffer has no movements", () => {
    render(
      <InventoryMembershipMovements
        warehouse={{
          id: WAREHOUSE_ID,
          name: "Склад СПБ",
          timeZone: "Europe/Moscow",
        }}
        movements={[]}
      />
    )

    expect(
      screen.getByText("Во время этой инвентаризации состав не менялся.")
    ).toBeTruthy()
  })
})
