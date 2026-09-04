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
  calculateChecksumSha256: vi.fn(),
  uploadFile: vi.fn(),
  listOwnerMedia: vi.fn(),
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

import {
  RentalItemCreationDialog,
  type RentalItemPhotoIntentWorkflow,
} from "@/features/rental-items/rental-item-create-dialog"
import type {
  RentalItemCreationIntent,
  RentalItemCreationPhotoManifestInput,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  RentalItemCreationPhotoUploader,
  type StagedRentalItemPhoto,
} from "@/features/rental-items/rental-item-creation-photo-uploader"

const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ID = "44444444-4444-4444-8444-444444444444"
const FINISHING_ID = "55555555-5555-4555-8555-555555555555"
const CHARACTERISTIC_ID = "66666666-6666-4666-8666-666666666666"
const INTENT_ID = "77777777-7777-4777-8777-777777777777"
const RENTAL_ITEM_ID = "88888888-8888-4888-8888-888888888888"
const MEDIA_FOLDER_ID = "99999999-9999-4999-8999-999999999999"
const MEDIA_COMMAND_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const FIRST_UPLOAD_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const SECOND_UPLOAD_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
const FIRST_CHECKSUM = "a".repeat(64)
const SECOND_CHECKSUM = "b".repeat(64)

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

function creationIntent(
  manifest: readonly RentalItemCreationPhotoManifestInput[] = [
    {
      photoIndex: 0,
      checksumSha256: FIRST_CHECKSUM,
      contentType: "image/jpeg",
      contentLength: 5,
    },
  ]
): RentalItemCreationIntent {
  return {
    id: INTENT_ID,
    version: 0,
    rentalItemId: RENTAL_ITEM_ID,
    warehouseId: WAREHOUSE_ID,
    state: "PENDING",
    expectedPhotoCount: manifest.length,
    mediaFolderId: MEDIA_FOLDER_ID,
    mediaCommandId: MEDIA_COMMAND_ID,
    photoManifestSha256: "d".repeat(64),
    photoManifest: manifest.map((photo, index) => ({
      ...photo,
      uploadCommandId: index === 0 ? FIRST_UPLOAD_ID : SECOND_UPLOAD_ID,
    })),
    coverMediaId: null,
    mediaProofSha256: null,
    createdAt: "2026-08-31T09:00:00Z",
    completedAt: null,
    abandonedAt: null,
  }
}

function creationResult(status = "FREE") {
  return {
    createdItem: {
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-122",
    },
    value: { id: RENTAL_ITEM_ID, status },
  }
}

function createPhotoIntentWorkflow(
  overrides: Partial<
    RentalItemPhotoIntentWorkflow<{ id: string; status?: string }>
  > = {}
) {
  const listPending = vi.fn().mockResolvedValue([])
  const create = vi.fn(
    async (
      _command: unknown,
      manifest: readonly RentalItemCreationPhotoManifestInput[]
    ) => ({ ...creationResult(), intent: creationIntent(manifest) })
  )
  const load = vi.fn().mockResolvedValue(creationResult())
  const complete = vi.fn(async (intent: RentalItemCreationIntent) => ({
    ...intent,
    version: intent.version + 1,
    state: "COMPLETED" as const,
    coverMediaId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    mediaProofSha256: "e".repeat(64),
    completedAt: "2026-08-31T09:05:00Z",
  }))
  const abandon = vi.fn(async (intent: RentalItemCreationIntent) => ({
    ...intent,
    version: intent.version + 1,
    state: "ABANDONED" as const,
    abandonedAt: "2026-08-31T09:06:00Z",
  }))
  return {
    pendingQueryKey: ["rental-item-creation-intents", WAREHOUSE_ID],
    listPending,
    create,
    load,
    complete,
    abandon,
    ...overrides,
  } satisfies RentalItemPhotoIntentWorkflow<{ id: string; status?: string }>
}

