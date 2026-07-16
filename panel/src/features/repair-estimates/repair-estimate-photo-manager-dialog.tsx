import { useId, useRef, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Delete02Icon,
  ImageUploadIcon,
  PlayIcon,
  RotateLeft01Icon,
  RotateRight01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import type { RepairEstimateMediaRotationDegrees } from "@/features/repair-estimates/model/repair-estimate"
import { useIsMobile } from "@/hooks/use-mobile"

export type RepairEstimatePhotoManagerItem = {
  id: string
  fileName: string
  previewUrl: string
  mimeType?: string
  rotationDegrees: RepairEstimateMediaRotationDegrees
  source: "SAVED" | "PENDING"
}

type RepairEstimatePhotoManagerDialogProps = {
  open: boolean
  items: RepairEstimatePhotoManagerItem[]
  maxItems?: number
  onOpenChange: (open: boolean) => void
  onAddFiles: (files: File[]) => void
  onRemove: (item: RepairEstimatePhotoManagerItem) => void
  onRotate: (
    item: RepairEstimatePhotoManagerItem,
    direction: "LEFT" | "RIGHT"
  ) => void
  onConfirm?: () => void
  onCancel?: () => void
  pending?: boolean
}

export function RepairEstimatePhotoManagerDialog({
  open,
  items,
  maxItems = 20,
  onOpenChange,
  onAddFiles,
  onRemove,
  onRotate,
  onConfirm,
  onCancel,
  pending = false,
}: RepairEstimatePhotoManagerDialogProps) {
  const inputId = useId()
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const isMobile = useIsMobile()
  const maxReached = items.length >= maxItems

  function isVideo(item: RepairEstimatePhotoManagerItem) {
    return item.mimeType?.toLowerCase().startsWith("video/") ?? false
  }

  function acceptFiles(files: FileList | null) {
    if (!files || maxReached) {
      return
    }

    const remaining = Math.max(0, maxItems - items.length)
    const media = Array.from(files)
      .filter(
        (file) =>
          file.type.startsWith("image/") || file.type.startsWith("video/")
      )
      .slice(0, remaining)

    if (media.length > 0) {
      onAddFiles(media)
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && onCancel) onCancel()
        else onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-1rem)] min-w-0 overflow-x-hidden overflow-y-auto sm:max-w-5xl">
        <DialogHeader className="min-w-0">
          <DialogTitle>Добавить медиа</DialogTitle>
          <DialogDescription>
            Выберите фото или видео, проверьте поворот и удалите лишние перед
            сохранением.
          </DialogDescription>
        </DialogHeader>

        <Card
          data-drag-active={!isMobile && dragActive}
          className="min-w-0 data-[drag-active=true]:ring-2 data-[drag-active=true]:ring-ring"
          onDragEnter={
            isMobile
              ? undefined
              : (event) => {
                  event.preventDefault()
                  if (!maxReached) {
                    setDragActive(true)
                  }
                }
          }
          onDragOver={
            isMobile
              ? undefined
              : (event) => {
                  event.preventDefault()
                  event.dataTransfer.dropEffect = "copy"
                }
          }
          onDragLeave={
            isMobile
              ? undefined
              : (event) => {
                  if (
                    !event.currentTarget.contains(event.relatedTarget as Node)
                  ) {
                    setDragActive(false)
                  }
                }
          }
          onDrop={
            isMobile
              ? undefined
              : (event) => {
                  event.preventDefault()
                  setDragActive(false)
                  acceptFiles(event.dataTransfer.files)
                }
          }
        >
          <CardHeader>
            <CardTitle>Загрузка файлов</CardTitle>
            <CardDescription>
              {isMobile
                ? "Выберите фото или видео на устройстве."
                : "Перетащите фото или видео в эту область или выберите их на устройстве."}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Field data-disabled={maxReached}>
              <FieldLabel htmlFor={inputId}>Файлы медиа</FieldLabel>
              <Input
                ref={inputRef}
                id={inputId}
                aria-label="Выбрать медиа"
                className="sr-only"
                type="file"
                accept="image/*,video/*"
                multiple
                disabled={maxReached}
                onChange={(event) => {
                  acceptFiles(event.target.files)
                  event.target.value = ""
                }}
              />
              <FieldDescription>
                {items.length} из {maxItems}
              </FieldDescription>
            </Field>
          </CardContent>
          <CardFooter>
            <Button
              type="button"
              variant="outline"
              disabled={maxReached}
              onClick={() => inputRef.current?.click()}
            >
              <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
              Выбрать файлы
            </Button>
          </CardFooter>
        </Card>

        {items.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            Медиа пока не добавлены.
          </p>
        ) : (
          <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((item, index) => {
              const isQuarterTurn =
                item.rotationDegrees === 90 || item.rotationDegrees === 270
              return (
                <Card key={item.id} size="sm" className="min-w-0">
                  <CardHeader className="min-w-0">
                    <CardTitle className="min-w-0 truncate">
                      {index + 1}. {item.fileName}
                    </CardTitle>
                    <CardDescription>
                      {item.source === "PENDING"
                        ? isVideo(item)
                          ? "Новое видео"
                          : "Новое фото"
                        : isVideo(item)
                          ? "Сохранённое видео"
                          : "Сохранённое фото"}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="flex aspect-video items-center justify-center overflow-hidden rounded-md bg-muted">
                      {isVideo(item) ? (
                        <div className="relative flex h-full w-full items-center justify-center">
                          <video
                            src={item.previewUrl}
                            aria-label={`Предпросмотр видео ${item.fileName}`}
                            muted
                            playsInline
                            preload="metadata"
                            className="max-h-full max-w-full object-contain"
                            style={{
                              transform: `rotate(${item.rotationDegrees}deg) scale(${isQuarterTurn ? 0.75 : 1})`,
                              transformOrigin: "center",
                            }}
                          />
                          <span className="pointer-events-none absolute flex size-10 items-center justify-center rounded-full bg-black/60 text-white">
                            <HugeiconsIcon icon={PlayIcon} />
                          </span>
                        </div>
                      ) : (
                        <img
                          src={item.previewUrl}
                          alt={`Предпросмотр ${item.fileName}`}
                          className="max-h-full max-w-full object-contain"
                          style={{
                            transform: `rotate(${item.rotationDegrees}deg) scale(${isQuarterTurn ? 0.75 : 1})`,
                            transformOrigin: "center",
                          }}
                        />
                      )}
                    </div>
                  </CardContent>
                  <CardFooter className="justify-between gap-2">
                    <div className="flex gap-1">
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        aria-label={`Повернуть ${item.fileName} влево`}
                        onClick={() => onRotate(item, "LEFT")}
                      >
                        <HugeiconsIcon icon={RotateLeft01Icon} />
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        aria-label={`Повернуть ${item.fileName} вправо`}
                        onClick={() => onRotate(item, "RIGHT")}
                      >
                        <HugeiconsIcon icon={RotateRight01Icon} />
                      </Button>
                    </div>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Удалить ${item.fileName}`}
                      onClick={() => onRemove(item)}
                    >
                      <HugeiconsIcon icon={Delete02Icon} />
                    </Button>
                  </CardFooter>
                </Card>
              )
            })}
          </div>
        )}

        <DialogFooter>
          {onConfirm ? (
            <>
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={onCancel}
              >
                Отмена
              </Button>
              <Button
                type="button"
                disabled={pending || items.length === 0}
                onClick={onConfirm}
              >
                Сохранить
              </Button>
            </>
          ) : (
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              Готово
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
