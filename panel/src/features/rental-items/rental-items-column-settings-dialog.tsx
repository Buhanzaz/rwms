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
import { GripVertical, RotateCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { RentalItemsColumnConfig } from "@/features/rental-items/model/rental-item"

type RentalItemsColumnSettingsDialogProps = {
  open: boolean
  columns: RentalItemsColumnConfig[]
  onOpenChange: (open: boolean) => void
  onColumnsChange: (columns: RentalItemsColumnConfig[]) => void
  onReset: () => void
}

function SortableColumnRow({
  column,
  onVisibilityChange,
}: {
  column: RentalItemsColumnConfig
  onVisibilityChange: (visible: boolean) => void
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: column.id,
  })

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
      }}
      className={[
        "flex items-center gap-3 rounded-md border bg-card px-3 py-2",
        isDragging ? "relative z-10 shadow-md" : "",
      ].join(" ")}
    >
      <button
        type="button"
        className="cursor-grab text-muted-foreground active:cursor-grabbing"
        {...attributes}
        {...listeners}
      >
        <GripVertical className="size-4" />
      </button>

      <Checkbox
        checked={column.visible}
        onCheckedChange={(value) => onVisibilityChange(value === true)}
      />

      <span className="text-sm">{column.label}</span>
    </div>
  )
}

export function RentalItemsColumnSettingsDialog({
  open,
  columns,
  onOpenChange,
  onColumnsChange,
  onReset,
}: RentalItemsColumnSettingsDialogProps) {
  const lockedColumns = columns.filter((column) => column.locked)
  const movableColumns = columns.filter((column) => !column.locked)

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  )

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event

    if (!over || active.id === over.id) {
      return
    }

    const oldIndex = movableColumns.findIndex(
      (column) => column.id === active.id
    )
    const newIndex = movableColumns.findIndex((column) => column.id === over.id)

    if (oldIndex === -1 || newIndex === -1) {
      return
    }

    const nextMovableColumns = arrayMove(movableColumns, oldIndex, newIndex)

    onColumnsChange([...lockedColumns, ...nextMovableColumns])
  }

  function setColumnVisibility(columnId: string, visible: boolean) {
    onColumnsChange(
      columns.map((column) => {
        if (column.id !== columnId || column.locked) {
          return column
        }

        return {
          ...column,
          visible,
        }
      })
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Настройка столбцов</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="rounded-md border bg-muted/40 p-3">
            {lockedColumns.map((column) => (
              <div
                key={column.id}
                className="flex items-center gap-3 rounded-md bg-card px-3 py-2"
              >
                <GripVertical className="size-4 text-muted-foreground opacity-30" />

                <Checkbox checked disabled />

                <div>
                  <div className="text-sm font-medium">{column.label}</div>
                  <div className="text-xs text-muted-foreground">
                    Столбец закреплён и всегда отображается первым
                  </div>
                </div>
              </div>
            ))}
          </div>

          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext
              items={movableColumns.map((column) => column.id)}
              strategy={verticalListSortingStrategy}
            >
              <div className="max-h-[55vh] space-y-2 overflow-auto pr-1">
                {movableColumns.map((column) => (
                  <SortableColumnRow
                    key={column.id}
                    column={column}
                    onVisibilityChange={(visible) =>
                      setColumnVisibility(column.id, visible)
                    }
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>

          <div className="flex justify-between gap-2">
            <Button variant="outline" onClick={onReset}>
              <RotateCcw className="mr-2 size-4" />
              Сбросить
            </Button>

            <Button onClick={() => onOpenChange(false)}>Готово</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
