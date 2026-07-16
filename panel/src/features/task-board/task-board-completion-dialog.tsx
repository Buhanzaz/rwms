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
import type { RepairTaskSubtaskDto } from "@/features/repair-tasks/model/repair-task"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

type TaskBoardCompletionDialogProps = {
  entry: TaskBoardEntryDto | null
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onComplete: (uploads: PendingEstimateMediaUpload[]) => void
}

function requiredPhotoCount(subtask: RepairTaskSubtaskDto) {
  const compatible = subtask as RepairTaskSubtaskDto & {
    photoRequired?: boolean
  }
  return compatible.photoRequired ? 3 : 0
}

export function TaskBoardCompletionDialog({
  entry,
  pending,
  error,
  onOpenChange,
  onComplete,
}: TaskBoardCompletionDialogProps) {
  return (
    <Dialog
      open={entry !== null}
      onOpenChange={(open) => {
        if (!pending) onOpenChange(open)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Завершение этапа</DialogTitle>
          <DialogDescription>
            Добавьте фотографии результата для выбранной рабочей группы.
          </DialogDescription>
        </DialogHeader>
        {entry ? (
          <TaskBoardCompletionForm
            key={`${entry.task.id}:${entry.task.version}:${entry.subtask.id}`}
            entry={entry}
            pending={pending}
            error={error}
            onCancel={() => onOpenChange(false)}
            onComplete={onComplete}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function TaskBoardCompletionForm({
  entry,
  pending,
  error,
  onCancel,
  onComplete,
}: {
  entry: TaskBoardEntryDto
  pending: boolean
  error: string | null
  onCancel: () => void
  onComplete: (uploads: PendingEstimateMediaUpload[]) => void
}) {
  const [pendingUploads, setPendingUploads] = useState<
    PendingEstimateMediaUpload[]
  >([])
  const pendingUploadsRef = useRef(pendingUploads)
  const minimum = requiredPhotoCount(entry.subtask)
  const missing = Math.max(0, minimum - pendingUploads.length)

  useEffect(() => {
    pendingUploadsRef.current = pendingUploads
  }, [pendingUploads])

  useEffect(() => {
    return () => {
      pendingUploadsRef.current.forEach((upload) =>
        URL.revokeObjectURL(upload.previewUrl)
      )
    }
  }, [])

  return (
    <div className="flex min-h-0 flex-col gap-4">
      <div className="min-h-72">
        <RepairEstimatePhotos
          media={[]}
          pendingUploads={pendingUploads}
          readOnly={pending}
          onMediaChange={() => undefined}
          onPendingUploadsChange={setPendingUploads}
        />
      </div>

      <p className="text-xs text-muted-foreground">
        {minimum > 0
          ? `Для этого этапа требуется минимум ${minimum} фото от группы.`
          : "Для этого этапа фотографии необязательны."}
      </p>
      {missing > 0 ? (
        <p role="alert" className="text-xs text-destructive">
          Добавьте ещё фотографий: {missing}.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}

      <DialogFooter>
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
          disabled={pending || missing > 0}
          onClick={() => onComplete(pendingUploads)}
        >
          {pending ? "Сохранение..." : "Завершить этап"}
        </Button>
      </DialogFooter>
    </div>
  )
}
