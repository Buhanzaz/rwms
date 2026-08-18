import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { ImageUploadIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import type {
  ReadyMediaReference,
  ServiceMediaOwner,
} from "@/features/media/media-service"
import { createHttpMediaClient } from "@/features/media/media-service"
import {
  ServiceMediaManagerDialog,
  type ServiceMediaManagerItem,
} from "@/features/media/service-media-manager-dialog"
import { useServiceOwnerMedia } from "@/features/media/use-service-owner-media"
import { serviceOwnerMediaQueryKey } from "@/features/media/use-service-owner-media"
import { runMediaUploadQueue } from "@/features/media/media-upload-queue"
import { ApiError } from "@/lib/api-client"
import {
  isRetryableOwnerProofError,
  retryOwnerProofOperation,
} from "@/features/media/owner-proof-retry"

const statusLabel = {
  UPLOADING: "Загрузка",
  PROCESSING: "Обработка",
  READY: "Готово",
  FAILED: "Ошибка обработки",
  DELETED: "Удалено",
} as const

function unavailableMessage(error: unknown) {
  if (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403)
  ) {
    return error.message
  }
  return "Сервис медиа недоступен"
}

type OwnedPendingMedia = Readonly<{
  id: string
  file: File
  kind: "IMAGE" | "VIDEO"
  previewUrl: string
  progress: number
  status: "UPLOADING" | "PROCESSING" | "FAILED"
  assetId: string | null
}>

export function ServiceOwnerPhotos({
  accessToken,
  owner,
  ensureOwner,
  readOnly,
  maxItems = 20,
  title = "Фотографии",
  toolbarAction,
  visibleMediaIds,
  authoritativeReadyReferences,
  coverMediaId = null,
  requireCover = false,
  onReadyReferencesChange,
  onReadyStateChange,
  onCoverMediaIdChange,
}: {
  accessToken: string | null
  owner: ServiceMediaOwner | null
  ensureOwner?: () => Promise<ServiceMediaOwner>
  readOnly: boolean
  maxItems?: number
  title?: string
  toolbarAction?: ReactNode
  visibleMediaIds?: readonly string[]
  /** Domain-owned references, not an unfiltered owner-media listing. */
  authoritativeReadyReferences?: readonly ReadyMediaReference[]
  coverMediaId?: string | null
  requireCover?: boolean
  onReadyReferencesChange?: (references: ReadyMediaReference[]) => void
  onReadyStateChange?: (ready: boolean) => void
  onCoverMediaIdChange?: (mediaId: string | null) => void
}) {
  if (owner === null) {
    return (
      <UnownedServiceOwnerPhotos
        accessToken={accessToken}
        readOnly={readOnly}
        maxItems={maxItems}
        title={title}
        ensureOwner={ensureOwner}
        toolbarAction={toolbarAction}
        coverMediaId={coverMediaId}
        requireCover={requireCover}
        onCoverMediaIdChange={onCoverMediaIdChange}
      />
    )
  }

  return (
    <OwnedServiceOwnerPhotos
      accessToken={accessToken}
      owner={owner}
      readOnly={readOnly}
      maxItems={maxItems}
      title={title}
      toolbarAction={toolbarAction}
      visibleMediaIds={visibleMediaIds}
      authoritativeReadyReferences={authoritativeReadyReferences}
      coverMediaId={coverMediaId}
      requireCover={requireCover}
      onReadyReferencesChange={onReadyReferencesChange}
      onReadyStateChange={onReadyStateChange}
      onCoverMediaIdChange={onCoverMediaIdChange}
    />
  )
}

