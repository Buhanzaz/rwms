import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const media = vi.hoisted(() => ({
  listOwnerMedia: vi.fn(),
  listCabinCovers: vi.fn(),
  createVariantObjectUrl: vi.fn(),
  createOriginalObjectUrl: vi.fn(),
  uploadFile: vi.fn(),
}))

vi.mock("@/features/media/media-service", () => ({
  cabinMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "CABIN",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  createHttpMediaClient: () => media,
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({ photos }: { photos: Array<{ id: string }> }) => (
    <div data-testid="folder-carousel">
      {photos.map((photo) => photo.id).join(",")}
    </div>
  ),
}))

import { useRentalItemMedia } from "@/features/rental-items/use-rental-item-media"
import { RentalItemPhotosRegister } from "@/features/rental-items/rental-item-media"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const ASSET_ID = "33333333-3333-4333-8333-333333333333"
const FOLDER_ID = "44444444-4444-4444-8444-444444444444"
const INITIAL_VARIANTS = ["SMALL"] as const
const ACTOR_ID = "55555555-5555-4555-8555-555555555555"
const NEW_ASSET_ID = "77777777-7777-4777-8777-777777777777"
const NEW_FOLDER_ID = "88888888-8888-4888-8888-888888888888"
const NEW_ACTOR_ID = "99999999-9999-4999-8999-999999999999"
const owner = {
  ownerType: "CABIN",
  ownerId: RENTAL_ITEM_ID,
  warehouseId: WAREHOUSE_ID,
  context: "WAREHOUSE",
} as const

const item: RentalItemDto = {
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
  linoleum: true,
  status: "WAREHOUSE",
  comment: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: {},
  tags: [],
}

function Harness() {
  const rentalItemMedia = useRentalItemMedia({
    item,
    accessToken: "media-token",
    initialVariants: INITIAL_VARIANTS,
    dossierActivities: [
      {
        activityId: "66666666-6666-4666-8666-666666666666",
        cabinId: RENTAL_ITEM_ID,
        warehouseId: WAREHOUSE_ID,
        activityCode: "MEDIA_READY",
        occurredAt: "2026-07-19T09:59:00Z",
        recordedAt: "2026-07-19T10:00:00Z",
        actorRef: {
          subjectId: ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "media-service",
          aggregateType: "MEDIA",
          aggregateId: ASSET_ID,
        },
        media: [
          {
            mediaId: ASSET_ID,
            folderId: FOLDER_ID,
            findingId: RENTAL_ITEM_ID,
            generation: 1,
            state: "READY",
          },
        ],
        taskEvidencePhotos: [],
      },
    ],
    actorDisplays: new Map([
      [
        ACTOR_ID,
        {
          subjectId: ACTOR_ID,
          principalType: "USER",
          globalRole: "WAREHOUSE_MANAGER",
          username: "f.ivanov",
          firstName: "Фёдор",
          lastName: "Иванов",
          email: "f.ivanov@example.test",
        },
      ],
    ]),
  })
  const service = rentalItemMedia.photos.find((photo) => photo.id === ASSET_ID)
  return (
    <div>
      <span data-testid="media-error">
        {String(Boolean(rentalItemMedia.error))}
      </span>
      <span data-testid="media-loading">
        {String(rentalItemMedia.isLoading)}
      </span>
      <span data-testid="photo-count">{rentalItemMedia.photos.length}</span>
      <span data-testid="logical-photo-count">
        {rentalItemMedia.logicalPhotoCount}
      </span>
      <span data-testid="folder-count">
        {rentalItemMedia.photoFolders.length}
      </span>
      <span data-testid="service-small">{service?.variants?.small?.url}</span>
      <span data-testid="service-medium">{service?.variants?.medium?.url}</span>
      <span data-testid="service-large">{service?.variants?.large?.url}</span>
      <span data-testid="service-actor">{service?.actorLabel}</span>
      <button
        type="button"
        onClick={() => void rentalItemMedia.requestFolderPreview(FOLDER_ID)}
      >
        Открыть папку
      </button>
      <button
        type="button"
        disabled={!service}
        onClick={() =>
          service && void rentalItemMedia.requestFullscreen(service)
        }
      >
        Полный экран
      </button>
      <button
        type="button"
        onClick={() =>
          void rentalItemMedia.upload([
            new File([new Uint8Array([1, 2, 3])], "first.jpg", {
              type: "image/jpeg",
            }),
            new File([new Uint8Array([4, 5, 6])], "second.jpg", {
              type: "image/jpeg",
            }),
          ])
        }
      >
        Загрузить
      </button>
    </div>
  )
}

