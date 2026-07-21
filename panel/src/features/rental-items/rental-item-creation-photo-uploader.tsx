import { useMemo, useRef, useState, type DragEvent } from "react"
import {
  Delete02Icon,
  EyeIcon,
  ImageUploadIcon,
  RotateClockwiseIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
import type {
  MediaRotationDegrees,
  MediaUploadCommandKeys,
} from "@/features/media/media-service"
import { cn } from "@/lib/utils"

const MAX_CREATION_PHOTOS = 20
const ACCEPTED_IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"])

export type StagedRentalItemPhoto = Readonly<{
  id: string
  file: File
  previewUrl: string
  rotationDegrees: MediaRotationDegrees
  commandKeys: MediaUploadCommandKeys
  rotateKey: string
}>

export function disposeStagedRentalItemPhotos(
  photos: readonly StagedRentalItemPhoto[]
) {
  photos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl))
}

function stagePhoto(file: File): StagedRentalItemPhoto {
  return {
    id: crypto.randomUUID(),
    file,
    previewUrl: URL.createObjectURL(file),
    rotationDegrees: 0,
    commandKeys: {
      createSession: crypto.randomUUID(),
      uploadAndFinalize: crypto.randomUUID(),
    },
    rotateKey: crypto.randomUUID(),
  }
}

function rotatePhoto(photo: StagedRentalItemPhoto): StagedRentalItemPhoto {
  return {
    ...photo,
    rotationDegrees: ((photo.rotationDegrees + 90) %
      360) as MediaRotationDegrees,
  }
}

export function RentalItemCreationPhotoUploader({
  photos,
  disabled = false,
  onChange,
}: {
  photos: StagedRentalItemPhoto[]
  disabled?: boolean
  onChange: (photos: StagedRentalItemPhoto[]) => void
}) {
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [dropActive, setDropActive] = useState(false)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const selectedPhoto = useMemo(
    () => photos.find((photo) => photo.id === previewId) ?? null,
    [photos, previewId]
  )

  function addFiles(files: FileList | File[] | null) {
    const candidates = Array.from(files ?? [])
    const valid = candidates.filter((file) =>
      ACCEPTED_IMAGE_TYPES.has(file.type.toLowerCase())
    )
    const remaining = Math.max(0, MAX_CREATION_PHOTOS - photos.length)
    const selected = valid.slice(0, remaining)
    if (selected.length > 0) {
      onChange([...photos, ...selected.map(stagePhoto)])
    }
    if (valid.length !== candidates.length) {
      setError("Поддерживаются только JPEG, PNG и WebP.")
    } else if (valid.length > remaining) {
      setError(`Можно добавить не более ${MAX_CREATION_PHOTOS} фотографий.`)
    } else {
      setError(null)
    }
    if (fileInputRef.current) fileInputRef.current.value = ""
  }

  function drop(event: DragEvent<HTMLButtonElement>) {
    event.preventDefault()
    setDropActive(false)
    if (!disabled) addFiles(event.dataTransfer.files)
  }

  function removePhoto(photo: StagedRentalItemPhoto) {
    URL.revokeObjectURL(photo.previewUrl)
    onChange(photos.filter((candidate) => candidate.id !== photo.id))
    if (previewId === photo.id) setPreviewId(null)
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
            disabled={disabled || photos.length >= MAX_CREATION_PHOTOS}
            onClick={() => fileInputRef.current?.click()}
          >
            <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
            Загрузить фото
          </Button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          className="hidden"
          aria-label="Фотографии новой бытовки"
          disabled={disabled}
          onChange={(event) => addFiles(event.target.files)}
        />
        <button
          type="button"
          disabled={disabled || photos.length >= MAX_CREATION_PHOTOS}
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
            if (!event.currentTarget.contains(event.relatedTarget as Node)) {
              setDropActive(false)
            }
          }}
          onDrop={drop}
        >
          Перетащите несколько изображений
        </button>
        {photos.length === 0 ? (
          <FieldDescription>
            Оригиналы загрузятся после создания бытовки; small, medium и large
            сформирует media-service.
          </FieldDescription>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {photos.map((photo) => (
              <article
                key={photo.id}
                className="overflow-hidden rounded-lg border bg-card"
              >
                <button
                  type="button"
                  className="block aspect-[4/3] w-full overflow-hidden bg-muted"
                  onClick={() => setPreviewId(photo.id)}
                >
                  <img
                    src={photo.previewUrl}
                    alt={photo.file.name}
                    className="size-full object-cover transition-transform"
                    style={{ transform: `rotate(${photo.rotationDegrees}deg)` }}
                  />
                </button>
                <div className="flex items-center justify-between gap-2 p-2">
                  <div className="min-w-0">
                    <div className="truncate text-xs font-medium">
                      {photo.file.name}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Поворот: {photo.rotationDegrees}°
                    </div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Просмотреть ${photo.file.name}`}
                      onClick={() => setPreviewId(photo.id)}
                    >
                      <HugeiconsIcon icon={EyeIcon} />
                    </Button>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Повернуть ${photo.file.name}`}
                      disabled={disabled}
                      onClick={() =>
                        onChange(
                          photos.map((candidate) =>
                            candidate.id === photo.id
                              ? rotatePhoto(candidate)
                              : candidate
                          )
                        )
                      }
                    >
                      <HugeiconsIcon icon={RotateClockwiseIcon} />
                    </Button>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Удалить ${photo.file.name}`}
                      disabled={disabled}
                      onClick={() => removePhoto(photo)}
                    >
                      <HugeiconsIcon icon={Delete02Icon} />
                    </Button>
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}
        {error ? <FieldError role="alert">{error}</FieldError> : null}
      </FieldSet>

      <Dialog
        open={selectedPhoto !== null}
        onOpenChange={(nextOpen) => !nextOpen && setPreviewId(null)}
      >
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>
              {selectedPhoto?.file.name ?? "Фото бытовки"}
            </DialogTitle>
            <DialogDescription>
              Локальный предпросмотр до загрузки в media-service.
            </DialogDescription>
          </DialogHeader>
          {selectedPhoto ? (
            <div className="flex max-h-[70svh] items-center justify-center overflow-hidden rounded-lg border bg-muted">
              <img
                src={selectedPhoto.previewUrl}
                alt={selectedPhoto.file.name}
                className="max-h-[70svh] max-w-full object-contain transition-transform"
                style={{
                  transform: `rotate(${selectedPhoto.rotationDegrees}deg)`,
                }}
              />
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  )
}
