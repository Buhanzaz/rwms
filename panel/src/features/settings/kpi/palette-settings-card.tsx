import {
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
} from "react"
import {
  Add01Icon,
  Delete02Icon,
  FloppyDiskIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
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
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  KpiPaletteResponse,
  SaveKpiPaletteInput,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  mergePaletteBoundary,
  movePaletteBoundary,
  normalizeRgb,
  setPaletteRangeColor,
  splitPaletteRange,
  validatePalette,
  type EditablePaletteRange,
} from "@/features/settings/kpi/domain/kpi-settings"
import { RgbColorControl } from "@/features/settings/kpi/rgb-color-control"

type PaletteMode = "DIVIDE" | "COLOR"

type BoundaryDrag = {
  pointerId: number
  currentBoundary: number
  minimum: number
  maximum: number
  moved: boolean
}

function setPointerCaptureSafely(element: Element, pointerId: number) {
  try {
    element.setPointerCapture(pointerId)
  } catch {
    // Synthetic PointerEvents used by tests may not have an active browser pointer.
  }
}

function releasePointerCaptureSafely(element: Element, pointerId: number) {
  try {
    if (element.hasPointerCapture(pointerId)) {
      element.releasePointerCapture(pointerId)
    }
  } catch {
    // Matching guard for synthetic PointerEvents.
  }
}

function rangeBackground(color: string | null) {
  return normalizeRgb(color) ?? "var(--muted)"
}

function rangeForeground(color: string | null) {
  const normalized = normalizeRgb(color)
  if (normalized === null) return undefined

  const red = Number.parseInt(normalized.slice(1, 3), 16)
  const green = Number.parseInt(normalized.slice(3, 5), 16)
  const blue = Number.parseInt(normalized.slice(5, 7), 16)
  const brightness = (red * 299 + green * 587 + blue * 114) / 1000

  return brightness >= 150
    ? "var(--rwms-dusk-blue)"
    : "var(--rwms-white)"
}

function ColorPopover({
  label,
  value,
  disabled,
  children,
  onChange,
}: {
  label: string
  value: string | null
  disabled: boolean
  children: React.ReactNode
  onChange: (value: string) => void
}) {
  return (
    <Popover>
      <PopoverTrigger asChild>{children}</PopoverTrigger>
      <PopoverContent align="center">
        <PopoverHeader>
          <PopoverTitle>Цвет {label}</PopoverTitle>
          <PopoverDescription>
            Значение сохраняется только для выбранного диапазона.
          </PopoverDescription>
        </PopoverHeader>
        <RgbColorControl
          label={label}
          value={value}
          disabled={disabled}
          onChange={onChange}
        />
      </PopoverContent>
    </Popover>
  )
}

