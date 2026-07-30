import { fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { ReturnDocument } from "@/features/logistics/returns/model"

const viewport = vi.hoisted(() => ({ isMobile: true }))
const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))

vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
}))
vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))

import { RequestEstimateDialog } from "@/features/logistics/returns/request-estimate-dialog"

const document: ReturnDocument = {
  id: "22222222-2222-4222-8222-222222222222",
  version: 4,
  documentType: "RETURN",
  state: "INSPECTION_REQUIRED",
  warehouseId: "11111111-1111-4111-8111-111111111111",
  destinationWarehouseId: null,
  partySnapshot: null,
  driverSnapshot: "Иванов Иван",
  clientId: "33333333-3333-4333-8333-333333333333",
  equipmentMovementTaskId: null,
  scheduledDate: "2026-07-29",
  rentalOrderId: "44444444-4444-4444-8444-444444444444",
  lines: [
    {
      id: "55555555-5555-4555-8555-555555555555",
      version: 2,
      lineNumber: 1,
      assetId: "66666666-6666-4666-8666-666666666666",
      assetVersion: 8,
      state: "PENDING",
      tenantSnapshot: "ООО Тест",
      rentalOrderId: "44444444-4444-4444-8444-444444444444",
    },
  ],
  createdAt: "2026-07-28T08:00:00Z",
  updatedAt: "2026-07-28T08:10:00Z",
}

beforeEach(() => {
  viewport.isMobile = true
})

afterEach(() => {
  vi.clearAllMocks()
})

describe("RequestEstimateDialog on mobile", () => {
  it("replaces the editable estimate form with the mobile-app warning", () => {
    const onOpenChange = vi.fn()

    render(
      <RequestEstimateDialog
        accessToken="return-token"
        document={document}
        onOpenChange={onOpenChange}
        onSuccess={vi.fn()}
        onConflict={vi.fn()}
      />
    )

    expect(
      screen.getByRole("dialog", {
        name: "Создание сметы доступно в мобильном приложении",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("link", { name: "Скачать приложение" })
    ).toBeTruthy()
    expect(screen.queryByText("Недостающее оборудование")).toBeNull()
    expect(screen.queryByRole("button", { name: "Создать смету" })).toBeNull()
    expect(equipmentApi.getEquipmentItems).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole("button", { name: "Понятно" }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
