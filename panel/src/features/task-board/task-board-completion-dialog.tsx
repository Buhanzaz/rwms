import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

type TaskBoardCompletionDialogProps = {
  entry: TaskBoardEntryDto | null
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onComplete: () => void
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
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Завершить этап</DialogTitle>
          <DialogDescription>
            {entry
              ? `Завершить работу над этапом «${entry.title}»? Если отмечены отсутствующие материалы или работы, задание останется на текущем этапе до восстановления.`
              : "Подтвердите завершение этапа."}
          </DialogDescription>
        </DialogHeader>
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
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={pending || !entry}
            onClick={onComplete}
          >
            {pending ? "Сохранение..." : "Завершить этап"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