function renderHarness() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>
  )
}

function ActivityAssociationHarness() {
  const rentalItemMedia = useRentalItemMedia({
    item,
    accessToken: "media-token",
    initialVariants: INITIAL_VARIANTS,
    dossierActivities: [
      {
        activityId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        cabinId: RENTAL_ITEM_ID,
        warehouseId: WAREHOUSE_ID,
        activityCode: "MEDIA_READY",
        occurredAt: "2026-07-20T09:59:00Z",
        recordedAt: "2026-07-20T10:00:00Z",
        actorRef: {
          subjectId: NEW_ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "media-service",
          aggregateType: "MEDIA",
          aggregateId: NEW_ASSET_ID,
        },
        media: [
          {
            mediaId: ASSET_ID,
            folderId: FOLDER_ID,
            findingId: RENTAL_ITEM_ID,
            generation: 1,
            state: "READY",
          },
          {
            mediaId: NEW_ASSET_ID,
            folderId: NEW_FOLDER_ID,
            findingId: RENTAL_ITEM_ID,
            generation: 1,
            state: "READY",
          },
        ],
        taskEvidencePhotos: [],
      },
      {
        activityId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        cabinId: RENTAL_ITEM_ID,
        warehouseId: WAREHOUSE_ID,
        activityCode: "MEDIA_READY",
        occurredAt: "2026-07-19T09:59:00Z",
        recordedAt: "2026-07-19T10:00:00Z",
        actorRef: {
          subjectId: ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "media-service",
          aggregateType: "MEDIA",
          aggregateId: ASSET_ID,
        },
        media: [
          {
            mediaId: ASSET_ID,
            folderId: FOLDER_ID,
            findingId: RENTAL_ITEM_ID,
            generation: 1,
            state: "READY",
          },
        ],
        taskEvidencePhotos: [],
      },
    ],
    actorDisplays: new Map([
      [
        ACTOR_ID,
        {
          subjectId: ACTOR_ID,
          principalType: "USER",
          globalRole: "WAREHOUSE_MANAGER",
          username: "old.actor",
          firstName: "Старый",
          lastName: "Автор",
          email: "old.actor@example.test",
        },
      ],
      [
        NEW_ACTOR_ID,
        {
          subjectId: NEW_ACTOR_ID,
          principalType: "USER",
          globalRole: "SYSTEM_ADMIN",
          username: "new.actor",
          firstName: "Новый",
          lastName: "Автор",
          email: "new.actor@example.test",
        },
      ],
    ]),
  })
  const oldFolder = rentalItemMedia.photoFolders.find(
    (folder) => folder.id === FOLDER_ID
  )
  const newFolder = rentalItemMedia.photoFolders.find(
    (folder) => folder.id === NEW_FOLDER_ID
  )

  return (
    <div>
      <span data-testid="old-folder-occurred-at">{oldFolder?.occurredAt}</span>
      <span data-testid="old-folder-actor">{oldFolder?.actorLabel}</span>
      <span data-testid="new-folder-occurred-at">{newFolder?.occurredAt}</span>
      <span data-testid="new-folder-actor">{newFolder?.actorLabel}</span>
    </div>
  )
}

function renderActivityAssociationHarness() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ActivityAssociationHarness />
    </QueryClientProvider>
  )
}

