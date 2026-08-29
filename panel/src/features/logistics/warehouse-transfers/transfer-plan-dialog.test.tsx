import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const transferApi = vi.hoisted(() => ({
  createWarehouseTransfer: vi.fn(),
  getWarehouseTransferPlan: vi.fn(),
  updateWarehouseTransferPlan: vi.fn(),
  confirmWarehouseTransferPlan: vi.fn(),
}))
const assetApi = vi.hoisted(() => ({
  getRentalItemCreationOptions: vi.fn(),
  listAssetRentalItems: vi.fn(),
}))
const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))
const driverApi = vi.hoisted(() => ({
  listLogisticsDriverResources: vi.fn(),
  createContractorDriver: vi.fn(),
}))

vi.mock(
  "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api",
  () => ({
    TRANSFER_PLAN_QUERY_KEY: ["logistics", "warehouse-transfer-plan"],
    WAREHOUSE_TRANSFERS_QUERY_KEY: ["logistics", "warehouse-transfers"],
    ...transferApi,
  })
)
vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  ...assetApi,
  rentalItemCreationOptionsQueryKey: (warehouseId: string) => [
    "rental-item-creation-options",
    warehouseId,
  ],
}))
vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))
vi.mock(
  "@/features/logistics/warehouse-transfers/api/logistics-driver-resources-api",
  () => driverApi
)

import { TransferPlanDialog } from "@/features/logistics/warehouse-transfers/transfer-plan-dialog"
import type {
  TransferDocument,
  TransferPlan,
  TransferPlanRequest,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  releasePointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
  setPointerCapture: { configurable: true, value: () => undefined },
})

const SOURCE_ID = "11111111-1111-4111-8111-111111111111"
const DESTINATION_ID = "22222222-2222-4222-8222-222222222222"
const TRANSFER_ID = "33333333-3333-4333-8333-333333333333"
const DRIVER_ID = "44444444-4444-4444-8444-444444444444"
const TYPE_ID = "55555555-5555-4555-8555-555555555555"
const DIMENSION_ID = "66666666-6666-4666-8666-666666666666"
const FINISHING_ID = "77777777-7777-4777-8777-777777777777"
const CHARACTERISTIC_ID = "88888888-8888-4888-8888-888888888888"
const BED_ID = "99999999-9999-4999-8999-999999999999"
const TABLE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const CABIN_IDS = [
  "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
]

const currentUser = {
  id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  username: "logist",
  displayName: "Логист",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER" as const,
  globalRole: "WAREHOUSE_MANAGER" as const,
  rentalAccess: false,
  warehouseAccessAll: false,
  warehouseAccesses: [
    { warehouseId: SOURCE_ID, level: "MANAGE" as const },
    { warehouseId: DESTINATION_ID, level: "MANAGE" as const },
  ],
}
const warehouses = [
  {
    id: SOURCE_ID,
    version: 1,
    name: "Санкт-Петербург",
    city: "Санкт-Петербург",
    address: null,
    latitude: null,
    longitude: null,
    timeZone: "Europe/Moscow",
    active: true,
    lifecycleState: "ACTIVE" as const,
    sortOrder: 1,
    representative: false,
  },
  {
    id: DESTINATION_ID,
    version: 1,
    name: "Великий Новгород",
    city: "Великий Новгород",
    address: null,
    latitude: null,
    longitude: null,
    timeZone: "Europe/Moscow",
    active: true,
    lifecycleState: "ACTIVE" as const,
    sortOrder: 2,
    representative: true,
  },
]
const document: TransferDocument = {
  id: TRANSFER_ID,
  version: 1,
  documentType: "TRANSFER",
  state: "DRAFT",
  warehouseId: SOURCE_ID,
  destinationWarehouseId: DESTINATION_ID,
  partySnapshot: null,
  driverSnapshot: null,
  driverWorkerId: null,
  clientId: null,
  equipmentMovementTaskId: null,
  scheduledDate: "2026-09-02",
  rentalOrderId: null,
  rentalShipmentId: null,
  lines: [],
  createdAt: "2026-08-29T08:00:00Z",
  updatedAt: "2026-08-29T08:00:00Z",
}