beforeEach(() => {
  vi.clearAllMocks()
  mediaApi.calculateChecksumSha256.mockImplementation(async (file: File) =>
    file.name.startsWith("second") ? SECOND_CHECKSUM : FIRST_CHECKSUM
  )
  mediaApi.uploadFile.mockImplementation(
    async (
      _accessToken: string,
      _owner: unknown,
      _file: File,
      sortOrder: number,
      folderId: string
    ) => ({
      asset: {
        id: sortOrder === 0 ? "media-1" : "media-2",
        folderId,
        status: "READY",
        generation: 1,
      },
    })
  )
  mediaApi.listOwnerMedia.mockResolvedValue({ items: [], next: null })
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
        photosEnabled={false}
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

    expect(screen.getByRole("group", { name: "Фото бытовки" })).toBeTruthy()
    const firstCard = screen
      .getByRole("img", { name: "first.jpg" })
      .closest("article")
    const secondCard = screen
      .getByRole("img", { name: "second.jpg" })
      .closest("article")

    expect(firstCard?.className).toContain("border-primary")
    expect(
      within(firstCard as HTMLElement)
        .getByRole("button", { name: "Титульное фото first.jpg" })
        .getAttribute("aria-pressed")
    ).toBe("true")

    await user.click(
      within(secondCard as HTMLElement).getByRole("button", {
        name: "Сделать титульным second.jpg",
      })
    )

    expect(secondCard?.className).toContain("border-primary")
    expect(
      within(secondCard as HTMLElement)
        .getByRole("button", { name: "Титульное фото second.jpg" })
        .getAttribute("aria-pressed")
    ).toBe("true")

    await user.click(
      within(secondCard as HTMLElement).getByRole("button", {
        name: "Удалить second.jpg",
      })
    )

    expect(screen.queryByRole("img", { name: "second.jpg" })).toBeNull()
    expect(
      within(firstCard as HTMLElement)
        .getByRole("button", { name: "Титульное фото first.jpg" })
        .getAttribute("aria-pressed")
    ).toBe("true")
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:second.jpg")
  })

  it("rejects an empty image before creating a staged photo", async () => {
    const user = userEvent.setup()
    render(<PhotoUploaderHarness />)

    await user.upload(
      screen.getByLabelText("Фотографии новой бытовки"),
      new File([], "empty.jpg", { type: "image/jpeg" })
    )

    expect(
      screen.getByText(
        "Пустое изображение нельзя загрузить. Сделайте фото заново или выберите другой файл."
      )
    ).toBeTruthy()
    expect(screen.queryByRole("img", { name: "empty.jpg" })).toBeNull()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it("creates an ordered intent and uploads with exact server keys", async () => {
    const user = userEvent.setup()
    const workflow = createPhotoIntentWorkflow()
    const onCompleted = vi.fn()

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        photoIntentWorkflow={workflow}
        onCompleted={onCompleted}
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
        name: "Сделать титульным second.jpg",
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
    expect(mediaApi.uploadFile.mock.calls[0]?.[4]).toBe(MEDIA_FOLDER_ID)
    expect(mediaApi.uploadFile.mock.calls[0]?.[5]).toEqual({
      createSession: FIRST_UPLOAD_ID,
      uploadAndFinalize: FIRST_UPLOAD_ID,
    })
    expect(mediaApi.uploadFile.mock.calls[1]?.[5]).toEqual({
      createSession: SECOND_UPLOAD_ID,
      uploadAndFinalize: SECOND_UPLOAD_ID,
    })
    expect(workflow.create).toHaveBeenCalledWith(
      expect.objectContaining({ number: "БЫТ-122" }),
      [
        {
          photoIndex: 0,
          checksumSha256: SECOND_CHECKSUM,
          contentType: "image/jpeg",
          contentLength: 6,
        },
        {
          photoIndex: 1,
          checksumSha256: FIRST_CHECKSUM,
          contentType: "image/jpeg",
          contentLength: 5,
        },
      ]
    )
    await waitFor(() => expect(workflow.complete).toHaveBeenCalledOnce())
    expect(workflow.complete).toHaveBeenCalledWith(
      expect.objectContaining({ id: INTENT_ID, version: 0 }),
      "11111111-1111-4111-8111-111111111111"
    )
    expect(onCompleted).toHaveBeenCalledWith(
      expect.objectContaining({ id: RENTAL_ITEM_ID }),
      expect.objectContaining({ id: RENTAL_ITEM_ID })
    )
  })
})