function CurrentInventoryFolderHarness() {
  const rentalItemMedia = useRentalItemMedia({
    item,
    accessToken: "media-token",
    initialVariants: INITIAL_VARIANTS,
    dossierActivities: [
      {
        activityId: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
        cabinId: RENTAL_ITEM_ID,
        warehouseId: WAREHOUSE_ID,
        activityCode: "INVENTORY_INSPECTION_SAVED",
        occurredAt: "2026-08-19T07:30:00Z",
        recordedAt: "2026-08-19T07:31:00Z",
        actorRef: {
          subjectId: NEW_ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "inventory-service",
          aggregateType: "INVENTORY_SESSION",
          aggregateId: "bbbbbbbb-1111-4bbb-8bbb-bbbbbbbbbbbb",
          secondaryId: "cccccccc-1111-4ccc-8ccc-cccccccccccc",
        },
        media: [
          {
            mediaId: NEW_ASSET_ID,
            folderId: NEW_FOLDER_ID,
            findingId: "cccccccc-1111-4ccc-8ccc-cccccccccccc",
            generation: 1,
            state: "READY",
          },
        ],
        taskEvidencePhotos: [],
      },
    ],
    actorDisplays: new Map([
      [
        NEW_ACTOR_ID,
        {
          subjectId: NEW_ACTOR_ID,
          principalType: "USER",
          globalRole: "WAREHOUSE_MANAGER",
          username: "inventory.actor",
          firstName: "Ирина",
          lastName: "Инвентаризатор",
          email: "inventory.actor@example.test",
        },
      ],
    ]),
  })
  const inventoryFolder = rentalItemMedia.photoFolders.find(
    (folder) => folder.id === NEW_FOLDER_ID
  )

  return (
    <div>
      <span data-testid="current-photo-ids">
        {rentalItemMedia.photos.map((photo) => photo.id).join(",")}
      </span>
      <span data-testid="current-photo-count">
        {rentalItemMedia.logicalPhotoCount}
      </span>
      <span data-testid="inventory-folder-source">
        {inventoryFolder?.sourceLabel}
      </span>
      <span data-testid="inventory-folder-actor">
        {inventoryFolder?.actorLabel}
      </span>
      <span data-testid="archive-folder-count">
        {rentalItemMedia.photoFolders.length}
      </span>
    </div>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

beforeEach(() => {
  media.listCabinCovers.mockResolvedValue({
    items: [
      {
        cabinId: RENTAL_ITEM_ID,
        photoCount: 1,
        cover: {
          mediaId: ASSET_ID,
          generation: 1,
          kind: "SMALL",
          contentType: "image/webp",
          contentPath: "/api/media/old-small.webp",
          width: 360,
          height: 240,
        },
        previews: [],
      },
    ],
  })
})

describe("rental item media", () => {
  it("keeps owner photos available when the cabin cover projection fails", async () => {
    media.listOwnerMedia.mockResolvedValue({
      items: [
        {
          id: ASSET_ID,
          folderId: FOLDER_ID,
          fileName: "service.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 1,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 0,
          sizeBytes: 3,
          createdAt: "2026-07-19T10:00:00Z",
          variants: [
            {
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/service-small.webp",
              width: 360,
              height: 240,
            },
          ],
        },
      ],
      next: null,
    })
    media.listCabinCovers.mockRejectedValue(
      new Error("Invalid cabin cover request")
    )
    media.createVariantObjectUrl.mockResolvedValue({
      url: "blob:service-small",
      contentType: "image/webp",
      size: 3,
      dispose: vi.fn(),
    })

    renderHarness()

    await waitFor(() =>
      expect(screen.getByTestId("photo-count").textContent).toBe("1")
    )
    expect(screen.getByTestId("folder-count").textContent).toBe("1")
    expect(screen.getByTestId("media-error").textContent).toBe("false")
    expect(screen.getByTestId("media-loading").textContent).toBe("false")
    expect(media.listCabinCovers).toHaveBeenCalledTimes(1)
  })

  it("still exposes an owner media failure", async () => {
    media.listOwnerMedia.mockRejectedValue(new Error("Owner media failed"))

    renderHarness()

    await waitFor(() =>
      expect(screen.getByTestId("media-error").textContent).toBe("true")
    )
    expect(screen.getByTestId("photo-count").textContent).toBe("0")
  })

  it("shows only the latest inventory folder as current and keeps older folders in history", async () => {
    media.listOwnerMedia.mockResolvedValue({
      items: [
        {
          id: ASSET_ID,
          folderId: FOLDER_ID,
          fileName: "acceptance.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 1,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 0,
          sizeBytes: 3,
          createdAt: "2026-07-19T09:58:00Z",
          variants: [
            {
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/acceptance-small.webp",
              width: 360,
              height: 240,
            },
          ],
        },
        {
          id: NEW_ASSET_ID,
          folderId: NEW_FOLDER_ID,
          fileName: "inventory.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 1,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 1,
          sizeBytes: 3,
          createdAt: "2026-08-19T07:29:00Z",
          variants: [
            {
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/inventory-small.webp",
              width: 360,
              height: 240,
            },
          ],
        },
      ],
      next: null,
    })
    media.listCabinCovers.mockResolvedValue({
      items: [
        {
          cabinId: RENTAL_ITEM_ID,
          photoCount: 1,
          cover: {
            mediaId: NEW_ASSET_ID,
            generation: 1,
            kind: "SMALL",
            contentType: "image/webp",
            contentPath: "/api/media/inventory-small.webp",
            width: 360,
            height: 240,
          },
          previews: [],
        },
      ],
    })
    media.createVariantObjectUrl.mockImplementation(
      async (_token, _owner, variant: { contentPath: string }) => ({
        url: `blob:${variant.contentPath}`,
        contentType: "image/webp",
        size: 3,
        dispose: vi.fn(),
      })
    )

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <CurrentInventoryFolderHarness />
      </QueryClientProvider>
    )

    await waitFor(() =>
      expect(screen.getByTestId("current-photo-ids").textContent).toBe(
        NEW_ASSET_ID
      )
    )
    expect(screen.getByTestId("current-photo-count").textContent).toBe("1")
    expect(screen.getByTestId("archive-folder-count").textContent).toBe("2")
    expect(screen.getByTestId("inventory-folder-source").textContent).toBe(
      "Инвентаризация"
    )
    expect(screen.getByTestId("inventory-folder-actor").textContent).toBe(
      "Руководитель склада — Инвентаризатор Ирина"
    )
  })

  it("prefers each asset's exact source activity over a newer secondary media association", async () => {
    media.listOwnerMedia.mockResolvedValue({
      items: [
        {
          id: ASSET_ID,
          folderId: FOLDER_ID,
          fileName: "old.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 1,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 0,
          sizeBytes: 3,
          createdAt: "2026-07-19T09:58:00Z",
          variants: [
            {
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/old-small.webp",
              width: 360,
              height: 240,
            },
          ],
        },
        {
          id: NEW_ASSET_ID,
          folderId: NEW_FOLDER_ID,
          fileName: "new.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 1,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 1,
          sizeBytes: 3,
          createdAt: "2026-07-20T09:58:00Z",
          variants: [
            {
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/new-small.webp",
              width: 360,
              height: 240,
            },
          ],
        },
      ],
      next: null,
    })
    media.createVariantObjectUrl.mockImplementation(
      async (_token, _owner, variant: { contentPath: string }) => ({
        url: `blob:${variant.contentPath}`,
        contentType: "image/webp",
        size: 3,
        dispose: vi.fn(),
      })
    )

    renderActivityAssociationHarness()

    await waitFor(() =>
      expect(screen.getByTestId("old-folder-occurred-at").textContent).toBe(
        "2026-07-19T09:59:00Z"
      )
    )
    expect(screen.getByTestId("old-folder-actor").textContent).toBe(
      "Руководитель склада — Автор Старый"
    )
    expect(screen.getByTestId("new-folder-occurred-at").textContent).toBe(
      "2026-07-20T09:59:00Z"
    )
    expect(screen.getByTestId("new-folder-actor").textContent).toBe(
      "Системный администратор — Автор Новый"
    )
  })

  it("loads SMALL, MEDIUM and LARGE only on demand and groups one batch", async () => {
    const dispose = vi.fn()
    media.listOwnerMedia.mockResolvedValue({
      items: [
        {
          id: ASSET_ID,
          folderId: FOLDER_ID,
          fileName: "service.jpg",
          contentType: "image/jpeg",
          kind: "IMAGE",
          status: "READY",
          version: 2,
          generation: 1,
          rotationDegrees: 0,
          sortOrder: 1,
          sizeBytes: 3,
          createdAt: "2026-07-19T10:00:00Z",
          variants: ["SMALL", "MEDIUM", "LARGE"].map((kind) => ({
            kind,
            contentType: "image/webp",
            contentPath: `/api/media/${kind.toLowerCase()}.webp`,
            width: kind === "SMALL" ? 360 : kind === "MEDIUM" ? 900 : 1800,
            height: kind === "SMALL" ? 240 : kind === "MEDIUM" ? 600 : 1200,
          })),
        },
      ],
      next: null,
    })
    media.createVariantObjectUrl.mockImplementation(
      async (_token, _owner, variant: { kind: string }) => ({
        url: `blob:${variant.kind.toLowerCase()}`,
        contentType: "image/webp",
        size: 3,
        dispose,
      })
    )
    media.uploadFile.mockResolvedValue({
      asset: {},
      session: {},
      uploadedObject: {},
    })
    const user = userEvent.setup()
    const view = renderHarness()

    await waitFor(() =>
      expect(screen.getByTestId("photo-count").textContent).toBe("1")
    )
    expect(screen.getByTestId("logical-photo-count").textContent).toBe("1")
    expect(screen.getByTestId("folder-count").textContent).toBe("1")
    expect(screen.getByTestId("service-small").textContent).toBe("blob:small")
    expect(screen.getByTestId("service-medium").textContent).toBe("")
    expect(screen.getByTestId("service-large").textContent).toBe("")
    expect(screen.getByTestId("service-actor").textContent).toBe(
      "Руководитель склада — Иванов Фёдор"
    )
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(1)
    expect(media.createVariantObjectUrl.mock.calls[0]?.[2]).toMatchObject({
      kind: "SMALL",
    })
    expect(media.createOriginalObjectUrl).not.toHaveBeenCalled()
    expect(media.listOwnerMedia).toHaveBeenCalledWith("media-token", owner, {
      limit: 100,
    })

    await user.click(screen.getByRole("button", { name: "Открыть папку" }))
    await waitFor(() =>
      expect(screen.getByTestId("service-medium").textContent).toBe(
        "blob:medium"
      )
    )
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(2)
    expect(media.createVariantObjectUrl.mock.calls[1]?.[2]).toMatchObject({
      kind: "MEDIUM",
    })
    expect(media.createOriginalObjectUrl).not.toHaveBeenCalled()

    await user.click(screen.getByRole("button", { name: "Полный экран" }))
    await waitFor(() =>
      expect(screen.getByTestId("service-large").textContent).toBe("blob:large")
    )
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(3)
    expect(media.createVariantObjectUrl.mock.calls[2]?.[2]).toMatchObject({
      kind: "LARGE",
    })
    expect(media.createOriginalObjectUrl).not.toHaveBeenCalled()

    await user.click(screen.getByRole("button", { name: "Загрузить" }))
    await waitFor(() => expect(media.uploadFile).toHaveBeenCalledTimes(2))
    const firstUpload = media.uploadFile.mock.calls[0]!
    const secondUpload = media.uploadFile.mock.calls[1]!
    expect(firstUpload.slice(0, 4)).toEqual([
      "media-token",
      owner,
      expect.objectContaining({ name: "first.jpg" }),
      1,
    ])
    expect(secondUpload.slice(0, 4)).toEqual([
      "media-token",
      owner,
      expect.objectContaining({ name: "second.jpg" }),
      2,
    ])
    expect(firstUpload[4]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    )
    expect(secondUpload[4]).toBe(firstUpload[4])
    expect(firstUpload[5]).toEqual({
      createSession: expect.any(String),
      uploadAndFinalize: expect.any(String),
    })
    expect(secondUpload[5]).toEqual({
      createSession: expect.any(String),
      uploadAndFinalize: expect.any(String),
    })
    expect(secondUpload[5]).not.toEqual(firstUpload[5])

    view.unmount()
    expect(dispose).toHaveBeenCalledTimes(3)
  })

  it("reuses upload command keys when owner proof is temporarily unavailable", async () => {
    media.listOwnerMedia.mockResolvedValue({ items: [], next: null })
    media.uploadFile
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is not ready",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValue({ asset: {}, session: {}, uploadedObject: {} })
    const user = userEvent.setup()
    renderHarness()

    await user.click(screen.getByRole("button", { name: "Загрузить" }))
    await waitFor(() => expect(media.uploadFile).toHaveBeenCalledTimes(3), {
      timeout: 3_000,
    })

    const failedAttempt = media.uploadFile.mock.calls[0]!
    const retryAttempt = media.uploadFile.mock.calls[1]!
    expect(retryAttempt[2]).toBe(failedAttempt[2])
    expect(retryAttempt[4]).toBe(failedAttempt[4])
    expect(retryAttempt[5]).toEqual(failedAttempt[5])
  })

  it("keeps the transferred searchable archive and folder back navigation", async () => {
    const user = userEvent.setup()
    const onOpenFolder = vi.fn(async () => undefined)
    const folders = [
      {
        id: "folder-1",
        occurredAt: "2026-07-19T10:00:00Z",
        actorLabel: "Руководитель склада — Фёдор Иванов",
        sourceLabel: "Добавленные фотографии",
        stage: "GENERAL" as const,
        photos: [
          {
            id: "photo-1",
            folderId: "folder-1",
            fileName: "front.jpg",
            url: "blob:medium",
            variants: {
              small: { url: "blob:small" },
              medium: { url: "blob:medium" },
            },
            occurredAt: "2026-07-19T10:00:00Z",
            actorLabel: "Руководитель склада — Фёдор Иванов",
            sourceLabel: "Добавленные фотографии",
            stage: "GENERAL" as const,
            processingStatus: "READY" as const,
            asset: null,
          },
        ],
        assets: [],
      },
      {
        id: "folder-2",
        occurredAt: "2026-07-18T10:00:00Z",
        actorLabel: "Менеджер аренды — Мария",
        sourceLabel: "Общие фотографии",
        stage: "GENERAL" as const,
        photos: [
          {
            id: "photo-2",
            folderId: "folder-2",
            fileName: "side.jpg",
            url: "blob:side-medium",
            variants: { small: { url: "blob:side-small" } },
            occurredAt: "2026-07-18T10:00:00Z",
            actorLabel: "Менеджер аренды — Мария",
            sourceLabel: "Общие фотографии",
            stage: "GENERAL" as const,
            processingStatus: "READY" as const,
            asset: null,
          },
        ],
        assets: [],
      },
    ]

    render(
      <RentalItemPhotosRegister
        item={item}
        folders={folders}
        assets={[]}
        loading={false}
        error={null}
        canEdit={false}
        onAdd={vi.fn()}
        onOpenFolder={onOpenFolder}
        onRequestFullscreen={vi.fn()}
      />
    )

    const search = screen.getByRole("textbox", { name: "Умный поиск" })
    await user.type(search, "федор фатографии")
    expect(
      screen.getByRole("button", { name: /Добавленные фотографии/ })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: /Общие фотографии.*Мария/ })
    ).toBeNull()

    await user.clear(search)
    await user.click(
      screen.getByRole("button", { name: /Добавленные фотографии/ })
    )
    expect(onOpenFolder).toHaveBeenCalledWith("folder-1")
    expect(
      screen.getByRole("button", { name: "Назад к фотоархиву" })
    ).toBeTruthy()
    expect(screen.getByText("front.jpg")).toBeTruthy()
    expect(screen.getByTestId("folder-carousel").textContent).toBe("photo-1")
    expect(screen.queryByRole("button", { name: "Повернуть" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "Назад к фотоархиву" }))
    expect(screen.getByTestId("photo-folder-grid")).toBeTruthy()
  })

  it("keeps the photo archive available when media-service is unavailable", () => {
    render(
      <RentalItemPhotosRegister
        item={item}
        folders={[]}
        assets={[]}
        loading={false}
        error={new Error("Invalid cabin cover request")}
        canEdit={false}
        onAdd={vi.fn()}
        onOpenFolder={vi.fn(async () => undefined)}
        onRequestFullscreen={vi.fn()}
      />
    )

    expect(screen.getByRole("alert").textContent).toContain(
      "Сервис фото недоступен"
    )
    expect(screen.queryByText("Invalid cabin cover request")).toBeNull()
    expect(screen.queryByText("Фотографии не найдены")).toBeNull()
    expect(screen.getByTestId("photo-folder-grid")).toBeTruthy()
  })
})
