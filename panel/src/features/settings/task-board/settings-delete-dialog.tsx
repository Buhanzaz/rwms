import { Delete02Icon, Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
  const deleteAction = confirmLabel === "Удалить"

  return (
    <AlertDialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          {deleteAction ? (
            <AlertDialogAction
              variant="destructive"
              size="icon"
              className="sm:mr-auto"
              aria-label={pending ? "Удаление…" : "Удалить"}
              title="Удалить"
              disabled={pending}
              onClick={(event) => {
                event.preventDefault()
                void onConfirm()
              }}
            >
              <HugeiconsIcon
                icon={pending ? Loading03Icon : Delete02Icon}
                className={pending ? "animate-spin" : undefined}
                aria-hidden="true"
              />
            </AlertDialogAction>
          ) : null}
          {error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null}
          <AlertDialogCancel disabled={pending}>Отмена</AlertDialogCancel>
          {!deleteAction ? (
            <AlertDialogAction
              variant="default"
              disabled={pending}
              onClick={(event) => {
                event.preventDefault()
                void onConfirm()
              }}
            >
              {pending ? "Выполняем…" : confirmLabel}
            </AlertDialogAction>
          ) : null}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
