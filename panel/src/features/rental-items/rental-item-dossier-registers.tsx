import { useEffect, useId, useMemo, useState, type ReactNode } from "react"
import {
  Calendar03Icon,
  FilterIcon,
  GridViewIcon,
  ListViewIcon,
  Search01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link } from "react-router-dom"

import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Checkbox } from "@/components/ui/checkbox"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  CabinActivityDto,
  CabinActivityType,
  CabinCommentDto,
  CabinEstimateDto,
  CabinInspectionDto,
  CabinPhotoGroupDto,
  CabinRentalMovementDto,
  CabinRepairDto,
} from "@/features/rental-items/dossier/model/rental-item-dossier"
import { RENTAL_ITEM_STATUS_LABEL } from "@/features/rental-items/model/rental-item"
import { repairTaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { RentalItemsGridSettingsDialog } from "@/features/rental-items/rental-items-grid-settings-dialog"
import {
  getEffectiveRentalItemsGridFormat,
  getRentalItemsDefaultGridSize,
  getRentalItemsGridFormatMax,
  isRentalItemsMobileViewport,
  normalizeRentalItemsGridSize,
  useRentalItemsGridViewport,
} from "@/features/rental-items/rental-items-grid-format"
import { smartLocalSearch } from "@/features/rental-items/smart-local-search"
import {
  DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY,
  readRentalItemsGridSizePreference,
  writeRentalItemsGridSizePreference,
} from "@/features/rental-items/rental-items-grid-preferences"
import { cn } from "@/lib/utils"

type RegisterView = "table" | "gallery"

const SANITARY_CHARACTERISTIC_PATTERN = /(?:душ|туалет|раковин|бойлер|санузел)/i

const COMPOUND_METAL_DOOR_CHARACTERISTIC = "Металлическая дверь, кондиционер"
const COMPOUND_CHARACTERISTIC_TOKEN = "__METAL_DOOR_AND_AC__"
const SOURCE_FILTER_LABEL: Record<string, string> = {
  "Все события": "Событие",
  "Все источники": "Источник",
  "Все статусы": "Статус",
}

function splitCharacteristics(value: string | null) {
  if (!value?.trim()) return []
  return value
    .replaceAll(
      COMPOUND_METAL_DOOR_CHARACTERISTIC,
      COMPOUND_CHARACTERISTIC_TOKEN
    )
    .split(/[,;\n]+/)
    .map((item) =>
      item
        .trim()
        .replaceAll(
          COMPOUND_CHARACTERISTIC_TOKEN,
          COMPOUND_METAL_DOOR_CHARACTERISTIC
        )
    )
    .filter(Boolean)
}

export function CharacteristicTags({ value }: { value: string | null }) {
  const values = splitCharacteristics(value)
  if (values.length === 0) {
    return <span className="text-muted-foreground">—</span>
  }
  return (
    <div className="flex flex-wrap gap-1.5">
      {values.map((item) => (
        <Badge
          key={item}
          variant={
            SANITARY_CHARACTERISTIC_PATTERN.test(item) ? "default" : "secondary"
          }
        >
          {item}
        </Badge>
      ))}
    </div>
  )
}

function formatDossierDateTime(value: string | null) {
  if (!value) return "Дата не зафиксирована"
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

// Exported for deterministic timezone edge-case coverage.
// eslint-disable-next-line react-refresh/only-export-components
export function dossierLocalDateKey(value: string | null) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function dossierActorLabel(actor: { displayName: string } | null | undefined) {
  return actor?.displayName || "Автор не зафиксирован"
}

function activityTypeLabel(type: CabinActivityType) {
  const labels: Record<CabinActivityType, string> = {
    INSPECTION_COMPLETED: "Осмотр выполнен",
    ESTIMATE_CREATED: "Смета создана",
    ESTIMATE_COMPLETED: "Смета завершена",
    REPAIR_CREATED: "Ремонт создан",
    REPAIR_STAGE_COMPLETED: "Этап ремонта завершён",
    REPAIR_COMPLETED: "Ремонт завершён",
    REPAIR_ACCEPTED: "Ремонт принят",
    REPAIR_WRITTEN_OFF: "Бытовка списана",
    SHIPPED: "Отгружена в аренду",
    RETURNED: "Возвращена из аренды",
    RETURN_INTAKE_REGISTERED: "Возврат зарегистрирован",
    RETURN_CONFLICT_REGISTERED: "Конфликт возврата зарегистрирован",
    RETURN_PASSPORT_CHANGED: "Паспорт бытовки изменён",
    RETURN_EXPECTED_CONTENTS_CORRECTED: "Ожидаемое наполнение изменено",
    RETURN_CONFLICT_RESOLVED: "Конфликт возврата разрешён",
    RETURN_FURNITURE_DISPOSITION_RECORDED: "Мебель распределена",
    SHIPMENT_CREATED: "Отгрузка создана",
    SHIPMENT_UPDATED: "План отгрузки изменён",
    SHIPMENT_TASK_DISPATCHED: "Задание подготовки создано",
    SHIPMENT_PREPARED: "Подготовка к отгрузке подтверждена",
    SHIPMENT_PREPARATION_CONFLICT: "Конфликт подготовки к отгрузке",
    SHIPMENT_CANCELLED: "Отгрузка отменена",
    WAREHOUSE_TRANSFER_CREATED: "Межскладское перемещение создано",
    WAREHOUSE_TRANSFER_TASK_REGISTERED: "Задание перемещения создано",
    WAREHOUSE_TRANSFER_DEPARTED: "Убытие со склада подтверждено",
    WAREHOUSE_TRANSFER_ARRIVAL_CONFLICT: "Конфликт приёмки перемещения",
    WAREHOUSE_TRANSFER_RECEIVED: "Межскладское перемещение принято",
    WAREHOUSE_TRANSFER_CANCELLED: "Межскладское перемещение отменено",
    WAREHOUSE_CORRECTION_CREATED: "Коррекция учёта создана",
    WAREHOUSE_CORRECTION_APPROVED: "Коррекция учёта подтверждена",
    WAREHOUSE_CORRECTION_APPLIED: "Коррекция учёта применена",
    WAREHOUSE_CORRECTION_REJECTED: "Коррекция учёта отклонена",
    PHOTO_ADDED: "Добавлены фотографии",
    STATUS_CHANGED: "Статус изменён",
    GENERAL_COMMENT_UPDATED: "Общий комментарий изменён",
    COMMENT_ADDED: "Добавлен комментарий",
    CONTENTS_TRANSFERRED: "Наполнение перемещено",
  }
  return labels[type]
}

function photoStageLabel(stage: CabinPhotoGroupDto["stage"]) {
  return {
    BEFORE: "До работ",
    WORK: "Во время работ",
    AFTER: "После работ",
    ACCEPTANCE: "Приёмка и осмотр",
    GENERAL: "Общие фотографии",
  }[stage]
}

function inspectionStatusLabel(status: CabinInspectionDto["status"]) {
  if (status === "READY") return "Готова"
  if (status === "WORK_STAGED") return "Нужны работы"
  return "Не осмотрена"
}

function acceptanceStatusLabel(status: CabinRepairDto["acceptanceStatus"]) {
  return {
    NOT_READY: "Не готов к приёмке",
    PENDING: "Ожидает приёмки",
    IN_REWORK: "На доработке",
    ACCEPTED: "Принят",
    WRITTEN_OFF: "Списан",
  }[status]
}

function EmptyRegister({ title }: { title: string }) {
  return (
    <p className="py-12 text-center text-sm text-muted-foreground lg:hidden">
      {title}
    </p>
  )
}

function DateFilterChip({
  dateFrom,
  onDateFromChange,
  dateTo,
  onDateToChange,
}: {
  dateFrom: string
  onDateFromChange: (value: string) => void
  dateTo: string
  onDateToChange: (value: string) => void
}) {
  const dateFromId = useId()
  const dateToId = useId()
  const activeCount = Number(Boolean(dateFrom)) + Number(Boolean(dateTo))
  const selectedDateLabel =
    dateFrom && dateTo
      ? `с ${dateFrom} по ${dateTo}`
      : dateFrom
        ? dateFrom
        : dateTo
          ? dateTo
          : "не выбрана"

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="sm"
          variant={activeCount ? "secondary" : "outline"}
          aria-label={`Фильтр по дате: ${selectedDateLabel}`}
          aria-pressed={activeCount > 0}
        >
          Дата
          {activeCount ? <Badge variant="outline">{activeCount}</Badge> : null}
          <HugeiconsIcon icon={Calendar03Icon} data-icon="inline-end" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[calc(100vw-2rem)] sm:w-80">
        <FieldGroup>
          <Field>
            <FieldLabel htmlFor={dateFromId}>Дата с</FieldLabel>
            <InputGroup>
              <InputGroupAddon>
                <HugeiconsIcon icon={Calendar03Icon} aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                id={dateFromId}
                name="dossier-date-from"
                type="date"
                value={dateFrom}
                max={dateTo || undefined}
                onChange={(event) => onDateFromChange(event.target.value)}
              />
            </InputGroup>
          </Field>
          <Field>
            <FieldLabel htmlFor={dateToId}>Дата по</FieldLabel>
            <InputGroup>
              <InputGroupAddon>
                <HugeiconsIcon icon={Calendar03Icon} aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                id={dateToId}
                name="dossier-date-to"
                type="date"
                value={dateTo}
                min={dateFrom || undefined}
                onChange={(event) => onDateToChange(event.target.value)}
              />
            </InputGroup>
          </Field>
          {activeCount ? (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => {
                onDateFromChange("")
                onDateToChange("")
              }}
            >
              Очистить даты
            </Button>
          ) : null}
        </FieldGroup>
      </PopoverContent>
    </Popover>
  )
}

