import { useCallback, useEffect, useMemo, useRef, useState } from "react"

import type { PhotoCarouselPhoto } from "@/components/media/photo-carousel"

import {
  cabinMediaOwner,
  createHttpMediaClient,
  type CabinCoverProjection,
  type DisposableMediaObjectUrl,
} from "@/features/media/media-service"
import {
  acquireMediaPreview,
  getCachedMediaPreviewUrl,
  mediaPreviewCacheKey,
  type MediaPreviewLease,
} from "@/features/media/media-preview-cache"
import { retryOwnerProofOperation } from "@/features/media/owner-proof-retry"

const mediaClient = createHttpMediaClient()

export const RENTAL_ITEM_COVERS_QUERY_KEY = [
  "rental-item-media-covers",
] as const

export type RentalItemCoverAvailability =
  "loading" | "available" | "unavailable"

export type RentalItemCardServicePhoto = Readonly<{
  id: string
  generation: number
  url: string
  variants: NonNullable<PhotoCarouselPhoto["variants"]>
  previewPending?: boolean
  previewError?: string
}>

type LoadedRentalItemPhotos = Readonly<{
  signature: string
  failedIds: readonly string[]
  photos: readonly RentalItemCardServicePhoto[]
}>

const EMPTY_CABIN_PREVIEWS: NonNullable<CabinCoverProjection["previews"]> = []

export function getCoverFirstCabinPreviews(
  projection: CabinCoverProjection | undefined
): CabinCoverProjection["previews"] {
  const previews = projection?.previews ?? EMPTY_CABIN_PREVIEWS
  const cover = projection?.cover
  if (!cover) return previews

  const coverIndex = previews.findIndex(
    (preview) =>
      preview.mediaId === cover.mediaId &&
      preview.generation === cover.generation
  )
  if (coverIndex <= 0) return previews

  return [
    previews[coverIndex],
    ...previews.slice(0, coverIndex),
    ...previews.slice(coverIndex + 1),
  ]
}

function previewCacheKey(
  warehouseId: string,
  cabinId: string,
  preview: NonNullable<CabinCoverProjection["previews"]>[number]
) {
  return mediaPreviewCacheKey({
    warehouseId,
    cabinId,
    mediaId: preview.mediaId,
    generation: preview.generation,
    variant: preview.kind,
  })
}

function previewPhoto(
  preview: NonNullable<CabinCoverProjection["previews"]>[number],
  url: string
): RentalItemCardServicePhoto {
  return {
    id: preview.mediaId,
    generation: preview.generation,
    url,
    variants: { small: { url } },
  }
}

function fullscreenPhotoKey(id: string, generation: number) {
  return `${id}:${generation}`
}

export function loadRentalItemCoverPage(
  accessToken: string,
  warehouseId: string,
  cabinIds: readonly string[]
) {
  return retryOwnerProofOperation(() =>
    mediaClient.listCabinCovers(accessToken, warehouseId, cabinIds)
  )
}

