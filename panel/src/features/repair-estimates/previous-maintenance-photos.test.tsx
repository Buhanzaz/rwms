import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import type { MediaAsset } from "@/features/media/media-service"

const media = vi.hoisted(() => ({
  listOwnerMedia: vi.fn(),
}))

const maintenance = vi.hoisted(() => ({
  listMaintenanceEstimates: vi.fn(),
  listMaintenanceRepairs: vi.fn(),
}))

vi.mock("@/features/media/media-service", () => ({
  cabinMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "CABIN",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  maintenanceEstimateMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "MAINTENANCE_ESTIMATE",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  maintenanceRepairMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "MAINTENANCE_REPAIR",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  createHttpMediaClient: () => media,
}))

vi.mock(
  "@/features/repair-estimates/api/http-maintenance-lifecycle-client",
  () => maintenance
)

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({
    owner,
    visibleMediaIds,
  }: {
    owner: { ownerType: string; ownerId: string }
    visibleMediaIds?: readonly string[]
  }) => (
    <output data-testid="previous-photo-source">
      {owner.ownerType}:{owner.ownerId}:{visibleMediaIds?.join(",")}
    </output>
  ),
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({ emptyLabel }: { emptyLabel?: string }) => (
    <div>{emptyLabel}</div>
  ),
}))

import { PreviousMaintenancePhotos } from "@/features/repair-estimates/previous-maintenance-photos"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const ASSET_ID = "33333333-3333-4333-8333-333333333333"

const previousCabinAsset: MediaAsset = {
  id: ASSET_ID,
  folderId: "44444444-4444-4444-8444-444444444444",
  fileName: "before.jpg",
  contentType: "image/jpeg",
  kind: "IMAGE",
  status: "READY",
  version: 1,
  generation: 1,
  rotationDegrees: 0,
  sortOrder: 0,
  sizeBytes: 100,
  createdAt: "2026-07-26T10:00:00Z",
  variants: [],
}

function renderPhotos() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <PreviousMaintenancePhotos
        accessToken="media-token"
        warehouseId={WAREHOUSE_ID}
        rentalItemId={CABIN_ID}
        currentOwner={null}
        currentCreatedAt="2026-07-27T10:00:00Z"
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  maintenance.listMaintenanceEstimates.mockResolvedValue({
    items: [],
    page: 0,
    size: 50,
    totalElements: 0,
  })
  maintenance.listMaintenanceRepairs.mockResolvedValue({
    items: [],
    page: 0,
    size: 50,
    totalElements: 0,
  })
  media.listOwnerMedia.mockResolvedValue({
    items: [previousCabinAsset],
    next: null,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("PreviousMaintenancePhotos", () => {
  it("recovers a previous-cabin photo read while owner proof propagates", async () => {
    media.listOwnerMedia
      .mockRejectedValueOnce(
        new ApiError(
          "Owner proof is catching up",
          403,
          "MEDIA_OWNER_PROOF_REQUIRED"
        )
      )
      .mockResolvedValueOnce({
        items: [previousCabinAsset],
        next: null,
      })

    renderPhotos()

    await waitFor(() =>
      expect(screen.getByTestId("previous-photo-source").textContent).toBe(
        `CABIN:${CABIN_ID}:${ASSET_ID}`
      )
    )

    expect(media.listOwnerMedia).toHaveBeenCalledTimes(2)
  })

  it("falls back to no previous photos for a nonretryable media access denial", async () => {
    media.listOwnerMedia.mockRejectedValue(
      new ApiError("Media access denied", 403, "MEDIA_FORBIDDEN")
    )

    renderPhotos()

    await screen.findByText("Предыдущие фотографии не найдены")
    await new Promise((resolve) => setTimeout(resolve, 300))

    expect(media.listOwnerMedia).toHaveBeenCalledOnce()
  })

  it("surfaces a nonretryable media-service error", async () => {
    media.listOwnerMedia.mockRejectedValue(
      new ApiError("Media service unavailable", 500, "MEDIA_UNAVAILABLE")
    )

    renderPhotos()

    expect(
      await screen.findByText("Не удалось найти фотографии до")
    ).toBeTruthy()
    expect(media.listOwnerMedia).toHaveBeenCalledOnce()
  })
})