function MultiValueFilterChip({
  label,
  options,
  selected,
  onChange,
}: {
  label: string
  options: string[]
  selected: string[]
  onChange: (values: string[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(selected)
  const selectedLabel =
    selected.length > 0
      ? `выбрано ${selected.length}: ${selected.join(", ")}`
      : "не выбрано"

  function toggle(value: string) {
    setDraft((current) =>
      current.includes(value)
        ? current.filter((item) => item !== value)
        : [...current, value]
    )
  }

  return (
    <Popover
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen)
        if (nextOpen) setDraft(selected)
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="sm"
          variant={selected.length ? "secondary" : "outline"}
          aria-label={`Фильтр ${label}: ${selectedLabel}`}
          aria-pressed={selected.length > 0}
        >
          {label}
          {selected.length ? (
            <Badge variant="outline">{selected.length}</Badge>
          ) : null}
          <HugeiconsIcon
            icon={FilterIcon}
            data-icon="inline-end"
            aria-hidden="true"
          />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[calc(100vw-2rem)] p-2 sm:w-72"
      >
        <div className="flex max-h-64 flex-col gap-1 overflow-y-auto">
          {options.length > 0 ? (
            options.map((option) => (
              <label
                key={option}
                className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
              >
                <Checkbox
                  checked={draft.includes(option)}
                  onCheckedChange={() => toggle(option)}
                />
                <span className="min-w-0 truncate">{option}</span>
              </label>
            ))
          ) : (
            <span className="px-2 py-3 text-sm text-muted-foreground">
              Нет вариантов
            </span>
          )}
        </div>
        <div className="flex justify-between gap-2">
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setDraft([])
              onChange([])
              setOpen(false)
            }}
          >
            Очистить
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={() => {
              onChange(draft)
              setOpen(false)
            }}
          >
            Применить
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function RegisterControls({
  query,
  onQueryChange,
  searchPlaceholder,
  action,
  dateFrom,
  onDateFromChange,
  dateTo,
  onDateToChange,
  sources,
  selectedSources,
  onSourcesChange,
  sourceLabel,
  actors,
  selectedActors,
  onActorsChange,
  compactOnMobile = true,
  mobileControlsLabel = "поиск и фильтры",
}: {
  query: string
  onQueryChange: (value: string) => void
  searchPlaceholder: string
  action?: ReactNode
  dateFrom: string
  onDateFromChange: (value: string) => void
  dateTo: string
  onDateToChange: (value: string) => void
  sources: string[]
  selectedSources: string[]
  onSourcesChange: (values: string[]) => void
  sourceLabel: string
  actors: string[]
  selectedActors: string[]
  onActorsChange: (values: string[]) => void
  compactOnMobile?: boolean
  mobileControlsLabel?: string
}) {
  const [mobileControlsOpen, setMobileControlsOpen] = useState(false)

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {compactOnMobile ? (
            <Button
              type="button"
              size="icon-sm"
              variant={mobileControlsOpen ? "secondary" : "outline"}
              className="lg:hidden"
              aria-label={`${
                mobileControlsOpen ? "Скрыть" : "Показать"
              } ${mobileControlsLabel}`}
              aria-expanded={mobileControlsOpen}
              onClick={() => setMobileControlsOpen((current) => !current)}
            >
              <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
            </Button>
          ) : null}
          <InputGroup
            className={cn(
              "w-full sm:max-w-xl",
              compactOnMobile && !mobileControlsOpen && "hidden lg:flex"
            )}
          >
            <InputGroupAddon>
              <HugeiconsIcon icon={Search01Icon} aria-hidden="true" />
            </InputGroupAddon>
            <InputGroupInput
              value={query}
              name="dossier-smart-search"
              onChange={(event) => onQueryChange(event.target.value)}
              aria-label="Умный поиск"
              autoComplete="off"
              placeholder={searchPlaceholder}
            />
          </InputGroup>
        </div>
        {action ? (
          <div
            className={cn(
              "w-full lg:w-auto [&>[data-slot=button]]:w-full lg:[&>[data-slot=button]]:w-auto",
              compactOnMobile && !mobileControlsOpen && "hidden lg:block"
            )}
          >
            {action}
          </div>
        ) : null}
      </div>
      <div
        data-testid="dossier-filter-bar"
        className={cn(
          "flex flex-wrap gap-2 rounded-lg border bg-card p-2",
          compactOnMobile && !mobileControlsOpen && "hidden lg:flex"
        )}
      >
        <DateFilterChip
          dateFrom={dateFrom}
          onDateFromChange={onDateFromChange}
          dateTo={dateTo}
          onDateToChange={onDateToChange}
        />
        <MultiValueFilterChip
          label={SOURCE_FILTER_LABEL[sourceLabel] ?? "Категория"}
          options={sources}
          selected={selectedSources}
          onChange={onSourcesChange}
        />
        <MultiValueFilterChip
          label="Автор"
          options={actors}
          selected={selectedActors}
          onChange={onActorsChange}
        />
      </div>
    </div>
  )
}

function dossierDateSearchValues(value: string | null) {
  if (!value) return ["Дата не зафиксирована"]
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return [value]

  return [
    value,
    dossierLocalDateKey(value),
    formatDossierDateTime(value),
    new Intl.DateTimeFormat("ru-RU", { dateStyle: "long" }).format(date),
  ]
}

function useRegisterFilter<T>(
  values: T[],
  getDate: (item: T) => string | null,
  getSource: (item: T) => string,
  getActor: (item: T) => string,
  getSearchValues: (item: T) => unknown[]
) {
  const [query, setQuery] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const [selectedSources, setSelectedSources] = useState<string[]>([])
  const [selectedActors, setSelectedActors] = useState<string[]>([])
  const sources = useMemo(
    () =>
      Array.from(new Set(values.map(getSource)))
        .filter(Boolean)
        .sort(),
    [getSource, values]
  )
  const actors = useMemo(
    () =>
      Array.from(new Set(values.map(getActor)))
        .filter(Boolean)
        .sort(),
    [getActor, values]
  )
  const filtered = useMemo(() => {
    const explicitlyFiltered = values.filter((item) => {
      const occurredAt = getDate(item)
      const occurredDate = dossierLocalDateKey(occurredAt)
      const dateMatches =
        dateFrom && dateTo
          ? occurredDate !== null &&
            occurredDate >= dateFrom &&
            occurredDate <= dateTo
          : dateFrom
            ? occurredDate === dateFrom
            : dateTo
              ? occurredDate === dateTo
              : true
      return (
        dateMatches &&
        (selectedSources.length === 0 ||
          selectedSources.includes(getSource(item))) &&
        (selectedActors.length === 0 || selectedActors.includes(getActor(item)))
      )
    })

    return smartLocalSearch(explicitlyFiltered, query, (item) => [
      ...dossierDateSearchValues(getDate(item)),
      getSource(item),
      getActor(item),
      ...getSearchValues(item),
    ])
  }, [
    dateFrom,
    dateTo,
    getActor,
    getDate,
    getSearchValues,
    getSource,
    query,
    selectedActors,
    selectedSources,
    values,
  ])
  return {
    filtered,
    filters: {
      query,
      onQueryChange: setQuery,
      dateFrom,
      onDateFromChange: setDateFrom,
      dateTo,
      onDateToChange: setDateTo,
      sources,
      selectedSources,
      onSourcesChange: setSelectedSources,
      actors,
      selectedActors,
      onActorsChange: setSelectedActors,
    },
  }
}

function ResponsiveRegister<T extends { id: string }>({
  items,
  columns,
  renderCard,
  emptyTitle,
}: {
  items: T[]
  columns: OperationsListGridColumn<T>[]
  renderCard: (item: T) => ReactNode
  emptyTitle: string
}) {
  return (
    <>
      <OperationsListGrid
        className="hidden lg:block"
        items={items}
        columns={columns}
      />
      {items.length > 0 ? (
        <div className="grid gap-3 lg:hidden">{items.map(renderCard)}</div>
      ) : (
        <EmptyRegister title={emptyTitle} />
      )}
    </>
  )
}

type EmptyRegisterRow = { id: string }

export function EmptyDossierRegister({
  title,
  columns: labels,
}: {
  title: string
  description: string
  columns: string[]
}) {
  const [query, setQuery] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const [selectedSources, setSelectedSources] = useState<string[]>([])
  const [selectedActors, setSelectedActors] = useState<string[]>([])
  const columns: OperationsListGridColumn<EmptyRegisterRow>[] = labels.map(
    (label, index) => ({
      id: `column-${index}`,
      label,
      render: () => null,
      getSortValue: () => null,
    })
  )

  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        query={query}
        onQueryChange={setQuery}
        searchPlaceholder="Поиск по разделу…"
        dateFrom={dateFrom}
        onDateFromChange={setDateFrom}
        dateTo={dateTo}
        onDateToChange={setDateTo}
        sources={[]}
        selectedSources={selectedSources}
        onSourcesChange={setSelectedSources}
        sourceLabel="Все события"
        actors={[]}
        selectedActors={selectedActors}
        onActorsChange={setSelectedActors}
      />
      <OperationsListGrid
        className="hidden lg:block"
        items={[]}
        columns={columns}
      />
      <p className="py-12 text-center text-sm text-muted-foreground lg:hidden">
        {title} не найдены
      </p>
    </div>
  )
}

