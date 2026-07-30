import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  Calendar03Icon,
  Delete02Icon,
  FilterIcon,
  GridViewIcon,
  ImageUploadIcon,
  ListViewIcon,
  RotateClockwiseIcon,
  Search01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"
import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import {
  PhotoCarousel,
  type PhotoCarouselPhoto,
} from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
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
import { Skeleton } from "@/components/ui/skeleton"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type { MediaAsset } from "@/features/media/media-service"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  getEffectiveRentalItemsGridFormat,
  getRentalItemsDefaultGridSize,
  getRentalItemsGridFormatMax,
  isRentalItemsMobileViewport,
  normalizeRentalItemsGridSize,
  useRentalItemsGridViewport,
} from "@/features/rental-items/rental-items-grid-format"
import { RentalItemsGridSettingsDialog } from "@/features/rental-items/rental-items-grid-settings-dialog"
import { smartLocalSearch } from "@/features/rental-items/smart-local-search"
import type { RentalItemPhotoFolder } from "@/features/rental-items/use-rental-item-media"
import { cn } from "@/lib/utils"

type PendingPhoto = {
  file: File
  previewUrl: string
}

export function RentalItemPhotoUploadDialog({
  open,
  pending,
  onOpenChange,
  onConfirm,
}: {
  open: boolean
  pending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (files: File[]) => Promise<unknown>
}) {
  const [photos, setPhotos] = useState<PendingPhoto[]>([])
  const photosRef = useRef(photos)

  useEffect(() => {
    photosRef.current = photos
  }, [photos])

  useEffect(() => {
    return () =>
      photosRef.current.forEach((photo) =>
        URL.revokeObjectURL(photo.previewUrl)
      )
  }, [])

  function clear() {
    photos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl))
    setPhotos([])
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !pending) clear()
        onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Добавить фотографии</DialogTitle>
          <DialogDescription>
            Файлы будут сохранены media-service и появятся в карточке после
            обработки.
          </DialogDescription>
        </DialogHeader>
        <Field data-disabled={pending || undefined}>
          <FieldLabel htmlFor="rental-item-photo-files">
            Фотографии бытовки
          </FieldLabel>
          <Input
            id="rental-item-photo-files"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            disabled={pending}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? [])
              setPhotos((current) => [
                ...current,
                ...files.map((file) => ({
                  file,
                  previewUrl: URL.createObjectURL(file),
                })),
              ])
              event.currentTarget.value = ""
            }}
          />
          <FieldDescription>
            Можно выбрать несколько изображений одновременно.
          </FieldDescription>
        </Field>
        {photos.length > 0 ? (
          <div className="grid max-h-[50svh] gap-3 overflow-y-auto sm:grid-cols-2 lg:grid-cols-3">
            {photos.map((photo, index) => (
              <article
                key={`${photo.file.name}-${photo.file.lastModified}-${index}`}
                className="overflow-hidden rounded-lg border bg-card"
              >
                <img
                  src={photo.previewUrl}
                  alt={photo.file.name}
                  className="aspect-[4/3] w-full object-cover"
                />
                <div className="flex items-center justify-between gap-2 p-2">
                  <span className="truncate text-xs font-medium">
                    {photo.file.name}
                  </span>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`Удалить ${photo.file.name}`}
                    disabled={pending}
                    onClick={() => {
                      URL.revokeObjectURL(photo.previewUrl)
                      setPhotos((current) =>
                        current.filter(
                          (_, currentIndex) => currentIndex !== index
                        )
                      )
                    }}
                  >
                    <HugeiconsIcon icon={Delete02Icon} />
                  </Button>
                </div>
              </article>
            ))}
          </div>
        ) : null}
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => {
              clear()
              onOpenChange(false)
            }}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={pending || photos.length === 0}
            onClick={() => {
              void onConfirm(photos.map((photo) => photo.file))
                .then(() => {
                  clear()
                  onOpenChange(false)
                })
                .catch(() => undefined)
            }}
          >
            <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
            {pending ? "Загрузка..." : "Добавить фото"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

const mediaStatusLabel = {
  UPLOADING: "Загрузка",
  PROCESSING: "Обработка",
  READY: "Готово",
  FAILED: "Ошибка",
  DELETED: "Удалено",
} as const

type RegisterView = "table" | "gallery"

const PHOTO_GRID_SIZE_PREFERENCE_KEY =
  "rental-item-dossier:photo-grid-format:v1"
const UNKNOWN_DATE = "Дата не зафиксирована"

function formatPhotoDateTime(value: string | null) {
  if (!value) return UNKNOWN_DATE
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date)
}