describe("RentalItemCreationDialog durable photo intent", () => {
  async function fillCreationForm(
    user: ReturnType<typeof userEvent.setup>,
    number: string
  ) {
    await user.type(await screen.findByLabelText("Номер бытовки"), number)
    await user.click(
      await screen.findByRole("button", { name: /Выберите тип/ })
    )
    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(screen.getByRole("button", { name: "2.4x6" }))
    await user.click(screen.getByRole("button", { name: /Выберите отделку/ }))
    await user.click(screen.getByRole("button", { name: "ДВП" }))
    await user.click(screen.getByRole("radio", { name: "Есть" }))
  }

  it("keeps a failed upload pending and never reports completion", async () => {
    const user = userEvent.setup()
    const workflow = createPhotoIntentWorkflow()
    const onCompleted = vi.fn()
    const onOpenChange = vi.fn()
    mediaApi.uploadFile.mockRejectedValueOnce(new Error("соединение потеряно"))

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={onOpenChange}
        photoIntentWorkflow={workflow}
        onCompleted={onCompleted}
      />
    )

    await fillCreationForm(user, "БЫТ-122")
    await user.upload(
      screen.getByLabelText("Фотографии новой бытовки"),
      new File(["first"], "first.jpg", { type: "image/jpeg" })
    )
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))

    expect(
      await screen.findByText(/Создание бытовки БЫТ-122 не завершено/)
    ).toBeTruthy()
    expect(screen.getByText(/пока недоступна для аренды/)).toBeTruthy()
    expect(workflow.complete).not.toHaveBeenCalled()
    expect(onCompleted).not.toHaveBeenCalled()

    await user.click(screen.getByRole("button", { name: "Отложить" }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(onCompleted).not.toHaveBeenCalled()
  })

  it("reuses the create key after a lost response for the same manifest", async () => {
    const user = userEvent.setup()
    const firstKey = "11111111-1111-4111-8111-111111111111"
    const unusedSecondKey = "22222222-2222-4222-8222-222222222223"
    assetApi.createIdempotencyKey
      .mockReturnValueOnce(firstKey)
      .mockReturnValueOnce(unusedSecondKey)
    const create = vi
      .fn()
      .mockRejectedValueOnce(new Error("соединение потеряно"))
      .mockImplementationOnce(
        async (
          _command: unknown,
          manifest: readonly RentalItemCreationPhotoManifestInput[]
        ) => ({ ...creationResult(), intent: creationIntent(manifest) })
      )
    const workflow = createPhotoIntentWorkflow({ create })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        photoIntentWorkflow={workflow}
      />
    )

    await fillCreationForm(user, "БЫТ-122")
    await user.upload(
      screen.getByLabelText("Фотографии новой бытовки"),
      new File(["first"], "first.jpg", { type: "image/jpeg" })
    )
    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))
    expect(await screen.findByText("соединение потеряно")).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Создать бытовку" }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(2))
    expect(
      create.mock.calls.map(([command]) => command.idempotencyKey)
    ).toEqual([firstKey, firstKey])
  })

  it("shows pending work after reload and explains a resume mismatch", async () => {
    const user = userEvent.setup()
    const pending = creationIntent()
    const workflow = createPhotoIntentWorkflow({
      listPending: vi.fn().mockResolvedValue([pending]),
    })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        photoIntentWorkflow={workflow}
      />
    )

    expect(await screen.findByText("Незавершённые создания")).toBeTruthy()
    expect(
      screen.getByText("Требуется фото: 1.", { exact: false })
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Продолжить" }))
    expect(await screen.findByText("Продолжение создания БЫТ-122")).toBeTruthy()
    await user.upload(
      screen.getByLabelText("Фотографии новой бытовки"),
      new File(["wrong!"], "first.jpg", { type: "image/jpeg" })
    )
    await user.click(
      screen.getByRole("button", { name: "Загрузить и завершить" })
    )

    expect(
      await screen.findByText(/не совпадают с сохранённым составом/)
    ).toBeTruthy()
    expect(mediaApi.uploadFile).not.toHaveBeenCalled()
    expect(workflow.complete).not.toHaveBeenCalled()
  })

  it("resumes matching files and completes the held cabin", async () => {
    const user = userEvent.setup()
    const pending = creationIntent()
    const workflow = createPhotoIntentWorkflow({
      listPending: vi.fn().mockResolvedValue([pending]),
    })
    const onCompleted = vi.fn()

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        photoIntentWorkflow={workflow}
        onCompleted={onCompleted}
      />
    )

    await user.click(await screen.findByRole("button", { name: "Продолжить" }))
    await user.upload(
      await screen.findByLabelText("Фотографии новой бытовки"),
      new File(["first"], "first.jpg", { type: "image/jpeg" })
    )
    await user.click(
      screen.getByRole("button", { name: "Загрузить и завершить" })
    )

    await waitFor(() => expect(workflow.complete).toHaveBeenCalledOnce())
    expect(mediaApi.uploadFile).toHaveBeenCalledWith(
      "asset-token",
      expect.objectContaining({ ownerId: RENTAL_ITEM_ID }),
      expect.objectContaining({ name: "first.jpg" }),
      0,
      MEDIA_FOLDER_ID,
      {
        createSession: FIRST_UPLOAD_ID,
        uploadAndFinalize: FIRST_UPLOAD_ID,
      }
    )
    expect(onCompleted).toHaveBeenCalledOnce()
  })

  it("abandons with confirmation without deleting the cabin or media", async () => {
    const user = userEvent.setup()
    const pending = creationIntent()
    const load = vi.fn().mockResolvedValue(creationResult("WAREHOUSE"))
    const workflow = createPhotoIntentWorkflow({
      listPending: vi.fn().mockResolvedValue([pending]),
      load,
    })

    renderWithQuery(
      <RentalItemCreationDialog
        open
        warehouseId={WAREHOUSE_ID}
        title="Создание бытовки"
        onOpenChange={vi.fn()}
        photoIntentWorkflow={workflow}
      />
    )

    await user.click(await screen.findByRole("button", { name: "Прекратить" }))
    const confirmation = await screen.findByRole("alertdialog")
    expect(
      within(confirmation).getByText(
        /Бытовка и уже загруженные фото не удалятся/
      )
    ).toBeTruthy()
    await user.click(
      within(confirmation).getByRole("button", { name: "Прекратить" })
    )

    await waitFor(() => expect(workflow.abandon).toHaveBeenCalledOnce())
    expect(workflow.abandon).toHaveBeenCalledWith(
      pending,
      "11111111-1111-4111-8111-111111111111"
    )
    expect(load).toHaveBeenCalledWith(
      expect.objectContaining({ state: "ABANDONED" })
    )
    expect(await screen.findByText(/статус бытовки — «На складе»/)).toBeTruthy()
  })
})
