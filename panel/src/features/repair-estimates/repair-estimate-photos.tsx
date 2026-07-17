import { useId, useRef, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { ImageUploadIcon } from "@hugeicons/core-free-icons"

import {
  PhotoCarousel,
  type PhotoCarouselPhoto,
} from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldLabel } from "@/components/ui/field"
import { useAuth } from "@/features/auth/use-auth"
import {
  mediaKindFromMimeType,
  type MediaViewerContext,
} from "@/features/media/model/media"
import { useOriginalPhotoPreference } from "@/features/media/use-media-preferences"
import { useOriginalMediaUrls } from "@/features/media/use-original-media-urls"
import type {
  PendingEstimateMediaUpload,
  RepairEstimateMediaRefDto,
  RepairEstimateMediaRotationDegrees,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  RepairEstimatePhotoManagerDialog,
  type RepairEstimatePhotoManagerItem,
} from "@/features/repair-estimates/repair-estimate-photo-manager-dialog"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"

type RepairEstimatePhotosProps = {
  media: RepairEstimateMediaRefDto[]
  pendingUploads: PendingEstimateMediaUpload[]
  readOnly: boolean
  onMediaChange: (media: RepairEstimateMediaRefDto[]) => void
  onPendingUploadsChange: (uploads: PendingEstimateMediaUpload[]) => void
  directUpload?: boolean
  viewerContext?: Extract<
    MediaViewerContext,
    "ESTIMATE" | "INSPECTION" | "WORK"
  >
}

