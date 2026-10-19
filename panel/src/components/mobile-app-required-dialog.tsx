import { InformationCircleIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

export const MANAGER_MOBILE_APP_DOWNLOAD_PATH =
  "/downloads/rwms-manager-app-debug.apk"

type MobileAppRequiredDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  operation: string
}

export function MobileAppRequiredDialog({
  open,
  onOpenChange,
  operation,
}: MobileAppRequiredDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{operation} доступно в мобильном приложении</DialogTitle>
          <DialogDescription>
            В мобильной версии панели эту операцию выполнить нельзя.
          </DialogDescription>
        </DialogHeader>

        <Alert>
          <HugeiconsIcon icon={InformationCircleIcon} />
          <AlertTitle>Используйте приложение RWMS Manager</AlertTitle>
          <AlertDescription>
            Установите приложение на устройство и выполните операцию в нём.
          </AlertDescription>
        </Alert>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Понятно
          </Button>
          <Button asChild>
            <a href={MANAGER_MOBILE_APP_DOWNLOAD_PATH} download>
              Скачать приложение
            </a>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
