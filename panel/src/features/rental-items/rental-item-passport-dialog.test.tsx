import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ID = "44444444-4444-4444-8444-444444444444"
const FINISHING_ID = "55555555-5555-4555-8555-555555555555"
const CHARACTERISTIC_ID = "66666666-6666-4666-8666-666666666666"

const assetApi = vi.hoisted(() => ({
  getRentalItemCreationOptions: vi.fn(),
  updateAssetRentalItemPassport: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "asset-token", currentUser: null }),
}))

vi.mock(
  "@/features/rental-items/api/asset-rental-items-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/rental-items/api/asset-rental-items-api")
    >()),
    ...assetApi,
  })
)

import { RentalItemPassportDialog } from "@/features/rental-items/rental-item-passport-dialog"

const item: RentalItemDto = {
  id: RENTAL_ITEM_ID,
  version: 7,
  warehouseId: WAREHOUSE_ID,
  number: "БЫТ-007",
  rentalTypeId: TYPE_ID,
  dimensionId: DIMENSION_ID,
  finishingId: FINISHING_ID,
  type: "БК-1",
  dimensions: "2.4x6",
  finishing: "ДВП",
  category: "Новая",
  characteristics: [{ id: CHARACTERISTIC_ID, name: "Пластиковое окно" }],
  linoleum: true,
  status: "WAREHOUSE",
  comment: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: { tenant: "ООО СтройПроект" },
  tags: ["ready"],
}

function renderDialog(onSaved = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RentalItemPassportDialog
        open
        rentalItem={item}
        onOpenChange={vi.fn()}
        onSaved={onSaved}
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: ["Обычная"],
    rentalTypes: [{ id: TYPE_ID, name: "БК-1" }],
    dimensions: [{ id: DIMENSION_ID, name: "2.4x6" }],
    finishings: [{ id: FINISHING_ID, name: "ДВП" }],
    characteristics: [
      { id: CHARACTERISTIC_ID, name: "Пластиковое окно" },
    ],
    typeDimensions: [
      { typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 },
    ],
  })
  assetApi.updateAssetRentalItemPassport.mockResolvedValue({
    ...item,
    version: 8,
  })
})

afterEach(cleanup)

describe("RentalItemPassportDialog", () => {
  it("saves UUID composition and preserves non-composition passport data", async () => {
    const user = userEvent.setup()
    const onSaved = vi.fn()
    renderDialog(onSaved)

    expect(
      await screen.findByRole("heading", { name: "Паспорт бытовки" })
    ).toBeTruthy()
    expect(screen.queryByText(TYPE_ID)).toBeNull()
    expect(await screen.findByText("Пластиковое окно")).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(assetApi.updateAssetRentalItemPassport).toHaveBeenCalledWith({
        accessToken: "asset-token",
        input: {
          id: RENTAL_ITEM_ID,
          expectedVersion: 7,
          rentalTypeId: TYPE_ID,
          dimensionId: DIMENSION_ID,
          finishingId: FINISHING_ID,
          category: "Новая",
          characteristicIds: [CHARACTERISTIC_ID],
          linoleum: true,
          passport: { tenant: "ООО СтройПроект" },
          tags: ["ready"],
        },
      })
    )
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ version: 8 }))
  })
})
