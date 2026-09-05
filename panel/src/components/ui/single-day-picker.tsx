import { useId, useState, type AriaAttributes } from "react"
import { Calendar03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { ru } from "date-fns/locale"

import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { cn } from "@/lib/utils"

function parseCalendarDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return undefined
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const parsed = new Date(year, month - 1, day)
  return parsed.getFullYear() === year &&
    parsed.getMonth() === month - 1 &&
    parsed.getDate() === day
    ? parsed
    : undefined
}

function calendarDateValue(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, "0")
  const day = String(value.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function calendarDateLabel(value: Date | undefined) {
  return value
    ? new Intl.DateTimeFormat("ru-RU", {
        day: "numeric",
        month: "long",
        year: "numeric",
      }).format(value)
    : "Выберите дату"
}

function compactCalendarDateLabel(value: Date | undefined) {
  return value
    ? new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
      }).format(value)
    : "Не выбрано"
}

/** Date-only form value, including the owning workflow's calendar limits. */
export interface SingleDayPickerProps {
  id?: string
  name?: string
  label: string
  value: string
  min?: string
  max?: string
  disabled?: boolean
  readOnly?: boolean
  required?: boolean
  allowClear?: boolean
  className?: string
  triggerClassName?: string
  hideLabel?: boolean
  triggerLabel?: string
  "aria-invalid"?: AriaAttributes["aria-invalid"]
  "aria-describedby"?: string
  onValueChange?: (value: string) => void
}

/** Selects a business date without converting it through the browser timezone. */
export function SingleDayPicker({
  id: idProp,
  name,
  label,
  value,
  min,
  max,
  disabled = false,
  readOnly = false,
  required = false,
  allowClear = false,
  className,
  triggerClassName,
  hideLabel = false,
  triggerLabel,
  "aria-invalid": invalid,
  "aria-describedby": describedBy,
  onValueChange,
}: SingleDayPickerProps) {
  const generatedId = useId()
  const id = idProp ?? generatedId
  const selected = parseCalendarDate(value)
  const minimum = min ? parseCalendarDate(min) : undefined
  const maximum = max ? parseCalendarDate(max) : undefined
  const [open, setOpen] = useState(false)
  const [month, setMonth] = useState(() => selected ?? new Date())
  const [monthValue, setMonthValue] = useState(value)

  if (value !== monthValue) {
    setMonthValue(value)
    setMonth(selected ?? new Date())
  }

  const selectedLabel = calendarDateLabel(selected)
  const triggerValue = triggerLabel
    ? compactCalendarDateLabel(selected)
    : selectedLabel

  return (
    <Field className={cn("min-w-0", className)}>
      {!hideLabel ? <FieldLabel htmlFor={id}>{label}</FieldLabel> : null}
      {required || name ? (
        <input
          type="text"
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          name={name}
          value={selected ? value : ""}
          required={required}
          disabled={disabled || readOnly}
          onChange={() => undefined}
          onInvalid={(event) => {
            event.preventDefault()
            setOpen(true)
          }}
        />
      ) : null}
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            disabled={disabled || readOnly}
            className={cn(
              "w-full min-w-0 justify-start rounded-lg text-sm font-normal",
              triggerClassName
            )}
            aria-label={`${label}: ${selectedLabel}`}
            aria-invalid={invalid}
            aria-describedby={describedBy}
          >
            <HugeiconsIcon icon={Calendar03Icon} aria-hidden="true" />
            <span className="inline-flex max-w-full min-w-0 items-baseline gap-1 text-sm leading-5">
              {triggerLabel ? (
                <span className="shrink-0">{triggerLabel}</span>
              ) : null}
              <span className="min-w-0 truncate">{triggerValue}</span>
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent
          align="start"
          side="bottom"
          collisionPadding={8}
          aria-label={`${label}: выбор даты`}
          className="w-auto max-w-[calc(100vw-1rem)] gap-0 overflow-hidden rounded-xl bg-popover/85 p-0 shadow-xl backdrop-blur-xl [@media(prefers-reduced-transparency:reduce)]:bg-popover"
        >
          <Calendar
            className="bg-transparent"
            mode="single"
            locale={ru}
            autoFocus
            disabled={[
              ...(minimum ? [{ before: minimum }] : []),
              ...(maximum ? [{ after: maximum }] : []),
            ]}
            labels={{
              labelNav: () => "Навигация по календарю",
              labelNext: () => "Следующий месяц",
              labelPrevious: () => "Предыдущий месяц",
              labelDayButton: (date, modifiers) => {
                let dayLabel = new Intl.DateTimeFormat("ru-RU", {
                  dateStyle: "full",
                }).format(date)
                if (modifiers.today) dayLabel = `Сегодня, ${dayLabel}`
                if (modifiers.selected) dayLabel = `${dayLabel}, выбрано`
                return dayLabel
              },
            }}
            month={month}
            onMonthChange={setMonth}
            selected={selected}
            aria-label={`${label}: календарь`}
            onSelect={(date) => {
              if (!date) return
              onValueChange?.(calendarDateValue(date))
              setOpen(false)
            }}
          />
          {allowClear && !required ? (
            <div className="border-t p-2">
              <Button
                type="button"
                variant="ghost"
                className="w-full"
                disabled={!value}
                onClick={() => {
                  onValueChange?.("")
                  setOpen(false)
                }}
              >
                Очистить дату
              </Button>
            </div>
          ) : null}
        </PopoverContent>
      </Popover>
    </Field>
  )
}

export { calendarDateLabel, calendarDateValue, parseCalendarDate }
