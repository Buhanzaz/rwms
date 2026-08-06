import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState, type ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const authState = vi.hoisted(() => ({
  accessToken: "asset-token",
}))

const mediaApi = vi.hoisted(() => ({
  uploadFile: vi.fn(),
  listOwnerMedia: vi.fn(),
  rotate: vi.fn(),
}))

const assetApi = vi.hoisted(() => ({
  getRentalItemCreationOptions: vi.fn(),
  createIdempotencyKey: vi.fn(() => "11111111-1111-4111-8111-111111111111"),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => authState,
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

vi.mock(
  "@/features/rental-items/api/asset-rental-items-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/rental-items/api/asset-rental-items-api")
    >()),
    ...assetApi,
  })
)

import { RentalItemCreationDialog } from "@/features/rental-items/rental-item-create-dialog"
import {
  RentalItemCreationPhotoUploader,
  type StagedRentalItemPhoto,
} from "@/features/rental-items/rental-item-creation-photo-uploader"

const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ID = "44444444-4444-4444-8444-444444444444"
const FINISHING_ID = "55555555-5555-4555-8555-555555555555"
const CHARACTERISTIC_ID = "66666666-6666-4666-8666-666666666666"

function renderWithQuery(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>{node}</QueryClientProvider>
  )
}

function PhotoUploaderHarness() {
  const [photos, setPhotos] = useState<StagedRentalItemPhoto[]>([])

  return (
    <RentalItemCreationPhotoUploader photos={photos} onChange={setPhotos} />
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mediaApi.uploadFile.mockResolvedValue({
    asset: { id: "media-1" },
  })
  mediaApi.listOwnerMedia.mockResolvedValue({ items: [], next: null })
  mediaApi.rotate.mockResolvedValue(undefined)
  assetApi.getRentalItemCreationOptions.mockResolvedValue({
    newCategory: "Новая",
    usedCategories: ["Обычная"],
    rentalTypes: [{ id: TYPE_ID, name: "БК-1" }],
    dimensions: [{ id: DIMENSION_ID, name: "2.4x6" }],
    finishings: [{ id: FINISHING_ID, name: "ДВП" }],
    categories: [{ id: "category-new", name: "Новая" }],
    characteristics: [{ id: CHARACTERISTIC_ID, name: "Пластиковое окно" }],
    typeDimensions: [
      { typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 },
    ],
  })
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn((file: File) => `blob:${file.name}`),
  })
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe("RentalItemCreationDialog linoleum selection", () => {
  it("blocks creation until Есть or Нет is selected and sends a boolean", async () => {
    const user = userEvent.setup()
    const createRentalItem = vi.fn().mockResolvedValue({
      createdItem: {
        id: "rental-item-1",
        warehouseId: "warehouse-1",
        number: "БЫТ-121",
      },
      value: { id: "rental-item-1" },
    })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        createRentalItem={createRentalItem}
      />
    )

    await user.type(await screen.findByLabelText("Номер бытовки"), "БЫТ-121")
    await user.click(
      await screen.findByRole("button", { name: /Выберите тип/ })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "2.4x6" }))
    await user.click(screen.getByRole("button", { name: /Выберите отделку/ }))
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))

    expect(screen.getByText("Выберите, есть ли линолеум.")).toBeTruthy()
    expect(createRentalItem).not.toHaveBeenCalled()

    await user.click(screen.getByRole("radio", { name: "Нет" }))
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))

    await waitFor(() => expect(createRentalItem).toHaveBeenCalledOnce())
    expect(createRentalItem).toHaveBeenCalledWith({
      idempotencyKey: "11111111-1111-4111-8111-111111111111",
      number: "БЫТ-121",
      rentalTypeId: TYPE_ID,
      dimensionId: DIMENSION_ID,
      finishingId: FINISHING_ID,
      category: "Новая",
      characteristicIds: [],
      linoleum: false,
      photos: [],
    })
  })
})

