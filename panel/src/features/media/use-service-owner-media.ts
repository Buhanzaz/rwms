import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import type { PhotoCarouselPhoto } from "@/components/media/photo-carousel"
import {
  createHttpMediaClient,
  readyMediaReference,
  type DerivedMediaVariantKind,
  type DisposableMediaObjectUrl,
  type MediaAsset,
  type MediaUploadCommandKeys,
  type MediaVariant,
  type MediaVariantKind,
  type PlaybackMediaVariant,
  type ReadyMediaReference,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import {
  ownerProofRetryDelay,
  retryOwnerProofOperation,
  shouldRetryOwnerProof,
} from "@/features/media/owner-proof-retry"
import { runMediaUploadQueue } from "@/features/media/media-upload-queue"

const mediaClient = createHttpMediaClient()
const INITIAL_VARIANTS: readonly MediaVariantKind[] = ["MEDIUM", "PLAYBACK"]
const EMPTY_MEDIA_ASSETS: readonly MediaAsset[] = []

export const SERVICE_OWNER_MEDIA_QUERY_KEY = ["service-owner-media"] as const

type LoadedAssetVariants = Readonly<{
  signature: string
  urls: Partial<Record<MediaVariantKind, string>>
  resolved: Partial<Record<MediaVariantKind, MediaVariantKind>>
}>

export type ServiceOwnerMediaUploadJob = Readonly<{
  file: File
  sortOrder: number
  folderId: string
  commandKeys: MediaUploadCommandKeys
  onProgress?: (percentage: number) => void
  /** Retains this server receipt even when another file in the batch fails. */
  onUploaded?: (asset: MediaAsset) => void
}>

export type ServiceOwnerVideo = Readonly<{
  id: string
  fileName: string
  url: string
  contentType: string
  createdAt: string
}>

function ownerIdentity(owner: ServiceMediaOwner) {
  return "ownerId" in owner
    ? `${owner.ownerType}:${owner.ownerId}`
    : `${owner.ownerType}:${owner.documentId}:${owner.lineId}`
}

export function serviceOwnerMediaQueryKey(owner: ServiceMediaOwner) {
  return [
    ...SERVICE_OWNER_MEDIA_QUERY_KEY,
    owner.warehouseId,
    ownerIdentity(owner),
    owner.context,
  ] as const
}

function assetSignature(asset: MediaAsset) {
  return `${asset.id}:${asset.version}:${asset.generation}`
}

function nearestVariant(
  asset: MediaAsset,
  requested: MediaVariantKind
): MediaVariant | PlaybackMediaVariant | null {
  if (requested === "PLAYBACK") return asset.playbackVariant ?? null

  const priority: Record<DerivedMediaVariantKind, DerivedMediaVariantKind[]> = {
    SMALL: ["SMALL", "MEDIUM", "LARGE"],
    MEDIUM: ["MEDIUM", "LARGE", "SMALL"],
    LARGE: ["LARGE", "MEDIUM", "SMALL"],
  }
  for (const kind of priority[requested]) {
    const variant = asset.variants.find((candidate) => candidate.kind === kind)
    if (variant) return variant
  }
  return null
}

function loadedUrl(
  loaded: LoadedAssetVariants | undefined,
  requested: MediaVariantKind
) {
  const resolved = loaded?.resolved[requested]
  return resolved ? loaded.urls[resolved] : undefined
}

function mutationError(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Операция с медиафайлами не выполнена"
}

export function useServiceOwnerMedia({
  accessToken,
  owner,
  enabled = true,
  initialVariants = INITIAL_VARIANTS,
}: {
  accessToken: string | null
  owner: ServiceMediaOwner
  enabled?: boolean
  initialVariants?: readonly MediaVariantKind[]
}) {
  const queryClient = useQueryClient()
  const queryKey = serviceOwnerMediaQueryKey(owner)
  const objectUrls = useRef(new Map<string, DisposableMediaObjectUrl>())
  const inFlight = useRef(new Map<string, Promise<void>>())
  const validSignatures = useRef(new Map<string, string>())
  const deleteKeys = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const [loadedVariants, setLoadedVariants] = useState<
    Record<string, LoadedAssetVariants>
  >({})
  const [variantErrors, setVariantErrors] = useState<Record<string, unknown>>(
    {}
  )
  const query = useQuery({
    queryKey,
    queryFn: () =>
      mediaClient.listOwnerMedia(accessToken!, owner, { limit: 100 }),
    enabled: enabled && Boolean(accessToken),
    retry: shouldRetryOwnerProof,
    retryDelay: ownerProofRetryDelay,
    refetchInterval: (result) =>
      result.state.data?.items.some(
        (asset) => asset.status === "UPLOADING" || asset.status === "PROCESSING"
      )
        ? 2_000
        : false,
  })
  const assets = query.data?.items ?? EMPTY_MEDIA_ASSETS
  const readyImages = useMemo(
    () =>
      assets.filter(
        (asset) => asset.kind === "IMAGE" && asset.status === "READY"
      ),
    [assets]
  )
  const readyVideos = useMemo(
    () =>
      assets.filter(
        (asset) => asset.kind === "VIDEO" && asset.status === "READY"
      ),
    [assets]
  )
  const readyMedia = useMemo(
    () => [...readyImages, ...readyVideos],
    [readyImages, readyVideos]
  )
  const signatures = useMemo(
    () => new Map(readyMedia.map((asset) => [asset.id, assetSignature(asset)])),
    [readyMedia]
  )
  const currentLoaded = useMemo(
    () =>
      Object.fromEntries(
        Object.entries(loadedVariants).filter(
          ([assetId, loaded]) => signatures.get(assetId) === loaded.signature
        )
      ),
    [loadedVariants, signatures]
  )
  const failedVariantKeys = useMemo(
    () =>
      Object.keys(variantErrors).filter((key) => {
        const [assetId, signature] = key.split("|")
        return signatures.get(assetId) === signature
      }),
    [signatures, variantErrors]
  )
  const previewError =
    failedVariantKeys.length > 0
      ? (variantErrors[failedVariantKeys[0]!] ?? null)
      : null

  useEffect(() => {
    mounted.current = true
    const urls = objectUrls.current
    const pending = inFlight.current
    return () => {
      mounted.current = false
      urls.forEach((objectUrl) => objectUrl.dispose())
      urls.clear()
      pending.clear()
    }
  }, [])

  useEffect(() => {
    validSignatures.current = signatures
    objectUrls.current.forEach((objectUrl, key) => {
      const [assetId, signature] = key.split("|")
      if (signatures.get(assetId) === signature) return
      objectUrl.dispose()
      objectUrls.current.delete(key)
    })
  }, [signatures])

  const ensureVariant = useCallback(
    async (asset: MediaAsset, requested: MediaVariantKind) => {
      if (!accessToken) return
      const variant = nearestVariant(asset, requested)
      if (!variant) return
      const signature = assetSignature(asset)
      const key = `${asset.id}|${signature}|${variant.kind}`
      const current = currentLoaded[asset.id]
      if (current?.urls[variant.kind]) {
        if (current.resolved[requested] !== variant.kind) {
          setLoadedVariants((state) => ({
            ...state,
            [asset.id]: {
              ...state[asset.id],
              resolved: {
                ...state[asset.id]?.resolved,
                [requested]: variant.kind,
              },
            },
          }))
        }
        return
      }
      const existingRequest = inFlight.current.get(key)
      if (existingRequest) return existingRequest

      setVariantErrors((currentErrors) => {
        if (!(key in currentErrors)) return currentErrors
        const remaining = { ...currentErrors }
        delete remaining[key]
        return remaining
      })
      const request = retryOwnerProofOperation(() =>
        mediaClient.createVariantObjectUrl(accessToken, owner, variant)
      )
        .then((objectUrl) => {
          if (
            !mounted.current ||
            validSignatures.current.get(asset.id) !== signature
          ) {
            objectUrl.dispose()
            return
          }
          objectUrls.current.get(key)?.dispose()
          objectUrls.current.set(key, objectUrl)
          setLoadedVariants((state) => {
            const currentState = state[asset.id]
            const reusable = currentState?.signature === signature
            return {
              ...state,
              [asset.id]: {
                signature,
                urls: {
                  ...(reusable ? currentState.urls : {}),
                  [variant.kind]: objectUrl.url,
                },
                resolved: {
                  ...(reusable ? currentState.resolved : {}),
                  [requested]: variant.kind,
                },
              },
            }
          })
        })
        .catch((error) => {
          if (!mounted.current) return
          setVariantErrors((currentErrors) => ({
            ...currentErrors,
            [key]: error,
          }))
        })
        .finally(() => inFlight.current.delete(key))
      inFlight.current.set(key, request)
      return request
    },
    [accessToken, currentLoaded, owner]
  )

  const initialVariantKey = initialVariants.join("|")
  useEffect(() => {
    if (!enabled || !accessToken) return
    let active = true
    void Promise.resolve().then(() => {
      if (!active) return
      for (const asset of readyMedia) {
        for (const variant of initialVariants)
          void ensureVariant(asset, variant)
      }
    })
    return () => {
      active = false
    }
  }, [
    accessToken,
    enabled,
    ensureVariant,
    initialVariantKey,
    initialVariants,
    readyMedia,
  ])

  const uploadMutation = useMutation({
    mutationFn: async (jobs: readonly ServiceOwnerMediaUploadJob[]) => {
      if (!accessToken) throw new Error("Для загрузки требуется авторизация")
      return runMediaUploadQueue(jobs, async (job) => {
        const result = await retryOwnerProofOperation(() =>
          job.onProgress
            ? mediaClient.uploadFile(
                accessToken,
                owner,
                job.file,
                job.sortOrder,
                job.folderId,
                job.commandKeys,
                (progress) => job.onProgress?.(progress.percentage)
              )
            : mediaClient.uploadFile(
                accessToken,
                owner,
                job.file,
                job.sortOrder,
                job.folderId,
                job.commandKeys
              )
        )
        job.onUploaded?.(result.asset)
        return result.asset
      })
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  })
  const deleteMutation = useMutation({
    mutationFn: async (asset: MediaAsset) => {
      if (!accessToken) throw new Error("Для удаления требуется авторизация")
      const signature = `${asset.id}:${asset.version}`
      const key = deleteKeys.current.get(signature) ?? crypto.randomUUID()
      deleteKeys.current.set(signature, key)
      return retryOwnerProofOperation(() =>
        mediaClient.deleteAsset(
          accessToken,
          owner,
          asset.id,
          asset.version,
          key
        )
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
  })

  const photos = useMemo<PhotoCarouselPhoto[]>(
    () =>
      readyImages.flatMap((asset) => {
        const loaded = currentLoaded[asset.id]
        const medium = loadedUrl(loaded, "MEDIUM")
        if (!medium) return []
        const large = loadedUrl(loaded, "LARGE")
        const fullscreenVariant = nearestVariant(asset, "LARGE")
        const fullscreenError = fullscreenVariant
          ? variantErrors[
              `${asset.id}|${assetSignature(asset)}|${fullscreenVariant.kind}`
            ]
          : null
        return [
          {
            id: asset.id,
            url: medium,
            ...(fullscreenError
              ? { fullscreenError: mutationError(fullscreenError) }
              : {}),
            variants: {
              medium: { url: medium },
              ...(large ? { large: { url: large } } : {}),
            },
            createdAt: asset.createdAt,
          },
        ]
      }),
    [currentLoaded, readyImages, variantErrors]
  )
  const videos = useMemo<ServiceOwnerVideo[]>(
    () =>
      readyVideos.flatMap((asset) => {
        const url = loadedUrl(currentLoaded[asset.id], "PLAYBACK")
        if (!url) return []
        return [
          {
            id: asset.id,
            fileName: asset.fileName,
            url,
            contentType: "video/mp4",
            createdAt: asset.createdAt,
          },
        ]
      }),
    [currentLoaded, readyVideos]
  )
  const readyReferences = useMemo<ReadyMediaReference[]>(
    () =>
      readyMedia.flatMap((asset) => {
        const reference = readyMediaReference(asset)
        return reference ? [reference] : []
      }),
    [readyMedia]
  )

  const requestFullscreen = useCallback(
    (photo: PhotoCarouselPhoto) => {
      const asset = readyImages.find((candidate) => candidate.id === photo.id)
      return asset ? ensureVariant(asset, "LARGE") : Promise.resolve()
    },
    [ensureVariant, readyImages]
  )
  const retryPreviews = useCallback(() => {
    for (const asset of readyMedia) {
      for (const variant of initialVariants) void ensureVariant(asset, variant)
    }
  }, [ensureVariant, initialVariants, readyMedia])

  const logicalPhotoCount = assets.filter(
    (asset) => asset.kind === "IMAGE" && asset.status !== "DELETED"
  ).length
  const logicalMediaCount = assets.filter(
    (asset) => asset.status !== "DELETED"
  ).length
  const operationError =
    uploadMutation.error ?? deleteMutation.error ?? query.error

  return {
    assets,
    photos,
    videos,
    logicalPhotoCount,
    logicalMediaCount,
    readyReferences,
    query,
    requestFullscreen,
    retryPreviews,
    upload: uploadMutation.mutateAsync,
    remove: deleteMutation.mutateAsync,
    uploadPending: uploadMutation.isPending,
    deletePending: deleteMutation.isPending,
    pending: uploadMutation.isPending || deleteMutation.isPending,
    error: operationError ? mutationError(operationError) : null,
    previewError,
    previewUnavailable:
      readyMedia.length > 0 &&
      photos.length + videos.length === 0 &&
      failedVariantKeys.length > 0,
  }
}