function localDateKey(value: string | null) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function searchPhotoFolders(values: RentalItemPhotoFolder[], query: string) {
  return smartLocalSearch(values, query, (folder) => [
    folder.sourceLabel,
    folder.actorLabel,
    "Общие фотографии",
    formatPhotoDateTime(folder.occurredAt),
    localDateKey(folder.occurredAt),
    folder.photos.map((photo) => photo.fileName).join(" "),
    folder.assets.map((asset) => mediaStatusLabel[asset.status]).join(" "),
  ])
}

function folderPhotoCount(folder: RentalItemPhotoFolder) {
  const logicalServicePhotos = folder.assets.filter(
    (asset) => asset.kind === "IMAGE" && asset.status !== "DELETED"
  ).length
  return logicalServicePhotos > 0
    ? logicalServicePhotos
    : folder.assets.length > 0
      ? 0
      : folder.photos.length
}

function readPhotoGridSizePreference() {
  try {
    const value = window.localStorage.getItem(PHOTO_GRID_SIZE_PREFERENCE_KEY)
    return value === null
      ? null
      : normalizeRentalItemsGridSize(JSON.parse(value), 5)
  } catch {
    return null
  }
}

function writePhotoGridSizePreference(value: number) {
  const normalized = normalizeRentalItemsGridSize(value, 5)
  if (normalized === null) return
  window.localStorage.setItem(
    PHOTO_GRID_SIZE_PREFERENCE_KEY,
    JSON.stringify(normalized)
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

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="sm"
          variant={activeCount ? "secondary" : "outline"}
          className="w-full justify-between lg:w-auto"
          aria-label={`Фильтр по дате: ${activeCount ? "выбран" : "не выбран"}`}
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
          className="w-full justify-between lg:w-auto"
          aria-pressed={selected.length > 0}
        >
          {label}
          {selected.length ? (
            <Badge variant="outline">{selected.length}</Badge>
          ) : null}
          <HugeiconsIcon icon={FilterIcon} data-icon="inline-end" />
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
                  onCheckedChange={() =>
                    setDraft((current) =>
                      current.includes(option)
                        ? current.filter((value) => value !== option)
                        : [...current, option]
                    )
                  }
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
  action,
  dateFrom,
  onDateFromChange,
  dateTo,
  onDateToChange,
  sources,
  selectedSources,
  onSourcesChange,
  actors,
  selectedActors,
  onActorsChange,
}: {
  query: string
  onQueryChange: (value: string) => void
  action?: ReactNode
  dateFrom: string
  onDateFromChange: (value: string) => void
  dateTo: string
  onDateToChange: (value: string) => void
  sources: string[]
  selectedSources: string[]
  onSourcesChange: (values: string[]) => void
  actors: string[]
  selectedActors: string[]
  onActorsChange: (values: string[]) => void
}) {
  const [mobileControlsOpen, setMobileControlsOpen] = useState(false)
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <Button
            type="button"
            size="icon-sm"
            variant={mobileControlsOpen ? "secondary" : "outline"}
            className="lg:hidden"
            aria-label={`${mobileControlsOpen ? "Скрыть" : "Показать"} поиск и фильтры фото`}
            aria-expanded={mobileControlsOpen}
            onClick={() => setMobileControlsOpen((current) => !current)}
          >
            <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
          </Button>
          <InputGroup
            className={cn(
              "w-full sm:max-w-xl",
              !mobileControlsOpen && "hidden lg:flex"
            )}
          >
            <InputGroupAddon>
              <HugeiconsIcon icon={Search01Icon} aria-hidden="true" />
            </InputGroupAddon>
            <InputGroupInput
              value={query}
              name="rental-item-photo-search"
              onChange={(event) => onQueryChange(event.target.value)}
              aria-label="Умный поиск"
              autoComplete="off"
              placeholder="Поиск по автору, дате, событию или этапу…"
            />
          </InputGroup>
        </div>
        {action ? (
          <div
            className={cn(
              "w-full lg:w-auto [&>[data-slot=button]]:w-full lg:[&>[data-slot=button]]:w-auto",
              !mobileControlsOpen && "hidden lg:block"
            )}
          >
            {action}
          </div>
        ) : null}
      </div>
      <div
        data-testid="photo-filter-bar"
        className={cn(
          "flex flex-col gap-2 rounded-lg border bg-card p-2 lg:flex-row",
          !mobileControlsOpen && "hidden lg:flex"
        )}
      >
        <DateFilterChip
          dateFrom={dateFrom}
          onDateFromChange={onDateFromChange}
          dateTo={dateTo}
          onDateToChange={onDateToChange}
        />
        <MultiValueFilterChip
          label="Событие"
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

function FolderCard({
  folder,
  onOpen,
}: {
  folder: RentalItemPhotoFolder
  onOpen: () => void
}) {
  const cover = folder.photos[0]
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
            src={cover.variants?.small?.url ?? cover.url}
            alt=""
            loading="lazy"
            className="size-full object-cover"
          />
        ) : (
          <div className="flex size-full items-center justify-center px-3 text-center text-xs text-muted-foreground">
            {folder.assets.some((asset) => asset.status === "FAILED")
              ? "Ошибка обработки"
              : "Фотографии обрабатываются"}
          </div>
        )}
      </div>
      <div className="flex min-w-0 flex-col gap-1 p-3">
        <span className="truncate font-medium">{folder.sourceLabel}</span>
        <span className="text-xs text-muted-foreground">
          {folderPhotoCount(folder)} фото ·{" "}
          {formatPhotoDateTime(folder.occurredAt)}
        </span>
        <span className="truncate text-xs text-muted-foreground">
          {folder.actorLabel} · Общие фотографии
        </span>
      </div>
    </Button>
  )
}

