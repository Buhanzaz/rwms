import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  Delete02Icon,
  ImageUploadIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import {
  type ReadyMediaReference,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import { useServiceOwnerMedia } from "@/features/media/use-service-owner-media"

type WorkLinePhotoControlsProps = {
  accessToken: string | null
  owner: ServiceMediaOwner | null
  ensureOwner?: () => Promise<ServiceMediaOwner>
  disabled?: boolean
  excludedMediaIds: ReadonlySet<string>
  value: ReadyMediaReference[]
  onChange: (references: ReadyMediaReference[]) => void
  onPendingChange?: (pending: boolean) => void
  open?: boolean
  onOpenChange?: (open: boolean) => void
}

export function WorkLinePhotoControls({
  accessToken,
  owner,
  ensureOwner,
  disabled = false,
  excludedMediaIds,
  value,
  onChange,
  onPendingChange,
  open,
  onOpenChange,
}: WorkLinePhotoControlsProps) {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [resolvedOwner, setResolvedOwner] = useState(owner)
  const [queuedFiles, setQueuedFiles] = useState<readonly File[]>([])
  const [selectRequest, setSelectRequest] = useState(0)
  const [resolvingOwner, setResolvingOwner] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [ownedPending, setOwnedPending] = useState(false)
  const activeOwner = owner ?? resolvedOwner
  const managerMode = open !== undefined && onOpenChange !== undefined
  const handleOwnedPendingChange = useCallback(
    (pending: boolean) => {
      setOwnedPending(pending)
      onPendingChange?.(pending)
    },
    [onPendingChange]
  )

  async function requireOwner() {
    if (resolvedOwner) return resolvedOwner
    if (owner) {
      setResolvedOwner(owner)
      return owner
    }
    if (!ensureOwner) {
      throw new Error("Сначала сохраните документ, чтобы прикрепить фото")
    }
    setResolvingOwner(true)
    try {
      const value = await ensureOwner()
      setResolvedOwner(value)
      return value
    } finally {
      setResolvingOwner(false)
    }
  }

  async function acceptFiles(files: FileList | null) {
    const images = Array.from(files ?? []).filter((file) =>
      file.type.startsWith("image/")
    )
    if (images.length === 0) return
    try {
      await requireOwner()
      setUploading(true)
      setQueuedFiles(images)
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось подготовить загрузку"
      )
    }
  }

  async function openExisting() {
    try {
      await requireOwner()
      setSelectRequest((current) => current + 1)
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось загрузить фотографии"
      )
    }
  }

  const unavailable = disabled || resolvingOwner || !accessToken
  const actions = (
    <>
      <Input
        ref={inputRef}
        className="sr-only"
        type="file"
        accept="image/*"
        multiple
        disabled={unavailable}
        aria-label="Добавить фото работы"
        onChange={(event) => {
          void acceptFiles(event.target.files)
          event.target.value = ""
        }}
      />
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <Button
          type="button"
          variant="outline"
          disabled={unavailable}
          onClick={() => inputRef.current?.click()}
        >
          <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
          {managerMode ? "Добавить новые фото" : "Добавить фото"}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={unavailable}
          onClick={() => void openExisting()}
        >
          Выбрать из сделанных
        </Button>
      </div>
    </>
  )
  const content = (
    <>
      {actions}
      {!managerMode ? (
        <p className="text-xs text-muted-foreground">
          {value.length > 0
            ? `К работе выбрано фото: ${value.length}`
            : "Фото необязательны и будут видны только у этой работы."}
        </p>
      ) : null}
      {activeOwner && accessToken ? (
        <OwnedWorkLinePhotoControls
          accessToken={accessToken}
          owner={activeOwner}
          excludedMediaIds={excludedMediaIds}
          value={value}
          queuedFiles={queuedFiles}
          selectRequest={selectRequest}
          uploading={uploading}
          onFilesConsumed={() => setQueuedFiles([])}
          onUploadFinished={() => setUploading(false)}
          onChange={onChange}
          showManager={managerMode}
          onPendingChange={handleOwnedPendingChange}
        />
      ) : null}
    </>
  )

  if (open !== undefined && onOpenChange !== undefined) {
    return (
      <Dialog
        open={open}
        onOpenChange={(nextOpen) => {
          if (!nextOpen && (uploading || ownedPending)) {
            toast.info("Дождитесь завершения обработки фотографий")
            return
          }
          onOpenChange(nextOpen)
        }}
      >
        <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-5xl">
          <DialogHeader>
            <DialogTitle>Фотографии работы</DialogTitle>
            <DialogDescription>
              Добавляйте фотографии только этой работы. После удаления фото
              останется доступно для повторного выбора.
            </DialogDescription>
          </DialogHeader>
          <section
            className="flex flex-col gap-4"
            aria-label="Редактор фотографий работы"
          >
            {content}
          </section>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={uploading || ownedPending}
              onClick={() => onOpenChange(false)}
            >
              Готово
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    )
  }

  return (
    <section className="flex flex-col gap-2" aria-label="Фотографии работы">
      {content}
    </section>
  )
}

