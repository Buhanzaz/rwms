import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import type {
  PhotoCarouselPhoto,
  PhotoRequestOptions,
} from "@/components/media/photo-carousel"
import {
  cabinMediaOwner,
  createHttpMediaClient,
  type DerivedMediaVariantKind,
  type DisposableMediaObjectUrl,
  type MediaAsset,
  type MediaUploadCommandKeys,
  type MediaVariant,
} from "@/features/media/media-service"
import {
  ownerProofRetryDelay,
  retryOwnerProofOperation,
  shouldRetryOwnerProof,
} from "@/features/media/owner-proof-retry"
import { RENTAL_ITEM_DOSSIER_QUERY_KEY } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import {
  formatDossierActorLabel,
  type DossierActorDisplay,
} from "@/features/rental-items/dossier/actor/actor-display"
import type { DossierActivity } from "@/features/rental-items/dossier/model/dossier-service"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { RENTAL_ITEM_COVERS_QUERY_KEY } from "@/features/rental-items/use-rental-item-covers"

const mediaClient = createHttpMediaClient()
const UNKNOWN_ACTOR = "Автор не зафиксирован"
const UNKNOWN_SOURCE = "Источник не зафиксирован"
const EMPTY_ACTOR_DISPLAYS = new Map<string, DossierActorDisplay>()
const DEFAULT_INITIAL_VARIANTS: readonly DerivedMediaVariantKind[] = ["MEDIUM"]

export const RENTAL_ITEM_MEDIA_QUERY_KEY = ["rental-item-media"] as const

export function rentalItemMediaQueryKey(
  warehouseId: string,
  rentalItemId: string
) {
  return [...RENTAL_ITEM_MEDIA_QUERY_KEY, warehouseId, rentalItemId] as const
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Операция с фотографиями не выполнена"
}

export type RentalItemMediaPhoto = PhotoCarouselPhoto & {
  folderId: string
  fileName: string
  occurredAt: string | null
  actorLabel: string
  sourceLabel: string
  stage: "GENERAL"
  processingStatus: "READY"
  asset: MediaAsset | null
}

export type RentalItemPhotoFolder = {
  id: string
  occurredAt: string | null
  actorLabel: string
  sourceLabel: string
  stage: "GENERAL"
  photos: RentalItemMediaPhoto[]
  assets: readonly MediaAsset[]
}

type LoadedAssetVariants = {
  signature: string
  urls: Partial<Record<DerivedMediaVariantKind, string>>
  resolved: Partial<Record<DerivedMediaVariantKind, DerivedMediaVariantKind>>
}

type RentalItemMediaUploadJob = Readonly<{
  file: File
  sortOrder: number
  folderId: string
  commandKeys: MediaUploadCommandKeys
}>

function mediaActivity(
  assetId: string,
  activities: readonly DossierActivity[]
) {
  const exactActivity = activities.find(
    (activity) =>
      activity.sourceRef.producer === "media-service" &&
      activity.sourceRef.aggregateId === assetId
  )
  if (exactActivity) return exactActivity

  return activities.find(
    (activity) =>
      activity.sourceRef.producer === "media-service" &&
      activity.media.some((media) => media.mediaId === assetId)
  )
}

function inventoryMediaActivity(
  assetId: string,
  activities: readonly DossierActivity[]
) {
  return activities.find(
    (activity) =>
      activity.sourceRef.producer === "inventory-service" &&
      activity.media.some((media) => media.mediaId === assetId)
  )
}

function photoActivity(
  assetId: string,
  activities: readonly DossierActivity[]
) {
  return (
    inventoryMediaActivity(assetId, activities) ??
    mediaActivity(assetId, activities)
  )
}

function photoSourceLabel(
  assetId: string,
  activities: readonly DossierActivity[]
) {
  if (inventoryMediaActivity(assetId, activities)) return "Инвентаризация"
  if (mediaActivity(assetId, activities)) return "Добавленные фотографии"
  return UNKNOWN_SOURCE
}

function dossierActorLabel(
  activity: DossierActivity | undefined,
  actorDisplays: ReadonlyMap<string, DossierActorDisplay>
) {
  return activity?.actorRef
    ? formatDossierActorLabel(
        activity.actorRef,
        actorDisplays.get(activity.actorRef.subjectId)
      )
    : UNKNOWN_ACTOR
}

function assetSignature(asset: MediaAsset) {
  return `${asset.id}:${asset.version}:${asset.generation}`
}

