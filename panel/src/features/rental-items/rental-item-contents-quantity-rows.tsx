import { Add01Icon, MinusSignIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"

export type RentalItemContentsQuantityRow = {
  equipmentId: string
  code: string
  name: string
  availableQuantity: number
  quantity: number
  selected: boolean
}

export function RentalItemContentsQuantityRows({
  idPrefix,
  legend,
  rows,
  onToggle,
  onChangeQuantity,
}: {
  idPrefix: string
  legend: string
  rows: RentalItemContentsQuantityRow[]
  onToggle: (equipmentId: string, selected: boolean) => void
  onChangeQuantity: (equipmentId: string, delta: number) => void
}) {
  if (rows.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">Доступных позиций нет.</p>
    )
  }

  return (
    <FieldSet className="gap-0 overflow-hidden rounded-md border">
      <FieldLegend className="sr-only">{legend}</FieldLegend>
      <FieldGroup data-slot="checkbox-group" className="gap-0">
        {rows.map((row) => {
          const checkboxId = `${idPrefix}-${row.equipmentId}`
          return (
            <Field
              key={row.equipmentId}
              orientation="horizontal"
              className="border-b px-3 py-2 text-sm last:border-b-0"
            >
              <Checkbox
                id={checkboxId}
                aria-label={`Выбрать ${row.name}`}
                checked={row.selected}
                onCheckedChange={(value) =>
                  onToggle(row.equipmentId, value === true)
                }
              />
              <FieldLabel htmlFor={checkboxId} className="min-w-0 flex-1">
                <span className="min-w-0">
                  <span className="block truncate font-medium">{row.name}</span>
                  <span className="block text-xs text-muted-foreground">
                    {row.code} · Доступно: {row.availableQuantity} шт.
                  </span>
                </span>
              </FieldLabel>
              <div className="flex items-center gap-2 whitespace-nowrap">
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  disabled={row.quantity <= 0}
                  aria-label={`Уменьшить ${row.name}`}
                  onClick={() => onChangeQuantity(row.equipmentId, -1)}
                >
                  <HugeiconsIcon icon={MinusSignIcon} />
                </Button>
                <div className="min-w-12 text-center">
                  <span className="font-semibold">{row.quantity}</span> шт.
                </div>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  disabled={row.quantity >= row.availableQuantity}
                  aria-label={`Увеличить ${row.name}`}
                  onClick={() => onChangeQuantity(row.equipmentId, 1)}
                >
                  <HugeiconsIcon icon={Add01Icon} />
                </Button>
              </div>
            </Field>
          )
        })}
      </FieldGroup>
    </FieldSet>
  )
}