function OwnedServiceOwnerPhotos({
  accessToken,
  owner,
  readOnly,
  maxItems,
  title,
  toolbarAction,
  visibleMediaIds,
  authoritativeReadyReferences,
  coverMediaId,
  requireCover,
  onReadyReferencesChange,
  onReadyStateChange,
  onCoverMediaIdChange,
}: {
  accessToken: string | null
  owner: ServiceMediaOwner
  readOnly: boolean
  maxItems: number
  title: string
  toolbarAction?: ReactNode
  visibleMediaIds?: readonly string[]
  authoritativeReadyReferences?: readonly ReadyMediaReference[]
  coverMediaId: string | null
  requireCover: boolean
  onReadyReferencesChange?: (references: ReadyMediaReference[]) => void
  onReadyStateChange?: (ready: boolean) => void
  onCoverMediaIdChange?: (mediaId: string | null) => void
}) {
  const [managerOpen, setManagerOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const [sessionMediaIds, setSessionMediaIds] = useState<readonly string[]>([])
  const [removedMediaIds, setRemovedMediaIds] = useState<readonly string[]>([])
  const [pendingUploads, setPendingUploads] = useState<OwnedPendingMedia[]>([])
  const pendingUploadsRef = useRef<OwnedPendingMedia[]>([])
  const releasedPreviewUrlsRef = useRef(new Set<string>())
  const media = useServiceOwnerMedia({ accessToken, owner })
  const visibleMediaIdSet = useMemo(
    () => (visibleMediaIds ? new Set(visibleMediaIds) : null),
    [visibleMediaIds]
  )
  const authoritativeMediaIdSet = useMemo(
    () =>
      authoritativeReadyReferences
        ? new Set(
            authoritativeReadyReferences.map((reference) => reference.mediaId)
          )
        : null,
    [authoritativeReadyReferences]
  )
  const selectedMediaIdSet = useMemo(() => {
    if (!authoritativeMediaIdSet) return visibleMediaIdSet
    const selected = new Set(authoritativeMediaIdSet)
    sessionMediaIds.forEach((mediaId) => selected.add(mediaId))
    removedMediaIds.forEach((mediaId) => selected.delete(mediaId))
    return selected
  }, [
    authoritativeMediaIdSet,
    removedMediaIds,
    sessionMediaIds,
    visibleMediaIdSet,
  ])
  const selectedAssets = useMemo(
    () =>
      selectedMediaIdSet
        ? media.assets.filter((asset) => selectedMediaIdSet.has(asset.id))
        : media.assets,
    [media.assets, selectedMediaIdSet]
  )
  const selectedReadyReferences = useMemo(() => {
    if (!authoritativeReadyReferences) return media.readyReferences

    const readyById = new Map(
      media.readyReferences.map((reference) => [reference.mediaId, reference])
    )
    const selected: ReadyMediaReference[] = []
    const seen = new Set<string>()
    for (const reference of authoritativeReadyReferences) {
      if (
        removedMediaIds.includes(reference.mediaId) ||
        seen.has(reference.mediaId)
      ) {
        continue
      }
      selected.push(readyById.get(reference.mediaId) ?? reference)
      seen.add(reference.mediaId)
    }
    for (const mediaId of sessionMediaIds) {
      if (removedMediaIds.includes(mediaId) || seen.has(mediaId)) continue
      const reference = readyById.get(mediaId)
      if (!reference) continue
      selected.push(reference)
      seen.add(mediaId)
    }
    return selected
  }, [
    authoritativeReadyReferences,
    media.readyReferences,
    removedMediaIds,
    sessionMediaIds,
  ])
  const readyAssetIds = useMemo(
    () =>
      new Set([
        ...media.photos.map((photo) => photo.id),
        ...media.videos.map((video) => video.id),
      ]),
    [media.photos, media.videos]
  )
  const activePendingUploads = useMemo(
    () =>
      pendingUploads.filter(
        (item) => !item.assetId || !readyAssetIds.has(item.assetId)
      ),
    [pendingUploads, readyAssetIds]
  )
  const visiblePhotos = useMemo(() => {
    const serverPhotos = selectedMediaIdSet
      ? media.photos.filter((photo) => selectedMediaIdSet.has(photo.id))
      : [...media.photos]
    const photos = [
      ...activePendingUploads
        .filter((item) => item.kind === "IMAGE")
        .map((item) => ({
          id: item.id,
          url: item.previewUrl,
          variants: { medium: { url: item.previewUrl } },
          createdAt: "",
        })),
      ...serverPhotos,
    ]
    if (!coverMediaId) return photos
    return photos.slice().sort((left, right) => {
      if (left.id === coverMediaId) return -1
      if (right.id === coverMediaId) return 1
      return 0
    })
  }, [activePendingUploads, coverMediaId, media.photos, selectedMediaIdSet])
  const pendingAssetIds = useMemo(
    () =>
      new Set(
        activePendingUploads.flatMap((item) =>
          item.assetId ? [item.assetId] : []
        )
      ),
    [activePendingUploads]
  )
  const serverLogicalMediaCount = selectedMediaIdSet
    ? selectedAssets.filter((asset) => asset.status !== "DELETED").length
    : media.logicalMediaCount
  const visibleLogicalMediaCount =
    serverLogicalMediaCount +
    activePendingUploads.filter(
      (item) =>
        !item.assetId ||
        !selectedAssets.some((asset) => asset.id === item.assetId)
    ).length
  const coverIsImage = selectedAssets.some(
    (asset) =>
      asset.id === coverMediaId &&
      asset.kind === "IMAGE" &&
      asset.status !== "DELETED"
  )
  const readyReferencesCallback = useRef(onReadyReferencesChange)
  const readyStateCallback = useRef(onReadyStateChange)
  const coverCallback = useRef(onCoverMediaIdChange)
  const lastReportedReadyReferencesKey = useRef<string | null>(null)
  const photoById = useMemo(
    () => new Map(media.photos.map((photo) => [photo.id, photo])),
    [media.photos]
  )
  const videoById = useMemo(
    () => new Map(media.videos.map((video) => [video.id, video])),
    [media.videos]
  )
  const visibleVideos = useMemo(
    () => [
      ...activePendingUploads
        .filter((item) => item.kind === "VIDEO")
        .map((item) => ({
          id: item.id,
          fileName: item.file.name,
          url: item.previewUrl,
          contentType: item.file.type,
          createdAt: "",
        })),
      ...(selectedMediaIdSet
        ? media.videos.filter((video) => selectedMediaIdSet.has(video.id))
        : media.videos),
    ],
    [activePendingUploads, media.videos, selectedMediaIdSet]
  )
  const managerItems = useMemo<ServiceMediaManagerItem[]>(
    () => [
      ...selectedAssets
        .filter((asset) => asset.status !== "DELETED")
        .filter((asset) => !pendingAssetIds.has(asset.id))
        .slice()
        .sort((left, right) => {
          if (left.kind !== right.kind) return left.kind === "IMAGE" ? -1 : 1
          return left.sortOrder - right.sortOrder
        })
        .map((asset) => ({
          id: asset.id,
          fileName: asset.fileName,
          kind: asset.kind,
          previewUrl:
            asset.kind === "VIDEO"
              ? (videoById.get(asset.id)?.url ?? null)
              : (photoById.get(asset.id)?.url ?? null),
          rotationDegrees: asset.rotationDegrees,
          statusLabel: statusLabel[asset.status],
          pending:
            asset.status === "UPLOADING" || asset.status === "PROCESSING",
        })),
      ...activePendingUploads.map((item) => ({
        id: item.id,
        fileName: item.file.name,
        kind: item.kind,
        previewUrl: item.previewUrl,
        statusLabel:
          item.status === "FAILED"
            ? "Ошибка загрузки"
            : item.status === "PROCESSING"
              ? "Обработка"
              : "Загрузка",
        pending: item.status !== "FAILED",
        uploadProgress: item.progress,
        coverEligible: false,
      })),
    ],
    [
      activePendingUploads,
      pendingAssetIds,
      photoById,
      selectedAssets,
      videoById,
    ]
  )

  useEffect(() => {
    pendingUploadsRef.current = pendingUploads
  }, [pendingUploads])

  useEffect(
    () => () => {
      pendingUploadsRef.current.forEach((item) => {
        if (releasedPreviewUrlsRef.current.has(item.previewUrl)) return
        URL.revokeObjectURL(item.previewUrl)
      })
      releasedPreviewUrlsRef.current.clear()
    },
    []
  )

  useEffect(() => {
    const completed = pendingUploads.filter(
      (item) => item.assetId && readyAssetIds.has(item.assetId)
    )
    if (completed.length === 0) return
    const completedIds = new Set(completed.map((item) => item.id))
    const releaseHandle = window.setTimeout(() => {
      completed.forEach((item) => {
        if (releasedPreviewUrlsRef.current.has(item.previewUrl)) return
        URL.revokeObjectURL(item.previewUrl)
        releasedPreviewUrlsRef.current.add(item.previewUrl)
      })
      setPendingUploads((current) =>
        current.filter((item) => !completedIds.has(item.id))
      )
    }, 0)
    return () => window.clearTimeout(releaseHandle)
  }, [pendingUploads, readyAssetIds])

  useEffect(() => {
    readyReferencesCallback.current = onReadyReferencesChange
  }, [onReadyReferencesChange])

  useEffect(() => {
    readyStateCallback.current = onReadyStateChange
  }, [onReadyStateChange])

  useEffect(() => {
    coverCallback.current = onCoverMediaIdChange
  }, [onCoverMediaIdChange])

  const readyReferencesKey = selectedReadyReferences
    .map((reference) => `${reference.mediaId}:${reference.generation}`)
    .join("|")
  useEffect(() => {
    if (
      !media.query.isSuccess ||
      lastReportedReadyReferencesKey.current === readyReferencesKey
    ) {
      return
    }
    lastReportedReadyReferencesKey.current = readyReferencesKey
    readyReferencesCallback.current?.([...selectedReadyReferences])
  }, [media.query.isSuccess, readyReferencesKey, selectedReadyReferences])

  useEffect(() => {
    if (media.query.isSuccess && coverMediaId && !coverIsImage) {
      coverCallback.current?.(null)
    }
  }, [coverIsImage, coverMediaId, media.query.isSuccess])

  const ready =
    media.query.isSuccess &&
    !media.pending &&
    activePendingUploads.length === 0 &&
    !selectedAssets.some(
      (asset) => asset.status === "UPLOADING" || asset.status === "PROCESSING"
    )
  useEffect(() => {
    readyStateCallback.current?.(ready)
  }, [ready])

  function addFiles(files: File[]) {
    const remaining = Math.max(0, maxItems - visibleLogicalMediaCount)
    const selected = files.slice(0, remaining).map((file) => ({
      id: crypto.randomUUID(),
      file,
      kind: file.type.startsWith("video/")
        ? ("VIDEO" as const)
        : ("IMAGE" as const),
      previewUrl: URL.createObjectURL(file),
      progress: 0,
      status: "UPLOADING" as const,
      assetId: null,
    }))
    if (selected.length === 0) return
    const folderId = crypto.randomUUID()
    const offset = visibleLogicalMediaCount
    setPendingUploads((current) => [...current, ...selected])
    void media
      .upload(
        selected.map((file, index) => ({
          file: file.file,
          folderId,
          sortOrder: offset + index,
          commandKeys: {
            createSession: crypto.randomUUID(),
            uploadAndFinalize: crypto.randomUUID(),
          },
          onProgress: (progress: number) =>
            setPendingUploads((current) =>
              current.map((item) =>
                item.id === file.id ? { ...item, progress } : item
              )
            ),
        }))
      )
      .then((uploaded) => {
        const assetIdByLocalId = new Map<string, string>(
          selected.map((item, index) => [item.id, uploaded[index]!.id])
        )
        setPendingUploads((current) =>
          current.map((item) => {
            const assetId = assetIdByLocalId.get(item.id)
            return assetId
              ? { ...item, assetId, progress: 100, status: "PROCESSING" }
              : item
          })
        )
        if (authoritativeMediaIdSet) {
          setSessionMediaIds((current) => [
            ...new Set([...current, ...uploaded.map((asset) => asset.id)]),
          ])
        }
        toast.success(
          selected.length === 1
            ? "Медиафайл загружен"
            : `Загружено медиафайлов: ${selected.length}`
        )
      })
      .catch((error) => {
        const selectedIds = new Set<string>(selected.map((item) => item.id))
        setPendingUploads((current) =>
          current.map((item) =>
            selectedIds.has(item.id) ? { ...item, status: "FAILED" } : item
          )
        )
        toast.error(
          error instanceof Error ? error.message : "Не удалось загрузить медиа"
        )
      })
  }

  function assetFor(item: ServiceMediaManagerItem) {
    return media.assets.find((asset) => asset.id === item.id) ?? null
  }

  if (!accessToken || (media.query.isError && media.assets.length === 0)) {
    return (
      <section
        className="flex min-h-56 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground"
        aria-label={title}
      >
        {unavailableMessage(media.query.error)}
      </section>
    )
  }

  return (
    <section className="flex h-full min-h-0 flex-col gap-3" aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <Badge variant="secondary">
          {visibleLogicalMediaCount} из {maxItems}
        </Badge>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {toolbarAction}
          {!readOnly ? (
            <Button
              type="button"
              variant="outline"
              disabled={
                media.uploadPending ||
                media.deletePending ||
                visibleLogicalMediaCount >= maxItems
              }
              onClick={() => setManagerOpen(true)}
            >
              <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
              Добавить
            </Button>
          ) : null}
        </div>
      </div>

      {media.error && !media.query.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {media.error}
        </p>
      ) : null}

      {requireCover && visibleLogicalMediaCount > 0 ? (
        <p
          role={coverIsImage ? "status" : "alert"}
          className={
            coverIsImage
              ? "text-sm text-muted-foreground"
              : "text-sm text-destructive"
          }
        >
          {coverIsImage
            ? "Титульная фотография выбрана."
            : "Выберите титульную фотографию в окне добавления."}
        </p>
      ) : null}

      {media.previewUnavailable &&
      selectedAssets.some((asset) => asset.status === "READY") &&
      visiblePhotos.length + visibleVideos.length === 0 ? (
        <div className="flex min-h-56 flex-1 flex-col items-center justify-center gap-3 rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground">
          <span>{unavailableMessage(media.previewError)}</span>
          {isRetryableOwnerProofError(media.previewError) ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => media.retryPreviews()}
            >
              Повторить
            </Button>
          ) : null}
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col gap-3">
          {visiblePhotos.length > 0 || visibleVideos.length === 0 ? (
            <PhotoCarousel
              photos={visiblePhotos}
              title={title}
              loading={media.query.isLoading}
              photoCount={visiblePhotos.length}
              activeIndex={activeIndex}
              onActiveIndexChange={setActiveIndex}
              onRequestFullscreen={media.requestFullscreen}
              className="min-h-56 flex-1 rounded-lg border"
              imageVariant="preview"
              fit="contain"
              controlsVisibility="mobile-visible"
              fullscreenQuality="preview"
              emptyLabel="Нет медиа"
            />
          ) : null}
          {visibleVideos.length > 0 ? (
            <div
              className="grid min-w-0 gap-3 sm:grid-cols-2"
              aria-label={`${title}: видео`}
            >
              {visibleVideos.map((video) => (
                <Card key={video.id} size="sm" className="min-w-0">
                  <CardHeader className="min-w-0">
                    <CardTitle className="truncate">{video.fileName}</CardTitle>
                    <CardDescription>Видео</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <video
                      src={video.url}
                      aria-label={`Видео ${video.fileName}`}
                      className="aspect-video w-full rounded-md bg-muted object-contain"
                      controls
                      preload="metadata"
                    />
                  </CardContent>
                </Card>
              ))}
            </div>
          ) : null}
        </div>
      )}

      {!readOnly ? (
        <ServiceMediaManagerDialog
          open={managerOpen}
          items={managerItems}
          maxItems={maxItems}
          pending={media.deletePending}
          coverMediaId={coverMediaId}
          requireCover={requireCover}
          onOpenChange={setManagerOpen}
          onAddFiles={addFiles}
          onRemove={(item) => {
            const pendingItem = activePendingUploads.find(
              (candidate) => candidate.id === item.id
            )
            if (pendingItem) {
              if (!releasedPreviewUrlsRef.current.has(pendingItem.previewUrl)) {
                URL.revokeObjectURL(pendingItem.previewUrl)
                releasedPreviewUrlsRef.current.add(pendingItem.previewUrl)
              }
              setPendingUploads((current) =>
                current.filter((candidate) => candidate.id !== item.id)
              )
              return
            }
            const asset = assetFor(item)
            if (!asset) return
            void media
              .remove(asset)
              .then(() => {
                if (!authoritativeMediaIdSet) return
                setRemovedMediaIds((current) => [
                  ...new Set([...current, item.id]),
                ])
                setSessionMediaIds((current) =>
                  current.filter((mediaId) => mediaId !== item.id)
                )
              })
              .catch((error) =>
                toast.error(
                  error instanceof Error
                    ? error.message
                    : "Не удалось удалить медиа"
                )
              )
          }}
          onSelectCover={
            requireCover
              ? (item) => {
                  if (item.kind === "IMAGE") onCoverMediaIdChange?.(item.id)
                }
              : undefined
          }
        />
      ) : null}
    </section>
  )
}