function createOpaqueId() {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? `estimate-media-${crypto.randomUUID()}`
    : `estimate-media-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function rotate(
  current: RepairEstimateMediaRotationDegrees,
  direction: "LEFT" | "RIGHT"
): RepairEstimateMediaRotationDegrees {
  const delta = direction === "RIGHT" ? 90 : 270
  return ((current + delta) % 360) as RepairEstimateMediaRotationDegrees
}

function rotationClassName(
  rotationDegrees: RepairEstimateMediaRotationDegrees
) {
  if (rotationDegrees === 90) {
    return "rotate-90 scale-75"
  }
  if (rotationDegrees === 180) {
    return "rotate-180"
  }
  if (rotationDegrees === 270) {
    return "-rotate-90 scale-75"
  }
  return "rotate-0"
}

export function RepairEstimatePhotos({
  media,
  pendingUploads,
  readOnly,
  onMediaChange,
  onPendingUploadsChange,
  directUpload = false,
  viewerContext = "ESTIMATE",
}: RepairEstimatePhotosProps) {
  const originalToggleId = useId()
  const { currentUser } = useAuth()
  const originalPreference = useOriginalPhotoPreference(
    currentUser?.id,
    viewerContext
  )
  const originalUrls = useOriginalMediaUrls(
    media,
    originalPreference.showOriginalPhotos,
    viewerContext
  )
  const [activeIndex, setActiveIndex] = useState(0)
  const [managerOpen, setManagerOpen] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const visualMedia = media.filter(
    (item) => !item.opaqueOnly && Boolean(item.variants.small.url)
  )
  const opaqueMedia = media.filter((item) => item.opaqueOnly)
  const items: RepairEstimatePhotoManagerItem[] = [
    ...visualMedia.map((item) => ({
      id: item.id,
      fileName: item.fileName,
      previewUrl: item.variants.small.url,
      mimeType: item.mimeType,
      rotationDegrees: item.rotationDegrees,
      source: "SAVED" as const,
    })),
    ...pendingUploads.map((item) => ({
      id: item.id,
      fileName: item.file.name,
      previewUrl: item.previewUrl,
      mimeType: item.file.type,
      rotationDegrees: item.rotationDegrees,
      source: "PENDING" as const,
    })),
  ]
  const carouselPhotos: PhotoCarouselPhoto[] = [
    ...visualMedia.map((item) => ({
      id: item.id,
      url: item.variants.small.url,
      kind: item.kind ?? mediaKindFromMimeType(item.mimeType),
      mimeType: item.mimeType,
      rotationDegrees: item.rotationDegrees,
      variants: {
        small: { url: item.variants.small.url },
        largeWebp: { url: item.variants.largeWebp.url },
        ...(originalUrls[item.id]
          ? { original: { url: originalUrls[item.id] } }
          : {}),
      },
      createdAt: item.createdAt,
    })),
    ...pendingUploads.map((item) => ({
      id: item.id,
      url: item.previewUrl,
      kind: mediaKindFromMimeType(item.file.type),
      mimeType: item.file.type,
      rotationDegrees: item.rotationDegrees,
      variants: {
        small: { url: item.previewUrl },
        largeWebp: { url: item.previewUrl },
      },
    })),
  ]
  const safeActiveIndex =
    activeIndex >= 0 && activeIndex < items.length ? activeIndex : 0
  const activeItem = items[safeActiveIndex] ?? null

  function addFiles(files: File[]) {
    if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return
    const remaining = Math.max(0, 20 - items.length)
    const uploads = files.slice(0, remaining).map((file) => ({
      id: createOpaqueId(),
      file,
      previewUrl: URL.createObjectURL(file),
      rotationDegrees: 0 as const,
    }))
    onPendingUploadsChange([...pendingUploads, ...uploads])
    if (uploads.length > 0) {
      setActiveIndex(items.length)
    }
  }

  function pickFiles() {
    fileInputRef.current?.click()
  }

  function removeItem(item: RepairEstimatePhotoManagerItem) {
    const removedIndex = items.findIndex(
      (candidate) => candidate.id === item.id
    )
    if (item.source === "SAVED") {
      onMediaChange(media.filter((candidate) => candidate.id !== item.id))
    } else {
      const pending = pendingUploads.find(
        (candidate) => candidate.id === item.id
      )
      if (pending) {
        URL.revokeObjectURL(pending.previewUrl)
      }
      onPendingUploadsChange(
        pendingUploads.filter((candidate) => candidate.id !== item.id)
      )
    }
    setActiveIndex((current) => {
      if (items.length <= 1) {
        return 0
      }
      if (removedIndex < current) {
        return current - 1
      }
      if (removedIndex === current && current === items.length - 1) {
        return current - 1
      }
      return current
    })
  }

  function rotateItem(
    item: RepairEstimatePhotoManagerItem,
    direction: "LEFT" | "RIGHT"
  ) {
    if (item.source === "SAVED") {
      onMediaChange(
        media.map((candidate) =>
          candidate.id === item.id
            ? {
                ...candidate,
                rotationDegrees: rotate(candidate.rotationDegrees, direction),
              }
            : candidate
        )
      )
      return
    }

    onPendingUploadsChange(
      pendingUploads.map((candidate) =>
        candidate.id === item.id
          ? {
              ...candidate,
              rotationDegrees: rotate(candidate.rotationDegrees, direction),
            }
          : candidate
      )
    )
  }

  return (
    <section className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <Badge variant="secondary">{items.length} из 20</Badge>
          <Field orientation="horizontal" className="w-auto gap-2">
            <Checkbox
              id={originalToggleId}
              checked={originalPreference.showOriginalPhotos}
              disabled={originalPreference.loading}
              onCheckedChange={(checked) => {
                void originalPreference
                  .setShowOriginalPhotos(checked === true)
                  .catch(() => {
                    toast.error("Не удалось сохранить качество фотографий")
                  })
              }}
            />
            <FieldLabel htmlFor={originalToggleId} className="font-normal">
              Оригиналы
            </FieldLabel>
          </Field>
        </div>
        {!readOnly && DEV_MAINTENANCE_FIXTURES_ENABLED ? (
          <Button
            type="button"
            variant="outline"
            onClick={directUpload ? pickFiles : () => setManagerOpen(true)}
          >
            <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
            Добавить
          </Button>
        ) : !readOnly ? (
          <Badge variant="outline">Загрузка медиа недоступна</Badge>
        ) : null}
      </div>

      {directUpload && DEV_MAINTENANCE_FIXTURES_ENABLED ? (
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*,video/*"
          multiple
          className="sr-only"
          onChange={(event) => {
            addFiles(Array.from(event.currentTarget.files ?? []))
            event.currentTarget.value = ""
          }}
        />
      ) : null}
      {opaqueMedia.length > 0 ? (
        <div className="flex flex-wrap gap-2" aria-label="Ссылки на медиа">
          {opaqueMedia.map((item) => (
            <Badge key={item.id} variant="outline" title={item.id}>
              {item.id}
            </Badge>
          ))}
        </div>
      ) : null}
      <div
        className={cn(
          "min-h-56 flex-1",
          directUpload &&
            DEV_MAINTENANCE_FIXTURES_ENABLED &&
            items.length === 0 &&
            "hidden md:block"
        )}
        onDragOver={
          directUpload && DEV_MAINTENANCE_FIXTURES_ENABLED
            ? (event) => event.preventDefault()
            : undefined
        }
        onDrop={
          directUpload && DEV_MAINTENANCE_FIXTURES_ENABLED
            ? (event) => {
                event.preventDefault()
                addFiles(
                  Array.from(event.dataTransfer.files).filter(
                    (file) =>
                      file.type.startsWith("image/") ||
                      file.type.startsWith("video/")
                  )
                )
              }
            : undefined
        }
      >
        <PhotoCarousel
          photos={carouselPhotos}
          title="Медиа осмотра"
          photoCount={items.length}
          showPhotoCount
          activeIndex={safeActiveIndex}
          onActiveIndexChange={setActiveIndex}
          className="h-full min-h-56 rounded-lg border"
          imageClassName={rotationClassName(activeItem?.rotationDegrees ?? 0)}
          fit="contain"
          controlsVisibility="mobile-visible"
          placeholder={
            directUpload ? (
              <span>Перетащите фото или видео сюда.</span>
            ) : undefined
          }
          fullscreenQuality={
            originalPreference.showOriginalPhotos ? "original" : "preview"
          }
        />
      </div>

      {!readOnly ? (
        <RepairEstimatePhotoManagerDialog
          open={managerOpen}
          items={items}
          maxItems={20}
          onOpenChange={setManagerOpen}
          onAddFiles={addFiles}
          onRemove={removeItem}
          onRotate={rotateItem}
        />
      ) : null}
    </section>
  )
}
