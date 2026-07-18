import { memo } from "react"
import { useDroppable } from "@dnd-kit/core"
import { SortableContext, verticalListSortingStrategy } from "@dnd-kit/sortable"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CheckmarkCircle01Icon,
  PlayIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardCard } from "@/features/task-board/task-board-card"

function queueKindLabel(queue: TaskBoardQueueDto) {
  if (queue.kind === "MOVEMENT") return "Перемещение"
  if (queue.kind === "HOLDING") return "Ожидание"
  if (queue.kind === "UNASSIGNED") return "Без маршрута"
  return "Ремонт"
}

// Header, list padding and gaps, plus three 20rem mobile cards.
const mobileQueueThreeCardHeightClassName = "max-h-[70.125rem]"

export const TaskBoardColumn = memo(function TaskBoardColumn({
  queue,
  visibleEntries,
  now,
  mobile,
  collapsed,
  dragDisabled,
  actionPending,
  queueActionsDisabled,
  onToggleCollapsed,
  onDetails,
  onTake,
  onPause,
  onResume,
  onComplete,
}: {
  queue: TaskBoardQueueDto
  visibleEntries: TaskBoardEntryDto[]
  now: number
  mobile: boolean
  collapsed: boolean
  dragDisabled: boolean
  actionPending: boolean
  queueActionsDisabled: boolean
  onToggleCollapsed: (queueKey: string) => void
  onDetails: (entry: TaskBoardEntryDto) => void
  onTake: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onComplete: (entry: TaskBoardEntryDto) => void
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `queue:${queue.key}`,
    data: { type: "queue", queueKey: queue.key },
  })
  const nextWaiting = queue.entries.find(
    (entry) => entry.entryType === "REAL" && entry.status === "WAITING"
  )
  const inProgress = queue.entries.find(
    (entry) => entry.status === "IN_PROGRESS"
  )

  if (collapsed) {
    return (
      <section
        className={cn(
          "flex shrink-0 items-center gap-3 rounded-lg bg-card ring-1 ring-foreground/10",
          mobile
            ? "h-auto w-full flex-row px-3 py-2"
            : "h-full min-h-0 w-16 flex-col py-3"
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
        <span className={cn(!mobile && "[writing-mode:vertical-rl]")}>
          {queue.label}
        </span>
      </section>
    )
  }

  return (
    <section
      ref={setNodeRef}
      className={cn(
        "flex min-h-0 shrink-0 flex-col overflow-hidden rounded-lg bg-muted/35 ring-1 ring-foreground/10",
        mobile ? "h-auto w-full" : "h-full w-80",
        mobile && mobileQueueThreeCardHeightClassName,
        isOver && "ring-2 ring-primary/40"
      )}
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

      <SortableContext
        items={visibleEntries.map((entry) => entry.id)}
        strategy={verticalListSortingStrategy}
      >
        <div
          className={cn(
            "flex min-h-0 flex-1 touch-pan-y flex-col gap-3 overflow-y-auto p-3",
            mobile ? "overscroll-y-auto" : "overscroll-contain"
          )}
        >
          {visibleEntries.map((entry) => (
            <TaskBoardCard
              key={entry.id}
              entry={entry}
              now={now}
              mobile={mobile}
              dragDisabled={dragDisabled || entry.status === "IN_PROGRESS"}
              actionPending={actionPending}
              onDetails={onDetails}
              onPause={onPause}
              onResume={onResume}
            />
          ))}
        </div>
      </SortableContext>
    </section>
  )
})
