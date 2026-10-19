import {
  useCallback,
  useMemo,
  useState,
  type ReactNode,
  type UIEvent,
} from "react"
import { useQuery } from "@tanstack/react-query"
import { Grid2X2 } from "lucide-react"
import { useNavigate } from "react-router-dom"

import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import type { CabinCoverProjection } from "@/features/media/media-service"
import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"
import {
  filterRentalItemsByFilters,
  pruneRentalItemsFilters,
} from "@/features/rental-items/rental-items-filtering"
import { RentalItemsFilters } from "@/features/rental-items/rental-items-filters"
import { RentalItemsGridSettingsDialog } from "@/features/rental-items/rental-items-grid-settings-dialog"
import { RentalItemsGridView } from "@/features/rental-items/rental-items-grid-view"
import {
  getEffectiveRentalItemsGridFormat,
  getRentalItemsDefaultGridSize,
  getRentalItemsGridFormatMax,
  normalizeRentalItemsGridSize,
  useRentalItemsGridViewport,
} from "@/features/rental-items/rental-items-grid-format"
import {
  getRentalItemFieldLabels,
  type RentalItemDto,
  type RentalItemsFilterOptionSet,
  type RentalItemsFiltersState,
} from "@/features/rental-items/model/rental-item"
import {
  getCoverFirstCabinPreviews,
  loadRentalItemCoverPage,
  RENTAL_ITEM_COVERS_QUERY_KEY,
} from "@/features/rental-items/use-rental-item-covers"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { formatMonthlyRentalPrice } from "@/features/assistant/api/rental-pricing-api"
import { useCabinRentalPrices } from "@/features/assistant/use-cabin-rental-prices"

const BOOKING_QUERY_CACHE_TIME_MS = 2 * 60 * 60 * 1_000
const COVER_BATCH_SIZE = 200
const LOAD_MORE_SCROLL_THRESHOLD = 160
const EMPTY_IDS: ReadonlySet<string> = new Set()

const BOOKING_FILTERS = [
  { id: "number", label: "Номер", dataType: "text" },
  { id: "type", label: "Тип", dataType: "text" },
  { id: "dimensions", label: "Габариты", dataType: "text" },
  { id: "finishing", label: "Отделка", dataType: "text" },
  { id: "category", label: "Категория", dataType: "text" },
  { id: "characteristics", label: "Характеристики", dataType: "text" },
  { id: "linoleum", label: "Линолеум", dataType: "boolean" },
] as const

function getGridStorageKey(warehouseId: string) {
  return `rental-booking:${warehouseId}:grid-format:v1`
}

function readInitialGridSize(warehouseId: string) {
  try {
    return normalizeRentalItemsGridSize(
      JSON.parse(
        window.localStorage.getItem(getGridStorageKey(warehouseId)) ?? "null"
      ),
      5
    )
  } catch {
    return null
  }
}

function buildFilterOptions(items: RentalItemDto[]) {
  return BOOKING_FILTERS.map<RentalItemsFilterOptionSet>((definition) => ({
    ...definition,
    values: Array.from(
      new Set(
        items.flatMap((item) => getRentalItemFieldLabels(item, definition.id))
      )
    ).sort((left, right) => left.localeCompare(right, "ru")),
  }))
}

function splitIds(ids: readonly string[]) {
  const batches: string[][] = []
  for (let index = 0; index < ids.length; index += COVER_BATCH_SIZE) {
    batches.push(ids.slice(index, index + COVER_BATCH_SIZE))
  }
  return batches
}

async function loadCoverPages(
  accessToken: string,
  warehouseId: string,
  ids: readonly string[]
) {
  const pages = await Promise.all(
    splitIds(ids).map((batch) =>
      loadRentalItemCoverPage(accessToken, warehouseId, batch)
    )
  )
  return { items: pages.flatMap((page) => page.items) }
}

function toBookingCardCover(projection: CabinCoverProjection) {
  return {
    ...projection,
    previews: getCoverFirstCabinPreviews(projection).slice(0, 1),
  }
}

