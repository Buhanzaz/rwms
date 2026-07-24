import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { maintenanceEstimateMediaOwner } from "@/features/media/media-service"

const mediaState = vi.hoisted(() => ({
  value: {} as Record<string, unknown>,
}))

vi.mock("@/features/media/use-service-owner-media", () => ({
  serviceOwnerMediaQueryKey: () => ["service-owner-media"],
  useServiceOwnerMedia: () => mediaState.value,
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
