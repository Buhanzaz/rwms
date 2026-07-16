import { useMemo, useRef, useState, type DragEvent } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"
import {
  Delete02Icon,
  EyeIcon,
  ImageUploadIcon,
  RotateClockwiseIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  FieldDescription,
  FieldError,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import {
  prepareRentalItemPhotoUpload,
  rotateRentalItemCreationPhoto,
} from "@/features/rental-items/api/rental-items-api"
import type { RentalItemCreationPhoto } from "@/features/rental-items/model/rental-item-create"
import { cn } from "@/lib/utils"

export function RentalItemPhotoUploader({
  photos,
  disabled = false,
  onChange,
}: {
  photos: RentalItemCreationPhoto[]
  disabled?: boolean
  onChange: (photos: RentalItemCreationPhoto[]) => void
}) {
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [processing, setProcessing] = useState(false)
  const [dropActive, setDropActive] = useState(false)
  const [rotatingId, setRotatingId] = useState<string | null>(null)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const selectedPhoto = useMemo(
    () => photos.find((photo) => photo.id === previewId) ?? null,
    [photos, previewId]
  )

  async function addFiles(files: FileList | File[] | null) {
    const imageFiles = Array.from(files ?? []).filter((file) =>
      file.type.startsWith("image/")
    )
    if (!imageFiles.length) return
    setError(null)
    setProcessing(true)
    try {
      const prepared = await Promise.all(
        imageFiles.map((file) => prepareRentalItemPhotoUpload(file))
      )
      onChange([...photos, ...prepared])
    } catch (cause) {
      const message =
        cause instanceof Error
          ? cause.message
          : "Не удалось подготовить фотографии"
      setError(message)
      toast.error(message)
    } finally {
      setProcessing(false)
      if (fileInputRef.current) fileInputRef.current.value = ""
    }
  }

  function drop(event: DragEvent<HTMLButtonElement>) {
    event.preventDefault()
    setDropActive(false)
    if (!processing && !disabled) void addFiles(event.dataTransfer.files)
  }

  async function rotate(photo: RentalItemCreationPhoto) {
    setRotatingId(photo.id)
    try {
      const rotated = await rotateRentalItemCreationPhoto(photo)
      onChange(
        photos.map((entry) => (entry.id === rotated.id ? rotated : entry))
      )
    } finally {
      setRotatingId(null)
    }
  }

  return (
    <>
      <FieldSet>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <FieldLegend>Фото бытовки</FieldLegend>
          <Button
            type="button"
            variant="outline"
            className="w-full justify-center md:w-[16.25rem]"
            disabled={disabled || processing}
            onClick={() => fileInputRef.current?.click()}
          >
            <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
            {processing ? "Обработка..." : "Загрузить фото"}
          </Button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          className="hidden"
          onChange={(event) => void addFiles(event.target.files)}
        />
        <button
          type="button"
          disabled={disabled || processing}
          className={cn(
            "hidden h-28 w-full items-center justify-center rounded-lg border border-dashed bg-muted/30 px-4 text-center text-sm font-medium text-muted-foreground transition xl:flex",
            "hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 focus-visible:outline-none disabled:pointer-events-none disabled:opacity-60",
            dropActive && "border-primary bg-primary/5 text-primary"
          )}
          onClick={() => fileInputRef.current?.click()}
          onDragEnter={(event) => {
            event.preventDefault()
            setDropActive(true)
          }}
          onDragOver={(event) => {
            event.preventDefault()
            event.dataTransfer.dropEffect = "copy"
            setDropActive(true)
          }}
          onDragLeave={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node))
              setDropActive(false)
          }}
          onDrop={drop}
        >
          Перетащите несколько изображений
        </button>
        {!photos.length ? (
          <FieldDescription>
            Фотографии можно добавить позднее.
          </FieldDescription>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {photos.map((photo) => (
              <div
                key={photo.id}
                className="overflow-hidden rounded-lg border bg-card"
              >
                <button
                  type="button"
                  className="block aspect-[4/3] w-full bg-muted"
                  onClick={() => setPreviewId(photo.id)}
                >
                  <img
                    src={photo.variants.small.url}
                    alt={photo.name}
                    className="h-full w-full object-cover"
                  />
                </button>
                <div className="flex items-center justify-between gap-2 p-2">
                  <div className="min-w-0">
                    <div className="truncate text-xs font-medium">
                      {photo.name}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Поворот: {photo.rotation}°
                    </div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label="Просмотреть фото"
                      onClick={() => setPreviewId(photo.id)}
                    >
                      <HugeiconsIcon icon={EyeIcon} />
                    </Button>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label="Повернуть фото"
                      disabled={disabled || rotatingId === photo.id}
                      onClick={() => void rotate(photo)}
                    >
                      <HugeiconsIcon icon={RotateClockwiseIcon} />
                    </Button>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label="Удалить фото"
                      disabled={disabled}
                      onClick={() =>
                        onChange(
                          photos.filter((entry) => entry.id !== photo.id)
                        )
                      }
                    >
                      <HugeiconsIcon icon={Delete02Icon} />
                    </Button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
        {error ? <FieldError role="alert">{error}</FieldError> : null}
      </FieldSet>
      <Dialog
        open={selectedPhoto !== null}
        onOpenChange={(open) => !open && setPreviewId(null)}
      >
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{selectedPhoto?.name ?? "Фото бытовки"}</DialogTitle>
            <DialogDescription>
              Предпросмотр загруженного изображения.
            </DialogDescription>
          </DialogHeader>
          {selectedPhoto ? (
            <div className="flex max-h-[70svh] items-center justify-center overflow-hidden rounded-lg border bg-muted">
              <img
                src={selectedPhoto.variants.largeWebp.url}
                alt={selectedPhoto.name}
                className="max-h-[70svh] max-w-full object-contain"
              />
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  )
}