export function useRentalItemCardPhotos({
  accessToken,
  cabinId,
  warehouseId,
  projection,
  coverAvailability,
  selectedPhotoId,
}: {
  accessToken: string
  cabinId: string
  warehouseId: string
  projection: CabinCoverProjection | undefined
  coverAvailability: RentalItemCoverAvailability
  selectedPhotoId?: string
}) {
  const [loadedPhotos, setLoadedPhotos] =
    useState<LoadedRentalItemPhotos | null>(null)
  const [fullscreenUrls, setFullscreenUrls] = useState<
    Readonly<Record<string, string>>
  >({})
  const fullscreenObjectUrls = useRef(
    new Map<string, DisposableMediaObjectUrl>()
  )
  const fullscreenRequests = useRef(new Map<string, Promise<void>>())
  const currentPhotoListSignature = useRef("")
  const mounted = useRef(true)
  const previews = useMemo(
    () => getCoverFirstCabinPreviews(projection),
    [projection]
  )
  const selectedIndex = Math.max(
    0,
    previews.findIndex((preview) => preview.mediaId === selectedPhotoId)
  )
  const wantedPreviews = useMemo(() => {
    if (previews.length === 0) return []
    const indices = [
      selectedIndex,
      0,
      (selectedIndex + 1) % previews.length,
      (selectedIndex + previews.length - 1) % previews.length,
    ]
    return [...new Set(indices)].map((index) => previews[index])
  }, [previews, selectedIndex])
  const cachedPhotos = wantedPreviews.flatMap((preview) => {
    const url = getCachedMediaPreviewUrl(
      previewCacheKey(warehouseId, cabinId, preview)
    )
    return url ? [previewPhoto(preview, url)] : []
  })
  const photoListSignature = `${warehouseId}:${cabinId}:${previews
    .map(
      (preview) =>
        `${preview.mediaId}:${preview.generation}:${preview.contentPath}`
    )
    .join("|")}`
  const requestSignature = `${photoListSignature}:${selectedIndex}`
  const owner = useMemo(
    () => cabinMediaOwner(cabinId, warehouseId),
    [cabinId, warehouseId]
  )

  useEffect(() => {
    currentPhotoListSignature.current = photoListSignature
  }, [photoListSignature])

  useEffect(() => {
    mounted.current = true
    const objectUrls = fullscreenObjectUrls.current
    const requests = fullscreenRequests.current
    return () => {
      mounted.current = false
      objectUrls.forEach((objectUrl) => objectUrl.dispose())
      objectUrls.clear()
      requests.clear()
    }
  }, [])

  useEffect(() => {
    if (coverAvailability !== "available" || wantedPreviews.length === 0) return
    let active = true
    const controller = new AbortController()
    const leases: MediaPreviewLease[] = []
    for (const preview of wantedPreviews) {
      void acquireMediaPreview(
        previewCacheKey(warehouseId, cabinId, preview),
        (signal) =>
          retryOwnerProofOperation(
            () =>
              mediaClient.createVariantObjectUrl(
                accessToken,
                owner,
                preview,
                signal
              ),
            signal
          ),
        {
          signal: controller.signal,
          priority:
            preview === previews[0] || preview === previews[selectedIndex]
              ? 0
              : 1,
        }
      )
        .then((lease) => {
          if (!active) {
            lease.release()
            return
          }
          leases.push(lease)
          setLoadedPhotos((current) => ({
            signature: requestSignature,
            failedIds:
              current?.signature === requestSignature ? current.failedIds : [],
            photos: [
              ...(current?.signature === requestSignature
                ? current.photos
                : []),
              previewPhoto(preview, lease.url),
            ],
          }))
        })
        .catch(() => {
          if (!active) return
          setLoadedPhotos((current) => ({
            signature: requestSignature,
            photos:
              current?.signature === requestSignature ? current.photos : [],
            failedIds: [
              ...(current?.signature === requestSignature
                ? current.failedIds
                : []),
              preview.mediaId,
            ],
          }))
        })
    }
    return () => {
      active = false
      controller.abort()
      leases.forEach((lease) => lease.release())
    }
  }, [
    accessToken,
    cabinId,
    coverAvailability,
    owner,
    previews,
    requestSignature,
    selectedIndex,
    wantedPreviews,
    warehouseId,
  ])

  const requestFullscreen = useCallback(
    (photo: PhotoCarouselPhoto) => {
      const source = previews.find(
        (candidate) => candidate.mediaId === photo.id
      )
      if (!source) return Promise.resolve()

      const key = fullscreenPhotoKey(source.mediaId, source.generation)
      if (fullscreenUrls[key]) return Promise.resolve()

      const existingRequest = fullscreenRequests.current.get(key)
      if (existingRequest) return existingRequest

      const request = retryOwnerProofOperation(() =>
        mediaClient.createOriginalObjectUrl(accessToken, owner, source.mediaId)
      )
        .then((objectUrl) => {
          if (
            !mounted.current ||
            currentPhotoListSignature.current !== photoListSignature
          ) {
            objectUrl.dispose()
            return
          }
          fullscreenObjectUrls.current.get(key)?.dispose()
          fullscreenObjectUrls.current.set(key, objectUrl)
          setFullscreenUrls((current) => ({ ...current, [key]: objectUrl.url }))
        })
        .catch(() => undefined)
        .finally(() => {
          fullscreenRequests.current.delete(key)
        })
      fullscreenRequests.current.set(key, request)
      return request
    },
    [accessToken, fullscreenUrls, owner, photoListSignature, previews]
  )

  const loaded =
    loadedPhotos?.signature === requestSignature ? loadedPhotos : null
  const urls = new Map(
    [...cachedPhotos, ...(loaded?.photos ?? [])].map((photo) => [
      photo.id,
      photo.url,
    ])
  )
  const wantedIds = new Set(wantedPreviews.map((preview) => preview.mediaId))
  const photos: RentalItemCardServicePhoto[] = previews.map((preview) => {
    const url = wantedIds.has(preview.mediaId)
      ? (urls.get(preview.mediaId) ?? "")
      : ""
    const failed = loaded?.failedIds.includes(preview.mediaId) ?? false
    const originalUrl =
      fullscreenUrls[fullscreenPhotoKey(preview.mediaId, preview.generation)]
    return {
      ...previewPhoto(preview, url),
      previewPending: !url && !failed,
      ...(failed ? { previewError: "Не удалось загрузить фото" } : {}),
      variants: {
        small: { url },
        ...(originalUrl ? { original: { url: originalUrl } } : {}),
      },
    }
  })
  const selectedPhoto = photos[selectedIndex]
  const availability: RentalItemCoverAvailability =
    coverAvailability !== "available"
      ? coverAvailability
      : !selectedPhoto || selectedPhoto.url
        ? "available"
        : selectedPhoto.previewError
          ? "unavailable"
          : "loading"

  return {
    photos: coverAvailability === "available" ? photos : [],
    availability,
    requestFullscreen,
  }
}