function OwnedWorkLinePhotoControls({
  accessToken,
  owner,
  excludedMediaIds,
  value,
  queuedFiles,
  selectRequest,
  uploading,
  onFilesConsumed,
  onUploadFinished,
  onChange,
  showManager,
  onPendingChange,
}: {
  accessToken: string
  owner: ServiceMediaOwner
  excludedMediaIds: ReadonlySet<string>
  value: ReadyMediaReference[]
  queuedFiles: readonly File[]
  selectRequest: number
  uploading: boolean
  onFilesConsumed: () => void
  onUploadFinished: () => void
  onChange: (references: ReadyMediaReference[]) => void
  showManager: boolean
  onPendingChange?: (pending: boolean) => void
}) {
  const media = useServiceOwnerMedia({ accessToken, owner })
  const [selectorOpen, setSelectorOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [pendingMediaIds, setPendingMediaIds] = useState<Set<string>>(new Set())
  const handledSelectRequest = useRef(0)
  const reportedReadyIds = useRef(new Set<string>())
  const reportedFailedIds = useRef(new Set<string>())

  useEffect(() => {
    if (selectRequest <= handledSelectRequest.current) return
    handledSelectRequest.current = selectRequest
    setSelectedIds(new Set(value.map((reference) => reference.mediaId)))
    setSelectorOpen(true)
  }, [selectRequest, value])

  useEffect(() => {
    if (queuedFiles.length === 0) return
    const files = [...queuedFiles]
    onFilesConsumed()
    const folderId = crypto.randomUUID()
    void media
      .upload(
        files.map((file, index) => ({
          file,
          folderId,
          sortOrder: media.logicalPhotoCount + index,
          commandKeys: {
            createSession: crypto.randomUUID(),
            uploadAndFinalize: crypto.randomUUID(),
          },
        }))
      )
      .then((assets) => {
        setPendingMediaIds(
          (current) => new Set([...current, ...assets.map((asset) => asset.id)])
        )
      })
      .catch((error) =>
        toast.error(
          error instanceof Error
            ? error.message
            : "Не удалось загрузить фото работы"
        )
      )
      .finally(onUploadFinished)
  }, [media, onFilesConsumed, onUploadFinished, queuedFiles])

  useEffect(() => {
    if (pendingMediaIds.size === 0) return
    const readyById = new Map(
      media.readyReferences.map((reference) => [reference.mediaId, reference])
    )
    const ready = [...pendingMediaIds].flatMap((mediaId) => {
      const reference = readyById.get(mediaId)
      return reference && !reportedReadyIds.current.has(mediaId)
        ? [reference]
        : []
    })
    if (ready.length === 0) return
    ready.forEach((reference) =>
      reportedReadyIds.current.add(reference.mediaId)
    )
    onChange(
      Array.from(
        new Map(
          [...value, ...ready].map((reference) => [
            reference.mediaId,
            reference,
          ])
        ).values()
      )
    )
  }, [media.readyReferences, onChange, pendingMediaIds, value])

  useEffect(() => {
    const failedIds = new Set(
      media.assets
        .filter((asset) => asset.status === "FAILED")
        .map((asset) => asset.id)
    )
    const newlyFailed = [...pendingMediaIds].filter(
      (mediaId) =>
        failedIds.has(mediaId) && !reportedFailedIds.current.has(mediaId)
    )
    if (newlyFailed.length === 0) return
    newlyFailed.forEach((mediaId) => reportedFailedIds.current.add(mediaId))
    toast.error(
      "Некоторые фотографии работы не обработаны. Повторите загрузку."
    )
  }, [media.assets, pendingMediaIds])

  const resolvedIds = new Set([
    ...media.readyReferences.map((reference) => reference.mediaId),
    ...media.assets
      .filter((asset) => asset.status === "FAILED")
      .map((asset) => asset.id),
  ])
  const pending =
    uploading ||
    media.pending ||
    [...pendingMediaIds].some((mediaId) => !resolvedIds.has(mediaId))
  useEffect(() => onPendingChange?.(pending), [onPendingChange, pending])
  useEffect(() => () => onPendingChange?.(false), [onPendingChange])

  const readyById = useMemo(
    () =>
      new Map(
        media.readyReferences.map((reference) => [reference.mediaId, reference])
      ),
    [media.readyReferences]
  )
  const photoById = useMemo(
    () => new Map(media.photos.map((photo) => [photo.id, photo])),
    [media.photos]
  )
  const availableAssets = media.assets.filter(
    (asset) =>
      asset.kind === "IMAGE" &&
      asset.status === "READY" &&
      !excludedMediaIds.has(asset.id)
  )
  const selectedMediaIds = useMemo(
    () => new Set(value.map((reference) => reference.mediaId)),
    [value]
  )
  const selectedAssets = media.assets.filter(
    (asset) =>
      asset.kind === "IMAGE" &&
      asset.status !== "DELETED" &&
      selectedMediaIds.has(asset.id)
  )

  useEffect(() => {
    const next = value.map(
      (reference) => readyById.get(reference.mediaId) ?? reference
    )
    const changed = next.some(
      (reference, index) =>
        reference.generation !== value[index]?.generation ||
        reference.mediaId !== value[index]?.mediaId
    )
    if (changed) onChange(next)
  }, [onChange, readyById, value])

  function confirmSelection() {
    onChange(
      [...selectedIds].flatMap((mediaId) => {
        const reference = readyById.get(mediaId)
        return reference ? [reference] : []
      })
    )
    setSelectorOpen(false)
  }

  return (
    <>
      {media.error ? (
        <p role="alert" className="text-xs text-destructive">
          {media.error}
        </p>
      ) : null}
      {pending ? (
        <p role="status" className="text-xs text-muted-foreground">
          Фото загружаются и обрабатываются…
        </p>
      ) : null}
      {showManager ? (
        media.query.isLoading ? (
          <p role="status" className="text-sm text-muted-foreground">
            Загружаем фотографии работы…
          </p>
        ) : selectedAssets.length === 0 ? (
          <Card size="sm">
            <CardHeader>
              <CardTitle>Фото пока не добавлены</CardTitle>
              <CardDescription>
                Загрузите новые изображения или выберите свободные фотографии
                этого документа.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {selectedAssets.map((asset, index) => {
              const photo = photoById.get(asset.id)
              const quarterTurn =
                asset.rotationDegrees === 90 || asset.rotationDegrees === 270
              return (
                <Card key={asset.id} size="sm" className="min-w-0">
                  <CardHeader className="min-w-0">
                    <CardTitle className="min-w-0 truncate">
                      {index + 1}. {asset.fileName}
                    </CardTitle>
                    <CardDescription>
                      <Badge variant="secondary">
                        {asset.status === "READY"
                          ? "Готово"
                          : asset.status === "FAILED"
                            ? "Ошибка обработки"
                            : "Обработка"}
                      </Badge>
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="flex aspect-video items-center justify-center overflow-hidden rounded-md bg-muted">
                      {photo ? (
                        <img
                          src={photo.url}
                          alt={`Предпросмотр ${asset.fileName}`}
                          className="max-h-full max-w-full object-contain"
                          style={{
                            transform: `rotate(${asset.rotationDegrees}deg) scale(${quarterTurn ? 0.75 : 1})`,
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
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Удалить ${asset.fileName} из работы`}
                      disabled={pending}
                      onClick={() =>
                        onChange(
                          value.filter(
                            (reference) => reference.mediaId !== asset.id
                          )
                        )
                      }
                    >
                      <HugeiconsIcon icon={Delete02Icon} />
                    </Button>
                  </CardFooter>
                </Card>
              )
            })}
          </div>
        )
      ) : null}
      <Dialog open={selectorOpen} onOpenChange={setSelectorOpen}>
        <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>Выбрать фото работы</DialogTitle>
            <DialogDescription>
              Показаны готовые фотографии этого документа, которые ещё не
              принадлежат другой работе.
            </DialogDescription>
          </DialogHeader>
          {media.query.isLoading ? (
            <p role="status" className="text-sm text-muted-foreground">
              Загружаем доступные фотографии…
            </p>
          ) : availableAssets.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Свободных готовых фотографий пока нет.
            </p>
          ) : (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              {availableAssets.map((asset) => {
                const checked = selectedIds.has(asset.id)
                const photo = photoById.get(asset.id)
                return (
                  <label
                    key={asset.id}
                    className="flex cursor-pointer flex-col gap-2 rounded-lg border p-2"
                  >
                    {photo ? (
                      <img
                        src={photo.url}
                        alt={asset.fileName}
                        className="aspect-video w-full rounded object-cover"
                      />
                    ) : (
                      <div className="flex aspect-video items-center justify-center rounded bg-muted text-xs text-muted-foreground">
                        Превью загружается
                      </div>
                    )}
                    <span className="flex items-center gap-2 text-sm">
                      <Checkbox
                        checked={checked}
                        onCheckedChange={(next) =>
                          setSelectedIds((current) => {
                            const result = new Set(current)
                            if (next === true) result.add(asset.id)
                            else result.delete(asset.id)
                            return result
                          })
                        }
                      />
                      <span className="truncate">{asset.fileName}</span>
                    </span>
                  </label>
                )
              })}
            </div>
          )}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setSelectorOpen(false)}
            >
              Отмена
            </Button>
            <Button
              type="button"
              disabled={media.query.isLoading}
              onClick={confirmSelection}
            >
              Выбрать
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