function FolderDetails({
  item,
  folder,
  previewLoading,
  canEdit,
  rotating,
  onBack,
  onRotate,
  onRequestFullscreen,
}: {
  item: RentalItemDto
  folder: RentalItemPhotoFolder
  previewLoading: boolean
  canEdit: boolean
  rotating: boolean
  onBack: () => void
  onRotate: (asset: MediaAsset) => void
  onRequestFullscreen: (photo: PhotoCarouselPhoto) => void | Promise<void>
}) {
  const [activeIndex, setActiveIndex] = useState(0)
  const visibleActiveIndex =
    activeIndex < folder.photos.length ? activeIndex : 0
  const activePhoto = folder.photos[visibleActiveIndex] ?? folder.photos[0]

  return (
    <div className="flex flex-col gap-4">
      <Button type="button" variant="ghost" className="w-fit" onClick={onBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад к фотоархиву
      </Button>
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle>{folder.sourceLabel}</CardTitle>
              <CardDescription>
                {folderPhotoCount(folder)} фото ·{" "}
                {formatPhotoDateTime(folder.occurredAt)}
              </CardDescription>
            </div>
            <Badge variant="secondary">Общие фотографии</Badge>
          </div>
        </CardHeader>
        <CardContent className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_18rem]">
          <div className="flex min-w-0 flex-col gap-3">
            <PhotoCarousel
              photos={folder.photos}
              item={item}
              loading={previewLoading && folder.photos.length === 0}
              photoCount={folderPhotoCount(folder)}
              showPhotoCount
              className="h-[55svh] min-h-80 rounded-lg border bg-muted"
              fit="contain"
              controlsVisibility="always"
              activeIndex={visibleActiveIndex}
              onActiveIndexChange={setActiveIndex}
              onRequestFullscreen={onRequestFullscreen}
            />
            {folder.photos.length > 1 ? (
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-5 lg:grid-cols-7">
                {folder.photos.map((photo, index) => (
                  <button
                    key={photo.id}
                    type="button"
                    aria-label={`Показать ${photo.fileName}`}
                    aria-pressed={visibleActiveIndex === index}
                    className={cn(
                      "aspect-square overflow-hidden rounded-md border bg-muted",
                      visibleActiveIndex === index &&
                        "ring-2 ring-primary ring-offset-2"
                    )}
                    onClick={() => setActiveIndex(index)}
                  >
                    <img
                      src={photo.variants?.small?.url ?? photo.url}
                      alt={photo.fileName}
                      loading="lazy"
                      className="size-full object-cover"
                    />
                  </button>
                ))}
              </div>
            ) : null}
          </div>
          <aside className="flex min-w-0 flex-col gap-3">
            <Card size="sm">
              <CardHeader>
                <CardTitle>Информация</CardTitle>
              </CardHeader>
              <CardContent>
                <dl className="grid grid-cols-[5rem_minmax(0,1fr)] gap-2 text-sm">
                  <dt className="text-muted-foreground">Объект</dt>
                  <dd>{item.number}</dd>
                  <dt className="text-muted-foreground">Дата</dt>
                  <dd>{formatPhotoDateTime(folder.occurredAt)}</dd>
                  <dt className="text-muted-foreground">Автор</dt>
                  <dd>{folder.actorLabel}</dd>
                  <dt className="text-muted-foreground">Этап</dt>
                  <dd>Общие фотографии</dd>
                  <dt className="text-muted-foreground">Файл</dt>
                  <dd className="truncate">{activePhoto?.fileName ?? "—"}</dd>
                </dl>
              </CardContent>
            </Card>
            <Card size="sm">
              <CardHeader>
                <CardTitle>Файлы media-service</CardTitle>
                <CardDescription>
                  Обработка и действия для каждой фотографии.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                {folder.assets.length > 0 ? (
                  folder.assets.map((asset) => (
                    <div
                      key={asset.id}
                      className="flex min-w-0 flex-col gap-2 rounded-md border p-2"
                    >
                      <div className="flex min-w-0 items-center justify-between gap-2">
                        <span className="truncate text-xs font-medium">
                          {asset.fileName}
                        </span>
                        <Badge
                          variant={
                            asset.status === "READY" ? "default" : "secondary"
                          }
                        >
                          {mediaStatusLabel[asset.status]}
                        </Badge>
                      </div>
                      {canEdit && asset.status === "READY" ? (
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          disabled={rotating}
                          onClick={() => onRotate(asset)}
                        >
                          <HugeiconsIcon
                            icon={RotateClockwiseIcon}
                            data-icon="inline-start"
                          />
                          Повернуть
                        </Button>
                      ) : null}
                    </div>
                  ))
                ) : (
                  <p className="text-sm text-muted-foreground">
                    Импортированные фотографии старой панели.
                  </p>
                )}
              </CardContent>
            </Card>
          </aside>
        </CardContent>
      </Card>
    </div>
  )
}