function ProjectionCard({
  title,
  description,
  details,
  action,
}: {
  title: string
  description: string
  details: Array<[string, ReactNode]>
  action?: ReactNode
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <dl className="grid grid-cols-[7rem_minmax(0,1fr)] gap-2 text-sm">
          {details.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-muted-foreground">{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        {action}
      </CardContent>
    </Card>
  )
}

function SourceLink({
  href,
  label = "Открыть",
}: {
  href: string
  label?: string
}) {
  return (
    <Button size="sm" variant="outline" asChild>
      <Link to={href} state={workspaceEntryNavigationOptions.state}>
        {label}
      </Link>
    </Button>
  )
}

function PhotoFolder({
  group,
  onOpen,
}: {
  group: CabinPhotoGroupDto
  onOpen: () => void
}) {
  const cover = group.photos[0]
  return (
    <Button
      type="button"
      variant="outline"
      className="h-auto min-w-0 flex-col items-stretch gap-0 overflow-hidden p-0 text-left"
      onClick={onOpen}
    >
      <div className="aspect-square overflow-hidden bg-muted">
        {cover ? (
          <img
            src={cover.variants.preview.url}
            alt=""
            loading="lazy"
            width={cover.variants.preview.width ?? 640}
            height={cover.variants.preview.height ?? 640}
            className="size-full object-cover"
          />
        ) : null}
      </div>
      <div className="flex min-w-0 flex-col gap-1 p-3">
        <span className="truncate font-medium">{group.sourceLabel}</span>
        <span className="text-xs text-muted-foreground">
          {group.photos.length} фото · {formatDossierDateTime(group.occurredAt)}
        </span>
        <span className="truncate text-xs text-muted-foreground">
          {dossierActorLabel(group.actor)} · {photoStageLabel(group.stage)}
        </span>
      </div>
    </Button>
  )
}

export function PhotosRegister({
  values,
  onOpen,
  onAddPhoto,
}: {
  values: CabinPhotoGroupDto[]
  onOpen: (group: CabinPhotoGroupDto) => void
  onAddPhoto: () => void
}) {
  const [view, setView] = useState<RegisterView>("gallery")
  const [gridSettingsOpen, setGridSettingsOpen] = useState(false)
  const [savedGridSize, setSavedGridSize] = useState<number | null>(() =>
    readRentalItemsGridSizePreference(DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY, 5)
  )
  const viewport = useRentalItemsGridViewport()
  const gridFormatMax = getRentalItemsGridFormatMax(viewport)
  const effectiveGridFormat = getEffectiveRentalItemsGridFormat(
    savedGridSize,
    viewport
  )
  const gridSettingsAvailable =
    !isRentalItemsMobileViewport(viewport) && gridFormatMax > 1
  useEffect(() => {
    if (savedGridSize === null) return
    writeRentalItemsGridSizePreference(
      DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY,
      savedGridSize,
      5
    )
  }, [savedGridSize])
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    (item) => item.sourceLabel,
    (item) => dossierActorLabel(item.actor),
    (item) => [
      item.sourceLabel,
      photoStageLabel(item.stage),
      item.photos.length,
      item.photos.map((photo) => photo.provenance.sourceLabel).join(" "),
    ]
  )
  const columns: OperationsListGridColumn<CabinPhotoGroupDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "event",
      label: "Событие",
      render: (item) => item.sourceLabel,
      getSortValue: (item) => item.sourceLabel,
    },
    {
      id: "stage",
      label: "Этап",
      render: (item) => photoStageLabel(item.stage),
      getSortValue: (item) => photoStageLabel(item.stage),
    },
    {
      id: "actor",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "count",
      label: "Фото",
      render: (item) => (
        <Button size="sm" variant="outline" onClick={() => onOpen(item)}>
          Открыть ({item.photos.length})
        </Button>
      ),
      getSortValue: (item) => item.photos.length,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все события"
        searchPlaceholder="Поиск по автору, дате, событию или этапу…"
        action={<Button onClick={onAddPhoto}>Добавить фото</Button>}
        mobileControlsLabel="поиск и фильтры фото"
      />
      <div className="flex flex-wrap items-center gap-2">
        <ToggleGroup
          type="single"
          value={view}
          variant="outline"
          size="lg"
          spacing={2}
          className="hidden lg:flex"
          aria-label="Вид фотографий"
        >
          <ToggleGroupItem
            value="table"
            className="size-8 min-w-0 px-0"
            aria-label="Таблица"
            onClick={() => setView("table")}
          >
            <HugeiconsIcon icon={ListViewIcon} aria-hidden="true" />
          </ToggleGroupItem>
          <ToggleGroupItem
            value="gallery"
            className="size-8 min-w-0 px-0"
            aria-label="Ячейки"
            onClick={() => setView("gallery")}
          >
            <HugeiconsIcon icon={GridViewIcon} aria-hidden="true" />
          </ToggleGroupItem>
        </ToggleGroup>
        {view === "gallery" && gridSettingsAvailable ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => setGridSettingsOpen(true)}
          >
            <HugeiconsIcon icon={Settings02Icon} data-icon="inline-start" />
            до {effectiveGridFormat.columns}x{effectiveGridFormat.rows}
          </Button>
        ) : null}
      </div>
      {view === "table" ? (
        <OperationsListGrid
          className="hidden lg:block"
          items={filtered}
          columns={columns}
        />
      ) : null}
      <div
        className={view === "table" ? "grid gap-3 lg:hidden" : "grid gap-3"}
        style={{
          gridTemplateColumns: `repeat(${effectiveGridFormat.columns}, minmax(0, 1fr))`,
        }}
        data-testid="photo-folder-grid"
        data-columns={effectiveGridFormat.columns}
      >
        {filtered.map((group) => (
          <PhotoFolder
            key={group.id}
            group={group}
            onOpen={() => onOpen(group)}
          />
        ))}
      </div>
      {filtered.length === 0 ? (
        <p
          className={cn(
            "py-8 text-center text-sm text-muted-foreground",
            view === "table" && "lg:hidden"
          )}
        >
          Фотографии не найдены
        </p>
      ) : null}
      {gridSettingsAvailable ? (
        <RentalItemsGridSettingsDialog
          open={gridSettingsOpen}
          value={effectiveGridFormat.columns}
          maxSize={gridFormatMax}
          defaultValue={getRentalItemsDefaultGridSize(viewport)}
          onOpenChange={setGridSettingsOpen}
          onValueChange={(value) =>
            setSavedGridSize(normalizeRentalItemsGridSize(value, gridFormatMax))
          }
        />
      ) : null}
    </div>
  )
}

