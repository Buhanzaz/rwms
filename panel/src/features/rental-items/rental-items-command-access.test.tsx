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
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"

const authState = vi.hoisted(() => ({
  level: "VIEW" as "VIEW" | "EDIT" | "MANAGE",
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
  getRentalItemCreationOptions: vi.fn(),
}))

const dossierApi = vi.hoisted(() => ({
  getRentalItemDossierPage: vi.fn(),
}))

const mediaApi = vi.hoisted(() => ({
  listCabinCovers: vi.fn(),
  listOwnerMedia: vi.fn(),
  uploadFile: vi.fn(),
  createVariantObjectUrl: vi.fn(),
  createOriginalObjectUrl: vi.fn(),
  rotate: vi.fn(),
}))

const propertyDispositionDialog = vi.hoisted(() => ({
  render: vi.fn(),
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
      name: "Москва",
    },
    selectedWarehouseId: WAREHOUSE_ID,
  }),
}))

vi.mock("@/hooks/use-workspace-back", () => ({
  useWorkspaceBack: () => vi.fn(),
  workspaceEntryNavigationOptions: {
    state: { workspaceEntry: true },
  },
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

vi.mock("@/features/media/media-service", () => ({
  cabinMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "CABIN",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  createHttpMediaClient: () => mediaApi,
}))

vi.mock("@/features/write-offs/property-disposition-create-dialog", () => ({
  PropertyDispositionCreateDialog: (props: {
    accessToken: string | null
    warehouseId: string
    disposition: string
    open: boolean
  }) => {
    propertyDispositionDialog.render(props)
    return props.open ? (
      <div role="dialog" aria-label="Заявка на списание бытовки">
        {props.disposition}:{props.warehouseId}
      </div>
    ) : null
  },
}))

import { RentalItemDetailPage } from "@/features/rental-items/rental-item-detail-page"
import { RentalItemsPage } from "@/features/rental-items/rental-items-page"

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
  return {
    id: RENTAL_ITEM_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    rentalTypeId: "33333333-3333-4333-8333-333333333333",
    dimensionId: "44444444-4444-4444-8444-444444444444",
    finishingId: "55555555-5555-4555-8555-555555555555",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Новая",
    characteristics: [
      {
        id: "66666666-6666-4666-8666-666666666666",
        name: "Пластиковое окно",
      },
    ],
    linoleum: false,
    status: "WAREHOUSE",
    comment: "Исходный комментарий",
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
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

function RepairLocationProbe() {
  const location = useLocation()
  return <pre data-testid="repair-location">{JSON.stringify(location)}</pre>
}

function renderDetail(path = `/warehouse/${RENTAL_ITEM_ID}?tab=comments`) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/warehouse/:rentalItemId"
            element={<RentalItemDetailPage />}
          />
          <Route path="/repairs" element={<RepairLocationProbe />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

async function selectRequiredComposition(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  rentalType = "БК-1"
) {
  await user.click(within(dialog).getByRole("button", { name: "Выберите тип" }))
  await user.click(screen.getByRole("button", { name: rentalType }))
  await user.click(
    within(dialog).getByRole("button", { name: "Выберите габариты" })
  )
  await user.click(screen.getByRole("button", { name: "2.4x6" }))
  await user.click(
    within(dialog).getByRole("button", { name: "Выберите отделку" })
  )
  await user.click(screen.getByRole("button", { name: "ДВП" }))
  await user.click(within(dialog).getByRole("radio", { name: "Нет" }))
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
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: ["Обычная", "ИТР"],
    rentalTypes: [
      { id: "type-bk-1", name: "БК-1" },
      { id: "type-sanblock", name: "БК-Санблок" },
    ],
    dimensions: [{ id: "dimension-24x6", name: "2.4x6" }],
    finishings: [
      { id: "finishing-dvp", name: "ДВП" },
      { id: "finishing-pvh", name: "ПВХ" },
    ],
    categories: [
      { id: "category-new", name: "Новая" },
      { id: "category-used", name: "Обычная" },
      { id: "category-engineer", name: "ИТР" },
    ],
    characteristics: [
      { id: "characteristic-window", name: "Пластиковое окно" },
    ],
    typeDimensions: [
      {
        typeId: "type-bk-1",
        dimensionId: "dimension-24x6",
        sortOrder: 0,
      },
      {
        typeId: "type-sanblock",
        dimensionId: "dimension-24x6",
        sortOrder: 0,
      },
    ],
  })
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
  mediaApi.listOwnerMedia.mockResolvedValue({ items: [], next: null })
  mediaApi.listCabinCovers.mockResolvedValue({ items: [] })
  mediaApi.uploadFile.mockResolvedValue({
    session: {},
    uploadedObject: {},
    asset: {},
  })
  propertyDispositionDialog.render.mockClear()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.localStorage.clear()
})

describe("rental item command access", () => {
  it("uses the empty table grid even when the saved view is cards", async () => {
    window.localStorage.setItem(
      `rental-items:${WAREHOUSE_ID}:view-mode`,
      JSON.stringify("grid")
    )
    const { container } = renderWithQuery(<RentalItemsPage />)

    const grid = await waitFor(() => {
      const element = container.querySelector<HTMLElement>(
        '[data-slot="rental-items-table-grid"]'
      )
      expect(element).not.toBeNull()
      return element!
    })

    expect(
      within(grid).getByRole("columnheader", { name: "Номер" })
    ).toBeTruthy()
    expect(grid.className).toContain("flex-1")
    expect(container.querySelector("[data-grid-format]")).toBeNull()
  })

  it("keeps the cabin write-off action visible but disabled from VIEW access", async () => {
    renderWithQuery(<RentalItemsPage />)

    await waitFor(() =>
      expect(assetApi.listAssetRentalItems).toHaveBeenCalled()
    )
    expect(
      screen.queryByRole("button", { name: "Добавить новую бытовку" })
    ).toBeNull()
    expect(
      screen
        .getByRole("button", { name: "Списать бытовку" })
        .hasAttribute("disabled")
    ).toBe(true)
    expect(
      screen.getByText(
        "Заявку на списание может создать управляющий склада или администратор с доступом MANAGE."
      )
    ).toBeTruthy()
    expect(assetApi.createAssetRentalItem).not.toHaveBeenCalled()
  })

  it("opens the cabin write-off proposal for a warehouse manager", async () => {
    authState.level = "MANAGE"
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Списать бытовку" })
    )

    expect(
      (
        await screen.findByRole("dialog", {
          name: "Заявка на списание бытовки",
        })
      ).textContent
    ).toBe(`WRITE_OFF:${WAREHOUSE_ID}`)
    expect(propertyDispositionDialog.render).toHaveBeenCalledWith(
      expect.objectContaining({
        accessToken: "asset-token",
        warehouseId: WAREHOUSE_ID,
        disposition: "WRITE_OFF",
        open: true,
      })
    )
  })

  it("removes the warehouse pagination footer when the registry is fully loaded", async () => {
    assetApi.listAssetRentalItems.mockResolvedValue({
      content: [rentalItem()],
      page: 0,
      size: 200,
      totalElements: 127,
      totalPages: 1,
    })

    renderWithQuery(<RentalItemsPage />)

    await screen.findByText("БЫТ-001")

    expect(screen.queryByText("Показано 1 из 127")).toBeNull()
    expect(screen.queryByRole("button", { name: "Назад" })).toBeNull()
    expect(screen.queryByText("Страница 1 из 1")).toBeNull()
    expect(screen.queryByRole("button", { name: "Вперёд" })).toBeNull()
  })

  it("uses one cover batch and loads owner media only after opening one cabin", async () => {
    window.localStorage.setItem(
      `rental-items:${WAREHOUSE_ID}:columns:v2`,
      JSON.stringify([{ id: "hasPhotos", visible: true }])
    )
    assetApi.listAssetRentalItems.mockResolvedValue({
      content: [rentalItem()],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    })
    mediaApi.listCabinCovers.mockRejectedValue(
      new Error("Media gateway returned 502")
    )
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: 3, retryDelay: 0 },
        mutations: { retry: false },
      },
    })

    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <RentalItemsPage />
        </QueryClientProvider>
      </MemoryRouter>
    )

    expect(await screen.findByText("Сервис фото недоступен")).toBeTruthy()
    expect(mediaApi.listCabinCovers).toHaveBeenCalledTimes(1)
    expect(mediaApi.listOwnerMedia).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("button", {
        name: "Открыть фото бытовки БЫТ-001",
      })
    )

    await waitFor(() =>
      expect(mediaApi.listOwnerMedia).toHaveBeenCalledTimes(1)
    )
    expect(mediaApi.listOwnerMedia).toHaveBeenCalledWith(
      "asset-token",
      {
        ownerType: "CABIN",
        ownerId: RENTAL_ITEM_ID,
        warehouseId: WAREHOUSE_ID,
        context: "WAREHOUSE",
      },
      { limit: 100 }
    )
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
    await selectRequiredComposition(user, dialog)
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

  it("keeps the canonical warehouse cabin passport visible for a sanblock", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Добавить новую бытовку" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создание новой бытовки",
    })
    await user.type(within(dialog).getByLabelText("Номер бытовки"), "БЫТ-010")
    await selectRequiredComposition(user, dialog, "БК-Санблок")

    expect(
      dialog.querySelector('[data-rental-item-section="passport"]')
    ).not.toBeNull()
    expect(
      dialog.querySelector('[data-rental-item-section="sanblock-settings"]')
    ).toBeNull()
    expect(
      within(dialog).getByRole("button", { name: "БК-Санблок" })
    ).toBeTruthy()
    expect(within(dialog).getByRole("button", { name: "2.4x6" })).toBeTruthy()
    expect(within(dialog).getByRole("button", { name: "ДВП" })).toBeTruthy()
    expect(within(dialog).getByRole("radio", { name: "Нет" })).toBeTruthy()
  })

  it("validates the cabin number locally and permits an underscore", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Добавить новую бытовку" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создание новой бытовки",
    })
    const numberInput = within(dialog).getByLabelText("Номер бытовки")
    await user.type(numberInput, "тест/1")
    await selectRequiredComposition(user, dialog)
    await user.click(
      within(dialog).getByRole("button", { name: "Создать бытовку" })
    )

    expect(
      await within(dialog).findByText(
        "Номер бытовки должен содержать от 1 до 128 символов, начинаться с буквы или цифры и включать только буквы, цифры, пробелы, дефис (-) или символ подчёркивания (_)."
      )
    ).toBeTruthy()
    expect(assetApi.createAssetRentalItem).not.toHaveBeenCalled()

    await user.clear(numberInput)
    await user.type(numberInput, "тест_1")
    await user.click(
      within(dialog).getByRole("button", { name: "Создать бытовку" })
    )

    await waitFor(() =>
      expect(assetApi.createAssetRentalItem).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "asset-token",
          input: expect.objectContaining({
            warehouseId: WAREHOUSE_ID,
            number: "тест_1",
          }),
        })
      )
    )
  })

  it("creates the cabin before uploading staged photos through media-service", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Добавить новую бытовку" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создание новой бытовки",
    })
    const file = new File([new Uint8Array([1, 2, 3])], "new-cabin.jpg", {
      type: "image/jpeg",
    })
    await user.upload(
      within(dialog).getByLabelText("Фотографии новой бытовки"),
      file
    )
    await user.type(within(dialog).getByLabelText("Номер бытовки"), "БЫТ-009")
    await selectRequiredComposition(user, dialog)
    await user.click(
      within(dialog).getByRole("button", { name: "Создать бытовку" })
    )

    await waitFor(() =>
      expect(assetApi.createAssetRentalItem).toHaveBeenCalledTimes(1)
    )
    await waitFor(() =>
      expect(mediaApi.uploadFile).toHaveBeenCalledWith(
        "asset-token",
        {
          ownerType: "CABIN",
          ownerId: RENTAL_ITEM_ID,
          warehouseId: WAREHOUSE_ID,
          context: "WAREHOUSE",
        },
        file,
        0,
        expect.any(String),
        {
          createSession: expect.any(String),
          uploadAndFinalize: expect.any(String),
        }
      )
    )
  })

  it("keeps a created cabin and retries only its failed photo upload", async () => {
    authState.level = "EDIT"
    mediaApi.uploadFile
      .mockRejectedValueOnce(new Error("Сервис фото недоступен"))
      .mockResolvedValueOnce({ session: {}, uploadedObject: {}, asset: {} })
    const user = userEvent.setup()
    renderWithQuery(<RentalItemsPage />)

    await user.click(
      await screen.findByRole("button", { name: "Добавить новую бытовку" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создание новой бытовки",
    })
    const file = new File([new Uint8Array([1, 2, 3])], "retry.jpg", {
      type: "image/jpeg",
    })
    await user.upload(
      within(dialog).getByLabelText("Фотографии новой бытовки"),
      file
    )
    await user.type(within(dialog).getByLabelText("Номер бытовки"), "БЫТ-010")
    await selectRequiredComposition(user, dialog)
    await user.click(
      within(dialog).getByRole("button", { name: "Создать бытовку" })
    )

    await screen.findByText(/Бытовка БЫТ-001 создана, но фото не загружены/)
    const firstUploadArguments = mediaApi.uploadFile.mock.calls[0]
    await user.click(
      within(dialog).getByRole("button", {
        name: "Повторить загрузку фото",
      })
    )

    await waitFor(() => expect(mediaApi.uploadFile).toHaveBeenCalledTimes(2))
    expect(assetApi.createAssetRentalItem).toHaveBeenCalledTimes(1)
    expect(mediaApi.uploadFile.mock.calls[1]?.[4]).toBe(
      firstUploadArguments?.[4]
    )
    expect(mediaApi.uploadFile.mock.calls[1]?.[5]).toEqual(
      firstUploadArguments?.[5]
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
    expect(dossierApi.getRentalItemDossierPage).toHaveBeenCalledWith(
      "asset-token",
      RENTAL_ITEM_ID,
      expect.objectContaining({ limit: 25, after: undefined })
    )
    expect(screen.queryByRole("button", { name: "Изменить статус" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Добавить фото" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Отправить в ремонт" })
    ).toBeNull()

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

  it("keeps every old-panel dossier tab in the service-backed detail", async () => {
    renderDetail(`/warehouse/${RENTAL_ITEM_ID}`)

    await screen.findByText("БЫТ-001")
    for (const tab of [
      "Обзор",
      "Фото",
      "Осмотры",
      "Сметы",
      "Ремонт",
      "Резервы",
      "Отгрузки",
      "Возвраты",
      "История",
      "Комментарии",
    ]) {
      expect(screen.getByRole("tab", { name: tab })).toBeTruthy()
    }
  })

  it("reserves service-backed contents controls for MANAGE access", async () => {
    authState.level = "MANAGE"
    renderDetail(`/warehouse/${RENTAL_ITEM_ID}`)

    await screen.findByText("БЫТ-001")
    expect(screen.getByRole("button", { name: "Добавить" })).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "В другую бытовку" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "На склад" })).toBeNull()
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
    await waitFor(() =>
      expect(screen.getAllByText("Свободна").length).toBeGreaterThan(0)
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

  it("uploads a cabin photo through media-service from the transferred action", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderDetail(`/warehouse/${RENTAL_ITEM_ID}`)

    await user.click(
      await screen.findByRole("button", { name: "Добавить фото" })
    )
    const dialog = screen.getByRole("dialog", {
      name: "Добавить фотографии",
    })
    const file = new File([new Uint8Array([1, 2, 3])], "cabin.jpg", {
      type: "image/jpeg",
    })
    await user.upload(within(dialog).getByLabelText("Фотографии бытовки"), file)
    await user.click(
      within(dialog).getByRole("button", { name: "Добавить фото" })
    )

    await waitFor(() =>
      expect(mediaApi.uploadFile).toHaveBeenCalledWith(
        "asset-token",
        {
          ownerType: "CABIN",
          ownerId: RENTAL_ITEM_ID,
          warehouseId: WAREHOUSE_ID,
          context: "WAREHOUSE",
        },
        file,
        0,
        expect.any(String),
        {
          createSession: expect.any(String),
          uploadAndFinalize: expect.any(String),
        }
      )
    )
  })

  it("opens a service-backed repair with the selected cabin prefilled", async () => {
    authState.level = "EDIT"
    const user = userEvent.setup()
    renderDetail(`/warehouse/${RENTAL_ITEM_ID}`)

    await user.click(
      await screen.findByRole("button", { name: "Отправить в ремонт" })
    )

    const location = JSON.parse(
      screen.getByTestId("repair-location").textContent ?? "{}"
    ) as { search?: string; state?: Record<string, unknown> }
    expect(location.search).toBe("?create=1")
    expect(location.state).toEqual({
      workspaceEntry: true,
      rentalItemSeed: {
        type: "rental-item-repair-seed-v1",
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID,
        number: "БЫТ-001",
      },
    })
  })
})