describe("RentalItemCreationDialog photo configuration", () => {
  it("skips cabin photo staging when the caller owns photos later", async () => {
    const user = userEvent.setup()
    const onCompleted = vi.fn()
    const createRentalItem = vi.fn().mockResolvedValue({
      createdItem: {
        id: "rental-item-1",
        warehouseId: "warehouse-1",
        number: "БЫТ-123",
      },
      value: { id: "rental-item-1" },
    })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        photosEnabled={false}
        onOpenChange={vi.fn()}
        createRentalItem={createRentalItem}
        onCompleted={onCompleted}
      />
    )

    await user.type(await screen.findByLabelText("Номер бытовки"), "БЫТ-123")
    expect(screen.queryByLabelText("Фотографии новой бытовки")).toBeNull()
    expect(screen.queryByRole("button", { name: "Загрузить фото" })).toBeNull()

    await user.click(
      await screen.findByRole("button", { name: /Выберите тип/ })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "2.4x6" }))
    await user.click(screen.getByRole("button", { name: /Выберите отделку/ }))
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("radio", { name: "Нет" }))
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))

    await waitFor(() => expect(createRentalItem).toHaveBeenCalledOnce())
    expect(mediaApi.uploadFile).not.toHaveBeenCalled()
    expect(onCompleted).toHaveBeenCalledWith(
      { id: "rental-item-1" },
      {
        id: "rental-item-1",
        warehouseId: "warehouse-1",
        number: "БЫТ-123",
      }
    )
  })
})

describe("RentalItemCreationPhotoUploader title photo", () => {
  it("marks the first photo and lets the user choose another title", async () => {
    const user = userEvent.setup()
    render(<PhotoUploaderHarness />)

    await user.upload(screen.getByLabelText("Фотографии новой бытовки"), [
      new File(["first"], "first.jpg", { type: "image/jpeg" }),
      new File(["second"], "second.jpg", { type: "image/jpeg" }),
    ])

    const firstCard = screen
      .getByRole("img", { name: "first.jpg" })
      .closest("article")
    const secondCard = screen
      .getByRole("img", { name: "second.jpg" })
      .closest("article")

    expect(firstCard?.className).toContain("border-primary")

    await user.click(
      within(secondCard as HTMLElement).getByRole("button", {
        name: "Выбрать титульным second.jpg",
      })
    )

    expect(secondCard?.className).toContain("border-primary")
  })

  it("uploads the chosen title with sortOrder zero", async () => {
    const user = userEvent.setup()
    const createRentalItem = vi.fn().mockResolvedValue({
      createdItem: {
        id: "rental-item-1",
        warehouseId: "warehouse-1",
        number: "БЫТ-122",
      },
      value: { id: "rental-item-1" },
    })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        createRentalItem={createRentalItem}
      />
    )

    await user.type(await screen.findByLabelText("Номер бытовки"), "БЫТ-122")
    await user.click(
      await screen.findByRole("button", { name: /Выберите тип/ })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "2.4x6" }))
    await user.click(screen.getByRole("button", { name: /Выберите отделку/ }))
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("radio", { name: "Есть" }))
    await user.upload(screen.getByLabelText("Фотографии новой бытовки"), [
      new File(["first"], "first.jpg", { type: "image/jpeg" }),
      new File(["second"], "second.jpg", { type: "image/jpeg" }),
    ])

    const secondCard = screen
      .getByRole("img", { name: "second.jpg" })
      .closest("article") as HTMLElement
    await user.click(
      within(secondCard).getByRole("button", {
        name: "Выбрать титульным second.jpg",
      })
    )
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))

    await waitFor(() => expect(mediaApi.uploadFile).toHaveBeenCalledTimes(2))
    expect(mediaApi.uploadFile.mock.calls[0]?.[2]).toMatchObject({
      name: "second.jpg",
    })
    expect(mediaApi.uploadFile.mock.calls[0]?.[3]).toBe(0)
    expect(mediaApi.uploadFile.mock.calls[1]?.[2]).toMatchObject({
      name: "first.jpg",
    })
    expect(mediaApi.uploadFile.mock.calls[1]?.[3]).toBe(1)
  })
})
