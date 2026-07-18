import {
  Field,
  FieldContent,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { RepairEstimateRentalItemPicker } from "@/features/repair-estimates/repair-estimate-rental-item-picker"

type RepairWorkInformationFieldsProps = {
  warehouseId: string
  rentalItemId: string
  rentalItemNumber?: string
  contextLabel: "От кого" | "Причина" | "Источник"
  contextValue: string
  dispatchDate: string | null
  comment: string
  disabled: boolean
  rentalItemDisabled?: boolean
  readOnly?: boolean
  rentalItemInvalid?: boolean
  showComment?: boolean
  onRentalItemChange: (rentalItemId: string) => void
  onContextChange: (value: string) => void
  onDispatchDateChange: (value: string | null) => void
  onCommentChange: (value: string) => void
}

const horizontalFieldClassName =
  "items-center gap-3 [&>[data-slot=field-label]]:w-32 [&>[data-slot=field-label]]:shrink-0 [&>[data-slot=field-label]]:flex-none"

export function RepairWorkInformationFields({
  warehouseId,
  rentalItemId,
  rentalItemNumber,
  contextLabel,
  contextValue,
  dispatchDate,
  comment,
  disabled,
  rentalItemDisabled = false,
  readOnly = false,
  rentalItemInvalid = false,
  showComment = true,
  onRentalItemChange,
  onContextChange,
  onDispatchDateChange,
  onCommentChange,
}: RepairWorkInformationFieldsProps) {
  const contextId =
    contextLabel === "Причина"
      ? "repair-task-reason"
      : contextLabel === "Источник"
        ? "repair-source-party"
        : "estimate-source-party"

  return (
    <FieldGroup className="gap-3">
      <Field
        orientation="horizontal"
        className={horizontalFieldClassName}
        data-invalid={rentalItemInvalid}
        data-disabled={disabled || rentalItemDisabled}
      >
        <FieldLabel htmlFor="repair-work-rental-item">Бытовка</FieldLabel>
        <FieldContent className="min-w-0">
          {readOnly ? (
            <p id="repair-work-rental-item" className="py-2 break-words">
              {rentalItemNumber || "—"}
            </p>
          ) : (
            <RepairEstimateRentalItemPicker
              id="repair-work-rental-item"
              warehouseId={warehouseId}
              value={rentalItemId}
              invalid={rentalItemInvalid}
              disabled={disabled || rentalItemDisabled}
              onValueChange={onRentalItemChange}
            />
          )}
          {rentalItemInvalid ? <FieldError>Выберите бытовку</FieldError> : null}
        </FieldContent>
      </Field>

      <Field
        orientation="horizontal"
        className={horizontalFieldClassName}
        data-disabled={disabled}
      >
        <FieldLabel htmlFor={contextId}>{contextLabel}</FieldLabel>
        <FieldContent className="min-w-0">
          <Input
            id={contextId}
            aria-label={contextLabel}
            disabled={disabled}
            value={contextValue}
            onChange={(event) => onContextChange(event.target.value)}
          />
        </FieldContent>
      </Field>

      <Field
        orientation="horizontal"
        className={horizontalFieldClassName}
        data-disabled={disabled}
      >
        <FieldLabel htmlFor="repair-work-dispatch-date">Прибытие</FieldLabel>
        <FieldContent className="min-w-0">
          <Input
            id="repair-work-dispatch-date"
            aria-label="Дата прибытия"
            type="date"
            disabled={disabled}
            value={dispatchDate ?? ""}
            onChange={(event) =>
              onDispatchDateChange(event.target.value || null)
            }
          />
        </FieldContent>
      </Field>

      {showComment ? (
        <Field data-disabled={disabled}>
          <FieldLabel htmlFor="repair-work-comment">
            Общий комментарий
          </FieldLabel>
          <Textarea
            id="repair-work-comment"
            aria-label="Общий комментарий"
            className="field-sizing-fixed h-32 min-h-32 resize-none"
            disabled={disabled}
            value={comment}
            onChange={(event) => onCommentChange(event.target.value)}
          />
        </Field>
      ) : null}
    </FieldGroup>
  )
}
