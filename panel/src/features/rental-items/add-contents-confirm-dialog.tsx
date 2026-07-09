import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"

type AddContentsConfirmDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

export function AddContentsConfirmDialog({
  open,
  onOpenChange,
  onConfirm,
}: AddContentsConfirmDialogProps) {
  function handleConfirm() {
    onConfirm()
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Добавить или нет?</DialogTitle>
        </DialogHeader>

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Нет
          </Button>

          <Button onClick={handleConfirm}>Да</Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
