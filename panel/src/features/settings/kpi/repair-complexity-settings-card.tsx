import { useState, type FormEvent } from "react"
import { FloppyDiskIcon, Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  ToggleGroup,
  ToggleGroupItem,
} from "@/components/ui/toggle-group"
import type {
  RepairComplexitySetting,
  RepairComplexityUpdate,
} from "@/features/settings/kpi/api/repair-complexity-api"
import {
  formatRepairDuration,
  hoursAndMinutesToMinutes,
  splitMinutes,
  validateRepairComplexityBoundaries,
} from "@/features/settings/kpi/domain/kpi-settings"

type DisplayFormat = "MINUTES" | "HOURS"
type BoundaryKey =
  | "lightBoundaryMinutes"
  | "mediumBoundaryMinutes"
  | "complexBoundaryMinutes"

const BOUNDARIES: {
  key: BoundaryKey
  id: string
  label: string
}[] = [
  {
    key: "lightBoundaryMinutes",
    id: "repair-complexity-light",
    label: "Лёгкий ремонт, до и включая",
  },
  {
    key: "mediumBoundaryMinutes",
    id: "repair-complexity-medium",
    label: "Средний ремонт, до и включая",
  },
  {
    key: "complexBoundaryMinutes",
    id: "repair-complexity-complex",
    label: "Тяжёлый ремонт, до и включая",
  },
]

export function RepairComplexitySettingsCard({
  setting,
  saving,
  blocked,
  actionError,
  onSave,
}: {
  setting: RepairComplexitySetting
  saving: boolean
  blocked: boolean
  actionError: string | null
  onSave: (input: RepairComplexityUpdate) => void
}) {
  const [format, setFormat] = useState<DisplayFormat>("MINUTES")
  const [values, setValues] = useState({
    lightBoundaryMinutes: setting.lightBoundaryMinutes,
    mediumBoundaryMinutes: setting.mediumBoundaryMinutes,
    complexBoundaryMinutes: setting.complexBoundaryMinutes,
  })
  const [validationError, setValidationError] = useState<string | null>(null)

  function setBoundary(key: BoundaryKey, value: number | null) {
    if (value === null) {
      setValidationError("Укажите положительное целое количество минут.")
      setValues((current) => ({ ...current, [key]: 0 }))
      return
    }
    setValidationError(null)
    setValues((current) => ({ ...current, [key]: value }))
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const validation = validateRepairComplexityBoundaries(values)
    if (!validation.valid) {
      setValidationError(validation.error)
      return
    }

    setValidationError(null)
    onSave({ expectedVersion: setting.version, ...values })
  }

  const visibleError = validationError ?? actionError
  const ranges = [
    {
      type: "Лёгкий ремонт",
      value: `0–${formatRepairDuration(values.lightBoundaryMinutes, format)}`,
    },
    {
      type: "Средний ремонт",
      value: `> ${formatRepairDuration(values.lightBoundaryMinutes, format)}, до ${formatRepairDuration(values.mediumBoundaryMinutes, format)}`,
    },
    {
      type: "Тяжёлый ремонт",
      value: `> ${formatRepairDuration(values.mediumBoundaryMinutes, format)}, до ${formatRepairDuration(values.complexBoundaryMinutes, format)}`,
    },
    {
      type: "Капитальный ремонт",
      value: `> ${formatRepairDuration(values.complexBoundaryMinutes, format)}`,
    },
  ]

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Сложность ремонта</CardTitle>
          <CardDescription>
            Три границы выбранного склада разделяют лёгкий, средний, тяжёлый и
            капитальный ремонт. Ровно граничное значение относится к предыдущему
            диапазону.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <Field>
            <FieldLabel>Формат времени</FieldLabel>
            <ToggleGroup
              type="single"
              variant="outline"
              value={format}
              disabled={blocked}
              aria-label="Формат времени"
              onValueChange={(value) => {
                if (value === "MINUTES" || value === "HOURS") setFormat(value)
              }}
            >
              <ToggleGroupItem value="MINUTES">Минуты</ToggleGroupItem>
              <ToggleGroupItem value="HOURS">Часы и минуты</ToggleGroupItem>
            </ToggleGroup>
            <FieldDescription>
              В сервисе значения всегда сохраняются целым количеством минут.
            </FieldDescription>
          </Field>

          <div
            className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4"
            aria-label="Диапазоны типов ремонта"
          >
            {ranges.map((range) => (
              <Card key={range.type} size="sm">
                <CardHeader>
                  <CardDescription>{range.type}</CardDescription>
                  <CardTitle>{range.value}</CardTitle>
                </CardHeader>
              </Card>
            ))}
          </div>

          <FieldGroup>
            {BOUNDARIES.map((boundary) => {
              const value = values[boundary.key]
              const parts = splitMinutes(value)
              return (
                <Field
                  key={boundary.key}
                  data-invalid={visibleError !== null || undefined}
                >
                  <FieldLabel htmlFor={boundary.id}>
                    {boundary.label}
                  </FieldLabel>
                  {format === "MINUTES" ? (
                    <Input
                      id={boundary.id}
                      aria-label={`${boundary.label}, минуты`}
                      type="number"
                      min={1}
                      step={1}
                      inputMode="numeric"
                      value={value}
                      disabled={blocked}
                      aria-invalid={visibleError !== null}
                      onChange={(event) => {
                        const next = Number(event.target.value)
                        setBoundary(
                          boundary.key,
                          Number.isInteger(next) && next > 0 ? next : null
                        )
                      }}
                    />
                  ) : (
                    <div className="grid grid-cols-2 gap-2">
                      <Field>
                        <FieldLabel htmlFor={`${boundary.id}-hours`}>
                          Часы
                        </FieldLabel>
                        <Input
                          id={`${boundary.id}-hours`}
                          aria-label={`${boundary.label}, часы`}
                          type="number"
                          min={0}
                          step={1}
                          inputMode="numeric"
                          value={parts.hours}
                          disabled={blocked}
                          aria-invalid={visibleError !== null}
                          onChange={(event) =>
                            setBoundary(
                              boundary.key,
                              hoursAndMinutesToMinutes(
                                event.target.value,
                                String(parts.minutes)
                              )
                            )
                          }
                        />
                      </Field>
                      <Field>
                        <FieldLabel htmlFor={`${boundary.id}-minutes`}>
                          Минуты
                        </FieldLabel>
                        <Input
                          id={`${boundary.id}-minutes`}
                          aria-label={`${boundary.label}, остаток минут`}
                          type="number"
                          min={0}
                          max={59}
                          step={1}
                          inputMode="numeric"
                          value={parts.minutes}
                          disabled={blocked}
                          aria-invalid={visibleError !== null}
                          onChange={(event) =>
                            setBoundary(
                              boundary.key,
                              hoursAndMinutesToMinutes(
                                String(parts.hours),
                                event.target.value
                              )
                            )
                          }
                        />
                      </Field>
                    </div>
                  )}
                  <FieldDescription>
                    Граница хранится целым количеством минут.
                  </FieldDescription>
                </Field>
              )
            })}
            {visibleError ? <FieldError>{visibleError}</FieldError> : null}
          </FieldGroup>
        </CardContent>
        <CardFooter className="justify-end">
          <Button type="submit" disabled={blocked}>
            <HugeiconsIcon
              icon={saving ? Loading03Icon : FloppyDiskIcon}
              data-icon="inline-start"
              className={saving ? "animate-spin" : undefined}
            />
            {saving ? "Сохраняем…" : "Сохранить границы"}
          </Button>
        </CardFooter>
      </Card>
    </form>
  )
}
