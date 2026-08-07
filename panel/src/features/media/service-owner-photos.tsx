import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { ImageUploadIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
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
  return "Сервис фото недоступен"
}

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
  const visiblePhotos = useMemo(() => {
    const photos = selectedMediaIdSet
      ? media.photos.filter((photo) => selectedMediaIdSet.has(photo.id))
      : [...media.photos]
    if (!coverMediaId) return photos
    return photos.slice().sort((left, right) => {
      if (left.id === coverMediaId) return -1
      if (right.id === coverMediaId) return 1
      return 0
    })
  }, [coverMediaId, media.photos, selectedMediaIdSet])
  const visibleLogicalPhotoCount = selectedMediaIdSet
    ? selectedAssets.filter(
        (asset) => asset.kind === "IMAGE" && asset.status !== "DELETED"
      ).length
    : media.logicalPhotoCount
  const readyReferencesCallback = useRef(onReadyReferencesChange)
  const readyStateCallback = useRef(onReadyStateChange)
  const coverCallback = useRef(onCoverMediaIdChange)
  const lastReportedReadyReferencesKey = useRef<string | null>(null)
  const photoById = useMemo(
    () => new Map(media.photos.map((photo) => [photo.id, photo])),
    [media.photos]
  )
  const managerItems = useMemo<ServiceMediaManagerItem[]>(
    () =>
      selectedAssets
        .filter((asset) => asset.kind === "IMAGE" && asset.status !== "DELETED")
        .map((asset) => ({
          id: asset.id,
          fileName: asset.fileName,
          previewUrl: photoById.get(asset.id)?.url ?? null,
          rotationDegrees: asset.rotationDegrees,
          statusLabel: statusLabel[asset.status],
          pending:
            asset.status === "UPLOADING" || asset.status === "PROCESSING",
        })),
    [photoById, selectedAssets]
  )

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
    if (
      media.query.isSuccess &&
      coverMediaId &&
      !selectedReadyReferences.some(
        (reference) => reference.mediaId === coverMediaId
      )
    ) {
      coverCallback.current?.(null)
    }
  }, [coverMediaId, media.query.isSuccess, selectedReadyReferences])

  const ready =
    media.query.isSuccess &&
    !media.pending &&
    !selectedAssets.some(
      (asset) => asset.status === "UPLOADING" || asset.status === "PROCESSING"
    )
  useEffect(() => {
    readyStateCallback.current?.(ready)
  }, [ready])

  async function addFiles(files: File[]) {
    const remaining = Math.max(0, maxItems - visibleLogicalPhotoCount)
    const selected = files.slice(0, remaining)
    if (selected.length === 0) return
    const folderId = crypto.randomUUID()
    const offset = visibleLogicalPhotoCount
    try {
      const uploaded = await media.upload(
        selected.map((file, index) => ({
          file,
          folderId,
          sortOrder: offset + index,
          commandKeys: {
            createSession: crypto.randomUUID(),
            uploadAndFinalize: crypto.randomUUID(),
          },
        }))
      )
      if (authoritativeMediaIdSet) {
        setSessionMediaIds((current) => [
          ...new Set([...current, ...uploaded.map((asset) => asset.id)]),
        ])
      }
      toast.success(
        selected.length === 1
          ? "Фотография загружена"
          : `Загружено фотографий: ${selected.length}`
      )
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Не удалось загрузить фото"
      )
    }
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
          {visibleLogicalPhotoCount} из {maxItems}
        </Badge>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {toolbarAction}
          {!readOnly ? (
            <Button
              type="button"
              variant="outline"
              disabled={media.pending || visibleLogicalPhotoCount >= maxItems}
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

      {requireCover && selectedReadyReferences.length > 0 ? (
        <p
          role={coverMediaId ? "status" : "alert"}
          className={
            coverMediaId
              ? "text-sm text-muted-foreground"
              : "text-sm text-destructive"
          }
        >
          {coverMediaId
            ? "Титульная фотография выбрана."
            : "Выберите титульную фотографию в окне добавления."}
        </p>
      ) : null}

      {media.previewUnavailable &&
      selectedAssets.some((asset) => asset.status === "READY") &&
      visiblePhotos.length === 0 ? (
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
        <PhotoCarousel
          photos={visiblePhotos}
          title={title}
          loading={media.query.isLoading}
          photoCount={visibleLogicalPhotoCount}
          showPhotoCount
          activeIndex={activeIndex}
          onActiveIndexChange={setActiveIndex}
          onRequestFullscreen={media.requestFullscreen}
          className="min-h-56 flex-1 rounded-lg border"
          imageVariant="preview"
          fit="contain"
          controlsVisibility="mobile-visible"
          fullscreenQuality="preview"
          emptyLabel="Нет фото"
        />
      )}

      {!readOnly ? (
        <ServiceMediaManagerDialog
          open={managerOpen}
          items={managerItems}
          maxItems={maxItems}
          pending={media.pending}
          coverMediaId={coverMediaId}
          requireCover={requireCover}
          onOpenChange={setManagerOpen}
          onAddFiles={(files) => void addFiles(files)}
          onRemove={(item) => {
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
                    : "Не удалось удалить фото"
                )
              )
          }}
          onSelectCover={(item) => onCoverMediaIdChange?.(item.id)}
        />
      ) : null}
    </section>
  )
}

type PendingOwnerPhoto = Readonly<{
  id: string
  file: File
  previewUrl: string
}>

const unownedMediaClient = createHttpMediaClient()

async function uploadNewOwnerFile(
  accessToken: string,
  owner: ServiceMediaOwner,
  item: PendingOwnerPhoto,
  sortOrder: number,
  folderId: string
) {
  const commandKeys = {
    createSession: crypto.randomUUID(),
    uploadAndFinalize: crypto.randomUUID(),
  }
  return retryOwnerProofOperation(async () => {
    return unownedMediaClient.uploadFile(
      accessToken,
      owner,
      item.file,
      sortOrder,
      folderId,
      commandKeys
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
  const [pendingItems, setPendingItems] = useState<PendingOwnerPhoto[]>([])
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
    const selected = files.slice(0, maxItems).map((file) => ({
      id: crypto.randomUUID(),
      file,
      previewUrl: URL.createObjectURL(file),
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
      (requireCover && !pendingCoverId)
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
      let resolvedCoverMediaId: string | null = null
      for (const [index, item] of selected.entries()) {
        const uploaded = await uploadNewOwnerFile(
          accessToken,
          resolvedOwner,
          item,
          index,
          folderId
        )
        if (item.id === selectedCoverId) {
          resolvedCoverMediaId = uploaded.asset.id
        }
      }
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
          ? "Фотография загружена"
          : `Загружено фотографий: ${selected.length}`
      )
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : "Не удалось загрузить фото"
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
        Сервис фото недоступен
      </section>
    )
  }

  const managerItems: ServiceMediaManagerItem[] = pendingItems.map((item) => ({
    id: item.id,
    fileName: item.file.name,
    previewUrl: item.previewUrl,
    statusLabel: uploading ? "Загрузка" : "Не загружено",
    pending: uploading,
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
        {pendingItems.length > 0 ? "Фотографии загружаются" : "Нет фото"}
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
          onSelectCover={(item) => setPendingCoverId(item.id)}
          onConfirm={() => void uploadPendingItems()}
        />
      ) : null}
    </section>
  )
}
