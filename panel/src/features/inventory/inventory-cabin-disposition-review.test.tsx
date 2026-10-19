import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { EquipmentItemDto } from "@/types/equipment"

const equipmentApi = vi.hoisted(() => ({
  getEquipmentItems: vi.fn(),
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))

vi.mock("@/features/logistics/rental-client-picker", () => ({
  RentalClientPicker: ({
    onChange,
    disabled,
  }: {
    onChange: (value: { id: string; displayName: string }) => void
    disabled?: boolean
  }) => (
    <button
      type="button"
      disabled={disabled}
      onClick={() =>
        onChange({
          id: "22222222-2222-4222-8222-222222222222",
          displayName: "ООО Клиент",
        })
      }
    >
      Выбрать клиента
    </button>
  ),
}))

import { InventoryCabinDispositionReviewCard } from "@/features/inventory/inventory-cabin-disposition-review"
import type { InventoryCabinDispositionReview } from "@/features/inventory/model/inventory-service"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const FINDING_ID = "33333333-3333-4333-8333-333333333333"
const EQUIPMENT_ID = "44444444-4444-4444-8444-444444444444"

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

const review: InventoryCabinDispositionReview = {
  inventoryId: "55555555-5555-4555-8555-555555555555",
  sessionRevision: 8,
  reviewRevision: 3,
  phase: "SHIPMENTS",
  returnCandidates: [],
  missingCandidates: [
    {
      findingId: FINDING_ID,
      findingRevision: 5,
      assetId: "66666666-6666-4666-8666-666666666666",
      assetVersion: 12,
      cabinNumber: "БЫТ-42",
      candidateKind: "MISSING",
      dispositionKind: null,
      dispositionDetails: null,
    },
  ],
}

function equipment(
  overrides: Partial<EquipmentItemDto> = {}
): EquipmentItemDto {
  return {
    id: EQUIPMENT_ID,
    version: 17,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    name: "Стол",
    active: true,
    comment: null,
    maximumPerCabin: null,
    totalQuantity: 0,
    stockQuantity: 0,
    cabinStockQuantity: 0,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 0,
    availableStock: 0,
    balances: [],
    usages: [],
    ...overrides,
  }
}

function renderReview(onConfirmShipments = vi.fn(), selectedReview = review) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <InventoryCabinDispositionReviewCard
        review={selectedReview}
        accessToken="access-token"
        warehouseId={WAREHOUSE_ID}
        defaultDate="2026-08-21"
        pending={false}
        error={null}
        onConfirmReturns={vi.fn()}
        onConfirmShipments={onConfirmShipments}
      />
    </QueryClientProvider>
  )
  return onConfirmShipments
}

beforeEach(() => {
  equipmentApi.getEquipmentItems.mockResolvedValue([equipment()])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("InventoryCabinDispositionReviewCard", () => {
  it("shows catalog loading but keeps zero-furniture shipment confirmation independent", async () => {
    equipmentApi.getEquipmentItems.mockReturnValue(new Promise(() => undefined))
    const onConfirm = renderReview()
    const user = userEvent.setup()

    expect(screen.getByText("Загружаем каталог мебели…")).toBeTruthy()
    await user.click(screen.getByLabelText("Бытовка БЫТ-42 была отгружена"))
    await user.click(screen.getByRole("button", { name: "Выбрать клиента" }))
    expect(
      (
        screen.getByRole("button", {
          name: "Добавить мебель",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    const confirm = screen.getByRole("button", {
      name: "Подтвердить отгрузки и списание остальных",
    })
    expect((confirm as HTMLButtonElement).disabled).toBe(false)
    await user.click(confirm)

    expect(onConfirm).toHaveBeenCalledWith({
      expectedSessionRevision: 8,
      expectedReviewRevision: 3,
      shipments: [
        {
          findingId: FINDING_ID,
          expectedFindingRevision: 5,
          departedOn: "2026-08-21",
          clientId: "22222222-2222-4222-8222-222222222222",
          clientSnapshot: "ООО Клиент",
          furniture: [],
        },
      ],
    })
  })

  it("allows all-writeoff confirmation when the furniture catalog fails", async () => {
    equipmentApi.getEquipmentItems.mockRejectedValue(
      new Error("Каталог временно недоступен")
    )
    const onConfirm = renderReview()
    const user = userEvent.setup()

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Каталог временно недоступен"
    )
    const confirm = screen.getByRole("button", {
      name: "Подтвердить отгрузки и списание остальных",
    })
    expect((confirm as HTMLButtonElement).disabled).toBe(false)
    await user.click(confirm)

    expect(onConfirm).toHaveBeenCalledWith({
      expectedSessionRevision: 8,
      expectedReviewRevision: 3,
      shipments: [],
    })
    await user.click(screen.getByRole("button", { name: "Повторить загрузку" }))
    await waitFor(() =>
      expect(equipmentApi.getEquipmentItems).toHaveBeenCalledTimes(2)
    )
  })

  it("selects an active furniture name and submits its exact server catalog version", async () => {
    equipmentApi.getEquipmentItems.mockResolvedValue([
      equipment(),
      equipment({
        id: "77777777-7777-4777-8777-777777777777",
        version: 99,
        name: "Скрытая мебель",
        active: false,
      }),
      equipment({
        id: "88888888-8888-4888-8888-888888888888",
        version: 7,
        name: "Удлинитель",
        category: "ELECTRICAL",
      }),
    ])
    const onConfirm = renderReview()
    const user = userEvent.setup()
    await user.click(screen.getByLabelText("Бытовка БЫТ-42 была отгружена"))
    await user.click(screen.getByRole("button", { name: "Выбрать клиента" }))
    const addFurniture = screen.getByRole("button", { name: "Добавить мебель" })
    await waitFor(() =>
      expect((addFurniture as HTMLButtonElement).disabled).toBe(false)
    )
    await user.click(addFurniture)
    await user.click(screen.getByRole("combobox", { name: "Мебель 1" }))
    await user.click(await screen.findByRole("option", { name: "Стол" }))
    expect(screen.queryByRole("option", { name: "Скрытая мебель" })).toBeNull()
    expect(screen.queryByRole("option", { name: "Удлинитель" })).toBeNull()
    const quantity = screen.getByRole("spinbutton", {
      name: "Количество мебели 1",
    })
    await user.clear(quantity)
    await user.type(quantity, "12")
    await user.click(
      screen.getByRole("button", {
        name: "Подтвердить отгрузки и списание остальных",
      })
    )

    expect(onConfirm).toHaveBeenCalledWith({
      expectedSessionRevision: 8,
      expectedReviewRevision: 3,
      shipments: [
        {
          findingId: FINDING_ID,
          expectedFindingRevision: 5,
          departedOn: "2026-08-21",
          clientId: "22222222-2222-4222-8222-222222222222",
          clientSnapshot: "ООО Клиент",
          furniture: [
            {
              equipmentId: EQUIPMENT_ID,
              catalogVersion: 17,
              quantity: 12,
            },
          ],
        },
      ],
    })
  })
})
