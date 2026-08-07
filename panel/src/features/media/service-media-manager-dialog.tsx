import { useId, useRef, useState } from "react"
import {
  CheckmarkCircle02Icon,
  Delete02Icon,
  ImageUploadIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
import type { MediaRotationDegrees } from "@/features/media/model/service-media"
import { useIsMobile } from "@/hooks/use-mobile"

export type ServiceMediaManagerItem = Readonly<{
  id: string
  fileName: string
  previewUrl: string | null
  /** Historic media can still need its persisted display orientation. */
  rotationDegrees?: MediaRotationDegrees
  statusLabel: string
  pending: boolean
}>

export function ServiceMediaManagerDialog({
  open,
  items,
  maxItems = 20,
  pending = false,
  coverMediaId = null,
  requireCover = false,
  onOpenChange,
  onAddFiles,
  onRemove,
  onSelectCover,
  onConfirm,
}: {
  open: boolean
  items: readonly ServiceMediaManagerItem[]
  maxItems?: number
  pending?: boolean
  coverMediaId?: string | null
  requireCover?: boolean
  onOpenChange: (open: boolean) => void
  onAddFiles: (files: File[]) => void
  onRemove: (item: ServiceMediaManagerItem) => void
  onSelectCover?: (item: ServiceMediaManagerItem) => void
  onConfirm?: () => void
}) {
  const inputId = useId()
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const isMobile = useIsMobile()
  const maxReached = items.length >= maxItems

  function acceptFiles(files: FileList | readonly File[] | null) {
    if (!files || maxReached || pending) return
    const remaining = Math.max(0, maxItems - items.length)
    const images = Array.from(files)
      .filter((file) => file.type.startsWith("image/"))
      .slice(0, remaining)
    if (images.length > 0) onAddFiles(images)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] min-w-0 overflow-x-hidden overflow-y-auto sm:max-w-5xl">
        <DialogHeader className="min-w-0">
          <DialogTitle>Добавить фото</DialogTitle>
          <DialogDescription>
            Выберите изображения и удалите лишние.
            {requireCover
              ? " Затем укажите титульную фотографию."
              : ""}
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
                  if (!maxReached && !pending) setDragActive(true)
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
                ? "Выберите изображения на устройстве."
                : "Перетащите изображения сюда или выберите их на устройстве."}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Field data-disabled={maxReached || pending}>
              <FieldLabel htmlFor={inputId}>Файлы изображений</FieldLabel>
              <Input
                ref={inputRef}
                id={inputId}
                aria-label="Выбрать фотографии"
                className="sr-only"
                type="file"
                accept="image/*"
                multiple
                disabled={maxReached || pending}
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
              disabled={maxReached || pending}
              onClick={() => inputRef.current?.click()}
            >
              <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
              Выбрать файлы
            </Button>
          </CardFooter>
        </Card>

        {items.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            Фотографии пока не добавлены.
          </p>
        ) : (
          <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((item, index) => {
              const rotationDegrees = item.rotationDegrees ?? 0
              const quarterTurn =
                rotationDegrees === 90 || rotationDegrees === 270
              return (
                <Card
                  key={item.id}
                  size="sm"
                  data-cover={coverMediaId === item.id}
                  className="min-w-0 data-[cover=true]:ring-2 data-[cover=true]:ring-primary"
                >
                  <CardHeader className="min-w-0">
                    <CardTitle className="min-w-0 truncate">
                      {index + 1}. {item.fileName}
                    </CardTitle>
                    <CardDescription>{item.statusLabel}</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="flex aspect-video items-center justify-center overflow-hidden rounded-md bg-muted">
                      {item.previewUrl ? (
                        <img
                          src={item.previewUrl}
                          alt={`Предпросмотр ${item.fileName}`}
                          className="max-h-full max-w-full object-contain"
                          style={{
                            transform: `rotate(${rotationDegrees}deg) scale(${quarterTurn ? 0.75 : 1})`,
                            transformOrigin: "center",
                          }}
                        />
                      ) : (
                        <span className="px-3 text-center text-xs text-muted-foreground">
                          Превью обрабатывается
                        </span>
                      )}
                    </div>
                  </CardContent>
                  <CardFooter className="flex-wrap justify-between gap-2">
                    {onSelectCover ? (
                      <Button
                        type="button"
                        size="sm"
                        variant={
                          coverMediaId === item.id ? "default" : "outline"
                        }
                        disabled={pending || item.pending}
                        aria-pressed={coverMediaId === item.id}
                        onClick={() => onSelectCover(item)}
                      >
                        {coverMediaId === item.id ? (
                          <HugeiconsIcon
                            icon={CheckmarkCircle02Icon}
                            data-icon="inline-start"
                          />
                        ) : null}
                        {coverMediaId === item.id
                          ? "Титульное"
                          : "Выбрать титульным"}
                      </Button>
                    ) : null}
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Удалить ${item.fileName}`}
                      disabled={pending || item.pending}
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
          {requireCover && items.length > 0 && !coverMediaId ? (
            <p role="alert" className="mr-auto text-sm text-destructive">
              Выберите титульную фотографию.
            </p>
          ) : null}
          <Button
            type="button"
            variant="outline"
            disabled={
              pending || (requireCover && items.length > 0 && !coverMediaId)
            }
            onClick={() => {
              if (onConfirm) onConfirm()
              else onOpenChange(false)
            }}
          >
            Готово
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
