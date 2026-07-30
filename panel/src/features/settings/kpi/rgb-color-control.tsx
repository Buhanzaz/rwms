import { Input } from "@/components/ui/input"
import { normalizeRgb } from "@/features/settings/kpi/domain/kpi-settings"

const PICKER_FALLBACK = "#808080"

export function RgbColorControl({
  label,
  value,
  disabled,
  onChange,
}: {
  label: string
  value: string | null
  disabled?: boolean
  onChange: (value: string) => void
}) {
  const normalized = normalizeRgb(value)

  return (
    <div className="flex items-center gap-2">
      <Input
        type="color"
        aria-label={`Выбрать цвет ${label}`}
        value={normalized ?? PICKER_FALLBACK}
        disabled={disabled}
        className="size-9 shrink-0"
        onChange={(event) => onChange(event.target.value.toUpperCase())}
      />
      <Input
        aria-label={`RGB ${label}`}
        value={value ?? ""}
        placeholder="#RRGGBB"
        disabled={disabled}
        aria-invalid={value !== null && normalized === null}
        className="w-28"
        onChange={(event) => onChange(event.target.value.toUpperCase())}
      />
    </div>
  )
}
