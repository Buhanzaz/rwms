import { useEffect, useMemo, useState } from "react"

import {
  cabinMediaOwner,
  createHttpMediaClient,
  type CabinCoverProjection,
  type DisposableMediaObjectUrl,
} from "@/features/media/media-service"

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
}>

type LoadedRentalItemPhotos = Readonly<{
  signature: string
  availability: "available" | "unavailable"
  photos: readonly RentalItemCardServicePhoto[]
}>

const EMPTY_CABIN_PREVIEWS: NonNullable<CabinCoverProjection["previews"]> = []

export function loadRentalItemCoverPage(
  accessToken: string,
  warehouseId: string,
  cabinIds: readonly string[]
) {
  return mediaClient.listCabinCovers(accessToken, warehouseId, cabinIds)
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
    if (coverAvailability !== "available" || previews.length === 0) return
    let active = true
    const objectUrls: DisposableMediaObjectUrl[] = []

    void Promise.allSettled(
      previews.map((preview) =>
        mediaClient
          .createVariantObjectUrl(accessToken, owner, preview)
          .then((objectUrl) => {
            if (!active) {
              objectUrl.dispose()
              return null
            }
            objectUrls.push(objectUrl)
            return {
              id: preview.mediaId,
              generation: preview.generation,
              url: objectUrl.url,
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

  if (coverAvailability !== "available") {
    return { photos: [], availability: coverAvailability }
  }
  if (previews.length === 0) {
    return { photos: [], availability: "available" as const }
  }
  if (loadedPhotos?.signature !== photoListSignature) {
    return { photos: [], availability: "loading" as const }
  }
  return loadedPhotos
}
