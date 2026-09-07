import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { maintenanceEstimateMediaOwner } from "@/features/media/media-service"

const mediaState = vi.hoisted(() => ({
  value: {} as Record<string, unknown>,
}))
const photoCarousel = vi.hoisted(() => vi.fn())

vi.mock("@/features/media/use-service-owner-media", () => ({
  serviceOwnerMediaQueryKey: () => ["service-owner-media"],
  useServiceOwnerMedia: () => mediaState.value,
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: (props: {
    photos: Array<{ id: string }>
    emptyLabel?: string
  }) => {
    photoCarousel(props)
    const { photos, emptyLabel = "Нет фото" } = props
    return (
      <div>
        {photos.length === 0
          ? emptyLabel
          : photos.map((photo) => <span key={photo.id}>{photo.id}</span>)}
      </div>
    )
  },
}))

import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

const owner = maintenanceEstimateMediaOwner(
  "11111111-1111-4111-8111-111111111111",
  "22222222-2222-4222-8222-222222222222"
)

function mediaValue(overrides: Record<string, unknown> = {}) {
  return {
    assets: [],
    photos: [],
    videos: [],
    logicalPhotoCount: 0,
    logicalMediaCount: 0,
    readyReferences: [],
    query: { isError: false, isLoading: false, isSuccess: true },
    requestFullscreen: vi.fn(),
    retryPreviews: vi.fn(),
    upload: vi.fn(),
    remove: vi.fn(),
    uploadPending: false,
    deletePending: false,
    pending: false,
    error: null,
    previewUnavailable: false,
    ...overrides,
  }
}

