import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { ApiError } from "@/lib/api-client"
import type { PhotoCarouselPhoto } from "@/components/media/photo-carousel"
import {
  cabinMediaOwner,
  createHttpMediaClient,
  type DerivedMediaVariantKind,
  type DisposableMediaObjectUrl,
  type MediaAsset,
  type MediaUploadCommandKeys,
  type MediaVariant,
} from "@/features/media/media-service"
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

function isRetryableOwnerMediaError(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 409 ||
      error.status === 503 ||
      (error.status === 403 && error.code === "MEDIA_OWNER_PROOF_REQUIRED"))
  )
}

function legacyPhotos(item: RentalItemDto): RentalItemMediaPhoto[] {
  const folderId = `${item.id}-legacy-folder`
  const source = item.legacyPhotos?.length
    ? item.legacyPhotos
    : (item.previewPhotoUrls ?? []).map((url, index) => ({
        id: `${item.id}-legacy-${index}`,
        url,
      }))

  return source.map((photo, index) => {
    const legacyVariants = (
      photo as {
        variants?: {
          small?: { url?: unknown }
          largeWebp?: { url?: unknown }
        }
      }
    ).variants
    const medium = photo.url
    const small =
      typeof legacyVariants?.small?.url === "string"
        ? legacyVariants.small.url
        : medium
    const large =
      typeof legacyVariants?.largeWebp?.url === "string"
        ? legacyVariants.largeWebp.url
        : medium
    const capturedAt = (photo as { capturedAt?: unknown }).capturedAt
    const occurredAt = typeof capturedAt === "string" ? capturedAt : null
    return {
      id: photo.id,
      folderId,
      fileName: `Фото ${index + 1}`,
      url: medium,
      variants: {
        small: { url: small },
        medium: { url: medium },
        large: { url: large },
      },
      createdAt: occurredAt ?? undefined,
      occurredAt,
      actorLabel: UNKNOWN_ACTOR,
      sourceLabel: "Общие фотографии",
      stage: "GENERAL",
      processingStatus: "READY",
      asset: null,
    }
  })
}

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
  const rotateKeys = useRef(new Map<string, string>())
  const objectUrls = useRef(new Map<string, DisposableMediaObjectUrl>())
  const inFlight = useRef(new Map<string, Promise<void>>())
  const validAssetSignatures = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const [loadedVariants, setLoadedVariants] = useState<
    Record<string, LoadedAssetVariants>
  >({})
  const [failedVariantLoads, setFailedVariantLoads] = useState<
    ReadonlySet<string>
  >(() => new Set())
  const query = useQuery({
    queryKey,
    queryFn: () =>
      mediaClient.listOwnerMedia(accessToken!, owner, { limit: 100 }),
    enabled: enabled && item !== null && Boolean(accessToken),
    refetchInterval: (result) =>
      result.state.data?.items.some(
        (asset) => asset.status === "UPLOADING" || asset.status === "PROCESSING"
      )
        ? 2_000
        : false,
  })
  const readyImages = useMemo(
    () =>
      (query.data?.items ?? []).filter(
        (asset) => asset.kind === "IMAGE" && asset.status === "READY"
      ),
    [query.data?.items]
  )
  const legacyMediaPhotos = useMemo(
    () => (item ? legacyPhotos(item) : []),
    [item]
  )
  const logicalPhotoCount = useMemo(
    () =>
      legacyMediaPhotos.length +
      (query.data?.items ?? []).filter(
        (asset) => asset.kind === "IMAGE" && asset.status !== "DELETED"
      ).length,
    [legacyMediaPhotos.length, query.data?.items]
  )
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
    async (asset: MediaAsset, requested: DerivedMediaVariantKind) => {
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
      if (failedVariantLoads.has(key)) return
      const pending = inFlight.current.get(key)
      if (pending) return pending

      const request = mediaClient
        .createVariantObjectUrl(accessToken, owner, variant)
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
        .catch(() => {
          if (mounted.current) {
            setFailedVariantLoads((currentFailures) => {
              const nextFailures = new Set(currentFailures)
              nextFailures.add(key)
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

  const photos = useMemo(() => {
    const servicePhotos = readyImages.flatMap<RentalItemMediaPhoto>((asset) => {
      const loaded = activeLoadedVariants[asset.id]
      if (!loaded || loaded.signature !== assetSignature(asset)) return []
      const small = resolvedLoadedUrl(loaded, "SMALL")
      const medium = resolvedLoadedUrl(loaded, "MEDIUM")
      const large = resolvedLoadedUrl(loaded, "LARGE")
      const url = medium ?? small ?? large
      if (!url) return []
      const activity = mediaActivity(asset.id, dossierActivities)
      return [
        {
          id: asset.id,
          folderId: asset.folderId,
          fileName: asset.fileName,
          url,
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
          sourceLabel: "Добавленные фотографии",
          stage: "GENERAL",
          processingStatus: "READY",
          asset,
        },
      ]
    })
    return [...legacyMediaPhotos, ...servicePhotos]
  }, [
    activeLoadedVariants,
    actorDisplays,
    dossierActivities,
    legacyMediaPhotos,
    readyImages,
  ])

  const photoFolders = useMemo<RentalItemPhotoFolder[]>(() => {
    const folders = new Map<string, RentalItemPhotoFolder>()
    const legacy = photos.filter((photo) => photo.asset === null)
    if (legacy.length > 0) {
      folders.set(legacy[0].folderId, {
        id: legacy[0].folderId,
        occurredAt:
          legacy.find((photo) => photo.occurredAt !== null)?.occurredAt ?? null,
        actorLabel: UNKNOWN_ACTOR,
        sourceLabel: "Общие фотографии",
        stage: "GENERAL",
        photos: legacy,
        assets: [],
      })
    }

    const photosByAsset = new Map(
      photos
        .filter((photo) => photo.asset !== null)
        .map((photo) => [photo.id, photo] as const)
    )
    for (const asset of query.data?.items ?? []) {
      if (asset.kind !== "IMAGE") continue
      const existing = folders.get(asset.folderId)
      const photo = photosByAsset.get(asset.id)
      const activity = mediaActivity(asset.id, dossierActivities)
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
          photos: photo ? [...existing.photos, photo] : existing.photos,
          assets: [...existing.assets, asset],
        })
        continue
      }
      folders.set(asset.folderId, {
        id: asset.folderId,
        occurredAt,
        actorLabel: dossierActorLabel(activity, actorDisplays),
        sourceLabel: "Добавленные фотографии",
        stage: "GENERAL",
        photos: photo ? [photo] : [],
        assets: [asset],
      })
    }

    return [...folders.values()].sort((left, right) => {
      const leftTime = left.occurredAt ? Date.parse(left.occurredAt) : 0
      const rightTime = right.occurredAt ? Date.parse(right.occurredAt) : 0
      return rightTime - leftTime
    })
  }, [actorDisplays, dossierActivities, photos, query.data?.items])

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
    (photo: PhotoCarouselPhoto) => {
      const asset = readyImages.find((candidate) => candidate.id === photo.id)
      return asset ? ensureVariant(asset, "LARGE") : Promise.resolve()
    },
    [ensureVariant, readyImages]
  )

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
    retry: (failureCount, error) =>
      failureCount < 5 && isRetryableOwnerMediaError(error),
    retryDelay: (attempt) => Math.min(250 * 2 ** attempt, 2_000),
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
  const rotateMutation = useMutation({
    mutationFn: async (asset: MediaAsset) => {
      if (!accessToken) throw new Error("Для поворота требуется авторизация")
      const rotationDegrees = ((asset.rotationDegrees + 90) % 360) as
        0 | 90 | 180 | 270
      const rotationSignature = `${asset.id}:${asset.version}:${rotationDegrees}`
      const idempotencyKey =
        rotateKeys.current.get(rotationSignature) ?? crypto.randomUUID()
      rotateKeys.current.set(rotationSignature, idempotencyKey)
      return mediaClient.rotate(
        accessToken,
        owner,
        asset.id,
        rotationDegrees,
        asset.version,
        idempotencyKey
      )
    },
    onSuccess: async (_, asset) => {
      rotateKeys.current.delete(
        `${asset.id}:${asset.version}:${(asset.rotationDegrees + 90) % 360}`
      )
      await queryClient.invalidateQueries({ queryKey })
      await queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
      })
      await queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_COVERS_QUERY_KEY,
      })
      toast.success("Фотография повёрнута")
    },
    onError: (error) => toast.error(errorMessage(error)),
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
    error: query.error,
    isLoading: query.isLoading || !initialVariantsLoaded,
    isUploading: uploadMutation.isPending,
    isRotating: rotateMutation.isPending,
    photos,
    photoFolders,
    requestFolderPreview,
    requestFullscreen,
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
    rotate: (asset: MediaAsset) => rotateMutation.mutate(asset),
  }
}
