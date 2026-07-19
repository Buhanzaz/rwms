import { useNavigate } from "react-router-dom"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type {
  RepairTaskDto,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"

type RepairReworkWizardDialogProps = {
  open: boolean
  task: RepairTaskDto
  canEdit: boolean
  onOpenChange: (open: boolean) => void
}

export function RepairReworkWizardDialog({
  open,
  task,
  canEdit,
  onOpenChange,
}: RepairReworkWizardDialogProps) {
  const navigate = useNavigate()

  function openEditor() {
    if (!canEdit) {
      return
    }

    const state: RepairsLocationState = {
      workspaceEntry: true,
      reworkSeed: {
        type: "repair-rework-seed-v1",
        warehouseId: task.warehouseId,
        sourceRepairTaskId: task.id,
        sourceRepairTaskVersion: task.version,
        sourceOrigin: task.origin,
        sourceEstimateId: task.sourceEstimateId,
        sourceEstimateVersion: task.sourceEstimateVersion,
        rentalItemId: task.rentalItemId,
        lines: [],
      },
    }
    onOpenChange(false)
    navigate("/repairs?create=1", { state })
  }

  return (
    <Dialog open={open && canEdit} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Создать доработку</DialogTitle>
          <DialogDescription>
            Откроется новый черновик доработки для текущей версии ремонта. План
            этапов будет взят из maintenance-service; причину и очереди можно
            проверить перед созданием.
          </DialogDescription>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">
          Адресный выбор исполнителя и отдельных строк не поддерживается
          публичным maintenance-контрактом и поэтому не отправляется.
        </p>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button type="button" onClick={openEditor}>
            Открыть черновик
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
