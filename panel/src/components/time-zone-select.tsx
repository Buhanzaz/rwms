import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

type TimeZoneSelectProps = {
  id?: string
  value: string
  onValueChange: (value: string) => void
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
  className?: string
}

const preferredTimeZones = ["Europe/Moscow"]
const fallbackTimeZones = [
  "Europe/Moscow",
  "Europe/Kaliningrad",
  "Europe/Samara",
  "Asia/Yekaterinburg",
  "Asia/Omsk",
  "Asia/Krasnoyarsk",
  "Asia/Irkutsk",
  "Asia/Yakutsk",
  "Asia/Vladivostok",
  "Asia/Magadan",
  "Asia/Kamchatka",
]

function supportedTimeZones() {
  const values =
    typeof Intl.supportedValuesOf === "function"
      ? Intl.supportedValuesOf("timeZone")
      : fallbackTimeZones

  return [...new Set([...preferredTimeZones, ...values])].sort(
    (left, right) => {
      const leftPriority = preferredTimeZones.indexOf(left)
      const rightPriority = preferredTimeZones.indexOf(right)
      if (leftPriority !== -1 || rightPriority !== -1) {
        return (
          (leftPriority === -1 ? Number.MAX_SAFE_INTEGER : leftPriority) -
          (rightPriority === -1 ? Number.MAX_SAFE_INTEGER : rightPriority)
        )
      }
      return left.localeCompare(right)
    }
  )
}

export const timeZoneOptions = supportedTimeZones()

export function formatTimeZoneOffset(timeZone: string, date = new Date()) {
  const offset = new Intl.DateTimeFormat("en-US", {
    timeZone,
    timeZoneName: "longOffset",
  })
    .formatToParts(date)
    .find((part) => part.type === "timeZoneName")?.value

  if (offset === "GMT" || offset === undefined) return "+0"

  const match = /^GMT([+-])(\d{2}):(\d{2})$/.exec(offset)
  if (match === null) return offset.replace("GMT", "")

  const [, sign, hours, minutes] = match
  const normalizedHours = String(Number(hours))
  return minutes === "00"
    ? `${sign}${normalizedHours}`
    : `${sign}${normalizedHours}:${minutes}`
}

export function formatTimeZoneOption(timeZone: string, date = new Date()) {
  return `${timeZone} · ${formatTimeZoneOffset(timeZone, date)}`
}

const timeZoneOptionLabels = new Map(
  timeZoneOptions.map((timeZone) => [timeZone, formatTimeZoneOption(timeZone)])
)

/** Reusable IANA timezone picker that keeps the current UTC offset visible next to every option. */
export function TimeZoneSelect({
  id,
  value,
  onValueChange,
  placeholder = "Выберите временную зону",
  disabled = false,
  invalid = false,
  className,
}: TimeZoneSelectProps) {
  return (
    <Select
      value={value || undefined}
      onValueChange={onValueChange}
      disabled={disabled}
    >
      <SelectTrigger
        id={id}
        className={className}
        aria-invalid={invalid || undefined}
      >
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {timeZoneOptions.map((timeZone) => (
            <SelectItem key={timeZone} value={timeZone}>
              {timeZoneOptionLabels.get(timeZone)}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  )
}
