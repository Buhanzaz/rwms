import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { useContext } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"
import { WarehouseProvider } from "@/contexts/warehouse-provider"

const { listWarehouses } = vi.hoisted(() => ({
  listWarehouses: vi.fn(),
}))

vi.mock("@/api/warehouse-api", () => ({
  listWarehouses,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "access-token" }),
}))

const warehouse: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000001",
  serviceId: "00000000-0000-4000-8000-000000000001",
  version: 0,
  code: "WH_NORTH",
  name: "Северный",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: null,
}

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
  window.localStorage.clear()
})

describe("WarehouseProvider", () => {
  it("loads through the authenticated API and replaces a stale mock selection", async () => {
    window.localStorage.setItem("wms:selected-warehouse-id", "spb")
    listWarehouses.mockResolvedValue([warehouse])

    render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() => expect(screen.getByText(warehouse.id)).toBeTruthy())

    expect(listWarehouses).toHaveBeenCalledWith("access-token")
    expect(window.localStorage.getItem("wms:selected-warehouse-id")).toBe(
      warehouse.id
    )
  })
})
