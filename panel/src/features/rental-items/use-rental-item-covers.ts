import { useCallback, useEffect, useMemo, useRef, useState } from "react"

import type { PhotoCarouselPhoto } from "@/components/media/photo-carousel"

import {
  cabinMediaOwner,
  createHttpMediaClient,
  type CabinCoverProjection,
  type DisposableMediaObjectUrl,
} from "@/features/media/media-service"
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
}>

type LoadedRentalItemPhotos = Readonly<{
  signature: string
  availability: "available" | "unavailable"
  photos: readonly RentalItemCardServicePhoto[]
}>

const EMPTY_CABIN_PREVIEWS: NonNullable<CabinCoverProjection["previews"]> = []

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
}: {
  accessToken: string
  cabinId: string
  warehouseId: string
  projection: CabinCoverProjection | undefined
  coverAvailability: RentalItemCoverAvailability
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
  const previews = projection?.previews ?? EMPTY_CABIN_PREVIEWS
  const photoListSignature = `${warehouseId}:${cabinId}:${previews
    .map(
      (preview) =>
        `${preview.mediaId}:${preview.generation}:${preview.contentPath}`
    )
    .join("|")}`
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
    if (coverAvailability !== "available" || previews.length === 0) return
    let active = true
    const objectUrls: DisposableMediaObjectUrl[] = []

    void Promise.allSettled(
      previews.map((preview) =>
        retryOwnerProofOperation(() =>
          mediaClient.createVariantObjectUrl(accessToken, owner, preview)
        ).then((objectUrl) => {
          if (!active) {
            objectUrl.dispose()
            return null
          }
          objectUrls.push(objectUrl)
          return {
            id: preview.mediaId,
            generation: preview.generation,
            url: objectUrl.url,
            variants: { small: { url: objectUrl.url } },
          }
        })
      )
    )
      .then((results) => {
        if (!active) return
        const photos = results.flatMap((result) =>
          result.status === "fulfilled" && result.value ? [result.value] : []
        )
        const failed = results.some((result) => result.status === "rejected")
        setLoadedPhotos({
          signature: photoListSignature,
          availability:
            failed && photos.length === 0 ? "unavailable" : "available",
          photos,
        })
      })
      .catch(() => {
        if (active) {
          setLoadedPhotos({
            signature: photoListSignature,
            availability: "unavailable",
            photos: [],
          })
        }
      })

    return () => {
      active = false
      objectUrls.forEach((objectUrl) => objectUrl.dispose())
    }
  }, [accessToken, coverAvailability, owner, photoListSignature, previews])

  const requestFullscreen = useCallback(
    (photo: PhotoCarouselPhoto) => {
      const source = loadedPhotos?.photos.find(
        (candidate) => candidate.id === photo.id
      )
      if (!source) return Promise.resolve()

      const key = fullscreenPhotoKey(source.id, source.generation)
      if (fullscreenUrls[key]) return Promise.resolve()

      const existingRequest = fullscreenRequests.current.get(key)
      if (existingRequest) return existingRequest

      const request = retryOwnerProofOperation(() =>
        mediaClient.createOriginalObjectUrl(accessToken, owner, source.id)
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
    [accessToken, fullscreenUrls, loadedPhotos, owner, photoListSignature]
  )

  const photos = useMemo(
    () =>
      loadedPhotos?.photos.map((photo) => {
        const originalUrl =
          fullscreenUrls[fullscreenPhotoKey(photo.id, photo.generation)]
        return originalUrl
          ? {
              ...photo,
              variants: {
                ...photo.variants,
                original: { url: originalUrl },
              },
            }
          : photo
      }) ?? [],
    [fullscreenUrls, loadedPhotos]
  )

  if (coverAvailability !== "available") {
    return {
      photos: [],
      availability: coverAvailability,
      requestFullscreen,
    }
  }
  if (previews.length === 0) {
    return {
      photos: [],
      availability: "available" as const,
      requestFullscreen,
    }
  }
  if (loadedPhotos?.signature !== photoListSignature) {
    return { photos: [], availability: "loading" as const, requestFullscreen }
  }
  return { ...loadedPhotos, photos, requestFullscreen }
}
