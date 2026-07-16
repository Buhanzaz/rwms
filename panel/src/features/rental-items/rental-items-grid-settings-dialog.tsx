import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react"
import { RotateLeft01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"

type RentalItemsGridSettingsDialogProps = {
  open: boolean
  value: number
  maxSize: number
  defaultValue: number
  onOpenChange: (open: boolean) => void
  onValueChange: (value: number) => void
}

function clampGridSize(value: number, maxSize: number) {
  return Math.max(1, Math.min(maxSize, value))
}

export function RentalItemsGridSettingsDialog({
  open,
  value,
  maxSize,
  defaultValue,
  onOpenChange,
  onValueChange,
}: RentalItemsGridSettingsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <RentalItemsGridSettingsDialogContent
        key={open ? `open-${value}-${maxSize}` : "closed"}
        initialValue={clampGridSize(value, maxSize)}
        maxSize={maxSize}
        defaultValue={defaultValue}
        onOpenChange={onOpenChange}
        onValueChange={onValueChange}
      />
    </Dialog>
  )
}

function RentalItemsGridSettingsDialogContent({
  initialValue,
  maxSize,
  defaultValue,
  onOpenChange,
  onValueChange,
}: {
  initialValue: number
  maxSize: number
  defaultValue: number
  onOpenChange: (open: boolean) => void
  onValueChange: (value: number) => void
}) {
  const pickerRef = useRef<HTMLDivElement | null>(null)
  const [draftValue, setDraftValue] = useState(initialValue)
  const [isDragging, setIsDragging] = useState(false)

  function getSizeFromPointer(event: PointerEvent<HTMLDivElement>) {
    const picker = pickerRef.current

    if (!picker) {
      return draftValue
    }

    const rect = picker.getBoundingClientRect()
    const x = Math.max(0, Math.min(rect.width - 1, event.clientX - rect.left))
    const y = Math.max(0, Math.min(rect.height - 1, event.clientY - rect.top))
    const column = Math.floor((x / rect.width) * maxSize)
    const row = Math.floor((y / rect.height) * maxSize)

    return clampGridSize(Math.max(column, row) + 1, maxSize)
  }

  function updateDraftFromPointer(event: PointerEvent<HTMLDivElement>) {
    setDraftValue(getSizeFromPointer(event))
  }

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId)
    setIsDragging(true)
    updateDraftFromPointer(event)
    event.preventDefault()
  }

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    if (!isDragging) {
      return
    }

    updateDraftFromPointer(event)
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    if (isDragging) {
      updateDraftFromPointer(event)
    }

    setIsDragging(false)

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    let nextValue: number

    if (event.key === "ArrowRight" || event.key === "ArrowUp") {
      nextValue = clampGridSize(draftValue + 1, maxSize)
    } else if (event.key === "ArrowLeft" || event.key === "ArrowDown") {
      nextValue = clampGridSize(draftValue - 1, maxSize)
    } else if (event.key === "Home") {
      nextValue = 1
    } else if (event.key === "End") {
      nextValue = maxSize
    } else {
      return
    }

    event.preventDefault()
    setDraftValue(nextValue)
  }

  function applyDraft() {
    onValueChange(draftValue)
    onOpenChange(false)
  }

  return (
    <DialogContent className="max-w-[calc(100%-2rem)] sm:max-w-md">
      <DialogHeader>
        <DialogTitle>Формат сетки</DialogTitle>
      </DialogHeader>

      <div className="flex flex-col gap-4">
        <div className="flex items-center justify-between gap-3 rounded-md border bg-muted/40 px-3 py-2">
          <span className="text-sm text-muted-foreground">Выбрано</span>
          <span className="font-heading text-xl font-semibold">
            {draftValue} x {draftValue}
          </span>
        </div>

        <div
          ref={pickerRef}
          data-grid-picker
          role="slider"
          tabIndex={0}
          aria-label="Формат сетки"
          aria-valuemin={1}
          aria-valuemax={maxSize}
          aria-valuenow={draftValue}
          aria-valuetext={`${draftValue} x ${draftValue}`}
          className="grid cursor-pointer touch-none gap-1 rounded-lg border bg-card p-2 select-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          style={{
            gridTemplateColumns: `repeat(${maxSize}, minmax(0, 1fr))`,
          }}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
          onKeyDown={handleKeyDown}
        >
          {Array.from({ length: maxSize * maxSize }, (_, index) => {
            const row = Math.floor(index / maxSize)
            const column = index % maxSize
            const selected = row < draftValue && column < draftValue

            return (
              <span
                key={`${row}-${column}`}
                aria-hidden="true"
                className={cn(
                  "aspect-square rounded-[3px] border transition-colors",
                  selected
                    ? "border-primary bg-primary"
                    : "border-border bg-background hover:bg-muted"
                )}
              />
            )
          })}
        </div>

        <div className="flex items-center justify-between gap-2">
          <Button
            variant="outline"
            onClick={() => setDraftValue(clampGridSize(defaultValue, maxSize))}
          >
            <HugeiconsIcon icon={RotateLeft01Icon} data-icon="inline-start" />
            Сбросить
          </Button>

          <Button onClick={applyDraft}>Готово</Button>
        </div>
      </div>
    </DialogContent>
  )
}