function nearestVariant(
  asset: MediaAsset,
  requested: DerivedMediaVariantKind
): MediaVariant | null {
  const priority: Record<DerivedMediaVariantKind, DerivedMediaVariantKind[]> = {
    SMALL: ["SMALL", "MEDIUM", "LARGE"],
    MEDIUM: ["MEDIUM", "SMALL", "LARGE"],
    LARGE: ["LARGE", "MEDIUM", "SMALL"],
  }
  for (const kind of priority[requested]) {
    const variant = asset.variants.find((candidate) => candidate.kind === kind)
    if (variant) return variant
  }
  return null
}

function resolvedLoadedUrl(
  loaded: LoadedAssetVariants | undefined,
  requested: DerivedMediaVariantKind
) {
  const resolved = loaded?.resolved[requested]
  return resolved ? loaded.urls[resolved] : undefined
}

function latestOccurredAt(left: string | null, right: string) {
  if (!left) return right
  const leftTime = Date.parse(left)
  const rightTime = Date.parse(right)
  if (Number.isNaN(leftTime)) return right
  if (Number.isNaN(rightTime)) return left
  return rightTime > leftTime ? right : left
}

function orderPhotosWithCoverFirst(
  photos: RentalItemMediaPhoto[],
  coverMediaId: string | null
) {
  if (!coverMediaId) return photos
  const coverIndex = photos.findIndex((photo) => photo.id === coverMediaId)
  if (coverIndex <= 0) return photos
  return [
    photos[coverIndex],
    ...photos.slice(0, coverIndex),
    ...photos.slice(coverIndex + 1),
  ]
}

async function loadRentalItemMediaArchive(
  accessToken: string,
  owner: ReturnType<typeof cabinMediaOwner>
) {
  const items: MediaAsset[] = []
  const seenMediaIds = new Set<string>()
  const seenCursors = new Set<string>()
  let cursor: string | undefined

  for (;;) {
    const page = await mediaClient.listOwnerMedia(accessToken, owner, {
      limit: 100,
      ...(cursor ? { cursor } : {}),
    })
    for (const asset of page.items) {
      if (seenMediaIds.has(asset.id)) {
        throw new Error("Media service repeated a cabin archive asset")
      }
      seenMediaIds.add(asset.id)
      items.push(asset)
    }

    if (!page.next) return { items, next: null }
    if (seenCursors.has(page.next)) {
      throw new Error("Media service repeated the cabin archive cursor")
    }
    seenCursors.add(page.next)
    cursor = page.next
  }
}

