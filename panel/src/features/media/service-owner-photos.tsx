import { useEffect, useMemo, useRef, useState } from "react"
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

const statusLabel = {
  UPLOADING: "Загрузка",
  PROCESSING: "Обработка",
  READY: "Готово",
  FAILED: "Ошибка обработки",
  DELETED: "Удалено",
} as const

function isRetryableOwnerMediaError(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 409 ||
      error.status === 503 ||
      (error.status === 403 && error.code === "MEDIA_OWNER_PROOF_REQUIRED"))
  )
}

export function ServiceOwnerPhotos({
  accessToken,
  owner,
  ensureOwner,
  readOnly,
  maxItems = 20,
  title = "Фотографии",
  onReadyReferencesChange,
}: {
  accessToken: string | null
  owner: ServiceMediaOwner | null
  ensureOwner?: () => Promise<ServiceMediaOwner>
  readOnly: boolean
  maxItems?: number
  title?: string
  onReadyReferencesChange?: (references: ReadyMediaReference[]) => void
}) {
  if (owner === null) {
    return (
      <UnownedServiceOwnerPhotos
        accessToken={accessToken}
        readOnly={readOnly}
        maxItems={maxItems}
        title={title}
        ensureOwner={ensureOwner}
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
      onReadyReferencesChange={onReadyReferencesChange}
    />
  )
}

function OwnedServiceOwnerPhotos({
  accessToken,
  owner,
  readOnly,
  maxItems,
  title,
  onReadyReferencesChange,
}: {
  accessToken: string | null
  owner: ServiceMediaOwner
  readOnly: boolean
  maxItems: number
  title: string
  onReadyReferencesChange?: (references: ReadyMediaReference[]) => void
}) {
  const [managerOpen, setManagerOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const media = useServiceOwnerMedia({ accessToken, owner })
  const readyReferencesCallback = useRef(onReadyReferencesChange)
  const photoById = useMemo(
    () => new Map(media.photos.map((photo) => [photo.id, photo])),
    [media.photos]
  )
  const managerItems = useMemo<ServiceMediaManagerItem[]>(
    () =>
      media.assets
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
    [media.assets, photoById]
  )

  useEffect(() => {
    readyReferencesCallback.current = onReadyReferencesChange
  }, [onReadyReferencesChange])

  const readyReferencesKey = media.readyReferences
    .map((reference) => `${reference.mediaId}:${reference.generation}`)
    .join("|")
  useEffect(() => {
    readyReferencesCallback.current?.([...media.readyReferences])
  }, [media.readyReferences, readyReferencesKey])

  async function addFiles(files: File[]) {
    const remaining = Math.max(0, maxItems - media.logicalPhotoCount)
    const selected = files.slice(0, remaining)
    if (selected.length === 0) return
    const folderId = crypto.randomUUID()
    const offset = media.logicalPhotoCount
    try {
      await media.upload(
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
        Сервис фото недоступен
      </section>
    )
  }

  return (
    <section className="flex h-full min-h-0 flex-col gap-3" aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <Badge variant="secondary">
          {media.logicalPhotoCount} из {maxItems}
        </Badge>
        {!readOnly ? (
          <Button
            type="button"
            variant="outline"
            disabled={media.pending || media.logicalPhotoCount >= maxItems}
            onClick={() => setManagerOpen(true)}
          >
            <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
            Добавить
          </Button>
        ) : null}
      </div>

      {media.error && !media.query.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {media.error}
        </p>
      ) : null}

      {media.previewUnavailable ? (
        <div className="flex min-h-56 flex-1 items-center justify-center rounded-lg border bg-muted px-4 text-center text-sm text-muted-foreground">
          Сервис фото недоступен
        </div>
      ) : (
        <PhotoCarousel
          photos={media.photos}
          title={title}
          loading={media.query.isLoading}
          photoCount={media.logicalPhotoCount}
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
          onOpenChange={setManagerOpen}
          onAddFiles={(files) => void addFiles(files)}
          onRemove={(item) => {
            const asset = assetFor(item)
            if (!asset) return
            void media
              .remove(asset)
              .catch((error) =>
                toast.error(
                  error instanceof Error
                    ? error.message
                    : "Не удалось удалить фото"
                )
              )
          }}
          onRotate={(item, direction) => {
            const asset = assetFor(item)
            if (!asset) return
            void media
              .rotate({ asset, direction })
              .catch((error) =>
                toast.error(
                  error instanceof Error
                    ? error.message
                    : "Не удалось повернуть фото"
                )
              )
          }}
        />
      ) : null}
    </section>
  )
}

type PendingOwnerPhoto = Readonly<{
  id: string
  file: File
  previewUrl: string
  rotationDegrees: 0 | 90 | 180 | 270
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
  const rotateKey = crypto.randomUUID()
  for (let attempt = 0; ; attempt += 1) {
    try {
      const result = await unownedMediaClient.uploadFile(
        accessToken,
        owner,
        item.file,
        sortOrder,
        folderId,
        commandKeys
      )
      if (item.rotationDegrees === 0) {
        return result
      }
      await unownedMediaClient.rotate(
        accessToken,
        owner,
        result.asset.id,
        item.rotationDegrees,
        result.asset.version,
        rotateKey
      )
      return result
    } catch (cause) {
      if (attempt >= 4 || !isRetryableOwnerMediaError(cause)) {
        throw cause
      }
      await new Promise((resolve) =>
        setTimeout(resolve, Math.min(250 * 2 ** attempt, 2_000))
      )
    }
  }
}

function UnownedServiceOwnerPhotos({
  accessToken,
  readOnly,
  maxItems,
  title,
  ensureOwner,
}: {
  accessToken: string | null
  readOnly: boolean
  maxItems: number
  title: string
  ensureOwner?: () => Promise<ServiceMediaOwner>
}) {
  const queryClient = useQueryClient()
  const [managerOpen, setManagerOpen] = useState(false)
  const [pendingItems, setPendingItems] = useState<PendingOwnerPhoto[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
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

  async function addFiles(files: File[]) {
    if (!accessToken || !ensureOwner || uploading) return
    const selected = files.slice(0, maxItems).map((file) => ({
      id: crypto.randomUUID(),
      file,
      previewUrl: URL.createObjectURL(file),
      rotationDegrees: 0 as const,
    }))
    if (selected.length === 0) return
    pendingItemsRef.current.forEach((item) =>
      URL.revokeObjectURL(item.previewUrl)
    )
    setPendingItems(selected)
    setUploading(true)
    setError(null)
    try {
      const resolvedOwner = await ensureOwner()
      const folderId = crypto.randomUUID()
      for (const [index, item] of selected.entries()) {
        await uploadNewOwnerFile(
          accessToken,
          resolvedOwner,
          item,
          index,
          folderId
        )
      }
      await queryClient.invalidateQueries({
        queryKey: serviceOwnerMediaQueryKey(resolvedOwner),
      })
      selected.forEach((item) => URL.revokeObjectURL(item.previewUrl))
      setPendingItems([])
      setManagerOpen(false)
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
    rotationDegrees: item.rotationDegrees,
    statusLabel: uploading ? "Загрузка" : "Не загружено",
    pending: uploading,
  }))

  return (
    <section className="flex h-full min-h-0 flex-col gap-3" aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <Badge variant="secondary">0 из {maxItems}</Badge>
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
          onOpenChange={setManagerOpen}
          onAddFiles={(files) => void addFiles(files)}
          onRemove={(item) => {
            const pendingItem = pendingItems.find(
              (candidate) => candidate.id === item.id
            )
            if (!pendingItem) return
            URL.revokeObjectURL(pendingItem.previewUrl)
            setPendingItems((current) =>
              current.filter((candidate) => candidate.id !== item.id)
            )
          }}
          onRotate={(item, direction) => {
            const delta = direction === "RIGHT" ? 90 : 270
            setPendingItems((current) =>
              current.map((candidate) =>
                candidate.id === item.id
                  ? {
                      ...candidate,
                      rotationDegrees: ((candidate.rotationDegrees + delta) %
                        360) as 0 | 90 | 180 | 270,
                    }
                  : candidate
              )
            )
          }}
        />
      ) : null}
    </section>
  )
}
