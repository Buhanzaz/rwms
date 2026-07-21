import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

const media = vi.hoisted(() => {
  const previewDispose = vi.fn()
  return {
    listOwnerMedia: vi.fn(),
    uploadFile: vi.fn(),
    createVariantObjectUrl: vi.fn(async () => ({
      url: "blob:inventory-preview",
      contentType: "image/jpeg",
      size: 3,
      dispose: previewDispose,
    })),
    createOriginalObjectUrl: vi.fn(),
    rotate: vi.fn(),
    previewDispose,
  }
})

vi.mock("@/features/media/media-service", () => ({
  createHttpMediaClient: () => media,
  inventoryFindingMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "INVENTORY_FINDING",
    ownerId,
    warehouseId,
    context: "INSPECTION",
  }),
  readyMediaReference: (asset: {
    id: string
    status: string
    generation: number
  }) =>
    asset.status === "READY" && asset.generation > 0
      ? { mediaId: asset.id, generation: asset.generation }
      : null,
}))

import { InventoryMediaEditor } from "@/features/inventory/inventory-media-editor"

const owner = {
  ownerType: "INVENTORY_FINDING",
  ownerId: "00000000-0000-4000-8000-000000000801",
  warehouseId: "00000000-0000-4000-8000-000000000802",
  context: "INSPECTION",
} as const

const readyAsset = {
  id: "00000000-0000-4000-8000-000000000803",
  folderId: "00000000-0000-4000-8000-000000000803",
  fileName: "inspection.jpg",
  contentType: "image/jpeg",
  kind: "IMAGE",
  status: "READY",
  version: 4,
  generation: 2,
  rotationDegrees: 0,
  sortOrder: 0,
  sizeBytes: 3,
  createdAt: "2026-07-18T10:00:00Z",
  variants: [
    {
      kind: "MEDIUM",
      contentType: "image/jpeg",
      contentPath:
        "/api/media/v1/assets/00000000-0000-4000-8000-000000000803/variants/MEDIUM/content",
      width: 640,
      height: 480,
    },
  ],
} as const

function renderEditor(
  onReadyChange = vi.fn(),
  onPendingChange = vi.fn(),
  readOnly = false
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <InventoryMediaEditor
        accessToken="inventory-token"
        scope={{ ownerId: owner.ownerId, warehouseId: owner.warehouseId }}
        readOnly={readOnly}
        onReadyChange={onReadyChange}
        onPendingChange={onPendingChange}
      />
    </QueryClientProvider>
  )
  return { ...view, onPendingChange, onReadyChange }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("InventoryMediaEditor", () => {
  it("keeps media mutations hidden in read-only mode", async () => {
    media.listOwnerMedia.mockResolvedValue({
      items: [readyAsset],
      next: null,
    })
    renderEditor(vi.fn(), vi.fn(), true)

    expect(await screen.findByText("Готов")).toBeTruthy()
    expect(screen.queryByLabelText("Фото и видео")).toBeNull()
    expect(screen.queryByRole("button", { name: "Повернуть 90°" })).toBeNull()
    expect(
      screen.getByRole("button", { name: "Открыть оригинал" })
    ).toBeTruthy()
  })

  it("uses the fixed inventory owner proof and exposes only READY generations", async () => {
    media.listOwnerMedia.mockResolvedValue({
      items: [
        readyAsset,
        {
          ...readyAsset,
          id: "00000000-0000-4000-8000-000000000804",
          status: "PROCESSING",
          generation: 0,
          variants: [],
        },
      ],
      next: null,
    })
    const { onPendingChange, onReadyChange, unmount } = renderEditor()

    await waitFor(() =>
      expect(onReadyChange).toHaveBeenLastCalledWith([
        { mediaId: readyAsset.id, generation: readyAsset.generation },
      ])
    )
    expect(media.listOwnerMedia).toHaveBeenCalledWith(
      "inventory-token",
      owner,
      { limit: 100 }
    )
    expect(media.createVariantObjectUrl).toHaveBeenCalledWith(
      "inventory-token",
      owner,
      readyAsset.variants[0]
    )
    expect(onPendingChange).toHaveBeenLastCalledWith(true)

    unmount()
    expect(media.previewDispose).toHaveBeenCalledOnce()
  })

  it("uploads through the shared same-origin client without browser persistence", async () => {
    media.listOwnerMedia.mockResolvedValue({ items: [], next: null })
    media.uploadFile.mockResolvedValue({
      session: {},
      uploadedObject: {},
      asset: readyAsset,
    })
    renderEditor()

    const input = await screen.findByLabelText("Фото и видео")
    const file = new File([new Uint8Array([1, 2, 3])], "inspection.jpg", {
      type: "image/jpeg",
    })
    fireEvent.change(input, { target: { files: [file] } })

    await waitFor(() =>
      expect(media.uploadFile).toHaveBeenCalledWith(
        "inventory-token",
        owner,
        file,
        0
      )
    )
  })
})
