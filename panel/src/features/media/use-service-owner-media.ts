import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { ApiError } from "@/lib/api-client"

import type { PhotoCarouselPhoto } from "@/components/media/photo-carousel"
import {
  createHttpMediaClient,
  readyMediaReference,
  type DerivedMediaVariantKind,
  type DisposableMediaObjectUrl,
  type MediaAsset,
  type MediaUploadCommandKeys,
  type MediaVariant,
  type ReadyMediaReference,
  type ServiceMediaOwner,
} from "@/features/media/media-service"

const mediaClient = createHttpMediaClient()
const INITIAL_VARIANTS: readonly DerivedMediaVariantKind[] = ["MEDIUM"]
const EMPTY_MEDIA_ASSETS: readonly MediaAsset[] = []

export const SERVICE_OWNER_MEDIA_QUERY_KEY = ["service-owner-media"] as const

type LoadedAssetVariants = Readonly<{
  signature: string
  urls: Partial<Record<DerivedMediaVariantKind, string>>
  resolved: Partial<Record<DerivedMediaVariantKind, DerivedMediaVariantKind>>
}>

export type ServiceOwnerMediaUploadJob = Readonly<{
  file: File
  sortOrder: number
  folderId: string
  commandKeys: MediaUploadCommandKeys
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
  requested: DerivedMediaVariantKind
): MediaVariant | null {
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
  requested: DerivedMediaVariantKind
) {
  const resolved = loaded?.resolved[requested]
  return resolved ? loaded.urls[resolved] : undefined
}

function mutationError(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Операция с фотографиями не выполнена"
}

function isRetryableOwnerMediaError(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 409 ||
      error.status === 503 ||
      (error.status === 403 && error.code === "MEDIA_OWNER_PROOF_REQUIRED"))
  )
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
  initialVariants?: readonly DerivedMediaVariantKind[]
}) {
  const queryClient = useQueryClient()
  const queryKey = serviceOwnerMediaQueryKey(owner)
  const objectUrls = useRef(new Map<string, DisposableMediaObjectUrl>())
  const inFlight = useRef(new Map<string, Promise<void>>())
  const validSignatures = useRef(new Map<string, string>())
  const rotateKeys = useRef(new Map<string, string>())
  const deleteKeys = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const [loadedVariants, setLoadedVariants] = useState<
    Record<string, LoadedAssetVariants>
  >({})
  const [failedVariants, setFailedVariants] = useState<ReadonlySet<string>>(
    () => new Set()
  )
  const query = useQuery({
    queryKey,
    queryFn: () =>
      mediaClient.listOwnerMedia(accessToken!, owner, { limit: 100 }),
    enabled: enabled && Boolean(accessToken),
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
  const signatures = useMemo(
    () =>
      new Map(readyImages.map((asset) => [asset.id, assetSignature(asset)])),
    [readyImages]
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
    async (asset: MediaAsset, requested: DerivedMediaVariantKind) => {
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
      if (failedVariants.has(key)) return
      const existingRequest = inFlight.current.get(key)
      if (existingRequest) return existingRequest

      const request = mediaClient
        .createVariantObjectUrl(accessToken, owner, variant)
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
        .catch(() => {
          if (!mounted.current) return
          setFailedVariants((current) => {
            const next = new Set(current)
            next.add(key)
            return next
          })
        })
        .finally(() => inFlight.current.delete(key))
      inFlight.current.set(key, request)
      return request
    },
    [accessToken, currentLoaded, failedVariants, owner]
  )

  const initialVariantKey = initialVariants.join("|")
  useEffect(() => {
    if (!enabled || !accessToken) return
    for (const asset of readyImages) {
      for (const variant of initialVariants) void ensureVariant(asset, variant)
    }
  }, [
    accessToken,
    enabled,
    ensureVariant,
    initialVariantKey,
    initialVariants,
    readyImages,
  ])

  const uploadMutation = useMutation({
    mutationFn: async (jobs: readonly ServiceOwnerMediaUploadJob[]) => {
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
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
  })
  const rotateMutation = useMutation({
    mutationFn: async ({
      asset,
      direction,
    }: {
      asset: MediaAsset
      direction: "LEFT" | "RIGHT"
    }) => {
      if (!accessToken) throw new Error("Для поворота требуется авторизация")
      const delta = direction === "RIGHT" ? 90 : 270
      const rotation = ((asset.rotationDegrees + delta) % 360) as
        0 | 90 | 180 | 270
      const signature = `${asset.id}:${asset.version}:${rotation}`
      const key = rotateKeys.current.get(signature) ?? crypto.randomUUID()
      rotateKeys.current.set(signature, key)
      return mediaClient.rotate(
        accessToken,
        owner,
        asset.id,
        rotation,
        asset.version,
        key
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
  })
  const deleteMutation = useMutation({
    mutationFn: async (asset: MediaAsset) => {
      if (!accessToken) throw new Error("Для удаления требуется авторизация")
      const signature = `${asset.id}:${asset.version}`
      const key = deleteKeys.current.get(signature) ?? crypto.randomUUID()
      deleteKeys.current.set(signature, key)
      return mediaClient.deleteAsset(
        accessToken,
        owner,
        asset.id,
        asset.version,
        key
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
        return [
          {
            id: asset.id,
            url: medium,
            variants: {
              medium: { url: medium },
              ...(large ? { large: { url: large } } : {}),
            },
            createdAt: asset.createdAt,
          },
        ]
      }),
    [currentLoaded, readyImages]
  )
  const readyReferences = useMemo<ReadyMediaReference[]>(
    () =>
      readyImages.flatMap((asset) => {
        const reference = readyMediaReference(asset)
        return reference ? [reference] : []
      }),
    [readyImages]
  )

  const requestFullscreen = useCallback(
    (photo: PhotoCarouselPhoto) => {
      const asset = readyImages.find((candidate) => candidate.id === photo.id)
      return asset ? ensureVariant(asset, "LARGE") : Promise.resolve()
    },
    [ensureVariant, readyImages]
  )

  const logicalPhotoCount = assets.filter(
    (asset) => asset.kind === "IMAGE" && asset.status !== "DELETED"
  ).length
  const operationError =
    uploadMutation.error ??
    rotateMutation.error ??
    deleteMutation.error ??
    query.error

  return {
    assets,
    photos,
    logicalPhotoCount,
    readyReferences,
    query,
    requestFullscreen,
    upload: uploadMutation.mutateAsync,
    rotate: rotateMutation.mutateAsync,
    remove: deleteMutation.mutateAsync,
    pending:
      uploadMutation.isPending ||
      rotateMutation.isPending ||
      deleteMutation.isPending,
    error: operationError ? mutationError(operationError) : null,
    previewUnavailable:
      readyImages.length > 0 && photos.length === 0 && failedVariants.size > 0,
  }
}
