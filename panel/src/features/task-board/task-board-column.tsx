import { memo } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CheckmarkCircle01Icon,
  PlayIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import { cn } from "@/lib/utils"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import {
  TaskBoardCard,
  type TaskBoardRepairComplexity,
} from "@/features/task-board/task-board-card"

function queueKindLabel(queue: TaskBoardQueueDto) {
  if (queue.kind === "MOVEMENT") return "Перемещение"
  if (queue.kind === "FURNITURE_MOVEMENT") return "Перемещение мебели"
  if (queue.kind === "HOLDING") return "Ожидание"
  return "Ремонт"
}

export const TaskBoardColumn = memo(function TaskBoardColumn({
  queue,
  visibleEntries,
  now,
  mobile,
  canEdit,
  collapsed,
  actionPending,
  queueActionsDisabled,
  onToggleCollapsed,
  onDetails,
  onEdit,
  onTake,
  onPause,
  onResume,
  onPin,
  isEntryCollapsed,
  onToggleEntryCollapsed,
  onComplete,
  palette,
  repairComplexitiesByRepairId,
}: {
  queue: TaskBoardQueueDto
  visibleEntries: TaskBoardEntryDto[]
  now: number
  mobile: boolean
  canEdit: boolean
  collapsed: boolean
  actionPending: boolean
  queueActionsDisabled: boolean
  onToggleCollapsed: (queueKey: string) => void
  onDetails: (entry: TaskBoardEntryDto) => void
  onEdit: (entry: TaskBoardEntryDto) => void
  onTake: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  isEntryCollapsed: (entryId: string) => boolean
  onToggleEntryCollapsed: (entryId: string) => void
  onComplete: (entry: TaskBoardEntryDto) => void
  palette: KpiPalette | null
  repairComplexitiesByRepairId: ReadonlyMap<string, TaskBoardRepairComplexity>
}) {
  const nextWaiting = visibleEntries.find(
    (entry) => entry.entryType === "REAL" && entry.status === "WAITING"
  )
  const inProgress = visibleEntries.find(
    (entry) => entry.status === "IN_PROGRESS"
  )

  if (collapsed) {
    return (
      <section
        className={cn(
          "flex shrink-0 items-center gap-3 rounded-lg bg-card ring-1 ring-foreground/10",
          "h-auto w-full flex-row px-3 py-2"
        )}
        aria-label={`${queue.label}, свёрнута`}
      >
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          aria-label={`Развернуть очередь ${queue.label}`}
          onClick={() => onToggleCollapsed(queue.key)}
        >
          <HugeiconsIcon icon={ArrowRight01Icon} />
        </Button>
        <Badge variant="secondary">{visibleEntries.length}</Badge>
        <span>{queue.label}</span>
      </section>
    )
  }

  return (
    <section
      className="flex w-full min-w-0 shrink-0 flex-col overflow-hidden rounded-lg bg-muted/35 ring-1 ring-foreground/10"
      aria-label={`Очередь ${queue.label}`}
    >
      <header className="sticky top-0 flex flex-col gap-3 bg-card p-3 shadow-xs">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h2 className="truncate font-heading text-sm font-medium">
              {queue.label}
            </h2>
            <div className="mt-1 flex flex-wrap gap-1">
              <Badge variant="secondary">{visibleEntries.length}</Badge>
              <Badge variant="outline">{queueKindLabel(queue)}</Badge>
              <Badge variant="outline">
                До {queue.availableTaskLimit} ожидающих
              </Badge>
            </div>
          </div>
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            aria-label={`Свернуть очередь ${queue.label}`}
            onClick={() => onToggleCollapsed(queue.key)}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} />
          </Button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!nextWaiting || actionPending || queueActionsDisabled}
            onClick={() => nextWaiting && onTake(nextWaiting)}
          >
            <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
            Начать
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!inProgress || actionPending || queueActionsDisabled}
            onClick={() => inProgress && onComplete(inProgress)}
          >
            <HugeiconsIcon
              icon={CheckmarkCircle01Icon}
              data-icon="inline-start"
            />
            Завершить
          </Button>
        </div>
      </header>

      <div className="flex min-w-0 gap-3 overflow-x-auto overscroll-x-contain p-3 pb-4">
        {visibleEntries.map((entry) => (
          <div key={entry.id} className="w-72 shrink-0 sm:w-80">
            <TaskBoardCard
              entry={entry}
              now={now}
              mobile={mobile}
              canEdit={canEdit}
              collapsed={isEntryCollapsed(entry.id)}
              actionPending={actionPending}
              onDetails={onDetails}
              onEdit={onEdit}
              onTake={onTake}
              onPause={onPause}
              onResume={onResume}
              onPin={onPin}
              onToggleCollapsed={onToggleEntryCollapsed}
              palette={palette}
              repairComplexity={
                entry.source?.type === "MAINTENANCE_REPAIR"
                  ? repairComplexitiesByRepairId.get(entry.source.sourceId)
                  : null
              }
            />
          </div>
        ))}
      </div>
    </section>
  )
})