export function InspectionsRegister({
  values,
  photoGroups,
  onOpenPhotoGroup,
}: {
  values: CabinInspectionDto[]
  photoGroups: CabinPhotoGroupDto[]
  onOpenPhotoGroup: (group: CabinPhotoGroupDto) => void
}) {
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    (item) =>
      item.sourceType === "INVENTORY" ? "Инвентаризация" : "Возврат из аренды",
    (item) => dossierActorLabel(item.actor),
    (item) => [
      inspectionStatusLabel(item.status),
      item.comment,
      item.links.map((link) => link.label).join(" "),
    ]
  )
  const openPhoto = (item: CabinInspectionDto) => {
    const group = photoGroups.find(
      (candidate) => candidate.id === item.photoGroupId
    )
    if (group) onOpenPhotoGroup(group)
  }
  const columns: OperationsListGridColumn<CabinInspectionDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "source",
      label: "Источник",
      render: (item) =>
        item.sourceType === "INVENTORY"
          ? "Инвентаризация"
          : "Возврат из аренды",
      getSortValue: (item) => item.sourceType,
    },
    {
      id: "result",
      label: "Результат",
      render: (item) => inspectionStatusLabel(item.status),
      getSortValue: (item) => inspectionStatusLabel(item.status),
    },
    {
      id: "actor",
      label: "Исполнитель",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "comment",
      label: "Комментарий",
      render: (item) => item.comment ?? "—",
      getSortValue: (item) => item.comment,
    },
    {
      id: "actions",
      label: "Действия",
      render: (item) => (
        <div className="flex gap-2">
          {item.photoGroupId ? (
            <Button size="sm" variant="outline" onClick={() => openPhoto(item)}>
              Фото
            </Button>
          ) : null}
          <SourceLink
            href={
              item.links[0]?.href ?? `/inventory/history/${item.inventoryId}`
            }
          />
        </div>
      ),
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все источники"
        searchPlaceholder="Поиск по дате, результату, автору или комментарию…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="Осмотры не найдены"
        renderCard={(item) => (
          <ProjectionCard
            key={item.id}
            title="Осмотр"
            description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
            details={[
              [
                "Источник",
                item.sourceType === "INVENTORY"
                  ? "Инвентаризация"
                  : "Возврат из аренды",
              ],
              ["Результат", inspectionStatusLabel(item.status)],
              ["Комментарий", item.comment ?? "—"],
            ]}
            action={
              <div className="flex gap-2">
                {item.photoGroupId ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => openPhoto(item)}
                  >
                    Фото
                  </Button>
                ) : null}
                <SourceLink
                  href={
                    item.links[0]?.href ??
                    `/inventory/history/${item.inventoryId}`
                  }
                />
              </div>
            }
          />
        )}
      />
    </div>
  )
}

