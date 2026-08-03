import { useEffect } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const mediaClient = vi.hoisted(() => ({
  listOwnerMedia: vi.fn(),
  createVariantObjectUrl: vi.fn(),
  uploadFile: vi.fn(),
  rotate: vi.fn(),
  deleteAsset: vi.fn(),
}))

vi.mock("@/features/media/media-service", () => ({
  createHttpMediaClient: () => mediaClient,
  readyMediaReference: (asset: {
    id: string
    status: string
    generation: number
  }) =>
    asset.status === "READY" && asset.generation > 0
      ? { mediaId: asset.id, generation: asset.generation }
      : null,
}))

import { useServiceOwnerMedia } from "@/features/media/use-service-owner-media"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const OWNER_ID = "22222222-2222-4222-8222-222222222222"
const MEDIA_ID = "33333333-3333-4333-8333-333333333333"
const FOLDER_ID = "44444444-4444-4444-8444-444444444444"
const owner = {
  ownerType: "MAINTENANCE_ESTIMATE",
  ownerId: OWNER_ID,
  warehouseId: WAREHOUSE_ID,
  context: "ESTIMATE",
} as const

const readyAsset = {
  id: MEDIA_ID,
  folderId: FOLDER_ID,
  fileName: "estimate.jpg",
  contentType: "image/jpeg",
  kind: "IMAGE",
  status: "READY",
  version: 2,
  generation: 4,
  rotationDegrees: 0,
  sortOrder: 0,
  sizeBytes: 32,
  createdAt: "2026-07-26T10:00:00Z",
  variants: [
    {
      kind: "MEDIUM",
      contentType: "image/webp",
      contentPath: "/api/media/v1/assets/preview",
      width: 640,
      height: 480,
    },
  ],
} as const

function MediaHarness({
  onReferences,
  onUpload,
}: {
  onReferences?: (
    references: Array<{ mediaId: string; generation: number }>
  ) => void
  onUpload?: (assets: unknown) => void
}) {
  const media = useServiceOwnerMedia({ accessToken: "media-token", owner })
  useEffect(() => {
    onReferences?.([...media.readyReferences])
  }, [media.readyReferences, onReferences])

  return (
    <div>
      <span data-testid="query-state">
        {media.query.isError
          ? "error"
          : media.query.isSuccess
            ? "ready"
            : "pending"}
      </span>
      <span data-testid="ready-references">
        {media.readyReferences
          .map((reference) => `${reference.mediaId}:${reference.generation}`)
          .join(",")}
      </span>
      <span data-testid="preview">
        {media.photos.map((photo) => photo.url).join(",")}
      </span>
      <button
        type="button"
        onClick={() =>
          void media
            .upload([
              {
                file: new File([new Uint8Array([1])], "estimate.jpg", {
                  type: "image/jpeg",
                }),
                folderId: FOLDER_ID,
                sortOrder: 0,
                commandKeys: {
                  createSession: "55555555-5555-4555-8555-555555555555",
                  uploadAndFinalize: "66666666-6666-4666-8666-666666666666",
                },
              },
            ])
            .then((assets) => onUpload?.(assets))
        }
      >
        Upload
      </button>
      <button
        type="button"
        onClick={() =>
          void media.rotate({ asset: readyAsset, direction: "RIGHT" })
        }
      >
        Rotate
      </button>
      <button type="button" onClick={() => void media.remove(readyAsset)}>
        Delete
      </button>
    </div>
  )
}

function renderMediaHarness({
  onReferences,
  onUpload,
}: {
  onReferences?: (
    references: Array<{ mediaId: string; generation: number }>
  ) => void
  onUpload?: (assets: unknown) => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MediaHarness onReferences={onReferences} onUpload={onUpload} />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  cleanup()
})

describe("useServiceOwnerMedia", () => {
  it("recovers owner-proof list and preview requests, then reports READY references", async () => {
    const onReferences = vi.fn()
    mediaClient.listOwnerMedia
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({ items: [readyAsset], next: null })
    mediaClient.createVariantObjectUrl
      .mockRejectedValueOnce(new ApiError("Media unavailable", 503))
      .mockResolvedValueOnce({
        url: "blob:recovered-preview",
        dispose: vi.fn(),
      })

    renderMediaHarness({ onReferences })

    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledOnce()
    )
    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledTimes(2)
    )
    expect(screen.getByTestId("query-state").textContent).toBe("ready")
    expect(screen.getByTestId("ready-references").textContent).toBe(
      `${MEDIA_ID}:4`
    )
    expect(onReferences).toHaveBeenLastCalledWith([
      { mediaId: MEDIA_ID, generation: 4 },
    ])

    await waitFor(() =>
      expect(mediaClient.createVariantObjectUrl).toHaveBeenCalledOnce()
    )
    await waitFor(() =>
      expect(mediaClient.createVariantObjectUrl).toHaveBeenCalledTimes(2)
    )
    expect(screen.getByTestId("preview").textContent).toBe(
      "blob:recovered-preview"
    )
  })

  it("retries an upload while owner proof propagates", async () => {
    mediaClient.listOwnerMedia.mockResolvedValue({ items: [], next: null })
    mediaClient.uploadFile
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({ asset: readyAsset })

    renderMediaHarness()

    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledOnce()
    )
    fireEvent.click(screen.getByRole("button", { name: "Upload" }))
    await waitFor(() => expect(mediaClient.uploadFile).toHaveBeenCalledOnce())
    await waitFor(() => expect(mediaClient.uploadFile).toHaveBeenCalledTimes(2))
  })

  it("returns assets created by this upload invocation", async () => {
    const onUpload = vi.fn()
    mediaClient.listOwnerMedia.mockResolvedValue({ items: [], next: null })
    mediaClient.uploadFile.mockResolvedValue({ asset: readyAsset })

    renderMediaHarness({ onUpload })

    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledOnce()
    )
    fireEvent.click(screen.getByRole("button", { name: "Upload" }))
    await waitFor(() => expect(onUpload).toHaveBeenCalledWith([readyAsset]))
  })

  it("retries rotation with the same command key while owner proof propagates", async () => {
    mediaClient.listOwnerMedia.mockResolvedValue({
      items: [readyAsset],
      next: null,
    })
    mediaClient.createVariantObjectUrl.mockResolvedValue({
      url: "blob:preview",
      dispose: vi.fn(),
    })
    mediaClient.rotate
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({})

    renderMediaHarness()

    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledOnce()
    )
    fireEvent.click(screen.getByRole("button", { name: "Rotate" }))
    await waitFor(() => expect(mediaClient.rotate).toHaveBeenCalledTimes(2))
    expect(mediaClient.rotate.mock.calls[1]?.[5]).toBe(
      mediaClient.rotate.mock.calls[0]?.[5]
    )
  })

  it("retries deletion with the same command key while owner proof propagates", async () => {
    mediaClient.listOwnerMedia.mockResolvedValue({
      items: [readyAsset],
      next: null,
    })
    mediaClient.createVariantObjectUrl.mockResolvedValue({
      url: "blob:preview",
      dispose: vi.fn(),
    })
    mediaClient.deleteAsset
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({})

    renderMediaHarness()

    await waitFor(() =>
      expect(mediaClient.listOwnerMedia).toHaveBeenCalledOnce()
    )
    fireEvent.click(screen.getByRole("button", { name: "Delete" }))
    await waitFor(() =>
      expect(mediaClient.deleteAsset).toHaveBeenCalledTimes(2)
    )
    expect(mediaClient.deleteAsset.mock.calls[1]?.[4]).toBe(
      mediaClient.deleteAsset.mock.calls[0]?.[4]
    )
  })
})