export function RentalItemPhotosRegister({
  item,
  folders,
  assets,
  loading,
  error,
  canEdit,
  rotating,
  onAdd,
  onRotate,
  onOpenFolder,
  onRequestFullscreen,
}: {
  item: RentalItemDto
  folders: RentalItemPhotoFolder[]
  assets: readonly MediaAsset[]
  loading: boolean
  error: unknown
  canEdit: boolean
  rotating: boolean
  onAdd: () => void
  onRotate: (asset: MediaAsset) => void
  onOpenFolder: (folderId: string) => Promise<unknown>
  onRequestFullscreen: (photo: PhotoCarouselPhoto) => void | Promise<void>
}) {
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [view, setView] = useState<RegisterView>("gallery")
  const [gridSettingsOpen, setGridSettingsOpen] = useState(false)
  const [savedGridSize, setSavedGridSize] = useState<number | null>(() =>
    readPhotoGridSizePreference()
  )
  const [query, setQuery] = useState("")
  const [dateFrom, setDateFrom] = useState("")
  const [dateTo, setDateTo] = useState("")
  const [selectedSources, setSelectedSources] = useState<string[]>([])
  const [selectedActors, setSelectedActors] = useState<string[]>([])
  const viewport = useRentalItemsGridViewport()
  const gridFormatMax = getRentalItemsGridFormatMax(viewport)
  const effectiveGridFormat = getEffectiveRentalItemsGridFormat(
    savedGridSize,
    viewport
  )
  const gridSettingsAvailable =
    !isRentalItemsMobileViewport(viewport) && gridFormatMax > 1
  const selectedFolder =
    folders.find((folder) => folder.id === selectedFolderId) ?? null
  const sources = useMemo(
    () =>
      Array.from(new Set(folders.map((folder) => folder.sourceLabel))).sort(),
    [folders]
  )
  const actors = useMemo(
    () =>
      Array.from(new Set(folders.map((folder) => folder.actorLabel))).sort(),
    [folders]
  )
  const filteredFolders = useMemo(() => {
    const explicitlyFiltered = folders.filter((folder) => {
      const date = localDateKey(folder.occurredAt)
      const dateMatches =
        dateFrom && dateTo
          ? date !== null && date >= dateFrom && date <= dateTo
          : dateFrom
            ? date === dateFrom
            : dateTo
              ? date === dateTo
              : true
      return (
        dateMatches &&
        (selectedSources.length === 0 ||
          selectedSources.includes(folder.sourceLabel)) &&
        (selectedActors.length === 0 ||
          selectedActors.includes(folder.actorLabel))
      )
    })
    return searchPhotoFolders(explicitlyFiltered, query)
  }, [dateFrom, dateTo, folders, query, selectedActors, selectedSources])

  useEffect(() => {
    if (savedGridSize !== null) writePhotoGridSizePreference(savedGridSize)
  }, [savedGridSize])

  const columns: OperationsListGridColumn<RentalItemPhotoFolder>[] = [
    {
      id: "date",
      label: "Дата",
      render: (folder) => formatPhotoDateTime(folder.occurredAt),
      getSortValue: (folder) => folder.occurredAt,
    },
    {
      id: "event",
      label: "Событие",
      render: (folder) => folder.sourceLabel,
      getSortValue: (folder) => folder.sourceLabel,
    },
    {
      id: "stage",
      label: "Этап",
      render: () => "Общие фотографии",
      getSortValue: () => "Общие фотографии",
    },
    {
      id: "actor",
      label: "Автор",
      render: (folder) => folder.actorLabel,
      getSortValue: (folder) => folder.actorLabel,
    },
    {
      id: "count",
      label: "Фото",
      render: (folder) => (
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            setSelectedFolderId(folder.id)
            setPreviewLoading(true)
            void onOpenFolder(folder.id).finally(() => setPreviewLoading(false))
          }}
        >
          Открыть ({folderPhotoCount(folder)})
        </Button>
      ),
      getSortValue: folderPhotoCount,
    },
  ]

  function openFolder(folder: RentalItemPhotoFolder) {
    setSelectedFolderId(folder.id)
    setPreviewLoading(true)
    void onOpenFolder(folder.id).finally(() => setPreviewLoading(false))
  }

  if (selectedFolder) {
    return (
      <FolderDetails
        item={item}
        folder={selectedFolder}
        previewLoading={previewLoading}
        canEdit={canEdit}
        rotating={rotating}
        onBack={() => setSelectedFolderId(null)}
        onRotate={onRotate}
        onRequestFullscreen={onRequestFullscreen}
      />
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <RegisterControls
        query={query}
        onQueryChange={setQuery}
        dateFrom={dateFrom}
        onDateFromChange={setDateFrom}
        dateTo={dateTo}
        onDateToChange={setDateTo}
        sources={sources}
        selectedSources={selectedSources}
        onSourcesChange={setSelectedSources}
        actors={actors}
        selectedActors={selectedActors}
        onActorsChange={setSelectedActors}
        action={
          canEdit ? (
            <Button type="button" onClick={onAdd}>
              <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
              Добавить фото
            </Button>
          ) : undefined
        }
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
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          Сервис фото недоступен. Доступные данные бытовки продолжают
          отображаться.
        </p>
      ) : null}
      {loading ? (
        <div
          className="grid gap-3"
          style={{
            gridTemplateColumns: `repeat(${effectiveGridFormat.columns}, minmax(0, 1fr))`,
          }}
          aria-label="Загрузка фотоархива"
        >
          {Array.from({ length: effectiveGridFormat.columns }, (_, index) => (
            <Skeleton key={index} className="aspect-square rounded-lg" />
          ))}
        </div>
      ) : (
        <>
          {view === "table" ? (
            <OperationsListGrid
              className="hidden lg:block"
              items={filteredFolders}
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
            {filteredFolders.map((folder) => (
              <FolderCard
                key={folder.id}
                folder={folder}
                onOpen={() => openFolder(folder)}
              />
            ))}
          </div>
          {filteredFolders.length === 0 && !error ? (
            <p
              className={cn(
                "py-8 text-center text-sm text-muted-foreground",
                view === "table" && "lg:hidden"
              )}
            >
              Фотографии не найдены
            </p>
          ) : null}
        </>
      )}
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
      <span className="sr-only">
        {assets.length} файлов media-service в фотоархиве
      </span>
    </div>
  )
}
