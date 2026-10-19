import { useId, useState } from "react"

import { Field, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  SingleDayPicker,
  type SingleDayPickerProps,
} from "@/components/ui/single-day-picker"
import { cn } from "@/lib/utils"

/** Selects a local date and time; the owning form applies its warehouse timezone. */
export function DateTimePicker({
  id: idProp,
  name,
  label,
  value,
  min,
  max,
  className,
  hideLabel = false,
  disabled,
  readOnly,
  required,
  allowClear = false,
  onValueChange,
  "aria-invalid": invalid,
  "aria-describedby": describedBy,
}: Omit<SingleDayPickerProps, "triggerLabel" | "triggerClassName">) {
  const generatedId = useId()
  const id = idProp ?? generatedId
  const [draft, setDraft] = useState(() => ({
    source: value,
    date: value.slice(0, 10),
    time: value.slice(11, 16),
  }))
  if (value !== draft.source) {
    setDraft({
      source: value,
      date: value.slice(0, 10),
      time: value.slice(11, 16),
    })
  }

  const change = (date: string, time: string) => {
    const next = date && time ? `${date}T${time}` : ""
    setDraft({ source: next, date, time })
    onValueChange?.(next)
  }

  return (
    <Field className={cn("min-w-0", className)}>
      {!hideLabel ? <FieldLabel htmlFor={id}>{label}</FieldLabel> : null}
      <div className="flex min-w-0 items-start gap-2">
        <SingleDayPicker
          id={id}
          label={label}
          value={draft.date}
          min={min?.slice(0, 10)}
          max={max?.slice(0, 10)}
          className="flex-1"
          hideLabel
          disabled={disabled}
          readOnly={readOnly}
          required={required}
          allowClear={allowClear}
          aria-invalid={invalid}
          aria-describedby={describedBy}
          onValueChange={(date) => change(date, draft.time || "00:00")}
        />
        <Input
          type="time"
          className="w-28 shrink-0"
          aria-label={`Время: ${label}`}
          aria-invalid={invalid}
          aria-describedby={describedBy}
          value={draft.time}
          min={min?.slice(0, 10) === draft.date ? min.slice(11, 16) : undefined}
          max={max?.slice(0, 10) === draft.date ? max.slice(11, 16) : undefined}
          disabled={disabled}
          readOnly={readOnly}
          required={required}
          onChange={(event) => change(draft.date, event.target.value)}
        />
      </div>
      {name ? (
        <input type="hidden" name={name} value={value} disabled={disabled} />
      ) : null}
    </Field>
  )
}
