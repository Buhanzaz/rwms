import { useId, useState } from "react"
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

/**
 * Selects one date-only business value without converting it through the browser timezone.
 */
export function SingleDayPicker({
  id: idProp,
  label,
  value,
  disabled = false,
  className,
  triggerClassName,
  hideLabel = false,
  triggerLabel,
  onValueChange,
}: {
  id?: string
  label: string
  value: string
  disabled?: boolean
  className?: string
  triggerClassName?: string
  hideLabel?: boolean
  triggerLabel?: string
  onValueChange: (value: string) => void
}) {
  const generatedId = useId()
  const id = idProp ?? generatedId
  const selected = parseCalendarDate(value)
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
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            disabled={disabled}
            className={cn(
              "relative w-full min-w-0 justify-center rounded-lg text-sm font-medium",
              triggerClassName
            )}
            aria-label={`${label}: ${selectedLabel}`}
          >
            <HugeiconsIcon
              icon={Calendar03Icon}
              aria-hidden="true"
              className="absolute left-3"
            />
            <span className="inline-flex min-w-0 max-w-full items-baseline justify-center gap-1 text-center text-sm leading-5">
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
          className="!w-[var(--radix-popover-trigger-width)] max-w-[calc(100vw-1rem)] overflow-hidden rounded-lg p-0"
        >
          <Calendar
            className="!w-full"
            mode="single"
            locale={ru}
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
              onValueChange(calendarDateValue(date))
              setOpen(false)
            }}
          />
        </PopoverContent>
      </Popover>
    </Field>
  )
}

export { calendarDateLabel, calendarDateValue, parseCalendarDate }