export function BookingCabinBrowser({
  accessToken,
  subjectId,
  warehouseId,
  items,
  selectedIds,
  stagedIds = EMPTY_IDS,
  search,
  onSearchChange,
  onToggle,
  onRemoveStaged,
  actions,
  emptyText = "Свободные бытовки не найдены.",
  footer,
  onReachEnd,
  compact = false,
}: {
  accessToken: string
  subjectId: string
  warehouseId: string
  items: RentalItemDto[]
  selectedIds: ReadonlySet<string>
  stagedIds?: ReadonlySet<string>
  search: string
  onSearchChange: (value: string) => void
  onToggle: (item: RentalItemDto) => void
  onRemoveStaged?: (item: RentalItemDto) => void
  actions?: ReactNode
  emptyText?: string
  footer?: ReactNode
  onReachEnd?: () => void
  compact?: boolean
}) {
  const navigate = useNavigate()
  const viewport = useRentalItemsGridViewport()
  const [filters, setFilters] = useState<RentalItemsFiltersState>({})
  const [photoItem, setPhotoItem] = useState<RentalItemDto | null>(null)
  const [gridSettingsOpen, setGridSettingsOpen] = useState(false)
  const [savedGridSize, setSavedGridSize] = useState<number | null>(() =>
    readInitialGridSize(warehouseId)
  )

  const filterOptions = useMemo(() => buildFilterOptions(items), [items])
  const effectiveFilters = useMemo(
    () => pruneRentalItemsFilters(filters, filterOptions),
    [filterOptions, filters]
  )
  const normalizedSearch = search.trim().toLocaleLowerCase("ru")
  const filteredItems = useMemo(
    () =>
      filterRentalItemsByFilters(items, effectiveFilters).filter((item) =>
        normalizedSearch
          ? item.number.toLocaleLowerCase("ru").includes(normalizedSearch)
          : true
      ),
    [effectiveFilters, items, normalizedSearch]
  )

  const itemIds = useMemo(() => items.map((item) => item.id), [items])
  const prices = useCabinRentalPrices({
    accessToken,
    subjectId,
    warehouseId,
    rentalItemIds: itemIds,
  })
  const coverQuery = useQuery({
    queryKey: [
      ...RENTAL_ITEM_COVERS_QUERY_KEY,
      subjectId,
      warehouseId,
      itemIds,
    ],
    queryFn: () => loadCoverPages(accessToken, warehouseId, itemIds),
    enabled: itemIds.length > 0,
    retry: false,
    staleTime: Infinity,
    gcTime: BOOKING_QUERY_CACHE_TIME_MS,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })
  const mediaCovers = useMemo(
    () =>
      new Map(
        (coverQuery.data?.items ?? []).map((projection) => {
          const cardCover = toBookingCardCover(projection)
          return [cardCover.cabinId, cardCover] as const
        })
      ) as ReadonlyMap<string, CabinCoverProjection>,
    [coverQuery.data?.items]
  )
  const coverAvailability = coverQuery.data
    ? "available"
    : coverQuery.isError
      ? "unavailable"
      : "loading"

  const gridFormatMax = getRentalItemsGridFormatMax(viewport)
  const gridFormat = getEffectiveRentalItemsGridFormat(savedGridSize, viewport)

  const changeGridSize = useCallback(
    (value: number) => {
      const normalized = normalizeRentalItemsGridSize(value, gridFormatMax)
      setSavedGridSize(normalized)
      if (normalized !== null) {
        window.localStorage.setItem(
          getGridStorageKey(warehouseId),
          JSON.stringify(normalized)
        )
      }
    },
    [gridFormatMax, warehouseId]
  )

  function handleScroll(event: UIEvent<HTMLDivElement>) {
    if (!onReachEnd) return
    const target = event.target
    if (
      !(target instanceof HTMLElement) ||
      !target.matches("[data-grid-format]")
    ) {
      return
    }
    const distanceToBottom =
      target.scrollHeight - target.scrollTop - target.clientHeight
    if (distanceToBottom <= LOAD_MORE_SCROLL_THRESHOLD) onReachEnd()
  }

  return (
    <div
      className={
        compact
          ? "flex min-h-0 flex-col gap-4"
          : "flex h-full min-h-0 flex-col gap-4"
      }
      onScrollCapture={handleScroll}
    >
      <div className="flex shrink-0 flex-col gap-3">
        <PageToolbar>
          <PageToolbarContent className="max-w-xl">
            <Input
              value={search}
              name="booking-cabin-number"
              autoComplete="off"
              aria-label="Поиск по номеру бытовки"
              placeholder="Поиск по номеру бытовки…"
              onChange={(event) => onSearchChange(event.target.value)}
            />
          </PageToolbarContent>
          <PageToolbarActions>
            {gridFormatMax > 1 ? (
              <Button
                type="button"
                variant="outline"
                onClick={() => setGridSettingsOpen(true)}
              >
                <Grid2X2 data-icon="inline-start" />
                до {gridFormat.columns}x{gridFormat.rows}
              </Button>
            ) : null}
            {actions}
          </PageToolbarActions>
        </PageToolbar>

        <RentalItemsFilters
          options={filterOptions}
          filters={effectiveFilters}
          onFiltersChange={setFilters}
        />
        {prices.error && (
          <Alert variant="destructive">
            <AlertTitle>Не удалось загрузить часть цен аренды</AlertTitle>
            <AlertDescription>
              {prices.error.message}
              <Button
                variant="outline"
                disabled={prices.isFetching}
                onClick={prices.retry}
              >
                Повторить загрузку цен
              </Button>
            </AlertDescription>
          </Alert>
        )}
      </div>

      {filteredItems.length === 0 ? (
        <div className="flex min-h-48 flex-1 items-center justify-center rounded-lg border bg-card p-4 text-sm text-muted-foreground">
          {emptyText}
        </div>
      ) : (
        <RentalItemsGridView
          items={filteredItems}
          gridFormat={gridFormat}
          autoHeight={compact}
          accessToken={accessToken}
          mediaCovers={mediaCovers}
          coverAvailability={coverAvailability}
          onOpenPhotos={setPhotoItem}
          onOpenItem={(item) =>
            navigate(`/warehouse/${item.id}`, workspaceEntryNavigationOptions)
          }
          renderPhotoOverlay={(item) => {
            const checked = selectedIds.has(item.id)
            const staged = stagedIds.has(item.id)
            const checkboxId = `booking-cabin-${item.id}`
            const price = prices.pricesById.get(item.id)
            return (
              <>
                <Badge
                  variant="secondary"
                  className="absolute bottom-2 left-2 max-w-[calc(100%-1rem)] whitespace-normal"
                  aria-label={`Цена аренды бытовки ${item.number}`}
                >
                  {price
                    ? formatMonthlyRentalPrice(price.monthlyPriceRubles)
                    : prices.failedIds.has(item.id)
                      ? "Цена недоступна"
                      : "Загружаем цену…"}
                </Badge>
                <div className="absolute top-2 right-2 z-10 flex items-center gap-1">
                  {staged ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      className="h-8 bg-background/95 shadow-sm backdrop-blur-sm"
                      aria-label={`Убрать добавленную бытовку ${item.number}`}
                      onClick={(event) => {
                        event.stopPropagation()
                        onRemoveStaged?.(item)
                      }}
                    >
                      Добавлена
                    </Button>
                  ) : null}
                  <label
                    htmlFor={checkboxId}
                    className="flex cursor-pointer items-center rounded-md border bg-background/95 p-2 shadow-sm backdrop-blur-sm"
                    onClick={(event) => event.stopPropagation()}
                  >
                    <Checkbox
                      id={checkboxId}
                      checked={checked}
                      disabled={staged}
                      aria-label={
                        staged
                          ? `Бытовка ${item.number} добавлена`
                          : `${checked ? "Снять выбор" : "Выбрать"} бытовки ${item.number}`
                      }
                      onCheckedChange={() => onToggle(item)}
                    />
                  </label>
                </div>
              </>
            )
          }}
        />
      )}

      {footer ? <div className="shrink-0">{footer}</div> : null}

      {gridFormatMax > 1 ? (
        <RentalItemsGridSettingsDialog
          open={gridSettingsOpen}
          value={gridFormat.columns}
          maxSize={gridFormatMax}
          defaultValue={getRentalItemsDefaultGridSize(viewport)}
          onOpenChange={setGridSettingsOpen}
          onValueChange={changeGridSize}
        />
      ) : null}

      <RentalItemPhotoDialog
        item={photoItem}
        open={photoItem !== null}
        onOpenChange={(open) => {
          if (!open) setPhotoItem(null)
        }}
      />
    </div>
  )
}
