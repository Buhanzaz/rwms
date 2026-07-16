import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { useContext } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"
import { WarehouseProvider } from "@/contexts/warehouse-provider"
import { LEGACY_WAREHOUSE_SELECTIONS } from "@/contexts/warehouse-selection"

const { listWarehouses } = vi.hoisted(() => ({
  listWarehouses: vi.fn(),
}))

vi.mock("@/api/warehouse-api", () => ({
  listWarehouses,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "access-token" }),
}))

const warehouses: WarehouseInfo[] = [
  {
    id: LEGACY_WAREHOUSE_SELECTIONS.spb,
    version: 0,
    code: "WH_00000000000000000000000000000001",
    name: "СПБ",
    city: "Санкт-Петербург",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: null,
  },
  {
    id: LEGACY_WAREHOUSE_SELECTIONS.msk,
    version: 0,
    code: "WH_00000000000000000000000000000002",
    name: "Москва",
    city: "Москва",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: null,
  },
]

function SelectionProbe() {
  const context = useContext(WarehouseContext)

  if (context === null) {
    throw new Error("WarehouseContext is unavailable")
  }

  return <output>{context.selectedWarehouseId ?? "none"}</output>
}

afterEach(() => {
  cleanup()
  listWarehouses.mockReset()
})

describe("WarehouseProvider", () => {
  it("loads through the authenticated API and migrates a legacy selection", async () => {
    window.localStorage.setItem("wms:selected-warehouse-id", "msk")
    listWarehouses.mockResolvedValue(warehouses)

    render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() =>
      expect(screen.getByText(LEGACY_WAREHOUSE_SELECTIONS.msk)).toBeTruthy()
    )

    expect(listWarehouses).toHaveBeenCalledWith("access-token")
    expect(window.localStorage.getItem("wms:selected-warehouse-id")).toBe(
      LEGACY_WAREHOUSE_SELECTIONS.msk
    )
  })

  it("resets an unknown legacy selection to the first active warehouse", async () => {
    window.localStorage.setItem(
      "wms:selected-warehouse-id",
      "removed-warehouse"
    )
    listWarehouses.mockResolvedValue(warehouses)

    render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() =>
      expect(screen.getByText(LEGACY_WAREHOUSE_SELECTIONS.spb)).toBeTruthy()
    )
    expect(window.localStorage.getItem("wms:selected-warehouse-id")).toBe(
      LEGACY_WAREHOUSE_SELECTIONS.spb
    )
  })
})