function planFromRequest(request: TransferPlanRequest): TransferPlan {
  return {
    transferId: TRANSFER_ID,
    documentVersion: 1,
    documentState: "DRAFT",
    planId: "ffffffff-ffff-4fff-8fff-ffffffffffff",
    planVersion: 0,
    state: "DRAFT",
    reservationReadiness: "NOT_RESERVED",
    readinessDetail: "ALLOCATIONS_REQUIRED",
    legacyCompatible: false,
    scheduledDate: "2026-09-02",
    plannedDepartureAt: request.plannedDepartureAt,
    plannedArrivalAt: request.plannedArrivalAt,
    logisticsComment: request.logisticsComment,
    tripDriverId: request.tripDriverId,
    tripVehicleId: request.tripVehicleId,
    driverReposition: request.driverReposition ?? {
      resourceId: null,
      mode: "NONE",
      until: null,
    },
    vehicleReposition: request.vehicleReposition ?? {
      resourceId: null,
      mode: "NONE",
      until: null,
    },
    cabinGroups: request.cabinGroups.map((group, index) => ({
      groupId: `00000000-0000-4000-8000-00000000000${index + 1}`,
      position: index + 1,
      ...group,
      furniturePerCabin: group.furniturePerCabin.map((item) => ({
        ...item,
        totalQuantity: item.quantityPerCabin * group.quantity,
      })),
    })),
    looseFurniture: request.looseFurniture,
    totalCabinCount: request.cabinGroups.reduce(
      (total, group) => total + group.quantity,
      0
    ),
    furnitureTotals: [],
  }
}

function renderDialog(
  initialDestinationWarehouseId: string | null = DESTINATION_ID
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TransferPlanDialog
        accessToken="access-token"
        currentUser={currentUser}
        warehouseId={SOURCE_ID}
        warehouses={warehouses}
        initialDestinationWarehouseId={initialDestinationWarehouseId}
        onCreated={vi.fn()}
        onLegacyCreate={vi.fn()}
        onOpenChange={vi.fn()}
      />
    </QueryClientProvider>
  )
}

async function choose(label: string, option: string | RegExp) {
  const user = userEvent.setup()
  await user.click(screen.getByRole("combobox", { name: label }))
  await user.click(await screen.findByRole("option", { name: option }))
}

async function addConfiguredGroup() {
  const user = userEvent.setup()
  await user.click(screen.getByRole("button", { name: "Добавить бытовки" }))
  await choose("Тип бытовки группы 1", "BK2")
  await choose("Исполнение группы 1", "6 × 2,4")
  await choose("Отделка группы 1", "ЛДСП")
  await user.click(screen.getByText("Линолеум"))
  fireEvent.change(
    screen.getByRole("spinbutton", { name: "Количество бытовок группы 1" }),
    { target: { value: "2" } }
  )
}

