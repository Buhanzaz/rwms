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
import type {
  SaveRepairComplexityInput,
  WarehouseKpiSettings,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  hoursToMinutes,
  minutesToHours,
  validateRepairComplexityBoundaries,
} from "@/features/settings/kpi/domain/kpi-settings"

export function RepairComplexitySettingsCard({
  settings,
  saving,
  blocked,
  actionError,
  onSave,
}: {
  settings: WarehouseKpiSettings
  saving: boolean
  blocked: boolean
  actionError: string | null
  onSave: (input: SaveRepairComplexityInput) => void
}) {
  const [lightHours, setLightHours] = useState(
    minutesToHours(settings.repairComplexity.lightBoundaryMinutes)
  )
  const [mediumHours, setMediumHours] = useState(
    minutesToHours(settings.repairComplexity.mediumBoundaryMinutes)
  )
  const [complexHours, setComplexHours] = useState(
    minutesToHours(settings.repairComplexity.complexBoundaryMinutes)
  )
  const [validationError, setValidationError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const lightBoundaryMinutes = hoursToMinutes(lightHours)
    const mediumBoundaryMinutes = hoursToMinutes(mediumHours)
    const complexBoundaryMinutes = hoursToMinutes(complexHours)
    if (
      lightBoundaryMinutes === null ||
      mediumBoundaryMinutes === null ||
      complexBoundaryMinutes === null
    ) {
      setValidationError(
        "Укажите положительные границы с точностью до одной минуты."
      )
      return
    }

    const boundaries = {
      lightBoundaryMinutes,
      mediumBoundaryMinutes,
      complexBoundaryMinutes,
    }
    const validation = validateRepairComplexityBoundaries(boundaries)
    if (!validation.valid) {
      setValidationError(validation.error)
      return
    }

    setValidationError(null)
    onSave({ expectedVersion: settings.version, ...boundaries })
  }

  const visibleError = validationError ?? actionError

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Сложность ремонта</CardTitle>
          <CardDescription>
            Три границы выбранного склада разделяют лёгкий, средний, сложный и
            капитальный ремонт. Ровно граничное значение относится к предыдущему
            диапазону.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="repair-complexity-light">
                Лёгкий ремонт, до и включая
              </FieldLabel>
              <Input
                id="repair-complexity-light"
                type="number"
                min={0.01}
                step="any"
                inputMode="decimal"
                value={lightHours}
                disabled={blocked}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setLightHours(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>Часы. Начальное значение — 1.</FieldDescription>
            </Field>
            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="repair-complexity-medium">
                Средний ремонт, до и включая
              </FieldLabel>
              <Input
                id="repair-complexity-medium"
                type="number"
                min={0.01}
                step="any"
                inputMode="decimal"
                value={mediumHours}
                disabled={blocked}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setMediumHours(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>Часы. Начальное значение — 3.</FieldDescription>
            </Field>
            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="repair-complexity-complex">
                Сложный ремонт, до и включая
              </FieldLabel>
              <Input
                id="repair-complexity-complex"
                type="number"
                min={0.01}
                step="any"
                inputMode="decimal"
                value={complexHours}
                disabled={blocked}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setComplexHours(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                Часы. Начальное значение — 6; выше начинается капитальный
                ремонт.
              </FieldDescription>
            </Field>
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