export function EstimatesRegister({ values }: { values: CabinEstimateDto[] }) {
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    (item) => (item.status === "COMPLETED" ? "Завершена" : "Черновик"),
    (item) => dossierActorLabel(item.actor),
    (item) => [
      item.status === "COMPLETED" ? "Завершена" : "Черновик",
      item.totalAmount,
      item.comment,
      item.link.label,
    ]
  )
  const columns: OperationsListGridColumn<CabinEstimateDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "status",
      label: "Статус",
      render: (item) =>
        item.status === "COMPLETED" ? "Завершена" : "Черновик",
      getSortValue: (item) => item.status,
    },
    {
      id: "author",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "amount",
      label: "Сумма",
      render: (item) => `${item.totalAmount} ₽`,
      getSortValue: (item) => Number(item.totalAmount),
    },
    {
      id: "comment",
      label: "Комментарий",
      render: (item) => item.comment ?? "—",
      getSortValue: (item) => item.comment,
    },
    {
      id: "actions",
      label: "Действия",
      render: (item) => <SourceLink href={item.link.href} />,
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все статусы"
        searchPlaceholder="Поиск по дате, статусу, автору, сумме или комментарию…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="Сметы не найдены"
        renderCard={(item) => (
          <ProjectionCard
            key={item.id}
            title={`Смета · ${item.status === "COMPLETED" ? "Завершена" : "Черновик"}`}
            description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
            details={[
              ["Сумма", `${item.totalAmount} ₽`],
              ["Комментарий", item.comment ?? "—"],
            ]}
            action={<SourceLink href={item.link.href} />}
          />
        )}
      />
    </div>
  )
}

