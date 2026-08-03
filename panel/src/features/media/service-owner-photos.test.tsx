import {
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

vi.mock("@/features/media/use-service-owner-media", () => ({
  serviceOwnerMediaQueryKey: () => ["service-owner-media"],
  useServiceOwnerMedia: () => mediaState.value,
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({
    photos,
    emptyLabel = "Нет фото",
  }: {
    photos: Array<{ id: string }>
    emptyLabel?: string
  }) => (
    <div>
      {photos.length === 0
        ? emptyLabel
        : photos.map((photo) => <span key={photo.id}>{photo.id}</span>)}
    </div>
  ),
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
    logicalPhotoCount: 0,
    readyReferences: [],
    query: { isError: false, isLoading: false, isSuccess: true },
    requestFullscreen: vi.fn(),
    upload: vi.fn(),
    rotate: vi.fn(),
    remove: vi.fn(),
    pending: false,
    error: null,
    previewUnavailable: false,
    ...overrides,
  }
}

beforeEach(() => {
  mediaState.value = mediaValue()
})

afterEach(cleanup)

describe("ServiceOwnerPhotos", () => {
  it("distinguishes an empty owner from an unavailable photo service", () => {
    const view = render(
      <ServiceOwnerPhotos
        accessToken="token"
        owner={owner}
        readOnly
        title="Фото сметы"
      />
    )

    expect(screen.getByText("Нет фото")).toBeTruthy()

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

    expect(screen.getByText("Сервис фото недоступен")).toBeTruthy()
    expect(screen.queryByText("Нет фото")).toBeNull()
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
    expect(screen.queryByText("Сервис фото недоступен")).toBeNull()
  })

  it("reports each logical READY asset once regardless of derived variants", () => {
    const onReadyReferencesChange = vi.fn()
    mediaState.value = mediaValue({
      logicalPhotoCount: 1,
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
    const upload = vi.fn().mockResolvedValue([uploadedAsset])
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
    fireEvent.change(screen.getByLabelText("Выбрать фотографии"), {
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
})
