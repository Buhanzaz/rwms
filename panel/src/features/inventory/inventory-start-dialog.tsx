import { HugeiconsIcon } from "@hugeicons/react"
import { ClipboardCheckIcon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"

type InventoryStartDialogProps = {
  open: boolean
  warehouseName: string
  authorName: string
  businessDate: string
  pending: boolean
  error: string | null
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

export function InventoryStartDialog({
  open,
  warehouseName,
  authorName,
  businessDate,
  pending,
  error,
  onOpenChange,
  onConfirm,
}: InventoryStartDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Начать инвентаризацию</DialogTitle>
          <DialogDescription>
            При старте будет зафиксирован текущий состав склада. На одном складе
            может быть только одна активная сессия.
          </DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field aria-labelledby="inventory-start-warehouse-label">
            <FieldLabel id="inventory-start-warehouse-label">Склад</FieldLabel>
            <p
              data-testid="inventory-start-warehouse-value"
              className="pointer-events-none flex h-9 w-full min-w-0 items-center rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs select-none md:text-sm dark:bg-input/30"
            >
              {warehouseName}
            </p>
          </Field>
          <Field aria-labelledby="inventory-start-author-label">
            <FieldLabel id="inventory-start-author-label">Автор</FieldLabel>
            <p
              data-testid="inventory-start-author-value"
              className="pointer-events-none flex h-9 w-full min-w-0 items-center rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs select-none md:text-sm dark:bg-input/30"
            >
              {authorName}
            </p>
          </Field>
          <Field aria-labelledby="inventory-start-date-label">
            <FieldLabel id="inventory-start-date-label">Дата</FieldLabel>
            <p
              data-testid="inventory-start-date-value"
              className="pointer-events-none flex h-9 w-full min-w-0 items-center rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs select-none md:text-sm dark:bg-input/30"
            >
              {businessDate}
            </p>
          </Field>
        </FieldGroup>

        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button type="button" disabled={pending} onClick={onConfirm}>
            <HugeiconsIcon icon={ClipboardCheckIcon} data-icon="inline-start" />
            {pending ? "Создаём..." : "Начать"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