export function RepairsRegister({ values }: { values: CabinRepairDto[] }) {
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    (item) => repairTaskStatusLabel(item.status),
    (item) => dossierActorLabel(item.actor),
    (item) => [
      repairTaskStatusLabel(item.status),
      acceptanceStatusLabel(item.acceptanceStatus),
      item.reason,
      item.comment,
      ...dossierDateSearchValues(item.completedAt),
      item.link.label,
    ]
  )
  const columns: OperationsListGridColumn<CabinRepairDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "status",
      label: "Статус",
      render: (item) => repairTaskStatusLabel(item.status),
      getSortValue: (item) => repairTaskStatusLabel(item.status),
    },
    {
      id: "acceptance",
      label: "Приёмка",
      render: (item) => acceptanceStatusLabel(item.acceptanceStatus),
      getSortValue: (item) => acceptanceStatusLabel(item.acceptanceStatus),
    },
    {
      id: "author",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "reason",
      label: "Причина",
      render: (item) => item.reason || "—",
      getSortValue: (item) => item.reason,
    },
    {
      id: "completed",
      label: "Завершён",
      render: (item) => formatDossierDateTime(item.completedAt),
      getSortValue: (item) => item.completedAt,
    },
    {
      id: "actions",
      label: "Действия",
      render: (item) => <SourceLink href={item.link.href} />,
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все статусы"
        searchPlaceholder="Поиск по дате, статусу, автору или причине…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="Ремонты не найдены"
        renderCard={(item) => (
          <ProjectionCard
            key={item.id}
            title={`Ремонт · ${repairTaskStatusLabel(item.status)}`}
            description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
            details={[
              ["Приёмка", acceptanceStatusLabel(item.acceptanceStatus)],
              ["Причина", item.reason || "—"],
              ["Комментарий", item.comment ?? "—"],
              ["Завершён", formatDossierDateTime(item.completedAt)],
            ]}
            action={<SourceLink href={item.link.href} />}
          />
        )}
      />
    </div>
  )
}

