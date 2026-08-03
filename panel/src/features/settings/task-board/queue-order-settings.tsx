import { useState } from "react"
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import { GripVerticalIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import { queueTypeLabels } from "@/features/settings/task-board/model/task-board-settings"
import { cn } from "@/lib/utils"

function SortableQueue({ queue }: { queue: WorkQueueDto }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: queue.id })

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={cn(
        "flex min-h-[49px] items-center gap-3 rounded-lg border bg-card px-3 py-2",
        isDragging && "opacity-50 shadow-lg"
      )}
    >
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={`Переместить очередь ${queue.name}`}
        {...attributes}
        {...listeners}
      >
        <HugeiconsIcon icon={GripVerticalIcon} data-icon="inline-start" />
      </Button>
      <span className="min-w-0 flex-1 truncate font-medium">{queue.name}</span>
    </div>
  )
}

/**
 * Reorders only the selected warehouse's GENERAL connections. The driver
 * queue is configured in logistics and intentionally never reaches this UI.
 */
export function QueueOrderSettings({
  queues,
  pending,
  onSave,
}: {
  queues: WorkQueueDto[]
  pending: boolean
  onSave: (queues: WorkQueueDto[]) => Promise<void>
}) {
  const [regular, setRegular] = useState(() =>
    queues.filter((queue) => queue.type !== "HOLDING")
  )
  const holding = queues.filter((queue) => queue.type === "HOLDING")
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event
    if (!over || active.id === over.id) return

    setRegular((current) => {
      const oldIndex = current.findIndex((queue) => queue.id === active.id)
      const newIndex = current.findIndex((queue) => queue.id === over.id)
      return oldIndex < 0 || newIndex < 0
        ? current
        : arrayMove(current, oldIndex, newIndex)
    })
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto">
      <div className="flex flex-col gap-1">
        <h2 className="font-semibold">Порядок очередей</h2>
        <p className="text-sm text-muted-foreground">
          Перетащите очереди выбранного склада. Удержание всегда остаётся
          последним.
        </p>
      </div>
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <SortableContext
          items={regular.map((queue) => queue.id)}
          strategy={verticalListSortingStrategy}
        >
          <div className="flex flex-col gap-2">
            {regular.map((queue) => (
              <SortableQueue key={queue.id} queue={queue} />
            ))}
          </div>
        </SortableContext>
      </DndContext>
      {holding.length ? (
        <div className="flex flex-col gap-2">
          <p className="text-xs font-medium text-muted-foreground">
            Последние очереди удержания
          </p>
          {holding.map((queue) => (
            <div
              key={queue.id}
              className="flex min-h-[49px] items-center gap-3 rounded-lg border bg-muted px-3 py-2"
            >
              <span className="min-w-0 flex-1 truncate font-medium">
                {queue.name}
              </span>
              <span className="text-xs text-muted-foreground">
                {queueTypeLabels[queue.type]}
              </span>
            </div>
          ))}
        </div>
      ) : null}
      <div className="flex justify-end">
        <Button
          type="button"
          disabled={pending}
          onClick={() => void onSave(regular)}
        >
          {pending ? "Сохраняем…" : "Сохранить порядок"}
        </Button>
      </div>
    </div>
  )
}
