import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { RepairWorkDetailWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationSnapshot } from "@/features/repair-estimates/repair-work-information-snapshot"
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
}: {
  accessToken: string | null
  task: RepairTaskDto
  readOnly?: boolean
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

  return (
    <RepairWorkDetailWorkspaceLayout
      ariaLabel={`Ремонт бытовки ${task.cabinNumber}`}
      photos={
        <ServiceOwnerPhotos
          accessToken={accessToken}
          owner={maintenanceRepairMediaOwner(task.id, task.warehouseId)}
          readOnly
          title="Фотографии ремонта"
        />
      }
      information={
        <RepairWorkInformationSnapshot
          cabinNumber={task.cabinNumber}
          contextLabel="Источник"
          contextValue={task.sourceParty ?? ""}
          dispatchDate={task.dispatchDate}
          authorName={task.actorId}
          authorLabel="Идентификатор автора"
          showComment={false}
          status={<RepairTaskStatusBadge status={task.status} />}
          comment=""
        />
      }
      informationDescription="Сведения о ремонтном задании."
      informationAction={estimateLink}
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