export function ShipmentsRegister({
  values,
}: {
  values: CabinRentalMovementDto[]
}) {
  const directionLabel = (item: CabinRentalMovementDto) =>
    item.direction === "OUTBOUND" ? "Отгрузка в аренду" : "Приёмка из аренды"
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    directionLabel,
    (item) => dossierActorLabel(item.actor),
    (item) => [
      directionLabel(item),
      item.party,
      item.driverName,
      item.contents.map((entry) => `${entry.name} ${entry.quantity}`).join(" "),
      item.link.label,
    ]
  )
  const contentsLabel = (item: CabinRentalMovementDto) =>
    item.contents.length > 0
      ? item.contents
          .map((entry) => `${entry.name} ${entry.quantity} шт.`)
          .join(", ")
      : "—"
  const columns: OperationsListGridColumn<CabinRentalMovementDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "direction",
      label: "Событие",
      render: directionLabel,
      getSortValue: directionLabel,
    },
    {
      id: "party",
      label: "Контрагент",
      render: (item) => item.party,
      getSortValue: (item) => item.party,
    },
    {
      id: "driver",
      label: "Водитель",
      render: (item) => item.driverName ?? "—",
      getSortValue: (item) => item.driverName,
    },
    {
      id: "contents",
      label: "Мебель",
      render: contentsLabel,
      getSortValue: contentsLabel,
    },
    {
      id: "actor",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "actions",
      label: "Действия",
      render: (item) => <SourceLink href={item.link.href} />,
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все события"
        searchPlaceholder="Поиск по дате, контрагенту, водителю или мебели…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="Отгрузки не найдены"
        renderCard={(item) => (
          <ProjectionCard
            key={item.id}
            title={directionLabel(item)}
            description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
            details={[
              [item.direction === "OUTBOUND" ? "Кому" : "От кого", item.party],
              ["Водитель", item.driverName ?? "—"],
              ["Мебель", contentsLabel(item)],
            ]}
            action={<SourceLink href={item.link.href} />}
          />
        )}
      />
    </div>
  )
}

type ActivityRow = CabinActivityDto & { depth: number }

function flattenActivities(values: CabinActivityDto[]) {
  const byParent = new Map<string | null, CabinActivityDto[]>()
  const ids = new Set(values.map((item) => item.id))
  values.forEach((item) => {
    const parent =
      item.parentActivityId && ids.has(item.parentActivityId)
        ? item.parentActivityId
        : null
    byParent.set(parent, [...(byParent.get(parent) ?? []), item])
  })
  const rows: ActivityRow[] = []
  const append = (parent: string | null, depth: number) => {
    ;(byParent.get(parent) ?? []).forEach((item) => {
      rows.push({ ...item, depth })
      append(item.id, depth + 1)
    })
  }
  append(null, 0)
  return rows
}