type PendingOwnerMedia = Readonly<{
  id: string
  file: File
  kind: "IMAGE" | "VIDEO"
  previewUrl: string
  progress: number
  commandKeys: Readonly<{
    createSession: string
    uploadAndFinalize: string
  }>
}>

const unownedMediaClient = createHttpMediaClient()

async function uploadNewOwnerFile(
  accessToken: string,
  owner: ServiceMediaOwner,
  item: PendingOwnerMedia,
  sortOrder: number,
  folderId: string,
  onProgress: (percentage: number) => void
) {
  return retryOwnerProofOperation(async () => {
    return unownedMediaClient.uploadFile(
      accessToken,
      owner,
      item.file,
      sortOrder,
      folderId,
      item.commandKeys,
      (progress) => onProgress(progress.percentage)
    )
  })
}

function UnownedServiceOwnerPhotos({
  accessToken,
  readOnly,
  maxItems,
  title,
  ensureOwner,
  toolbarAction,
  coverMediaId,
  requireCover,
  onCoverMediaIdChange,
}: {
  accessToken: string | null
  readOnly: boolean
  maxItems: number
  title: string
  ensureOwner?: () => Promise<ServiceMediaOwner>
  toolbarAction?: ReactNode
  coverMediaId: string | null
  requireCover: boolean
  onCoverMediaIdChange?: (mediaId: string | null) => void
}) {
  const queryClient = useQueryClient()
  const [managerOpen, setManagerOpen] = useState(false)
  const [pendingItems, setPendingItems] = useState<PendingOwnerMedia[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pendingCoverId, setPendingCoverId] = useState<string | null>(null)
  const pendingItemsRef = useRef(pendingItems)

  useEffect(() => {
    pendingItemsRef.current = pendingItems
  }, [pendingItems])

  useEffect(
    () => () => {
      pendingItemsRef.current.forEach((item) =>
        URL.revokeObjectURL(item.previewUrl)
      )
    },
    []
  )

  function addFiles(files: File[]) {
    if (!accessToken || !ensureOwner || uploading) return
    const remaining = Math.max(0, maxItems - pendingItems.length)
    const selected = files.slice(0, remaining).map((file) => ({
      id: crypto.randomUUID(),
      file,
      kind: file.type.startsWith("video/")
        ? ("VIDEO" as const)
        : ("IMAGE" as const),
      previewUrl: URL.createObjectURL(file),
      progress: 0,
      commandKeys: {
        createSession: crypto.randomUUID(),
        uploadAndFinalize: crypto.randomUUID(),
      },
    }))
    if (selected.length === 0) return
    setPendingItems((current) => [...current, ...selected].slice(0, maxItems))
  }

  async function uploadPendingItems() {
    if (
      !accessToken ||
      !ensureOwner ||
      uploading ||
      pendingItems.length === 0 ||
      (requireCover &&
        !pendingItems.some(
          (item) => item.kind === "IMAGE" && item.id === pendingCoverId
        ))
    ) {
      return
    }
    const selected = [...pendingItems]
    const selectedCoverId = pendingCoverId
    setUploading(true)
    setError(null)
    try {
      const resolvedOwner = await ensureOwner()
      const folderId = crypto.randomUUID()
      const uploaded = await runMediaUploadQueue(selected, (item, index) =>
        uploadNewOwnerFile(
          accessToken,
          resolvedOwner,
          item,
          index,
          folderId,
          (progress) =>
            setPendingItems((current) =>
              current.map((candidate) =>
                candidate.id === item.id
                  ? { ...candidate, progress }
                  : candidate
              )
            )
        )
      )
      const coverIndex = selected.findIndex(
        (item) => item.id === selectedCoverId
      )
      const resolvedCoverMediaId =
        coverIndex >= 0 ? (uploaded[coverIndex]?.asset.id ?? null) : null
      await queryClient.invalidateQueries({
        queryKey: serviceOwnerMediaQueryKey(resolvedOwner),
      })
      selected.forEach((item) => URL.revokeObjectURL(item.previewUrl))
      setPendingItems([])
      setPendingCoverId(null)
      setManagerOpen(false)
      onCoverMediaIdChange?.(resolvedCoverMediaId)
      toast.success(
        selected.length === 1
          ? "Медиафайл загружен"
          : `Загружено медиафайлов: ${selected.length}`
      )
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : "Не удалось загрузить медиа"
      )
    } finally {
      setUploading(false)
    }
  }

  if (!accessToken) {
    return (
      <section
        className="flex min-h-56 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground"
        aria-label={title}
      >
        Сервис медиа недоступен
      </section>
    )
  }

  const managerItems: ServiceMediaManagerItem[] = pendingItems.map((item) => ({
    id: item.id,
    fileName: item.file.name,
    kind: item.kind,
    previewUrl: item.previewUrl,
    statusLabel: uploading ? "Загрузка" : "Не загружено",
    pending: uploading,
    uploadProgress: uploading ? item.progress : undefined,
    coverEligible: !uploading,
  }))

  return (
    <section className="flex h-full min-h-0 flex-col gap-3" aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <Badge variant="secondary">0 из {maxItems}</Badge>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {toolbarAction}
          {!readOnly && ensureOwner ? (
            <Button
              type="button"
              variant="outline"
              disabled={uploading}
              onClick={() => setManagerOpen(true)}
            >
              <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
              Добавить
            </Button>
          ) : null}
        </div>
      </div>

      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}

      <div className="flex min-h-56 flex-1 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground">
        {pendingItems.length > 0 ? "Медиафайлы загружаются" : "Нет медиа"}
      </div>

      {!readOnly && ensureOwner ? (
        <ServiceMediaManagerDialog
          open={managerOpen}
          items={managerItems}
          maxItems={maxItems}
          pending={uploading}
          coverMediaId={pendingCoverId ?? coverMediaId}
          requireCover={requireCover}
          onOpenChange={setManagerOpen}
          onAddFiles={addFiles}
          onRemove={(item) => {
            const pendingItem = pendingItems.find(
              (candidate) => candidate.id === item.id
            )
            if (!pendingItem) return
            URL.revokeObjectURL(pendingItem.previewUrl)
            setPendingItems((current) =>
              current.filter((candidate) => candidate.id !== item.id)
            )
            if (pendingCoverId === item.id) setPendingCoverId(null)
          }}
          onSelectCover={
            requireCover
              ? (item) => {
                  if (item.kind === "IMAGE") setPendingCoverId(item.id)
                }
              : undefined
          }
          onConfirm={() => void uploadPendingItems()}
        />
      ) : null}
    </section>
  )
}
