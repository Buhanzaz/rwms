import { useEffect, useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { RepairEstimatePhotos } from "@/features/repair-estimates/repair-estimate-photos"
import type { PendingEstimateMediaUpload } from "@/features/repair-estimates/model/repair-estimate"

export function LogisticsPhotoDialog({
  open,
  title,
  pending,
  error,
  onOpenChange,
  onSubmit,
}: {
  open: boolean
  title: string
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onSubmit: (uploads: PendingEstimateMediaUpload[]) => void
}) {
  const [uploads, setUploads] = useState<PendingEstimateMediaUpload[]>([])
  const uploadsRef = useRef(uploads)

  useEffect(() => {
    uploadsRef.current = uploads
  }, [uploads])

  useEffect(
    () => () => {
      uploadsRef.current.forEach((item) => URL.revokeObjectURL(item.previewUrl))
    },
    []
  )

  function close(next: boolean) {
    if (!next && !pending) {
      uploads.forEach((item) => URL.revokeObjectURL(item.previewUrl))
      setUploads([])
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="flex max-h-[calc(100svh-1rem)] min-h-0 flex-col sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Добавьте от 1 до 20 фотографий состояния бытовки.
          </DialogDescription>
        </DialogHeader>
        <div className="min-h-0 overflow-hidden md:min-h-80 md:flex-1">
          <RepairEstimatePhotos
            media={[]}
            pendingUploads={uploads}
            readOnly={pending}
            directUpload
            onMediaChange={() => undefined}
            onPendingUploadsChange={setUploads}
          />
        </div>
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => close(false)}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={pending || uploads.length === 0}
            onClick={() => onSubmit(uploads)}
          >
            {pending ? "Сохранение..." : "Продолжить"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