export function HistoryRegister({
  values,
  photoGroups,
  onOpenPhotoGroup,
}: {
  values: CabinActivityDto[]
  photoGroups: CabinPhotoGroupDto[]
  onOpenPhotoGroup: (group: CabinPhotoGroupDto) => void
}) {
  const rows = useMemo(() => flattenActivities(values), [values])
  const { filtered, filters } = useRegisterFilter(
    rows,
    (item) => item.occurredAt,
    (item) => activityTypeLabel(item.type),
    (item) => dossierActorLabel(item.actor),
    (item) => [
      activityTypeLabel(item.type),
      item.sourceLabel,
      item.comment,
      item.type === "GENERAL_COMMENT_UPDATED" && !item.comment
        ? "Комментарий очищен"
        : null,
      item.statusTransition?.reason,
      item.statusTransition
        ? RENTAL_ITEM_STATUS_LABEL[item.statusTransition.from]
        : null,
      item.statusTransition
        ? RENTAL_ITEM_STATUS_LABEL[item.statusTransition.to]
        : null,
      item.links.map((link) => link.label).join(" "),
    ]
  )
  const photoFor = (item: ActivityRow) =>
    photoGroups.find((group) => group.id === item.photoGroupId)
  const transition = (item: ActivityRow) =>
    item.statusTransition
      ? `${RENTAL_ITEM_STATUS_LABEL[item.statusTransition.from]} → ${RENTAL_ITEM_STATUS_LABEL[item.statusTransition.to]}`
      : "—"
  const activityDetails = (item: ActivityRow) => {
    if (item.type === "GENERAL_COMMENT_UPDATED" && !item.comment) {
      return "Комментарий очищен"
    }
    return item.comment || transition(item)
  }
  const actions = (item: ActivityRow) => (
    <div className="flex flex-wrap gap-2">
      {photoFor(item) ? (
        <Button
          size="sm"
          variant="outline"
          onClick={() => onOpenPhotoGroup(photoFor(item)!)}
        >
          Фото
        </Button>
      ) : null}
      {item.links.map((link) => (
        <SourceLink key={link.href} href={link.href} label={link.label} />
      ))}
    </div>
  )
  const columns: OperationsListGridColumn<ActivityRow>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "event",
      label: "Событие",
      render: (item) => (
        <span style={{ paddingLeft: `${item.depth * 16}px` }}>
          {item.depth > 0 ? "↳ " : ""}
          {activityTypeLabel(item.type)}
        </span>
      ),
      getSortValue: (item) => activityTypeLabel(item.type),
    },
    {
      id: "source",
      label: "Источник",
      render: (item) => item.sourceLabel,
      getSortValue: (item) => item.sourceLabel,
    },
    {
      id: "author",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "details",
      label: "Изменение / комментарий",
      render: activityDetails,
      getSortValue: activityDetails,
    },
    {
      id: "actions",
      label: "Действия",
      render: actions,
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все события"
        searchPlaceholder="Поиск по дате, событию, автору или изменению…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="События не найдены"
        renderCard={(item) => (
          <div
            key={item.id}
            style={{ marginLeft: `${Math.min(item.depth, 2) * 12}px` }}
          >
            <ProjectionCard
              title={activityTypeLabel(item.type)}
              description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
              details={[
                ["Источник", item.sourceLabel],
                ["Изменение", transition(item)],
                ["Комментарий", activityDetails(item)],
              ]}
              action={actions(item)}
            />
          </div>
        )}
      />
    </div>
  )
}

export function CommentsRegister({ values }: { values: CabinCommentDto[] }) {
  const { filtered, filters } = useRegisterFilter(
    values,
    (item) => item.occurredAt,
    (item) => item.sourceLabel,
    (item) => dossierActorLabel(item.actor),
    (item) => [item.sourceLabel, item.text, item.link?.label]
  )
  const columns: OperationsListGridColumn<CabinCommentDto>[] = [
    {
      id: "date",
      label: "Дата",
      render: (item) => formatDossierDateTime(item.occurredAt),
      getSortValue: (item) => item.occurredAt,
    },
    {
      id: "source",
      label: "Источник",
      render: (item) => item.sourceLabel,
      getSortValue: (item) => item.sourceLabel,
    },
    {
      id: "author",
      label: "Автор",
      render: (item) => dossierActorLabel(item.actor),
      getSortValue: (item) => dossierActorLabel(item.actor),
    },
    {
      id: "comment",
      label: "Комментарий",
      render: (item) => item.text,
      getSortValue: (item) => item.text,
    },
    {
      id: "actions",
      label: "Действия",
      render: (item) =>
        item.link ? (
          <SourceLink href={item.link.href} label={item.link.label} />
        ) : (
          "—"
        ),
      getSortValue: () => null,
    },
  ]
  return (
    <div className="flex flex-col gap-3">
      <RegisterControls
        {...filters}
        sourceLabel="Все источники"
        searchPlaceholder="Поиск по дате, автору, источнику или комментарию…"
      />
      <ResponsiveRegister
        items={filtered}
        columns={columns}
        emptyTitle="Комментарии не найдены"
        renderCard={(item) => (
          <ProjectionCard
            key={item.id}
            title={item.sourceLabel}
            description={`${formatDossierDateTime(item.occurredAt)} · ${dossierActorLabel(item.actor)}`}
            details={[["Комментарий", item.text]]}
            action={
              item.link ? (
                <SourceLink href={item.link.href} label={item.link.label} />
              ) : undefined
            }
          />
        )}
      />
    </div>
  )
}
