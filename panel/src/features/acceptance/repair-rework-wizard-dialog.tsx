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
  selectedLineageRootIds: string[]
  onOpenChange: (open: boolean) => void
}

export function RepairReworkWizardDialog({
  open,
  task,
  canEdit,
  selectedLineageRootIds,
  onOpenChange,
}: RepairReworkWizardDialogProps) {
  const navigate = useNavigate()

  function openEditor() {
    if (!canEdit) {
      return
    }

    const state: RepairsLocationState = { workspaceEntry: true }
    const params = new URLSearchParams({
      create: "1",
      reworkWarehouseId: task.warehouseId,
      reworkSourceId: task.id,
      reworkSourceVersion: String(task.version),
    })
    selectedLineageRootIds.forEach((lineageRootId) =>
      params.append("reworkLineage", lineageRootId)
    )
    onOpenChange(false)
    navigate(`/repairs?${params.toString()}`, { state })
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
          В черновик автоматически попадут отмеченные для переделки работы.
          Материалы при необходимости можно добавить из существующей цепочки.
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
