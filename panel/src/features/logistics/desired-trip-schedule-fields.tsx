import { type ComponentProps } from "react"
import { ru } from "date-fns/locale"
import type { Matcher } from "react-day-picker"

import { Badge } from "@/components/ui/badge"
import { Calendar, CalendarDayButton } from "@/components/ui/calendar"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field"
import type { DesiredDeliveryWindow } from "@/features/orders/domain/orders"
import { cn } from "@/lib/utils"

function localDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const [year, month, day] = value.split("-").map(Number)
  const parsed = new Date(year, month - 1, day)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed
}

function dateValue(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, "0")
  const day = String(value.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function formatDate(value: string) {
  const parsed = localDate(value)
  return parsed
    ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(parsed)
    : value
}

function desiredWindowLabel(window: DesiredDeliveryWindow) {
  return (
    window.startDate === window.endDate
      ? formatDate(window.startDate)
      : `${formatDate(window.startDate)} — ${formatDate(window.endDate)}`
  )
}

function desiredMatchers(windows: readonly DesiredDeliveryWindow[]): Matcher[] {
  return windows.flatMap((window): Matcher[] => {
    const from = localDate(window.startDate)
    const to = localDate(window.endDate)
    if (!from || !to) return []
    return window.startDate === window.endDate ? [from] : [{ from, to }]
  })
}

/**
 * Selects the actual logistics date while rendering client preferences as
 * advisory calendar marks rather than disabled dates.
 */
export function DesiredTripScheduleFields({
  dateLabel,
  scheduledDate,
  desiredDeliveryWindows,
  disabled = false,
  error,
  onDateChange,
}: {
  dateLabel: string
  scheduledDate: string
  desiredDeliveryWindows: readonly DesiredDeliveryWindow[]
  disabled?: boolean
  error?: string | null
  onDateChange: (value: string) => void
}) {
  const requested = desiredMatchers(desiredDeliveryWindows)
  const selected = localDate(scheduledDate)
  const initialMonth =
    selected ?? localDate(desiredDeliveryWindows[0]?.startDate ?? "")
  const calendarComponents = {
    DayButton: ({
      modifiers,
      className,
      ...props
    }: ComponentProps<typeof CalendarDayButton>) => (
      <CalendarDayButton
        {...props}
        modifiers={modifiers}
        locale={ru}
        data-desired-window={modifiers.requested || undefined}
        className={cn(
          className,
          modifiers.requested &&
            "text-destructive-foreground bg-destructive hover:bg-destructive/90"
        )}
      />
    ),
  }

  return (
    <>
      <Field data-invalid={Boolean(error) || undefined}>
        <FieldLabel>{dateLabel}</FieldLabel>
        <FieldDescription>
          Красным отмечены пожелания клиента. Можно назначить любую другую дату:
          пожелания не ограничивают календарь логиста.
        </FieldDescription>
        <div className="w-fit max-w-full overflow-x-auto rounded-lg border">
          <Calendar
            mode="single"
            locale={ru}
            defaultMonth={initialMonth}
            selected={selected}
            disabled={disabled}
            modifiers={{ requested }}
            components={calendarComponents}
            aria-label={`${dateLabel}: календарь фактической ходки`}
            onSelect={(date) => date && onDateChange(dateValue(date))}
          />
        </div>
        {desiredDeliveryWindows.length > 0 ? (
          <div
            className="flex flex-wrap gap-1"
            aria-label="Желаемые даты клиента"
          >
            {desiredDeliveryWindows.map((window, index) => (
              <Badge
                key={`${window.startDate}:${window.endDate}:${index}`}
                variant="outline"
              >
                {desiredWindowLabel(window)}
              </Badge>
            ))}
          </div>
        ) : (
          <FieldDescription>
            Клиент не указал желаемые даты.
          </FieldDescription>
        )}
        {error ? <FieldError>{error}</FieldError> : null}
      </Field>
    </>
  )
}
