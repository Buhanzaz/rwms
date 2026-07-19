import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"

const authState = vi.hoisted(() => ({
  level: "VIEW" as "VIEW" | "EDIT",
}))

const assetApi = vi.hoisted(() => ({
  listAssetRentalItems: vi.fn(),
  createAssetRentalItem: vi.fn(),
  getAssetRentalItem: vi.fn(),
  listAssetRentalItemManualNotes: vi.fn(),
  addAssetRentalItemManualNote: vi.fn(),
  updateAssetRentalItemGeneralComment: vi.fn(),
  updateAssetRentalItemStatus: vi.fn(),
  createIdempotencyKey: vi.fn(() => "33333333-3333-4333-8333-333333333333"),
}))

const dossierApi = vi.hoisted(() => ({
  getRentalItemDossierPage: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    status: "authenticated",
    accessToken: "asset-token",
    currentUser: {
      id: "operator-1",
      username: "operator",
      displayName: "Operator",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        { warehouseId: WAREHOUSE_ID, level: authState.level },
      ],
    },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      code: "MSK",
      name: "Москва",
    },
    selectedWarehouseId: WAREHOUSE_ID,
  }),
}))

vi.mock("@/hooks/use-workspace-back", () => ({
  useWorkspaceBack: () => vi.fn(),
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

vi.mock("@/features/rental-items/dossier/api/rental-item-dossier-api", () => ({
  RENTAL_ITEM_DOSSIER_QUERY_KEY: ["rental-item-dossier"],
  rentalItemDossierQueryKey: (rentalItemId: string) => [
    "rental-item-dossier",
    rentalItemId,
  ],
  getRentalItemDossierPage: dossierApi.getRentalItemDossierPage,
}))

import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
  return {
    id: RENTAL_ITEM_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Новая",
    characteristics: "Пластиковое окно",
    linoleum: false,
    status: "WAREHOUSE",
    comment: "Исходный комментарий",
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    ...overrides,
  }
}

function renderWithQuery(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </MemoryRouter>
  )
}

function renderDetail() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter
      initialEntries={[`/warehouse/${RENTAL_ITEM_ID}?tab=comments`]}
    >
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/warehouse/:rentalItemId"
            element={<RentalItemDetailPage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "VIEW"
  assetApi.listAssetRentalItems.mockResolvedValue({
    content: [],
    page: 0,
    size: 50,
    totalElements: 0,
    totalPages: 0,
  })
  assetApi.getAssetRentalItem.mockResolvedValue(rentalItem())
  assetApi.listAssetRentalItemManualNotes.mockResolvedValue([])
  assetApi.createAssetRentalItem.mockResolvedValue(rentalItem())
  assetApi.updateAssetRentalItemStatus.mockResolvedValue(
    rentalItem({ version: 2, status: "FREE" })
  )
  assetApi.updateAssetRentalItemGeneralComment.mockResolvedValue(
    rentalItem({ version: 3, status: "FREE", comment: "Новый комментарий" })
  )
  assetApi.addAssetRentalItemManualNote.mockResolvedValue({
    id: "44444444-4444-4444-8444-444444444444",
    rentalItemId: RENTAL_ITEM_ID,
    text: "Ручная заметка",
    createdAt: "2026-07-18T12:00:00Z",
  })
  dossierApi.getRentalItemDossierPage.mockResolvedValue({
    cabinId: RENTAL_ITEM_ID,
    activities: [],
    nextCursor: null,
    visibility: "COMPLETE",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("rental item command access", () => {
  it("keeps the registry readable but hides create from VIEW access", async () => {
    renderWithQuery(<RentalItemsPage />)

    await waitFor(() =>
      expect(assetApi.listAssetRentalItems).toHaveBeenCalled()
    )
    expect(
      screen.queryByRole("button", { name: "Добавить новую бытовку" })
    ).toBeNull()
    expect(assetApi.createAssetRentalItem).not.toHaveBeenCalled()
  })

  it("lets EDIT access open and execute create", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Добавить новую бытовку" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создание новой бытовки",
    })
    await user.type(within(dialog).getByLabelText("Номер бытовки"), "БЫТ-009")
    await user.click(
      within(dialog).getByRole("button", { name: "Выберите тип" })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(
      within(dialog).getByRole("button", { name: "Выберите отделку" })
    )
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(
      within(dialog).getByRole("button", { name: "Создать бытовку" })
    )

    await waitFor(() =>
      expect(assetApi.createAssetRentalItem).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "asset-token",
          input: expect.objectContaining({
            warehouseId: WAREHOUSE_ID,
            number: "БЫТ-009",
          }),
        })
      )
    )
  })

  it("keeps detail reads but prevents VIEW from opening or executing commands", async () => {
    const user = userEvent.setup()
    renderDetail()

    await screen.findByText("БЫТ-001")
    expect(assetApi.getAssetRentalItem).toHaveBeenCalledWith(
      "asset-token",
      RENTAL_ITEM_ID
    )
    expect(screen.queryByRole("button", { name: "Изменить статус" })).toBeNull()

    const generalComment = screen.getByLabelText("Комментарий")
    const manualNote = screen.getByLabelText("Текст")
    const saveComment = screen.getByRole("button", {
      name: "Сохранить изменения",
    })
    const addNote = screen.getByRole("button", { name: "Добавить заметку" })
    expect(generalComment.hasAttribute("disabled")).toBe(true)
    expect(manualNote.hasAttribute("disabled")).toBe(true)
    expect(saveComment.hasAttribute("disabled")).toBe(true)
    expect(addNote.hasAttribute("disabled")).toBe(true)

    await user.click(saveComment)
    await user.click(addNote)
    expect(assetApi.updateAssetRentalItemStatus).not.toHaveBeenCalled()
    expect(assetApi.updateAssetRentalItemGeneralComment).not.toHaveBeenCalled()
    expect(assetApi.addAssetRentalItemManualNote).not.toHaveBeenCalled()
  })

  it("lets EDIT execute status, general-comment and manual-note commands", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderDetail()

    await user.click(
      await screen.findByRole("button", { name: "Изменить статус" })
    )
    const statusDialog = screen.getByRole("dialog", {
      name: "Изменить статус",
    })
    await user.click(
      within(statusDialog).getByRole("button", { name: "Сохранить" })
    )
    await waitFor(() =>
      expect(assetApi.updateAssetRentalItemStatus).toHaveBeenCalled()
    )

    const generalComment = screen.getByLabelText("Комментарий")
    await user.clear(generalComment)
    await user.type(generalComment, "Новый комментарий")
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )
    await waitFor(() =>
      expect(assetApi.updateAssetRentalItemGeneralComment).toHaveBeenCalled()
    )

    await user.type(screen.getByLabelText("Текст"), "Ручная заметка")
    await user.click(screen.getByRole("button", { name: "Добавить заметку" }))
    await waitFor(() =>
      expect(assetApi.addAssetRentalItemManualNote).toHaveBeenCalled()
    )
  })
})
