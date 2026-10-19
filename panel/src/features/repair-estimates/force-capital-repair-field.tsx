import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"

type ForceCapitalRepairFieldProps = {
  id: string
  checked: boolean
  disabled?: boolean
  inherited?: boolean
  onCheckedChange?: (checked: boolean) => void
}

/**
 * Renders the explicit manager choice independently from catalog-derived
 * repair complexity without attempting to calculate complexity in the browser.
 */
export function ForceCapitalRepairField({
  id,
  checked,
  disabled = false,
  inherited = false,
  onCheckedChange,
}: ForceCapitalRepairFieldProps) {
  return (
    <Field orientation="horizontal" data-disabled={disabled}>
      <Checkbox
        id={id}
        aria-label="Направить на капитальный ремонт"
        checked={checked}
        disabled={disabled}
        onCheckedChange={(value) => onCheckedChange?.(value === true)}
      />
      <div className="flex flex-col gap-1">
        <FieldLabel htmlFor={id}>Направить на капитальный ремонт</FieldLabel>
        <FieldDescription>
          {inherited
            ? "Выбор унаследован от основного ремонта и недоступен для изменения в доработке."
            : "Явный выбор менеджера. Итоговую сложность и маршрут определяет сервис ремонта."}
        </FieldDescription>
      </div>
    </Field>
  )
}