beforeEach(() => {
  photoCarousel.mockClear()
  mediaState.value = mediaValue()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe("ServiceOwnerPhotos", () => {
  it("allows an explicit retry after a failed image download, not only owner-proof failures", () => {
    const retryPreviews = vi.fn()
    mediaState.value = mediaValue({
      assets: [{ id: "failed-photo", kind: "IMAGE", status: "READY" }],
      previewUnavailable: true,
      previewError: new Error("Network request failed"),
      retryPreviews,
    })
    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото ремонта"
        shrinkToContainer
      />
    )
    expect(
      screen
        .getByText("Сервис медиа недоступен")
        .parentElement?.classList.contains("xl:min-h-0")
    ).toBe(true)
    fireEvent.click(screen.getByRole("button", { name: "Повторить" }))
    expect(retryPreviews).toHaveBeenCalledOnce()
  })

  it("bounds work galleries to 320px with a fixed aspect ratio and preserves full-screen requests", () => {
    const photoId = "33333333-3333-4333-8333-333333333333"
    mediaState.value = mediaValue({
      assets: [{ id: photoId, kind: "IMAGE", status: "READY" }],
      photos: [{ id: photoId, url: "blob:work-photo" }],
      logicalPhotoCount: 1,
      logicalMediaCount: 1,
      readyReferences: [{ mediaId: photoId, generation: 1 }],
    })

    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото работы"
      />
    )

    expect(photoCarousel).toHaveBeenLastCalledWith(
      expect.objectContaining({
        showPhotoCount: false,
        controlsVisibility: "mobile-visible",
        className: "min-h-56 flex-1 rounded-lg border",
        fit: "contain",
      })
    )

    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото работы"
        shrinkToContainer
      />
    )

    expect(photoCarousel).toHaveBeenLastCalledWith(
      expect.objectContaining({
        className: "min-h-56 flex-1 rounded-lg border xl:min-h-0",
        fit: "contain",
      })
    )

    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото работы"
        presentation="work-carousel"
        shrinkToContainer
      />
    )

    expect(photoCarousel).toHaveBeenLastCalledWith(
      expect.objectContaining({
        showPhotoCount: true,
        controlsVisibility: "always",
        className: "aspect-[4/3] w-full shrink-0 rounded-lg border",
        fit: "contain",
        onRequestFullscreen: mediaState.value.requestFullscreen,
      })
    )
    expect(
      screen.getByRole("region", { name: "Фото работы" }).className
    ).toContain("max-w-xs")
  })

  it("distinguishes an empty owner from an unavailable media service", () => {
    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото сметы"
      />
    )

    expect(screen.getByText("Нет медиа")).toBeTruthy()

    mediaState.value = mediaValue({
      query: { isError: true, isLoading: false, isSuccess: false },
    })
    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото сметы"
      />
    )

    expect(screen.getByText("Сервис медиа недоступен")).toBeTruthy()
    expect(screen.queryByText("Нет медиа")).toBeNull()
  })

  it("keeps an ordinary authorization error visible", () => {
    mediaState.value = mediaValue({
      query: {
        isError: true,
        isLoading: false,
        isSuccess: false,
        error: new ApiError("Нет доступа к фотографиям", 403),
      },
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото сметы"
      />
    )

    expect(screen.getByText("Нет доступа к фотографиям")).toBeTruthy()
    expect(screen.queryByText("Сервис медиа недоступен")).toBeNull()
  })

  it("reports each logical READY asset once regardless of derived variants", () => {
    const onReadyReferencesChange = vi.fn()
    mediaState.value = mediaValue({
      logicalPhotoCount: 1,
      logicalMediaCount: 1,
      readyReferences: [
        {
          mediaId: "33333333-3333-4333-8333-333333333333",
          generation: 4,
        },
      ],
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото сметы"
        onReadyReferencesChange={onReadyReferencesChange}
      />
    )

    expect(screen.getByText("1 из 20")).toBeTruthy()
    expect(onReadyReferencesChange).toHaveBeenCalledWith([
      {
        mediaId: "33333333-3333-4333-8333-333333333333",
        generation: 4,
      },
    ])
  })

  it("uses an inventory finding's accepted references instead of every owner asset", () => {
    const onReadyReferencesChange = vi.fn()
    const acceptedMediaId = "33333333-3333-4333-8333-333333333333"
    const retryOrphanMediaId = "44444444-4444-4444-8444-444444444444"
    mediaState.value = mediaValue({
      assets: [
        { id: acceptedMediaId, kind: "IMAGE", status: "READY" },
        { id: retryOrphanMediaId, kind: "IMAGE", status: "READY" },
      ],
      photos: [
        { id: acceptedMediaId, url: "blob:accepted" },
        { id: retryOrphanMediaId, url: "blob:orphan" },
      ],
      logicalPhotoCount: 2,
      logicalMediaCount: 2,
      readyReferences: [
        { mediaId: acceptedMediaId, generation: 1 },
        { mediaId: retryOrphanMediaId, generation: 1 },
      ],
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        authoritativeReadyReferences={[
          { mediaId: acceptedMediaId, generation: 1 },
        ]}
        onReadyReferencesChange={onReadyReferencesChange}
      />
    )

    expect(screen.getByText("1 из 20")).toBeTruthy()
    expect(screen.getByText(acceptedMediaId)).toBeTruthy()
    expect(screen.queryByText(retryOrphanMediaId)).toBeNull()
    expect(onReadyReferencesChange).toHaveBeenCalledWith([
      { mediaId: acceptedMediaId, generation: 1 },
    ])
  })

  it("adds only the current editor upload to an authoritative selection", async () => {
    const onReadyReferencesChange = vi.fn()
    const uploadedMediaId = "55555555-5555-4555-8555-555555555555"
    const retryOrphanMediaId = "66666666-6666-4666-8666-666666666666"
    const uploadedAsset = {
      id: uploadedMediaId,
      kind: "IMAGE",
      status: "PROCESSING",
    }
    const upload = vi.fn(
      async (
        jobs: Array<{ onUploaded: (asset: typeof uploadedAsset) => void }>
      ) => {
        jobs[0]!.onUploaded(uploadedAsset)
        return [uploadedAsset]
      }
    )
    mediaState.value = mediaValue({ upload })

    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        authoritativeReadyReferences={[]}
        onReadyReferencesChange={onReadyReferencesChange}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Добавить" }))
    fireEvent.change(screen.getByLabelText("Выбрать медиафайлы"), {
      target: {
        files: [
          new File([new Uint8Array([1])], "new-photo.jpg", {
            type: "image/jpeg",
          }),
        ],
      },
    })
    await waitFor(() => expect(upload).toHaveBeenCalledOnce())

    mediaState.value = mediaValue({
      assets: [
        { ...uploadedAsset, status: "READY" },
        { id: retryOrphanMediaId, kind: "IMAGE", status: "READY" },
      ],
      photos: [
        { id: uploadedMediaId, url: "blob:uploaded" },
        { id: retryOrphanMediaId, url: "blob:orphan" },
      ],
      logicalPhotoCount: 2,
      logicalMediaCount: 2,
      readyReferences: [
        { mediaId: uploadedMediaId, generation: 1 },
        { mediaId: retryOrphanMediaId, generation: 1 },
      ],
    })
    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        authoritativeReadyReferences={[]}
        onReadyReferencesChange={onReadyReferencesChange}
      />
    )

    await waitFor(() =>
      expect(onReadyReferencesChange).toHaveBeenLastCalledWith([
        { mediaId: uploadedMediaId, generation: 1 },
      ])
    )
    expect(screen.getByText(uploadedMediaId)).toBeTruthy()
    expect(screen.queryByText(retryOrphanMediaId)).toBeNull()
  })

  it("shows the selected original and byte progress before enabling actions", async () => {
    let resolveUpload!: (value: Array<Record<string, unknown>>) => void
    const uploadResult = new Promise<Array<Record<string, unknown>>>(
      (resolve) => {
        resolveUpload = resolve
      }
    )
    const upload = vi.fn(
      (
        jobs: Array<{
          onProgress: (percentage: number) => void
          onUploaded: (asset: Record<string, unknown>) => void
        }>
      ) => {
        expect(jobs).toHaveLength(1)
        return uploadResult.then((assets) => {
          jobs[0]!.onUploaded(assets[0]!)
          return assets
        })
      }
    )
    mediaState.value = mediaValue({ upload, pending: true })
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:selected-original")
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined)
    const uploadedMediaId = "77777777-7777-4777-8777-777777777777"

    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        requireCover
      />
    )
    fireEvent.click(screen.getByRole("button", { name: "Добавить" }))
    const file = new File([new Uint8Array([1, 2, 3])], "original.jpg", {
      type: "image/jpeg",
    })
    fireEvent.change(screen.getByLabelText("Выбрать медиафайлы"), {
      target: { files: [file] },
    })

    expect(createObjectUrl).toHaveBeenCalledWith(file)
    expect(
      screen.getByAltText("Предпросмотр original.jpg").getAttribute("src")
    ).toBe("blob:selected-original")
    const jobs = upload.mock.calls[0]![0]
    act(() => jobs[0]!.onProgress(42))
    expect(
      screen
        .getByRole("progressbar", { name: "Загрузка original.jpg" })
        .getAttribute("aria-valuenow")
    ).toBe("42")
    expect(
      screen.queryByRole("button", { name: "Удалить original.jpg" })
    ).toBeNull()

    await act(async () => {
      resolveUpload([{ id: uploadedMediaId }])
      await uploadResult
    })
    mediaState.value = mediaValue({
      assets: [
        {
          id: uploadedMediaId,
          fileName: "original.jpg",
          kind: "IMAGE",
          status: "READY",
          sortOrder: 0,
          rotationDegrees: 0,
        },
      ],
      photos: [{ id: uploadedMediaId, url: "blob:server-preview" }],
      logicalPhotoCount: 1,
      logicalMediaCount: 1,
      readyReferences: [{ mediaId: uploadedMediaId, generation: 1 }],
    })
    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        requireCover
      />
    )

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Выбрать титульным" })
      ).toBeTruthy()
    )
    expect(
      screen.getByRole("button", { name: "Удалить original.jpg" })
    ).toBeTruthy()
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:selected-original")

    mediaState.value = mediaValue()
    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        requireCover
      />
    )
    expect(screen.queryByAltText("Предпросмотр original.jpg")).toBeNull()
    expect(
      screen.queryByRole("progressbar", { name: "Загрузка original.jpg" })
    ).toBeNull()
  })

  it("renders an adjacent action and limits a read-only comparison gallery", () => {
    mediaState.value = mediaValue({
      assets: [
        {
          id: "33333333-3333-4333-8333-333333333333",
          kind: "IMAGE",
          status: "READY",
        },
        {
          id: "44444444-4444-4444-8444-444444444444",
          kind: "IMAGE",
          status: "READY",
        },
      ],
      photos: [
        {
          id: "33333333-3333-4333-8333-333333333333",
          url: "blob:first",
        },
        {
          id: "44444444-4444-4444-8444-444444444444",
          url: "blob:second",
        },
      ],
      logicalPhotoCount: 2,
      logicalMediaCount: 2,
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото до"
        visibleMediaIds={["44444444-4444-4444-8444-444444444444"]}
        toolbarAction={<button type="button">Источник фото</button>}
      />
    )

    expect(screen.getByText("1 из 20")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Источник фото" })).toBeTruthy()
  })

  it("does not clear persisted references before the owner query succeeds", () => {
    const onReadyReferencesChange = vi.fn()
    const onReadyStateChange = vi.fn()
    mediaState.value = mediaValue({
      query: { isError: false, isLoading: true, isSuccess: false },
    })

    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        onReadyReferencesChange={onReadyReferencesChange}
        onReadyStateChange={onReadyStateChange}
      />
    )

    expect(onReadyReferencesChange).not.toHaveBeenCalled()
    expect(onReadyStateChange).toHaveBeenLastCalledWith(false)

    mediaState.value = mediaValue({
      readyReferences: [
        {
          mediaId: "44444444-4444-4444-8444-444444444444",
          generation: 3,
        },
      ],
    })
    view.rerender(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        onReadyReferencesChange={onReadyReferencesChange}
        onReadyStateChange={onReadyStateChange}
      />
    )

    expect(onReadyReferencesChange).toHaveBeenLastCalledWith([
      {
        mediaId: "44444444-4444-4444-8444-444444444444",
        generation: 3,
      },
    ])
    expect(onReadyStateChange).toHaveBeenLastCalledWith(true)
  })

  it("renders READY videos after images and reports their references", () => {
    const imageId = "33333333-3333-4333-8333-333333333333"
    const videoId = "44444444-4444-4444-8444-444444444444"
    const onReadyReferencesChange = vi.fn()
    mediaState.value = mediaValue({
      assets: [
        {
          id: imageId,
          fileName: "before.jpg",
          kind: "IMAGE",
          status: "READY",
          sortOrder: 0,
        },
        {
          id: videoId,
          fileName: "work.mp4",
          kind: "VIDEO",
          status: "READY",
          sortOrder: 1,
        },
      ],
      photos: [{ id: imageId, url: "blob:image" }],
      videos: [
        {
          id: videoId,
          fileName: "work.mp4",
          url: "blob:playback",
          contentType: "video/mp4",
          createdAt: "2026-08-17T00:00:00Z",
        },
      ],
      logicalPhotoCount: 1,
      logicalMediaCount: 2,
      readyReferences: [
        { mediaId: imageId, generation: 2 },
        { mediaId: videoId, generation: 3 },
      ],
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Медиа работ"
        onReadyReferencesChange={onReadyReferencesChange}
      />
    )

    expect(screen.getByText("2 из 20")).toBeTruthy()
    expect(screen.getByText(imageId)).toBeTruthy()
    const video = screen.getByLabelText("Видео work.mp4")
    expect(video.tagName).toBe("VIDEO")
    expect((video as HTMLVideoElement).src).toContain("blob:playback")
    expect((video as HTMLVideoElement).controls).toBe(true)
    expect((video as HTMLVideoElement).preload).toBe("metadata")
    expect(onReadyReferencesChange).toHaveBeenCalledWith([
      { mediaId: imageId, generation: 2 },
      { mediaId: videoId, generation: 3 },
    ])
  })

  it("clears a video id supplied as a cabin cover", async () => {
    const videoId = "44444444-4444-4444-8444-444444444444"
    const onCoverMediaIdChange = vi.fn()
    mediaState.value = mediaValue({
      assets: [
        {
          id: videoId,
          fileName: "work.mp4",
          kind: "VIDEO",
          status: "READY",
          sortOrder: 0,
        },
      ],
      videos: [
        {
          id: videoId,
          fileName: "work.mp4",
          url: "blob:playback",
          contentType: "video/mp4",
          createdAt: "2026-08-17T00:00:00Z",
        },
      ],
      logicalMediaCount: 1,
      readyReferences: [{ mediaId: videoId, generation: 1 }],
    })

    render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly={false}
        requireCover
        coverMediaId={videoId}
        onCoverMediaIdChange={onCoverMediaIdChange}
      />
    )

    await waitFor(() => expect(onCoverMediaIdChange).toHaveBeenCalledWith(null))
  })
})
