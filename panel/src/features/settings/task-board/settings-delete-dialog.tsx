import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

export function SettingsDeleteDialog({
  title,
  description,
  pending,
  confirmLabel = "Удалить",
  error,
  onClose,
  onConfirm,
}: {
  title: string
  description: string
  pending: boolean
  confirmLabel?: string
  error?: string | null
  onClose: () => void
  onConfirm: () => Promise<void>
}) {
  return (
    <AlertDialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>Отмена</AlertDialogCancel>
          {error ? (
            <p role="alert" className="text-xs text-destructive sm:mr-auto">
              {error}
            </p>
          ) : null}
          <AlertDialogAction
            variant="destructive"
            disabled={pending}
            onClick={(event) => {
              event.preventDefault()
              void onConfirm()
            }}
          >
            {pending ? "Выполняем…" : confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