beforeEach(() => {
  vi.stubGlobal("crypto", { randomUUID: () => TRANSFER_ID })
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: [],
    rentalTypes: [{ id: TYPE_ID, name: "BK2" }],
    dimensions: [{ id: DIMENSION_ID, name: "6 × 2,4" }],
    finishings: [{ id: FINISHING_ID, name: "ЛДСП" }],
    categories: [],
    characteristics: [{ id: CHARACTERISTIC_ID, name: "Утепление" }],
    typeDimensions: [
      { typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 1 },
    ],
  })
  assetApi.listAssetRentalItems.mockResolvedValue({
    content: CABIN_IDS.map((id, index) => ({
      id,
      version: index + 1,
      warehouseId: SOURCE_ID,
      number: `БК-${index + 1}`,
      rentalTypeId: TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      type: "BK2",
      dimensions: "6 × 2,4",
      finishing: "ЛДСП",
      category: null,
      characteristics: [],
      linoleum: true,
      status: "FREE",
      comment: null,
      contents: index === 0 ? "Кровать — 3" : null,
      contentsItems:
        index === 0
          ? [{ equipmentId: BED_ID, name: "Кровать", quantity: 3 }]
          : [],
      shipmentDate: null,
      tenant: null,
      price: null,
      activeOrderReservation: null,
      passport: {},
      tags: [],
    })),
  })
  equipmentApi.getEquipmentItems.mockResolvedValue([
    {
      id: BED_ID,
      version: 1,
      warehouseId: SOURCE_ID,
      category: "FURNITURE",
      name: "Кровать",
      active: true,
      comment: null,
      maximumPerCabin: 10,
      totalQuantity: 30,
      stockQuantity: 20,
      cabinStockQuantity: 10,
      rentedQuantity: 0,
      writtenOffQuantity: 0,
      lostQuantity: 0,
      activeHeldQuantity: 2,
      reservedQuantity: 2,
      availableQuantity: 28,
      availableStock: 18,
      balances: [],
      usages: [],
    },
    {
      id: TABLE_ID,
      version: 1,
      warehouseId: SOURCE_ID,
      category: "FURNITURE",
      name: "Стол",
      active: true,
      comment: null,
      maximumPerCabin: 2,
      totalQuantity: 10,
      stockQuantity: 10,
      cabinStockQuantity: 0,
      rentedQuantity: 0,
      writtenOffQuantity: 0,
      lostQuantity: 0,
      activeHeldQuantity: 0,
      reservedQuantity: 0,
      availableQuantity: 10,
      availableStock: 10,
      balances: [],
      usages: [],
    },
  ])
  driverApi.listLogisticsDriverResources.mockResolvedValue([
    {
      workerId: DRIVER_ID,
      displayName: "Петров Пётр",
      employmentType: "STAFF",
      operationalWarehouseId: SOURCE_ID,
      availableFrom: null,
      availableUntil: null,
      availabilityKind: "HOME",
    },
  ])
  transferApi.createWarehouseTransfer.mockResolvedValue(document)
  transferApi.getWarehouseTransferPlan.mockImplementation(() => {
    const request = transferApi.createWarehouseTransfer.mock.calls.at(-1)?.[0]
      ?.plan as TransferPlanRequest
    return Promise.resolve(planFromRequest(request))
  })
  transferApi.confirmWarehouseTransferPlan.mockResolvedValue({
    ...planFromRequest({
      plannedDepartureAt: "2026-09-02T05:00:00Z",
      plannedArrivalAt: "2026-09-02T09:00:00Z",
      logisticsComment: null,
      tripDriverId: DRIVER_ID,
      tripVehicleId: null,
      driverReposition: { resourceId: null, mode: "NONE", until: null },
      vehicleReposition: null,
      cabinGroups: [],
      looseFurniture: [],
    }),
    state: "CONFIRMED",
    reservationReadiness: "RESERVED",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe("TransferPlanDialog", () => {
  it("prefills a valid destination and builds catalog groups with calculated totals", async () => {
    const user = userEvent.setup()
    renderDialog()

    expect(
      screen.getByRole("combobox", { name: "Склад назначения" }).textContent
    ).toContain("Великий Новгород")
    await addConfiguredGroup()
    await user.click(
      within(screen.getByLabelText("Группа бытовок 1")).getByRole("button", {
        name: "Добавить мебель",
      })
    )
    await choose("Мебель 1", /Кровать · доступно 18 · резерв 2/)
    fireEvent.change(
      screen.getByLabelText("На одну бытовку", { selector: "input" }),
      { target: { value: "4" } }
    )

    expect(
      screen.getByText(/Кровать — 8 \(в бытовки 8, отдельно 0\)/)
    ).toBeTruthy()
  })

  it("automatically allocates exact cabins and allows a manual replacement", async () => {
    const user = userEvent.setup()
    renderDialog()
    await addConfiguredGroup()

    await user.click(screen.getByRole("button", { name: "Подобрать бытовки" }))
    expect(
      screen
        .getByRole("checkbox", { name: "Выбрать бытовку БК-1" })
        .getAttribute("data-state")
    ).toBe("checked")
    expect(
      screen
        .getByRole("checkbox", { name: "Выбрать бытовку БК-2" })
        .getAttribute("data-state")
    ).toBe("checked")

    await user.click(
      screen.getByRole("checkbox", { name: "Выбрать бытовку БК-1" })
    )
    await user.click(
      screen.getByRole("checkbox", { name: "Выбрать бытовку БК-3" })
    )
    expect(
      screen
        .getByRole("checkbox", { name: "Выбрать бытовку БК-3" })
        .getAttribute("data-state")
    ).toBe("checked")
  })

  it("saves a zero-line draft without allocations and surfaces confirm blockers", async () => {
    const user = userEvent.setup()
    renderDialog()
    await addConfiguredGroup()
    fireEvent.change(screen.getByLabelText("Дата"), {
      target: { value: "2026-09-02" },
    })

    expect(screen.getByText("Не назначено бытовок: 2.")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Сохранить черновик" }))

    await waitFor(() =>
      expect(transferApi.createWarehouseTransfer).toHaveBeenCalledTimes(1)
    )
    expect(
      transferApi.createWarehouseTransfer.mock.calls[0]?.[0]
    ).toMatchObject({
      warehouseId: SOURCE_ID,
      destinationWarehouseId: DESTINATION_ID,
      scheduledDate: "2026-09-02",
      lines: [],
      furnitureReplacements: [],
      plan: {
        cabinGroups: [
          expect.objectContaining({ quantity: 2, allocatedCabins: [] }),
        ],
      },
    })
    expect(
      (
        screen.getByRole("button", {
          name: "Подтвердить перемещение",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
  })

  it("selects a driver, stores temporary reposition intent, and confirms a complete draft", async () => {
    const user = userEvent.setup()
    renderDialog()
    fireEvent.change(screen.getByLabelText("Дата"), {
      target: { value: "2026-09-02" },
    })
    fireEvent.change(screen.getByLabelText("Отправление"), {
      target: { value: "2026-09-02T08:00" },
    })
    fireEvent.change(screen.getByLabelText("Прибытие"), {
      target: { value: "2026-09-02T12:00" },
    })
    await waitFor(() =>
      expect(driverApi.listLogisticsDriverResources).toHaveBeenCalled()
    )
    await choose("Водитель рейса", "Петров Пётр")
    await choose(
      "Назначение водителя после прибытия",
      "Временно работает на складе назначения"
    )
    fireEvent.change(screen.getByLabelText("Работает до"), {
      target: { value: "2026-09-04T18:00" },
    })
    await addConfiguredGroup()
    await user.click(screen.getByRole("button", { name: "Подобрать бытовки" }))
    await user.click(screen.getByRole("button", { name: "Сохранить черновик" }))

    await waitFor(() =>
      expect(
        (
          screen.getByRole("button", {
            name: "Подтвердить перемещение",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )
    expect(
      transferApi.createWarehouseTransfer.mock.calls[0]?.[0].plan
    ).toMatchObject({
      tripDriverId: DRIVER_ID,
      driverReposition: {
        resourceId: DRIVER_ID,
        mode: "TEMPORARY",
        until: "2026-09-04T15:00:00.000Z",
      },
      tripVehicleId: null,
      vehicleReposition: null,
    })
    await user.click(
      screen.getByRole("button", { name: "Подтвердить перемещение" })
    )
    await waitFor(() =>
      expect(transferApi.confirmWarehouseTransferPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: TRANSFER_ID,
          expectedVersion: 1,
        })
      )
    )
  })

  it("creates and immediately selects a bounded contractor driver", async () => {
    const user = userEvent.setup()
    driverApi.createContractorDriver.mockResolvedValue({
      workerId: DRIVER_ID,
      version: 0,
      homeWarehouseId: SOURCE_ID,
      displayName: "Наёмный Николай",
      phone: "+79990000000",
      availableFrom: "2026-09-02T05:00:00Z",
      availableUntil: "2026-09-02T15:00:00Z",
      comment: null,
      active: true,
      employmentType: "CONTRACTOR",
    })
    renderDialog()
    await user.click(
      screen.getByRole("button", { name: "Добавить наёмного водителя" })
    )
    const dialogs = screen.getAllByRole("dialog")
    const contractorDialog = dialogs.at(-1)!
    await user.type(
      within(contractorDialog).getByLabelText("Имя"),
      "Наёмный Николай"
    )
    await user.type(
      within(contractorDialog).getByLabelText("Телефон"),
      "+79990000000"
    )
    fireEvent.change(within(contractorDialog).getByLabelText("Начало смены"), {
      target: { value: "2026-09-02T08:00" },
    })
    fireEvent.change(within(contractorDialog).getByLabelText("Конец смены"), {
      target: { value: "2026-09-02T18:00" },
    })
    await user.click(
      within(contractorDialog).getByRole("button", {
        name: "Добавить водителя",
      })
    )

    await waitFor(() =>
      expect(driverApi.createContractorDriver).toHaveBeenCalled()
    )
    expect(driverApi.createContractorDriver.mock.calls[0]?.[0]).toMatchObject({
      warehouseId: SOURCE_ID,
      contractor: {
        displayName: "Наёмный Николай",
        phone: "+79990000000",
        availableFrom: "2026-09-02T05:00:00.000Z",
        availableUntil: "2026-09-02T15:00:00.000Z",
      },
    })
  })
})
