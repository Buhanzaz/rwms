import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { inventoryDetailQueryKey } from "@/features/inventory/api/inventory-api"
import type {
  InventoryActorSnapshot,
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const INVENTORY_ID = "22222222-2222-4222-8222-222222222222"
const FINDING_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const TYPE_ONE_ID = "55555555-5555-4555-8555-555555555555"
const TYPE_TWO_ID = "66666666-6666-4666-8666-666666666666"
const DIMENSION_STANDARD_ID = "77777777-7777-4777-8777-777777777777"
const DIMENSION_WIDE_ID = "88888888-8888-4888-8888-888888888888"
const FINISHING_DVP_ID = "99999999-9999-4999-8999-999999999999"
const FINISHING_LDSP_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const CHARACTERISTIC_WINDOW_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const CHARACTERISTIC_DOOR_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

const inventoryApi = vi.hoisted(() => ({
  resolveInventoryNumber: vi.fn(),
  addInventoryRentalItem: vi.fn(),
  createInventoryFindingId: vi.fn(() => FINDING_ID),
}))

const mediaApi = vi.hoisted(() => ({
  uploadFile: vi.fn(),
  listOwnerMedia: vi.fn(),
  rotate: vi.fn(),
}))

const assetApi = vi.hoisted(() => ({
  getRentalItemCreationOptions: vi.fn(),
  createIdempotencyKey: vi.fn(() => "dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
}))

vi.mock("@/features/inventory/api/inventory-api", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("@/features/inventory/api/inventory-api")
  >()),
  ...inventoryApi,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "inventory-access-token",
    currentUser: null,
  }),
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

vi.mock("@/features/media/media-service", () => ({
  cabinMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "CABIN",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  createHttpMediaClient: () => mediaApi,
}))

import { InventoryAddDialog } from "@/features/inventory/inventory-add-dialog"

const session = {
  id: INVENTORY_ID,
  version: 7,
  warehouseId: WAREHOUSE_ID,
} as InventorySessionDto

const resolvedSession = {
  ...session,
  version: session.version + 1,
} as InventorySessionDto

const createdSession = {
  ...resolvedSession,
  version: resolvedSession.version + 1,
} as InventorySessionDto

const actor = {
  id: "inventory-user",
  displayName: "Инвентаризатор",
  permissions: ["EDIT"],
  authorizedWarehouseIds: [WAREHOUSE_ID],
} satisfies InventoryActorSnapshot

const finding = {
  id: FINDING_ID,
  version: 1,
  rentalItemId: ASSET_ID,
  cabinNumber: "БУ-901",
  canonicalNumber: "БУ-901",
  currentSnapshot: {
    rentalItemId: ASSET_ID,
    number: "БУ-901",
    canonicalNumber: "БУ-901",
    warehouseId: WAREHOUSE_ID,
    status: "WAREHOUSE",
    tenant: null,
  },
} as InventoryFindingDto

function renderDialog(onResolved = vi.fn(), onOpenChange = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  render(
    <QueryClientProvider client={queryClient}>
      <InventoryAddDialog
        open
        session={session}
        actor={actor}
        onOpenChange={onOpenChange}
        onResolved={onResolved}
      />
    </QueryClientProvider>
  )

  return { onOpenChange, onResolved, queryClient }
}