export function PaletteSettingsCard({
  settings,
  saving,
  blocked,
  actionError,
  onSave,
}: {
  settings: KpiPaletteResponse
  saving: boolean
  blocked: boolean
  actionError: string | null
  onSave: (input: SaveKpiPaletteInput) => void
}) {
  const [mode, setMode] = useState<PaletteMode>("DIVIDE")
  const [ranges, setRanges] = useState<EditablePaletteRange[]>(() =>
    settings.palette
      ? settings.palette.ranges.map((range) => ({ ...range }))
      : [{ fromPercent: 0, toPercent: 100, color: null }]
  )
  const [overdueColor, setOverdueColor] = useState<string | null>(
    settings.palette?.overdueColor ?? null
  )
  const [problemColor, setProblemColor] = useState<string | null>(
    settings.palette?.problemColor ?? "#FF3B30"
  )
  const [newBoundary, setNewBoundary] = useState("50")
  const [selectedBoundary, setSelectedBoundary] = useState<number | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const boundaryDragRef = useRef<BoundaryDrag | null>(null)
  const suppressBoundaryClickRef = useRef(false)
  const validation = validatePalette(ranges, overdueColor)

  function addBoundary(value: number) {
    const next = splitPaletteRange(ranges, value)
    if (next === ranges) return
    setRanges(next)
    setSelectedBoundary(value)
    setValidationError(null)
  }

  function clickTrack(event: MouseEvent<HTMLDivElement>) {
    if (blocked || mode !== "DIVIDE" || ranges.length >= 6) return
    const bounds = trackRef.current?.getBoundingClientRect()
    if (!bounds || bounds.width <= 0) return
    const percent = Math.round(
      ((event.clientX - bounds.left) / bounds.width) * 100
    )
    addBoundary(Math.min(99, Math.max(1, percent)))
  }

  function percentAtClientX(clientX: number) {
    const bounds = trackRef.current?.getBoundingClientRect()
    if (!bounds || bounds.width <= 0) return null
    return Math.round(((clientX - bounds.left) / bounds.width) * 100)
  }

  function startBoundaryDrag(
    event: PointerEvent<HTMLButtonElement>,
    boundary: number
  ) {
    if (blocked || (event.pointerType === "mouse" && event.button !== 0)) {
      return
    }

    const rightIndex = ranges.findIndex(
      (range) => range.fromPercent === boundary
    )
    const leftRange = rightIndex > 0 ? ranges[rightIndex - 1] : null
    const rightRange = rightIndex >= 0 ? ranges[rightIndex] : null
    if (!leftRange || !rightRange) return

    event.preventDefault()
    event.stopPropagation()
    setPointerCaptureSafely(event.currentTarget, event.pointerId)
    boundaryDragRef.current = {
      pointerId: event.pointerId,
      currentBoundary: boundary,
      minimum: leftRange.fromPercent + 1,
      maximum: rightRange.toPercent - 1,
      moved: false,
    }
  }

  function moveBoundaryDrag(event: PointerEvent<HTMLButtonElement>) {
    const drag = boundaryDragRef.current
    if (drag === null || drag.pointerId !== event.pointerId) return

    const rawPercent = percentAtClientX(event.clientX)
    if (rawPercent === null) return

    event.preventDefault()
    event.stopPropagation()
    const nextBoundary = Math.min(
      drag.maximum,
      Math.max(drag.minimum, rawPercent)
    )
    if (nextBoundary === drag.currentBoundary) return

    const previousBoundary = drag.currentBoundary
    drag.currentBoundary = nextBoundary
    drag.moved = true
    suppressBoundaryClickRef.current = true
    setRanges((current) =>
      movePaletteBoundary(current, previousBoundary, nextBoundary)
    )
    setSelectedBoundary(nextBoundary)
  }

  function finishBoundaryDrag(event: PointerEvent<HTMLButtonElement>) {
    const drag = boundaryDragRef.current
    if (drag === null || drag.pointerId !== event.pointerId) return

    boundaryDragRef.current = null
    releasePointerCaptureSafely(event.currentTarget, event.pointerId)
    setSelectedBoundary(drag.currentBoundary)
    if (drag.moved) {
      suppressBoundaryClickRef.current = true
      event.preventDefault()
      event.stopPropagation()
    }
  }

  function cancelBoundaryDrag(event: PointerEvent<HTMLButtonElement>) {
    const drag = boundaryDragRef.current
    if (drag === null || drag.pointerId !== event.pointerId) return

    boundaryDragRef.current = null
    releasePointerCaptureSafely(event.currentTarget, event.pointerId)
    setSelectedBoundary(drag.currentBoundary)
    if (drag.moved) suppressBoundaryClickRef.current = true
  }

  function boundaryKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    boundary: number
  ) {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault()
      const next = boundary + (event.key === "ArrowLeft" ? -1 : 1)
      const moved = movePaletteBoundary(ranges, boundary, next)
      if (moved !== ranges) {
        setRanges(moved)
        setSelectedBoundary(next)
      }
    }
    if (event.key === "Delete" || event.key === "Backspace") {
      event.preventDefault()
      setRanges(mergePaletteBoundary(ranges, boundary))
      setSelectedBoundary(null)
    }
  }

  const selectedBoundaryExists =
    selectedBoundary !== null &&
    ranges.some(
      (range, index) => index > 0 && range.fromPercent === selectedBoundary
    )

  function save() {
    if (!validation.valid) {
      setValidationError(validation.error)
      return
    }
    onSave({
      expectedVersion: settings.version,
      ranges: ranges.map((range) => ({
        fromPercent: range.fromPercent,
        toPercent: range.toPercent,
        color: normalizeRgb(range.color)!,
      })),
      overdueColor: normalizeRgb(overdueColor)!,
      problemColor: normalizeRgb(problemColor)!,
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Диапазоны KPI</CardTitle>
        <CardDescription>
          Деления и RGB-цвета применяются к доске и показателям всех объектов
          всех объектов.
        </CardDescription>
        <CardAction>
          <ToggleGroup
            type="single"
            variant="outline"
            size="sm"
            spacing={0}
            value={mode}
            aria-label="Режим редактирования шкалы KPI"
            onValueChange={(value) => {
              if (value === "DIVIDE" || value === "COLOR") setMode(value)
            }}
          >
            <ToggleGroupItem value="DIVIDE" aria-label="Настройка делений">
              <span
                className="relative block h-3 w-5 rounded-xs border"
                aria-hidden="true"
              >
                <span className="absolute inset-y-0 left-1/2 border-l" />
              </span>
            </ToggleGroupItem>
            <ToggleGroupItem value="COLOR" aria-label="Назначение цветов">
              <span className="text-[9px] leading-none" aria-hidden="true">
                RGB
              </span>
            </ToggleGroupItem>
          </ToggleGroup>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div className="flex justify-between text-xs text-muted-foreground">
          <span>0%</span>
          <span>100%</span>
        </div>

        <div
          className={`relative pt-7 ${
            mode === "DIVIDE" && selectedBoundaryExists ? "pb-11" : ""
          }`}
        >
          <div
            ref={trackRef}
            className="relative flex h-14 overflow-visible rounded-lg border bg-muted"
            aria-label="Шкала диапазонов KPI"
            onClick={clickTrack}
          >
            {ranges.map((range) =>
              mode === "COLOR" ? (
                <ColorPopover
                  key={`range-${range.fromPercent}`}
                  label={`диапазона ${range.fromPercent}–${range.toPercent}%`}
                  value={range.color}
                  disabled={blocked}
                  onChange={(value) =>
                    setRanges((current) =>
                      setPaletteRangeColor(current, range.fromPercent, value)
                    )
                  }
                >
                  <button
                    type="button"
                    aria-label={`Диапазон от ${range.fromPercent}% до ${range.toPercent}%`}
                    className="h-full border-r px-1 text-[10px] font-medium last:border-r-0 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    style={{
                      width: `${range.toPercent - range.fromPercent}%`,
                      backgroundColor: rangeBackground(range.color),
                      color: rangeForeground(range.color),
                    }}
                    disabled={blocked}
                  >
                    {range.fromPercent}–{range.toPercent}%
                  </button>
                </ColorPopover>
              ) : (
                <div
                  key={`range-${range.fromPercent}`}
                  className="h-full border-r last:border-r-0"
                  style={{
                    width: `${range.toPercent - range.fromPercent}%`,
                    backgroundColor: rangeBackground(range.color),
                  }}
                />
              )
            )}

            {mode === "DIVIDE"
              ? ranges.slice(1).map((range) => (
                  <button
                    key={`boundary-${range.toPercent}`}
                    type="button"
                    className="absolute inset-y-0 w-3 -translate-x-1/2 cursor-col-resize border-x bg-background/80 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    style={{ left: `${range.fromPercent}%` }}
                    aria-label={`Граница ${range.fromPercent}%`}
                    aria-pressed={selectedBoundary === range.fromPercent}
                    disabled={blocked}
                    onClick={(event) => {
                      event.stopPropagation()
                      if (suppressBoundaryClickRef.current) {
                        suppressBoundaryClickRef.current = false
                        return
                      }
                      setSelectedBoundary(range.fromPercent)
                    }}
                    onPointerDown={(event) =>
                      startBoundaryDrag(event, range.fromPercent)
                    }
                    onPointerMove={moveBoundaryDrag}
                    onPointerUp={finishBoundaryDrag}
                    onPointerCancel={cancelBoundaryDrag}
                    onKeyDown={(event) =>
                      boundaryKeyDown(event, range.fromPercent)
                    }
                  />
                ))
              : null}

            {mode === "DIVIDE"
              ? ranges.slice(1).map((range) => (
                  <span
                    key={`boundary-label-${range.toPercent}`}
                    className="pointer-events-none absolute -top-6 -translate-x-1/2 rounded bg-background px-1 text-xs leading-5 font-medium whitespace-nowrap shadow-sm"
                    style={{
                      left: `clamp(1.5rem, ${range.fromPercent}%, calc(100% - 1.5rem))`,
                    }}
                    aria-hidden="true"
                  >
                    {range.fromPercent}%
                  </span>
                ))
              : null}

            {mode === "DIVIDE" && selectedBoundaryExists ? (
              <div
                className="absolute top-full z-10 mt-2 -translate-x-1/2"
                style={{
                  left: `clamp(3.5rem, ${selectedBoundary}%, calc(100% - 3.5rem))`,
                }}
              >
                <Button
                  type="button"
                  variant="destructive"
                  size="icon"
                  aria-label={`Удалить границу ${selectedBoundary}%`}
                  title="Удалить границу"
                  disabled={blocked}
                  onClick={(event) => {
                    event.stopPropagation()
                    if (selectedBoundary === null) return
                    setRanges((current) =>
                      mergePaletteBoundary(current, selectedBoundary)
                    )
                    setSelectedBoundary(null)
                  }}
                >
                  <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
                </Button>
              </div>
            ) : null}
          </div>
        </div>

        {mode === "DIVIDE" ? (
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="kpi-new-boundary">
                Новая граница, %
              </FieldLabel>
              <div className="flex gap-2">
                <Input
                  id="kpi-new-boundary"
                  type="number"
                  min={1}
                  max={99}
                  step={1}
                  value={newBoundary}
                  disabled={blocked || ranges.length >= 6}
                  onChange={(event) => setNewBoundary(event.target.value)}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={blocked || ranges.length >= 6}
                  onClick={() => addBoundary(Number(newBoundary))}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить границу
                </Button>
              </div>
              <FieldDescription>
                Кликните по шкале или задайте целое значение. Стрелки сдвигают
                выбранную границу на 1%.
              </FieldDescription>
            </Field>
          </FieldGroup>
        ) : (
          <FieldDescription>
            Нажмите на нужный промежуток, чтобы открыть компактный RGB-контрол.
          </FieldDescription>
        )}

        <Field>
          <FieldLabel>Цвет просрочки</FieldLabel>
          <ColorPopover
            label="просрочки"
            value={overdueColor}
            disabled={blocked}
            onChange={setOverdueColor}
          >
            <Button
              type="button"
              variant="outline"
              className="rwms-button-color shadow-sm"
              style={{
                backgroundColor: rangeBackground(overdueColor),
                color: rangeForeground(overdueColor),
              }}
              disabled={blocked}
            >
              Цвет просрочки
            </Button>
          </ColorPopover>
          <FieldDescription>
            Просроченные задания не используют цвет диапазона 0%.
          </FieldDescription>
          <FieldError>{validationError ?? actionError}</FieldError>
        </Field>
        <Field>
          <FieldLabel>Цвет задания с проблемой</FieldLabel>
          <ColorPopover label="проблемы" value={problemColor} disabled={blocked} onChange={setProblemColor}>
            <Button type="button" variant="outline" className="rwms-button-color shadow-sm" style={{ backgroundColor: rangeBackground(problemColor), color: rangeForeground(problemColor) }} disabled={blocked}>
              Цвет проблемы
            </Button>
          </ColorPopover>
          <FieldDescription>Этот яркий цвет перекрывает KPI, когда рабочий сообщил о проблеме.</FieldDescription>
        </Field>

        <div className="flex flex-wrap gap-2">
          {ranges.map((range) => (
            <ColorPopover
              key={range.fromPercent}
              label={`диапазона ${range.fromPercent}–${range.toPercent}%`}
              value={range.color}
              disabled={blocked}
              onChange={(value) =>
                setRanges((current) =>
                  setPaletteRangeColor(current, range.fromPercent, value)
                )
              }
            >
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="rwms-button-color shadow-sm"
                style={{
                  backgroundColor: rangeBackground(range.color),
                  color: rangeForeground(range.color),
                }}
                disabled={blocked}
              >
                {range.fromPercent}–{range.toPercent}%
              </Button>
            </ColorPopover>
          ))}
        </div>
      </CardContent>
      <CardFooter className="justify-end gap-3 border-t">
        <Button
          type="button"
          disabled={blocked || !validation.valid}
          onClick={save}
        >
          <HugeiconsIcon
            icon={saving ? Loading03Icon : FloppyDiskIcon}
            data-icon="inline-start"
            className={saving ? "animate-spin" : undefined}
          />
          {saving ? "Сохраняем…" : "Сохранить палитру"}
        </Button>
      </CardFooter>
    </Card>
  )
}
