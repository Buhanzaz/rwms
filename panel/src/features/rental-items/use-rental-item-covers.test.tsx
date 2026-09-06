import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import type {
  CabinCoverProjection,
  MediaVariant,
} from "@/features/media/media-service"
import { clearMediaPreviewCache } from "@/features/media/media-preview-cache"

const media = vi.hoisted(() => ({
  createOriginalObjectUrl: vi.fn(),
  createVariantObjectUrl: vi.fn(),
  listCabinCovers: vi.fn(),
  listOwnerMedia: vi.fn(),
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

import {
  loadRentalItemCoverPage,
  useRentalItemCardPhotos,
} from "@/features/rental-items/use-rental-item-covers"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const COVER_ID = "33333333-3333-4333-8333-333333333333"
const SECOND_ID = "44444444-4444-4444-8444-444444444444"

function variant(kind: "SMALL" | "MEDIUM" | "LARGE", contentPath: string) {
  return {
    kind,
    contentType: "image/webp",
    contentPath,
    width: 320,
    height: 240,
  } satisfies MediaVariant
}

const projection: CabinCoverProjection = {
  cabinId: CABIN_ID,
  photoCount: 2,
  cover: {
    mediaId: COVER_ID,
    generation: 1,
    ...variant("SMALL", "/cover-small"),
  },
  previews: [
    {
      mediaId: SECOND_ID,
      generation: 1,
      ...variant("SMALL", "/second-small"),
    },
    {
      mediaId: COVER_ID,
      generation: 1,
      ...variant("SMALL", "/cover-small"),
    },
  ],
}

function Harness({
  value = projection,
  selectedPhotoId,
}: {
  value?: CabinCoverProjection
  selectedPhotoId?: string
}) {
  const result = useRentalItemCardPhotos({
    accessToken: "read-token",
    cabinId: value.cabinId,
    warehouseId: WAREHOUSE_ID,
    selectedPhotoId,
    projection: value,
    coverAvailability: "available",
  })
  return (
    <div>
      <span data-testid="availability">{result.availability}</span>
      <span data-testid="photo-ids">
        {result.photos
          .filter((photo) => photo.url)
          .map((photo) => photo.id)
          .join(",")}
      </span>
      <span data-testid="original-url">
        {result.photos[0]?.variants?.original?.url ?? ""}
      </span>
      <button
        type="button"
        onClick={() => {
          const photo = result.photos[0]
          if (photo) void result.requestFullscreen(photo)
        }}
      >
        Открыть оригинал
      </button>
    </div>
  )
}

afterEach(() => {
  cleanup()
  clearMediaPreviewCache()
  vi.useRealTimers()
  media.createOriginalObjectUrl.mockReset()
  media.createVariantObjectUrl.mockReset()
  media.listCabinCovers.mockReset()
  media.listOwnerMedia.mockReset()
})

describe("useRentalItemCardPhotos", () => {
  it("renders cached photos immediately on remount without repeating byte requests", async () => {
    const dispose = vi.fn()
    media.createVariantObjectUrl.mockImplementation(
      async (_token: string, _owner: unknown, requested: MediaVariant) => ({
        url: `blob:${requested.contentPath}`,
        size: 1024,
        dispose,
      })
    )
    const first = render(<Harness />)
    await waitFor(() =>
      expect(screen.getByTestId("availability").textContent).toBe("available")
    )
    first.unmount()
    render(<Harness />)
    expect(screen.getByTestId("photo-ids").textContent).toBe(
      `${COVER_ID},${SECOND_ID}`
    )
    await waitFor(() =>
      expect(screen.getByTestId("availability").textContent).toBe("available")
    )
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(2)
    expect(dispose).not.toHaveBeenCalled()
  })

  it("loads the explicit cover first and preserves the remaining preview order", async () => {
    media.createVariantObjectUrl.mockImplementation(
      async (_token: string, _owner: unknown, requested: MediaVariant) => ({
        url: `blob:${requested.contentPath}`,
        dispose: vi.fn(),
      })
    )

    render(<Harness />)

    await waitFor(() =>
      expect(screen.getByTestId("photo-ids").textContent).toBe(
        `${COVER_ID},${SECOND_ID}`
      )
    )
    expect(screen.getByTestId("availability").textContent).toBe("available")
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(2)
    expect(
      media.createVariantObjectUrl.mock.calls.map(
        (call) => (call[2] as MediaVariant).kind
      )
    ).toEqual(["SMALL", "SMALL"])
    expect(
      media.createVariantObjectUrl.mock.calls.map(
        (call) => (call[2] as MediaVariant).contentPath
      )
    ).toEqual(["/cover-small", "/second-small"])
    expect(media.listOwnerMedia).not.toHaveBeenCalled()
    expect(media.createOriginalObjectUrl).not.toHaveBeenCalled()
  })

  it("keeps the server preview order when no explicit cover exists", async () => {
    media.createVariantObjectUrl.mockImplementation(
      async (_token: string, _owner: unknown, requested: MediaVariant) => ({
        url: `blob:${requested.contentPath}`,
        dispose: vi.fn(),
      })
    )

    render(<Harness value={{ ...projection, cover: null }} />)

    await waitFor(() =>
      expect(screen.getByTestId("photo-ids").textContent).toBe(
        `${SECOND_ID},${COVER_ID}`
      )
    )
    expect(
      media.createVariantObjectUrl.mock.calls.map(
        (call) => (call[2] as MediaVariant).contentPath
      )
    ).toEqual(["/second-small", "/cover-small"])
  })

  it("loads the original only when the card photo is opened fullscreen", async () => {
    media.createVariantObjectUrl.mockImplementation(
      async (_token: string, _owner: unknown, requested: MediaVariant) => ({
        url: `blob:${requested.contentPath}`,
        dispose: vi.fn(),
      })
    )
    media.createOriginalObjectUrl.mockResolvedValue({
      url: "blob:cover-original",
      dispose: vi.fn(),
    })

    render(<Harness />)

    await waitFor(() =>
      expect(screen.getByTestId("photo-ids").textContent).toBe(
        `${COVER_ID},${SECOND_ID}`
      )
    )
    expect(media.createOriginalObjectUrl).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole("button", { name: "Открыть оригинал" }))

    await waitFor(() =>
      expect(media.createOriginalObjectUrl).toHaveBeenCalledWith(
        "read-token",
        {
          ownerType: "CABIN",
          ownerId: CABIN_ID,
          warehouseId: WAREHOUSE_ID,
          context: "WAREHOUSE",
        },
        COVER_ID
      )
    )
    expect(screen.getByTestId("original-url").textContent).toBe(
      "blob:cover-original"
    )
  })

  it("retries a transient owner-proof failure while loading cabin covers", async () => {
    vi.useFakeTimers()
    media.listCabinCovers
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({ items: [] })

    const result = loadRentalItemCoverPage("read-token", WAREHOUSE_ID, [
      CABIN_ID,
    ])

    await vi.advanceTimersByTimeAsync(250)

    await expect(result).resolves.toEqual({ items: [] })
    expect(media.listCabinCovers).toHaveBeenCalledTimes(2)
  })

  it("surfaces a nonretryable cabin-cover error without retrying", async () => {
    const error = new ApiError("Media access denied", 403, "MEDIA_FORBIDDEN")
    media.listCabinCovers.mockRejectedValue(error)

    await expect(
      loadRentalItemCoverPage("read-token", WAREHOUSE_ID, [CABIN_ID])
    ).rejects.toBe(error)

    expect(media.listCabinCovers).toHaveBeenCalledOnce()
  })

  it("retries a transient owner-proof failure while loading card previews", async () => {
    media.createVariantObjectUrl
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockImplementation(
        async (_token: string, _owner: unknown, requested: MediaVariant) => ({
          url: `blob:${requested.contentPath}`,
          dispose: vi.fn(),
        })
      )

    render(<Harness />)

    await waitFor(() =>
      expect(screen.getByTestId("photo-ids").textContent).toBe(
        `${COVER_ID},${SECOND_ID}`
      )
    )

    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(3)
  })

  it("does not retry a nonretryable card-preview failure", async () => {
    media.createVariantObjectUrl.mockRejectedValue(
      new ApiError("Media access denied", 403, "MEDIA_FORBIDDEN")
    )

    render(<Harness />)

    await waitFor(() =>
      expect(screen.getByTestId("availability").textContent).toBe("unavailable")
    )
    await new Promise((resolve) => setTimeout(resolve, 300))

    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(2)
  })
})

it("loads only the cover and adjacent slides from a hundred-photo projection", async () => {
  const previews = Array.from({ length: 100 }, (_, index) => ({
    mediaId: `media-${index}`,
    generation: 1,
    ...variant("SMALL", `/preview-${index}`),
  }))
  const value: CabinCoverProjection = {
    cabinId: CABIN_ID,
    photoCount: 100,
    cover: previews[0],
    previews,
  }
  media.createVariantObjectUrl.mockImplementation(
    async (_token: string, _owner: unknown, preview: MediaVariant) => ({
      url: `blob:${preview.contentPath}`,
      size: 10,
      dispose: vi.fn(),
    })
  )
  const view = render(<Harness value={value} />)
  await waitFor(() =>
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(3)
  )
  expect(
    media.createVariantObjectUrl.mock.calls.map((call) => call[2].contentPath)
  ).toEqual(["/preview-0", "/preview-1", "/preview-99"])
  view.rerender(<Harness value={value} selectedPhotoId="media-50" />)
  await waitFor(() =>
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(6)
  )
  expect(
    media.createVariantObjectUrl.mock.calls
      .slice(3)
      .map((call) => call[2].contentPath)
  ).toEqual(["/preview-50", "/preview-51", "/preview-49"])
})

it("publishes the cover while its neighbour is still downloading and aborts on unmount", async () => {
  let neighbourSignal!: AbortSignal
  media.createVariantObjectUrl.mockImplementation(
    (
      _token: string,
      _owner: unknown,
      preview: MediaVariant,
      signal: AbortSignal
    ) => {
      if (preview.contentPath === "/cover-small")
        return Promise.resolve({
          url: "blob:cover",
          size: 10,
          dispose: vi.fn(),
        })
      neighbourSignal = signal
      return new Promise((_resolve, reject) =>
        signal.addEventListener("abort", () => reject(signal.reason), {
          once: true,
        })
      )
    }
  )
  const view = render(<Harness />)
  await waitFor(() =>
    expect(screen.getByTestId("availability").textContent).toBe("available")
  )
  expect(screen.getByTestId("photo-ids").textContent).toBe(COVER_ID)
  view.unmount()
  await waitFor(() => expect(neighbourSignal.aborted).toBe(true))
})
