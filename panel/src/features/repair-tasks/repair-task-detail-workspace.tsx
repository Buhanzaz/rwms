import { Link } from "react-router-dom"
import { HugeiconsIcon } from "@hugeicons/react"
import { PencilEdit01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { RepairWorkDetailWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationSnapshot } from "@/features/repair-estimates/repair-work-information-snapshot"
import { ForceCapitalRepairField } from "@/features/repair-estimates/force-capital-repair-field"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairSubtasksEditor } from "@/features/repair-tasks/repair-subtasks-editor"
import { RepairTaskStatusBadge } from "@/features/repair-tasks/repair-task-status-badge"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { maintenanceRepairMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

export function RepairTaskDetailWorkspace({
  accessToken,
  task,
  readOnly = false,
  onEdit,
}: {
  accessToken: string | null
  task: RepairTaskDto
  readOnly?: boolean
  onEdit?: () => void
}) {
  const estimateLink = task.sourceEstimateId ? (
    <Button variant="outline" size="sm" asChild>
      <Link
        to={`/estimates?estimateId=${encodeURIComponent(task.sourceEstimateId)}`}
        state={workspaceEntryNavigationOptions.state}
      >
        Перейти к смете
      </Link>
    </Button>
  ) : undefined
  const informationAction =
    estimateLink || onEdit ? (
      <div className="flex flex-wrap items-center gap-2">
        {onEdit ? (
          <Button type="button" size="sm" onClick={onEdit}>
            <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
            Редактировать план
          </Button>
        ) : null}
        {estimateLink}
      </div>
    ) : undefined

  return (
    <RepairWorkDetailWorkspaceLayout
      ariaLabel={`Ремонт бытовки ${task.cabinNumber}`}
      photos={
        <ServiceOwnerPhotos
          accessToken={accessToken}
          owner={maintenanceRepairMediaOwner(task.id, task.warehouseId)}
          readOnly
          title="Фотографии ремонта"
          coverMediaId={task.coverMediaId ?? null}
        />
      }
      information={
        <div className="flex flex-col gap-3">
          <RepairWorkInformationSnapshot
            cabinNumber={task.cabinNumber}
            contextLabel="Источник"
            contextValue={task.sourceParty ?? ""}
            dispatchDate={task.dispatchDate}
            authorName="Автор недоступен"
            authorLabel="Автор"
            showComment={false}
            status={
              <RepairTaskStatusBadge
                status={task.status}
                awaitingMovement={task.awaitingMovement}
              />
            }
            comment=""
          />
          <div className="flex items-center justify-between gap-3 rounded-lg border bg-card p-3">
            <span className="text-sm text-muted-foreground">
              Приоритет ремонта
            </span>
            <Badge
              variant={(task.priority ?? 3) <= 2 ? "default" : "secondary"}
            >
              {task.priority ?? 3}
            </Badge>
          </div>
          <ForceCapitalRepairField
            id={`repair-task-force-capital-detail-${task.id}`}
            checked={task.forceCapitalRepair === true}
            disabled
            inherited={task.kind === "REWORK"}
          />
        </div>
      }
      informationDescription="Сведения о ремонтном задании."
      informationAction={informationAction}
      lowerTitle="Подзадания"
      lowerDescription="Этапы ремонта в сохранённом порядке."
      lowerContent={
        task.subtasks.length > 0 ? (
          <RepairSubtasksEditor
            key={task.version}
            task={task}
            readOnly={readOnly}
          />
        ) : (
          <p className="text-muted-foreground">Подзаданий нет.</p>
        )
      }
    />
  )
}