beforeEach(() => {
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn(() => "blob:inventory-photo"),
  })
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  })
  inventoryApi.resolveInventoryNumber.mockResolvedValue({
    kind: "NOT_FOUND",
    canonicalNumber: "БУ-901",
    lookup: { kind: "NOT_FOUND" },
    session: resolvedSession,
  })
  inventoryApi.addInventoryRentalItem.mockResolvedValue({
    session: createdSession,
    finding,
    rentalItem: {
      assetId: ASSET_ID,
      assetVersion: 0,
      warehouseId: WAREHOUSE_ID,
      status: "FREE",
      displayCanonicalNumber: "БУ-901",
      tenantSnapshot: null,
    },
  })
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: ["Обычная", "ИТР"],
    rentalTypes: [
      { id: TYPE_ONE_ID, name: "БК-1" },
      { id: TYPE_TWO_ID, name: "БК-2" },
    ],
    dimensions: [
      { id: DIMENSION_STANDARD_ID, name: "2.4x6" },
      { id: DIMENSION_WIDE_ID, name: "4.8x6" },
    ],
    finishings: [
      { id: FINISHING_DVP_ID, name: "ДВП" },
      { id: FINISHING_LDSP_ID, name: "ЛДСП" },
    ],
    characteristics: [
      { id: CHARACTERISTIC_WINDOW_ID, name: "Пластиковое окно" },
      { id: CHARACTERISTIC_DOOR_ID, name: "Металлическая дверь" },
    ],
    typeDimensions: [
      {
        typeId: TYPE_ONE_ID,
        dimensionId: DIMENSION_STANDARD_ID,
        sortOrder: 0,
      },
      {
        typeId: TYPE_TWO_ID,
        dimensionId: DIMENSION_WIDE_ID,
        sortOrder: 0,
      },
    ],
  })
  mediaApi.uploadFile.mockResolvedValue({
    asset: { id: "55555555-5555-4555-8555-555555555555" },
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("InventoryAddDialog", () => {
  it("creates from passport fields without staging cabin photos and resolves the finding", async () => {
    const user = userEvent.setup()
    const { onOpenChange, onResolved, queryClient } = renderDialog()

    await user.type(screen.getByLabelText("Номер бытовки"), "бу-901")
    await user.click(screen.getByRole("button", { name: "Найти" }))
    await waitFor(() =>
      expect(
        queryClient.getQueryData(inventoryDetailQueryKey(INVENTORY_ID))
      ).toEqual(resolvedSession)
    )
    await user.click(
      await screen.findByRole("button", { name: "Добавить б/у" })
    )

    expect(
      await screen.findByRole("heading", { name: "Создание б/у бытовки" })
    ).toBeTruthy()
    expect(screen.getByText("Характеристики")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Загрузить фото" })).toBeNull()
    expect(screen.queryByLabelText("Фотографии новой бытовки")).toBeNull()

    const numberInput = screen.getByLabelText(
      "Номер бытовки"
    ) as HTMLInputElement
    expect(numberInput.value).toBe("БУ-901")
    expect(numberInput.readOnly).toBe(true)
    expect(await screen.findByRole("button", { name: "Обычная" })).toBeTruthy()

    await user.click(
      await screen.findByRole("button", { name: "Выберите тип" })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))

    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "2.4x6" }))

    await user.click(screen.getByRole("button", { name: "Выберите отделку" }))
    await user.click(screen.getByRole("button", { name: "ЛДСП" }))
    await user.click(screen.getByRole("radio", { name: "Нет" }))
    await user.click(
      screen.getByRole("button", { name: "Выбрать характеристики" })
    )
    await user.click(screen.getByLabelText("Металлическая дверь"))
    await user.click(screen.getByRole("button", { name: "Применить" }))
    await user.click(
      screen.getByRole("button", { name: "Создать и осмотреть" })
    )

    await waitFor(() =>
      expect(inventoryApi.addInventoryRentalItem).toHaveBeenCalledTimes(1)
    )
    expect(inventoryApi.addInventoryRentalItem).toHaveBeenCalledWith({
      inventoryId: INVENTORY_ID,
      expectedVersion: resolvedSession.version,
      actor,
      findingId: FINDING_ID,
      condition: "USED",
      rentalItem: {
        number: "БУ-901",
        rentalTypeId: TYPE_ONE_ID,
        dimensionId: DIMENSION_STANDARD_ID,
        finishingId: FINISHING_LDSP_ID,
        category: "Обычная",
        characteristicIds: [CHARACTERISTIC_DOOR_ID],
        linoleum: false,
      },
    })
    await waitFor(() =>
      expect(onResolved).toHaveBeenCalledWith(createdSession, finding)
    )
    expect(mediaApi.uploadFile).not.toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenLastCalledWith(false)
  })

  it("uses the resolved session revision for a new cabin", async () => {
    const user = userEvent.setup()
    renderDialog()

    await user.type(screen.getByLabelText("Номер бытовки"), "бу-901")
    await user.click(screen.getByRole("button", { name: "Найти" }))
    await user.click(
      await screen.findByRole("button", { name: "Добавить новую" })
    )
    await user.click(
      await screen.findByRole("button", { name: "Выберите тип" })
    )
    await user.click(screen.getByRole("button", { name: "БК-2" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "4.8x6" }))
    await user.click(screen.getByRole("button", { name: "Выберите отделку" }))
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("radio", { name: "Нет" }))
    await user.click(
      screen.getByRole("button", { name: "Создать и осмотреть" })
    )

    await waitFor(() =>
      expect(inventoryApi.addInventoryRentalItem).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: resolvedSession.version,
          condition: "NEW",
          rentalItem: expect.objectContaining({
            category: "Новая",
            rentalTypeId: TYPE_TWO_ID,
            dimensionId: DIMENSION_WIDE_ID,
            finishingId: FINISHING_DVP_ID,
          }),
        })
      )
    )
  })

  it.each([
    {
      addButton: "Добавить новую",
      title: "Создание новой бытовки",
    },
    {
      addButton: "Добавить б/у",
      title: "Создание б/у бытовки",
    },
  ])(
    "filters dimensions by the selected catalog type for $addButton",
    async ({ addButton, title }) => {
      const user = userEvent.setup()
      renderDialog()

      await user.type(screen.getByLabelText("Номер бытовки"), "бу-901")
      await user.click(screen.getByRole("button", { name: "Найти" }))
      await user.click(await screen.findByRole("button", { name: addButton }))

      const dialog = await screen.findByRole("dialog", { name: title })
      await user.click(
        await within(dialog).findByRole("button", { name: "Выберите тип" })
      )
      await user.click(screen.getByRole("button", { name: "БК-2" }))

      expect(
        dialog.querySelector('[data-rental-item-section="passport"]')
      ).not.toBeNull()
      expect(within(dialog).getByRole("button", { name: "БК-2" })).toBeTruthy()
      await user.click(
        within(dialog).getByRole("button", { name: "Выберите габариты" })
      )
      expect(screen.getByRole("button", { name: "4.8x6" })).toBeTruthy()
      expect(screen.queryByRole("button", { name: "2.4x6" })).toBeNull()
    }
  )
})
