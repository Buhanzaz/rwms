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

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

const api = vi.hoisted(() => ({
  create: vi.fn(),
  getEquipment: vi.fn(),
  listCabins: vi.fn(),
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: api.getEquipment,
}))
vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: api.listCabins,
}))
vi.mock("@/features/write-offs/property-dispositions-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/write-offs/property-dispositions-api")
  >("@/features/write-offs/property-dispositions-api")
  return {
    ...actual,
    createPropertyDisposition: api.create,
    createPropertyDispositionIdempotencyKey: () =>
      "99999999-9999-4999-8999-999999999999",
  }
})

import { PropertyDispositionCreateDialog } from "./property-disposition-create-dialog"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const EQUIPMENT_ID = "33333333-3333-4333-8333-333333333333"

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

function cabin(contentsItems: RentalItemDto["contentsItems"]): RentalItemDto {
  return {
    id: CABIN_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-1",
    rentalTypeId: "",
    dimensionId: "",
    finishingId: "",
    type: "Стандарт",
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: [],
    linoleum: null,
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

function cabinEquipment(quantity = 4): EquipmentItemDto {
  return {
    id: EQUIPMENT_ID,
    version: 2,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    name: "Стул",
    active: true,
    comment: null,
    totalQuantity: quantity,
    stockQuantity: 0,
    cabinStockQuantity: quantity,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: quantity,
    availableStock: 0,
    usages: [],
    balances: [
      {
        id: "44444444-4444-4444-8444-444444444444",
        version: 7,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: CABIN_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity,
        activeHeldQuantity: 0,
        availableStock: 0,
      },
    ],
  }
}

function renderDialog() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <PropertyDispositionCreateDialog
        accessToken="token"
        warehouseId={WAREHOUSE_ID}
        disposition="WRITE_OFF"
        open
        onOpenChange={vi.fn()}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>
  )
}

async function selectCabin(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    await screen.findByRole("combobox", { name: "Имущество со склада" })
  )
  await user.click(
    await screen.findByRole("option", { name: "БЫТ-1 — Стандарт" })
  )
}

beforeEach(() => {
  api.create.mockResolvedValue({ id: "decision" })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("manual property disposition", () => {
  it("sends null contentsPlan and no choice UI for an empty cabin", async () => {
    const user = userEvent.setup()
    api.getEquipment.mockResolvedValue([])
    api.listCabins.mockResolvedValue({ content: [cabin([])] })
    renderDialog()

    await selectCabin(user)
    expect(screen.queryByText("Наполнение бытовки")).toBeNull()
    await user.type(screen.getByLabelText("Причина"), "Повреждение корпуса")
    await user.click(
      screen.getByRole("button", { name: "Отправить администратору" })
    )

    await waitFor(() =>
      expect(api.create).toHaveBeenCalledWith(
        expect.objectContaining({
          idempotencyKey: "99999999-9999-4999-8999-999999999999",
          request: expect.objectContaining({
            assetKind: "CABIN",
            assetId: CABIN_ID,
            expectedAssetVersion: 4,
            contentsPlan: null,
          }),
        })
      )
    )
  })

  it("sends every current content line with the selected stock quantity", async () => {
    const user = userEvent.setup()
    api.listCabins.mockResolvedValue({
      content: [
        cabin([
          {
            equipmentId: EQUIPMENT_ID,
            equipmentName: "Стул",
            name: "Стул",
            quantity: 4,
            locationKind: "CABIN_NON_RENTED",
          },
        ]),
      ],
    })
    api.getEquipment.mockResolvedValue([cabinEquipment()])
    renderDialog()

    await selectCabin(user)
    expect(await screen.findByText("Наполнение бытовки")).toBeTruthy()
    const quantity = screen.getByRole("spinbutton")
    await user.clear(quantity)
    await user.type(quantity, "3")
    await user.type(screen.getByLabelText("Причина"), "Износ")
    await user.click(
      screen.getByRole("button", { name: "Отправить администратору" })
    )

    await waitFor(() =>
      expect(api.create).toHaveBeenCalledWith(
        expect.objectContaining({
          request: expect.objectContaining({
            contentsPlan: {
              mode: "MOVE_SELECTED_TO_STOCK",
              lines: [
                {
                  equipmentId: EQUIPMENT_ID,
                  expectedBalanceVersion: 7,
                  moveToStockQuantity: 3,
                },
              ],
            },
          }),
        })
      )
    )
  })

  it("blocks a stale cabin composition instead of silently disposing it", async () => {
    const user = userEvent.setup()
    api.listCabins.mockResolvedValue({
      content: [
        cabin([
          {
            equipmentId: EQUIPMENT_ID,
            name: "Стул",
            quantity: 4,
          },
        ]),
      ],
    })
    api.getEquipment.mockResolvedValue([cabinEquipment(3)])
    renderDialog()

    await selectCabin(user)
    expect(
      await screen.findByText(/Остатки наполнения не совпали/)
    ).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Отправить администратору",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
  })
})