export function useRentalItemMedia({
  item,
  accessToken,
  dossierActivities = [],
  actorDisplays = EMPTY_ACTOR_DISPLAYS,
  initialVariants = DEFAULT_INITIAL_VARIANTS,
  enabled = true,
}: {
  item: RentalItemDto | null
  accessToken: string | null
  dossierActivities?: readonly DossierActivity[]
  actorDisplays?: ReadonlyMap<string, DossierActorDisplay>
  initialVariants?: readonly DerivedMediaVariantKind[]
  enabled?: boolean
}) {
  const queryClient = useQueryClient()
  const rentalItemId = item?.id ?? "none"
  const warehouseId = item?.warehouseId ?? "none"
  const owner = useMemo(
    () => cabinMediaOwner(rentalItemId, warehouseId),
    [rentalItemId, warehouseId]
  )
  const queryKey = rentalItemMediaQueryKey(warehouseId, rentalItemId)
  const objectUrls = useRef(new Map<string, DisposableMediaObjectUrl>())
  const inFlight = useRef(new Map<string, Promise<void>>())
  const validAssetSignatures = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const [loadedVariants, setLoadedVariants] = useState<
    Record<string, LoadedAssetVariants>
  >({})
  const [failedVariantLoads, setFailedVariantLoads] = useState<
    ReadonlyMap<string, unknown>
  >(() => new Map())
  const query = useQuery({
    queryKey,
    queryFn: () => loadRentalItemMediaArchive(accessToken!, owner),
    enabled: enabled && item !== null && Boolean(accessToken),
    retry: shouldRetryOwnerProof,
    retryDelay: ownerProofRetryDelay,
    refetchInterval: (result) =>
      result.state.data?.items.some(
        (asset) => asset.status === "UPLOADING" || asset.status === "PROCESSING"
      )
        ? 2_000
        : false,
  })
  const coverQuery = useQuery({
    queryKey: [
      ...RENTAL_ITEM_COVERS_QUERY_KEY,
      "detail",
      warehouseId,
      rentalItemId,
    ],
    queryFn: () =>
      mediaClient.listCabinCovers(accessToken!, warehouseId, [rentalItemId]),
    enabled: enabled && item !== null && Boolean(accessToken),
    retry: shouldRetryOwnerProof,
    retryDelay: ownerProofRetryDelay,
  })
  const readyImages = useMemo(
    () =>
      (query.data?.items ?? []).filter(
        (asset) => asset.kind === "IMAGE" && asset.status === "READY"
      ),
    [query.data?.items]
  )
  const currentCover = coverQuery.error
    ? undefined
    : coverQuery.data?.items.find(
        (projection) => projection.cabinId === rentalItemId
      )
  const currentCoverMediaId = currentCover?.cover?.mediaId ?? null
  const currentFolderId = useMemo(() => {
    const currentMediaId =
      currentCoverMediaId ?? currentCover?.previews[0]?.mediaId
    return currentMediaId
      ? (readyImages.find((asset) => asset.id === currentMediaId)?.folderId ??
          null)
      : null
  }, [currentCover?.previews, currentCoverMediaId, readyImages])
  const logicalPhotoCount = currentCover?.photoCount ?? 0
  const assetSignatures = useMemo(
    () =>
      new Map(readyImages.map((asset) => [asset.id, assetSignature(asset)])),
    [readyImages]
  )
  const activeLoadedVariants = useMemo(
    () =>
      Object.fromEntries(
        Object.entries(loadedVariants).filter(
          ([assetId, value]) => assetSignatures.get(assetId) === value.signature
        )
      ),
    [assetSignatures, loadedVariants]
  )

  useEffect(() => {
    mounted.current = true
    const urls = objectUrls.current
    const pendingRequests = inFlight.current
    return () => {
      mounted.current = false
      urls.forEach((objectUrl) => objectUrl.dispose())
      urls.clear()
      pendingRequests.clear()
    }
  }, [])

  useEffect(() => {
    validAssetSignatures.current = assetSignatures
    objectUrls.current.forEach((objectUrl, key) => {
      const [assetId, signature] = key.split("|")
      if (assetSignatures.get(assetId) === signature) return
      objectUrl.dispose()
      objectUrls.current.delete(key)
    })
  }, [assetSignatures])

  const ensureVariant = useCallback(
    async (
      asset: MediaAsset,
      requested: DerivedMediaVariantKind,
      retry = false
    ) => {
      if (!accessToken) return
      await Promise.resolve()
      const variant = nearestVariant(asset, requested)
      if (!variant) return
      const signature = assetSignature(asset)
      const key = `${asset.id}|${signature}|${variant.kind}`
      const current = activeLoadedVariants[asset.id]
      if (current?.signature === signature && current.urls[variant.kind]) {
        if (current.resolved[requested] !== variant.kind) {
          setLoadedVariants((state) => ({
            ...state,
            [asset.id]: {
              ...state[asset.id],
              resolved: {
                ...state[asset.id].resolved,
                [requested]: variant.kind,
              },
            },
          }))
        }
        return
      }
      if (!retry && failedVariantLoads.has(key)) return
      const pending = inFlight.current.get(key)
      if (pending) return pending
      if (retry) {
        setFailedVariantLoads((failures) => {
          const remaining = new Map(failures)
          remaining.delete(key)
          return remaining
        })
      }

      const request = retryOwnerProofOperation(() =>
        mediaClient.createVariantObjectUrl(accessToken, owner, variant)
      )
        .then((objectUrl) => {
          if (
            !mounted.current ||
            validAssetSignatures.current.get(asset.id) !== signature
          ) {
            objectUrl.dispose()
            return
          }
          objectUrls.current.get(key)?.dispose()
          objectUrls.current.set(key, objectUrl)
          setLoadedVariants((state) => {
            const existing = state[asset.id]
            return {
              ...state,
              [asset.id]: {
                signature,
                urls: {
                  ...(existing?.signature === signature ? existing.urls : {}),
                  [variant.kind]: objectUrl.url,
                },
                resolved: {
                  ...(existing?.signature === signature
                    ? existing.resolved
                    : {}),
                  [requested]: variant.kind,
                },
              },
            }
          })
        })
        .catch((error: unknown) => {
          if (
            mounted.current &&
            validAssetSignatures.current.get(asset.id) === signature
          ) {
            setFailedVariantLoads((currentFailures) => {
              const nextFailures = new Map(currentFailures)
              nextFailures.set(key, error)
              return nextFailures
            })
          }
        })
        .finally(() => {
          inFlight.current.delete(key)
        })
      inFlight.current.set(key, request)
      return request
    },
    [accessToken, activeLoadedVariants, failedVariantLoads, owner]
  )

  const initialVariantKey = initialVariants.join("|")
  useEffect(() => {
    if (!enabled || !accessToken) return
    for (const asset of readyImages) {
      for (const variant of initialVariants) {
        void Promise.resolve().then(() => ensureVariant(asset, variant))
      }
    }
  }, [
    accessToken,
    enabled,
    ensureVariant,
    initialVariantKey,
    initialVariants,
    readyImages,
  ])

  const allPhotos = useMemo(() => {
    const servicePhotos = readyImages.flatMap<RentalItemMediaPhoto>((asset) => {
      const loaded = activeLoadedVariants[asset.id]
      if (!loaded || loaded.signature !== assetSignature(asset)) return []
      const small = resolvedLoadedUrl(loaded, "SMALL")
      const medium = resolvedLoadedUrl(loaded, "MEDIUM")
      const large = resolvedLoadedUrl(loaded, "LARGE")
      const fullscreenVariant = nearestVariant(asset, "LARGE")
      const fullscreenError = fullscreenVariant
        ? failedVariantLoads.get(
            `${asset.id}|${assetSignature(asset)}|${fullscreenVariant.kind}`
          )
        : null
      const url = medium ?? small ?? large
      if (!url) return []
      const activity = photoActivity(asset.id, dossierActivities)
      return [
        {
          id: asset.id,
          folderId: asset.folderId,
          fileName: asset.fileName,
          url,
          ...(fullscreenError
            ? { fullscreenError: errorMessage(fullscreenError) }
            : {}),
          variants: {
            ...(small ? { small: { url: small } } : {}),
            ...(medium ? { medium: { url: medium } } : {}),
            ...(large ? { large: { url: large } } : {}),
          },
          createdAt: asset.createdAt,
          occurredAt:
            activity?.occurredAt ??
            activity?.recordedAt ??
            asset.createdAt ??
            null,
          actorLabel: dossierActorLabel(activity, actorDisplays),
          sourceLabel: photoSourceLabel(asset.id, dossierActivities),
          stage: "GENERAL",
          processingStatus: "READY",
          asset,
        },
      ]
    })
    return servicePhotos
  }, [
    activeLoadedVariants,
    actorDisplays,
    dossierActivities,
    failedVariantLoads,
    readyImages,
  ])

  const photos = useMemo(() => {
    const currentPhotos =
      coverQuery.error || !coverQuery.data || currentFolderId === null
        ? []
        : allPhotos.filter((photo) => photo.folderId === currentFolderId)
    return orderPhotosWithCoverFirst(currentPhotos, currentCoverMediaId)
  }, [
    allPhotos,
    coverQuery.data,
    coverQuery.error,
    currentCoverMediaId,
    currentFolderId,
  ])

  const archivePhotos = useMemo(
    () => orderPhotosWithCoverFirst(allPhotos, currentCoverMediaId),
    [allPhotos, currentCoverMediaId]
  )

  const photoFolders = useMemo<RentalItemPhotoFolder[]>(() => {
    const folders = new Map<string, RentalItemPhotoFolder>()
    const photosByAsset = new Map(
      allPhotos.map((photo) => [photo.id, photo] as const)
    )
    for (const asset of query.data?.items ?? []) {
      if (asset.kind !== "IMAGE") continue
      const existing = folders.get(asset.folderId)
      const photo = photosByAsset.get(asset.id)
      const activity = photoActivity(asset.id, dossierActivities)
      const sourceLabel = photoSourceLabel(asset.id, dossierActivities)
      const occurredAt =
        activity?.occurredAt ?? activity?.recordedAt ?? asset.createdAt
      if (existing) {
        folders.set(asset.folderId, {
          ...existing,
          occurredAt: latestOccurredAt(existing.occurredAt, occurredAt),
          actorLabel:
            existing.actorLabel === UNKNOWN_ACTOR
              ? dossierActorLabel(activity, actorDisplays)
              : existing.actorLabel,
          sourceLabel:
            existing.sourceLabel === "Инвентаризация"
              ? existing.sourceLabel
              : sourceLabel,
          photos: photo ? [...existing.photos, photo] : existing.photos,
          assets: [...existing.assets, asset],
        })
        continue
      }
      folders.set(asset.folderId, {
        id: asset.folderId,
        occurredAt,
        actorLabel: dossierActorLabel(activity, actorDisplays),
        sourceLabel,
        stage: "GENERAL",
        photos: photo ? [photo] : [],
        assets: [asset],
      })
    }

    const orderedFolders = [...folders.values()].sort((left, right) => {
      const leftTime = left.occurredAt ? Date.parse(left.occurredAt) : 0
      const rightTime = right.occurredAt ? Date.parse(right.occurredAt) : 0
      return rightTime - leftTime
    })
    return orderedFolders.map((folder) =>
      folder.id === currentFolderId
        ? {
            ...folder,
            photos: orderPhotosWithCoverFirst(
              folder.photos,
              currentCoverMediaId
            ),
          }
        : folder
    )
  }, [
    actorDisplays,
    allPhotos,
    currentCoverMediaId,
    currentFolderId,
    dossierActivities,
    query.data?.items,
  ])

  const requestFolderPreview = useCallback(
    (folderId: string) =>
      Promise.all(
        readyImages
          .filter((asset) => asset.folderId === folderId)
          .map((asset) => ensureVariant(asset, "MEDIUM"))
      ),
    [ensureVariant, readyImages]
  )
  const requestFullscreen = useCallback(
    (photo: PhotoCarouselPhoto, options?: PhotoRequestOptions) => {
      const asset = readyImages.find((candidate) => candidate.id === photo.id)
      return asset
        ? ensureVariant(asset, "LARGE", options?.retry)
        : Promise.resolve()
    },
    [ensureVariant, readyImages]
  )

  function retry() {
    void query.refetch()
    void coverQuery.refetch()
    for (const asset of readyImages) {
      for (const variant of asset.variants) {
        if (
          failedVariantLoads.has(
            `${asset.id}|${assetSignature(asset)}|${variant.kind}`
          )
        ) {
          void ensureVariant(asset, variant.kind, true)
        }
      }
    }
  }

  const uploadMutation = useMutation({
    mutationFn: async (jobs: readonly RentalItemMediaUploadJob[]) => {
      if (!accessToken) throw new Error("Для загрузки требуется авторизация")
      for (const job of jobs) {
        await mediaClient.uploadFile(
          accessToken,
          owner,
          job.file,
          job.sortOrder,
          job.folderId,
          job.commandKeys
        )
      }
    },
    retry: shouldRetryOwnerProof,
    retryDelay: ownerProofRetryDelay,
    onSuccess: () => toast.success("Фотографии добавлены"),
    onError: (error) => toast.error(errorMessage(error)),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey })
      await queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
      })
      await queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_COVERS_QUERY_KEY,
      })
    },
  })
  const initialVariantsLoaded = readyImages.every((asset) =>
    initialVariants.every((requested) => {
      const variant = nearestVariant(asset, requested)
      if (!variant) return true
      const signature = assetSignature(asset)
      const key = `${asset.id}|${signature}|${variant.kind}`
      return (
        activeLoadedVariants[asset.id]?.urls[variant.kind] !== undefined ||
        failedVariantLoads.has(key)
      )
    })
  )

  return {
    assets: query.data?.items ?? [],
    logicalPhotoCount,
    error:
      query.error ??
      coverQuery.error ??
      [...failedVariantLoads].find(([key]) => {
        const [assetId, signature] = key.split("|")
        return assetSignatures.get(assetId) === signature
      })?.[1],
    isLoading:
      query.isLoading || coverQuery.isLoading || !initialVariantsLoaded,
    isUploading: uploadMutation.isPending,
    photos,
    archivePhotos,
    photoFolders,
    requestFolderPreview,
    requestFullscreen,
    retry,
    upload: (files: File[]) => {
      const imageFiles = files.filter((file) => file.type.startsWith("image/"))
      if (imageFiles.length !== files.length || imageFiles.length === 0) {
        return Promise.reject(
          new Error("Выберите изображения JPEG, PNG или WebP")
        )
      }
      const offset = query.data?.items.length ?? 0
      const folderId = crypto.randomUUID()
      return uploadMutation.mutateAsync(
        imageFiles.map((file, index) => ({
          file,
          sortOrder: offset + index,
          folderId,
          commandKeys: {
            createSession: crypto.randomUUID(),
            uploadAndFinalize: crypto.randomUUID(),
          },
        }))
      )
    },
  }
}
