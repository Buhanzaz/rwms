import { Input } from "@/components/ui/input"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"

import type { CabinDispositionContent } from "./cabin-contents-disposition"
import type { CabinContentsDispositionMode } from "./property-dispositions-api"

export function CabinContentsDispositionFields({
  contents,
  mode,
  quantities,
  disabled = false,
  error = null,
  onModeChange,
  onQuantityChange,
}: {
  contents: readonly CabinDispositionContent[]
  mode: CabinContentsDispositionMode
  quantities: Readonly<Record<string, number>>
  disabled?: boolean
  error?: string | null
  onModeChange: (mode: CabinContentsDispositionMode) => void
  onQuantityChange: (equipmentId: string, quantity: number) => void
}) {
  if (contents.length === 0) return null

  return (
    <FieldSet>
      <FieldLegend>Наполнение бытовки</FieldLegend>
      <FieldDescription>
        Выберите, что физически вернуть на склад. Остаток, не включённый в
        перемещение, будет списан вместе с бытовкой после решения
        администратора.
      </FieldDescription>
      <FieldGroup>
        <Field>
          <ToggleGroup
            type="single"
            variant="outline"
            value={mode}
            disabled={disabled}
            aria-label="Действие с наполнением бытовки"
            onValueChange={(value) => {
              if (
                value === "MOVE_SELECTED_TO_STOCK" ||
                value === "DISPOSE_WITH_CABIN"
              ) {
                onModeChange(value)
              }
            }}
          >
            <ToggleGroupItem value="MOVE_SELECTED_TO_STOCK">
              Переместить на склад
            </ToggleGroupItem>
            <ToggleGroupItem value="DISPOSE_WITH_CABIN">
              Списать с бытовкой
            </ToggleGroupItem>
          </ToggleGroup>
        </Field>

        {mode === "MOVE_SELECTED_TO_STOCK"
          ? contents.map((content) => {
              const value = quantities[content.equipmentId] ?? 0
              const invalid =
                !Number.isSafeInteger(value) ||
                value < 0 ||
                value > content.currentQuantity
              return (
                <Field key={content.equipmentId} data-invalid={invalid}>
                  <FieldLabel htmlFor={`cabin-content-${content.equipmentId}`}>
                    {content.equipmentName} — в бытовке{" "}
                    {content.currentQuantity} {content.equipmentFormat}
                  </FieldLabel>
                  <Input
                    id={`cabin-content-${content.equipmentId}`}
                    type="number"
                    min={0}
                    max={content.currentQuantity}
                    step={1}
                    value={value}
                    disabled={disabled}
                    aria-invalid={invalid}
                    onChange={(event) =>
                      onQuantityChange(
                        content.equipmentId,
                        Number(event.target.value)
                      )
                    }
                  />
                  <FieldDescription>
                    В задание перемещения: от 0 до {content.currentQuantity}{" "}
                    {content.equipmentFormat}.
                  </FieldDescription>
                  {invalid ? (
                    <FieldError>
                      Укажите целое количество не больше текущего наполнения.
                    </FieldError>
                  ) : null}
                </Field>
              )
            })
          : null}
        {error ? <FieldError>{error}</FieldError> : null}
      </FieldGroup>
    </FieldSet>
  )
}
