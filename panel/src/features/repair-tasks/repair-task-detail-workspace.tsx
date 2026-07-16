import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { RepairEstimatePhotos } from "@/features/repair-estimates/repair-estimate-photos"
import { RepairWorkDetailWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import { RepairWorkInformationSnapshot } from "@/features/repair-estimates/repair-work-information-snapshot"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { RepairSubtasksEditor } from "@/features/repair-tasks/repair-subtasks-editor"
import { RepairTaskStatusBadge } from "@/features/repair-tasks/repair-task-status-badge"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

export function RepairTaskDetailWorkspace({ task }: { task: RepairTaskDto }) {
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
        <RepairEstimatePhotos
          viewerContext="WORK"
          media={task.media}
          pendingUploads={[]}
          readOnly
          onMediaChange={() => undefined}
          onPendingUploadsChange={() => undefined}
        />
      }
      information={
        <RepairWorkInformationSnapshot
          cabinNumber={task.cabinNumber}
          contextLabel="Причина"
          contextValue={task.reason}
          dispatchDate={task.dispatchDate}
          authorName={task.authorName}
          status={<RepairTaskStatusBadge status={task.status} />}
          comment={task.comment}
        />
      }
      informationDescription="Сведения о ремонтном задании."
      informationAction={estimateLink}
      lowerTitle="Подзадания"
      lowerDescription="Этапы ремонта в сохранённом порядке."
      lowerContent={
        task.subtasks.length > 0 ? (
          <RepairSubtasksEditor key={task.version} task={task} />
        ) : (
          <p className="text-muted-foreground">Подзаданий нет.</p>
        )
      }
    />
  )
}
