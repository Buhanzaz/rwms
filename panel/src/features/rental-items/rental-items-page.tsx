import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type UIEvent,
} from "react"
import { useInfiniteQuery, useQuery } from "@tanstack/react-query"
import type { CabinCoverProjection } from "@/features/media/media-service"
import type { SortingState } from "@tanstack/react-table"
import { Filter, Grid2X2, List, Plus, Settings2 } from "lucide-react"
import { useNavigate, useSearchParams } from "react-router-dom"

import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { RentalItemCreateDialog } from "@/features/rental-items/rental-item-create-dialog"
import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"
import { RentalItemsColumnSettingsDialog } from "@/features/rental-items/rental-items-column-settings-dialog"
import {
  buildRentalItemsFilterOptions,
  filterRentalItemsByFilters,
  pruneRentalItemsFilters,
} from "@/features/rental-items/rental-items-filtering"
import { RentalItemsFilters } from "@/features/rental-items/rental-items-filters"
import { RentalItemsGridView } from "@/features/rental-items/rental-items-grid-view"
import { RentalItemsGridSettingsDialog } from "@/features/rental-items/rental-items-grid-settings-dialog"
import {
  getEffectiveRentalItemsGridFormat,
  getRentalItemsDefaultGridSize,
  getRentalItemsGridFormatMax,
  isRentalItemsMobileViewport,
  normalizeRentalItemsGridSize,
  useRentalItemsGridViewport,
} from "@/features/rental-items/rental-items-grid-format"
import { RentalItemsTableView } from "@/features/rental-items/rental-items-table-view"
import {
  loadRentalItemCoverPage,
  RENTAL_ITEM_COVERS_QUERY_KEY,
} from "@/features/rental-items/use-rental-item-covers"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  buildRentalItemsTableSchema,
  getRentalItemSortValue,
  normalizeRentalItemsColumnConfig,
  type RentalItemDto,
  type RentalItemsColumnConfig,
  type RentalItemsFiltersState,
  type RentalItemsViewMode,
} from "@/features/rental-items/model/rental-item"

function readLocalStorage<T>(key: string, fallback: T): T {
  try {
    const value = window.localStorage.getItem(key)

    if (value === null) {
      return fallback
    }

    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

function writeLocalStorage<T>(key: string, value: T) {
  window.localStorage.setItem(key, JSON.stringify(value))
}

function getStorageKey(warehouseId: string, key: string) {
  return `rental-items:${warehouseId}:${key}`
}

const DESKTOP_GRID_FORMAT_MAX = 5
const RENTAL_ITEMS_PAGE_SIZE = 200
const RENTAL_ITEM_COVERS_BATCH_SIZE = 200
const LOAD_MORE_SCROLL_THRESHOLD = 160
const EMPTY_RENTAL_ITEMS: RentalItemDto[] = []

function getInitialSearch(warehouseId: string) {
  return readLocalStorage(getStorageKey(warehouseId, "search"), "")
}

function getInitialFilters(warehouseId: string) {
  return readLocalStorage<RentalItemsFiltersState>(
    getStorageKey(warehouseId, "filters"),
    {}
  )
}

function getInitialViewMode(warehouseId: string) {
  return readLocalStorage<RentalItemsViewMode>(
    getStorageKey(warehouseId, "view-mode"),
    "table"
  )
}

function getInitialSorting(warehouseId: string) {
  return readLocalStorage<SortingState>(
    getStorageKey(warehouseId, "sorting:v2"),
    []
  )
}

function getInitialColumnsConfig(warehouseId: string) {
  return readLocalStorage<RentalItemsColumnConfig[]>(
    getStorageKey(warehouseId, "columns:v2"),
    []
  )
}

function getInitialGridSize(warehouseId: string) {
  try {
    const value = window.localStorage.getItem(
      getStorageKey(warehouseId, "grid-format:v1")
    )

    if (value === null) {
      return null
    }

    return normalizeRentalItemsGridSize(
      JSON.parse(value),
      DESKTOP_GRID_FORMAT_MAX
    )
  } catch {
    return null
  }
}

function sortRentalItems(
  items: RentalItemDto[],
  sortBy: string | undefined,
  sortDirection: "asc" | "desc"
) {
  if (!sortBy) return items

  const direction = sortDirection === "desc" ? -1 : 1
  return [...items].sort((left, right) => {
    const leftValue = getRentalItemSortValue(left, sortBy)
    const rightValue = getRentalItemSortValue(right, sortBy)
    const compared =
      typeof leftValue === "number" && typeof rightValue === "number"
        ? leftValue - rightValue
        : String(leftValue).localeCompare(String(rightValue), "ru", {
            numeric: true,
          })
    return compared * direction
  })
}

function uniqueRentalItems(
  pages: readonly { content: RentalItemDto[] }[] | undefined
) {
  if (!pages) {
    return EMPTY_RENTAL_ITEMS
  }

  const ids = new Set<string>()
  const items: RentalItemDto[] = []

  for (const page of pages) {
    for (const item of page.content) {
      if (!ids.has(item.id)) {
        ids.add(item.id)
        items.push(item)
      }
    }
  }

  return items
}

function splitRentalItemIds(cabinIds: readonly string[]) {
  const batches: string[][] = []

  for (
    let index = 0;
    index < cabinIds.length;
    index += RENTAL_ITEM_COVERS_BATCH_SIZE
  ) {
    batches.push(cabinIds.slice(index, index + RENTAL_ITEM_COVERS_BATCH_SIZE))
  }

  return batches
}

async function loadRentalItemCoverPages(
  accessToken: string,
  warehouseId: string,
  cabinIds: readonly string[]
): Promise<{ items: readonly CabinCoverProjection[] }> {
  const pages = await Promise.all(
    splitRentalItemIds(cabinIds).map((ids) =>
      loadRentalItemCoverPage(accessToken, warehouseId, ids)
    )
  )

  return { items: pages.flatMap((page) => page.items) }
}

export function RentalItemsPage() {
  const { selectedWarehouse } = useWarehouse()

  if (selectedWarehouse === null) {
    return (
      <div className="h-full rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Склад не выбран.
      </div>
    )
  }

  return (
    <RentalItemsPageState
      key={selectedWarehouse.id}
      warehouseId={selectedWarehouse.id}
    />
  )
}

function RentalItemsPageState({ warehouseId }: { warehouseId: string }) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { accessToken, currentUser, status } = useAuth()
  const canEditRentalItems = hasWarehouseAccess(
    currentUser,
    warehouseId,
    "EDIT"
  )
  const canManageRentalItemContents = hasWarehouseAccess(
    currentUser,
    warehouseId,
    "MANAGE"
  )

  const [search, setSearch] = useState(() => getInitialSearch(warehouseId))
  const [filters, setFilters] = useState<RentalItemsFiltersState>(() =>
    getInitialFilters(warehouseId)
  )
  const [viewMode, setViewMode] = useState<RentalItemsViewMode>(() =>
    getInitialViewMode(warehouseId)
  )
  const [sorting, setSorting] = useState<SortingState>(() =>
    getInitialSorting(warehouseId)
  )
  const [savedColumnsConfig, setSavedColumnsConfig] = useState<
    RentalItemsColumnConfig[]
  >(() => getInitialColumnsConfig(warehouseId))
  const [savedGridSize, setSavedGridSize] = useState<number | null>(() =>
    getInitialGridSize(warehouseId)
  )
  const viewport = useRentalItemsGridViewport()

  const [photoItem, setPhotoItem] = useState<RentalItemDto | null>(null)
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false)
  const [columnsDialogOpen, setColumnsDialogOpen] = useState(false)
  const [gridSettingsDialogOpen, setGridSettingsDialogOpen] = useState(false)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  useEffect(() => {
    writeLocalStorage(getStorageKey(warehouseId, "search"), search)
  }, [search, warehouseId])

  useEffect(() => {
    writeLocalStorage(getStorageKey(warehouseId, "filters"), filters)
  }, [filters, warehouseId])

  useEffect(() => {
    writeLocalStorage(getStorageKey(warehouseId, "view-mode"), viewMode)
  }, [viewMode, warehouseId])

  useEffect(() => {
    writeLocalStorage(getStorageKey(warehouseId, "sorting:v2"), sorting)
  }, [sorting, warehouseId])

  useEffect(() => {
    writeLocalStorage(
      getStorageKey(warehouseId, "columns:v2"),
      savedColumnsConfig
    )
  }, [savedColumnsConfig, warehouseId])

  useEffect(() => {
    if (savedGridSize !== null) {
      writeLocalStorage(
        getStorageKey(warehouseId, "grid-format:v1"),
        savedGridSize
      )
    }
  }, [savedGridSize, warehouseId])

  const rentalItemsQuery = useInfiniteQuery({
    // Never place a bearer token in a query key: it is not an identity and may
    // be exposed by query-devtools. The authenticated subject still separates
    // cached warehouse data when a different user signs in during this session.
    queryKey: [
      "rental-items",
      currentUser?.id ?? "unknown-user",
      warehouseId,
      search,
    ],
    queryFn: ({ pageParam }) =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: pageParam,
        size: RENTAL_ITEMS_PAGE_SIZE,
        search,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled: status === "authenticated" && Boolean(accessToken),
  })

  const loadedItems = useMemo(
    () => uniqueRentalItems(rentalItemsQuery.data?.pages),
    [rentalItemsQuery.data?.pages]
  )
  const loadedItemIds = useMemo(
    () => loadedItems.map((item) => item.id),
    [loadedItems]
  )
  const coverQuery = useQuery({
    queryKey: [
      ...RENTAL_ITEM_COVERS_QUERY_KEY,
      currentUser?.id ?? "unknown-user",
      warehouseId,
      loadedItemIds,
    ],
    queryFn: () =>
      loadRentalItemCoverPages(accessToken!, warehouseId, loadedItemIds),
    retry: false,
    enabled:
      status === "authenticated" &&
      Boolean(accessToken) &&
      loadedItemIds.length > 0,
  })
  const mediaCovers = useMemo(
    () =>
      new Map(
        (coverQuery.data?.items ?? []).map((projection) => [
          projection.cabinId,
          projection,
        ])
      ),
    [coverQuery.data?.items]
  )
  const coverAvailability = coverQuery.data
    ? "available"
    : coverQuery.isError
      ? "unavailable"
      : "loading"
  const tableSchema = useMemo(
    () => buildRentalItemsTableSchema(loadedItems),
    [loadedItems]
  )

  const columnsConfig = useMemo(() => {
    return normalizeRentalItemsColumnConfig(
      tableSchema.columns,
      savedColumnsConfig
    )
  }, [savedColumnsConfig, tableSchema.columns])

  const filterOptions = useMemo(
    () =>
      buildRentalItemsFilterOptions(loadedItems, tableSchema, columnsConfig),
    [columnsConfig, loadedItems, tableSchema]
  )

  const effectiveFilters = useMemo(() => {
    return pruneRentalItemsFilters(filters, filterOptions)
  }, [filterOptions, filters])

  const sortBy = sorting[0]?.id
  const sortDirection = sorting[0]?.desc ? "desc" : "asc"
  const items = useMemo(
    () =>
      sortRentalItems(
        filterRentalItemsByFilters(loadedItems, effectiveFilters),
        sortBy,
        sortDirection
      ),
    [effectiveFilters, loadedItems, sortBy, sortDirection]
  )

  const activeFiltersCount = useMemo(() => {
    return Object.values(effectiveFilters).filter(
      (value) => value && value.length > 0
    ).length
  }, [effectiveFilters])
  const mobileMenuToggleEnabled = isRentalItemsMobileViewport(viewport)
  const mobileMenuHidden =
    mobileMenuToggleEnabled && searchParams.get("toolbar") === "collapsed"
  const gridFormatMax = getRentalItemsGridFormatMax(viewport)
  const effectiveGridFormat = getEffectiveRentalItemsGridFormat(
    savedGridSize,
    viewport
  )
  const gridFormatSelectionAvailable =
    !isRentalItemsMobileViewport(viewport) && gridFormatMax > 1
  const queryScopeKey = `${warehouseId}:${search}`
  const nextPageRequestScopeRef = useRef<string | null>(null)
  const hasLoadedPages = (rentalItemsQuery.data?.pages.length ?? 0) > 0
  const totalRentalItems =
    rentalItemsQuery.data?.pages[0]?.totalElements ?? 0

  useEffect(() => {
    nextPageRequestScopeRef.current = null
  }, [queryScopeKey])

  const loadNextPage = useCallback(() => {
    if (
      !rentalItemsQuery.hasNextPage ||
      rentalItemsQuery.isFetchingNextPage ||
      nextPageRequestScopeRef.current === queryScopeKey
    ) {
      return
    }

    nextPageRequestScopeRef.current = queryScopeKey
    void rentalItemsQuery
      .fetchNextPage()
      .catch(() => undefined)
      .finally(() => {
        if (nextPageRequestScopeRef.current === queryScopeKey) {
          nextPageRequestScopeRef.current = null
        }
      })
  }, [queryScopeKey, rentalItemsQuery])

  const handleRentalItemsScroll = useCallback(
    (event: UIEvent<HTMLDivElement>) => {
      const target = event.target

      if (!(target instanceof HTMLElement)) {
        return
      }

      const isRentalItemsList =
        target.matches("[data-grid-format]") ||
        target.closest("[data-slot='rental-items-table-grid']") !== null
      const distanceToBottom =
        target.scrollHeight - target.scrollTop - target.clientHeight

      if (
        isRentalItemsList &&
        target.scrollHeight > target.clientHeight &&
        distanceToBottom <= LOAD_MORE_SCROLL_THRESHOLD
      ) {
        loadNextPage()
      }
    },
    [loadNextPage]
  )

  function openRentalItem(item: RentalItemDto) {
    navigate(`/warehouse/${item.id}`)
  }

  return (
    <div
      className="flex h-full min-h-0 flex-col gap-4"
      onScrollCapture={handleRentalItemsScroll}
    >
      <div className="flex flex-col gap-4">
        {!mobileMenuToggleEnabled && activeFiltersCount > 0 ? (
          <div className="flex min-w-0 items-center gap-3">
            <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
              Фильтры: {activeFiltersCount}
            </span>
          </div>
        ) : null}

        {!mobileMenuHidden ? (
          <div className="flex flex-col gap-4">
            <PageToolbar>
              <PageToolbarContent className="max-w-xl">
                <Input
                  value={search}
                  onChange={(event) => {
                    setSearch(event.target.value)
                  }}
                  aria-label="Поиск по реестру склада"
                  name="rental-items-search"
                  autoComplete="off"
                  placeholder="Поиск по номеру бытовки…"
                />
              </PageToolbarContent>

              <PageToolbarActions className="w-full sm:w-auto">
                <Button
                  type="button"
                  size="icon-sm"
                  variant={mobileFiltersOpen ? "secondary" : "outline"}
                  className="lg:hidden"
                  aria-label="Фильтры"
                  aria-pressed={mobileFiltersOpen}
                  onClick={() => setMobileFiltersOpen((current) => !current)}
                >
                  <Filter aria-hidden="true" />
                </Button>

                <Button
                  type="button"
                  size="icon-sm"
                  variant="outline"
                  className="lg:hidden"
                  aria-label="Настроить столбцы"
                  onClick={() => setColumnsDialogOpen(true)}
                >
                  <Settings2 aria-hidden="true" />
                </Button>

                <ToggleGroup
                  type="single"
                  value={viewMode}
                  variant="outline"
                  size="lg"
                  spacing={2}
                  className="lg:hidden"
                  aria-label="Вид реестра"
                >
                  <ToggleGroupItem
                    value="table"
                    className="size-8 min-w-0 px-0"
                    aria-label="Список"
                    onClick={() => setViewMode("table")}
                  >
                    <List aria-hidden="true" />
                  </ToggleGroupItem>

                  <ToggleGroupItem
                    value="grid"
                    className="size-8 min-w-0 px-0"
                    aria-label="Сетка"
                    onClick={() => setViewMode("grid")}
                  >
                    <Grid2X2 aria-hidden="true" />
                  </ToggleGroupItem>
                </ToggleGroup>

                <Button
                  variant="outline"
                  className="hidden lg:inline-flex"
                  onClick={() => setColumnsDialogOpen(true)}
                >
                  <Settings2 data-icon="inline-start" />
                  Столбцы
                </Button>

                <ToggleGroup
                  type="single"
                  value={viewMode}
                  variant="outline"
                  size="lg"
                  spacing={2}
                  className="hidden lg:flex"
                  aria-label="Вид реестра"
                >
                  <ToggleGroupItem
                    value="table"
                    className="size-8 min-w-0 px-0"
                    aria-label="Список"
                    onClick={() => setViewMode("table")}
                  >
                    <List aria-hidden="true" />
                  </ToggleGroupItem>

                  <ToggleGroupItem
                    value="grid"
                    className="size-8 min-w-0 px-0"
                    aria-label="Сетка"
                    onClick={() => setViewMode("grid")}
                  >
                    <Grid2X2 aria-hidden="true" />
                  </ToggleGroupItem>
                </ToggleGroup>

                {viewMode === "grid" && gridFormatSelectionAvailable && (
                  <Button
                    variant="outline"
                    onClick={() => setGridSettingsDialogOpen(true)}
                  >
                    <Grid2X2 data-icon="inline-start" />
                    до {effectiveGridFormat.columns}x{effectiveGridFormat.rows}
                  </Button>
                )}

                {canEditRentalItems ? (
                  <Button
                    size="sm"
                    className="min-w-0 flex-1 justify-center px-2 text-xs lg:flex-none lg:px-3 lg:text-sm"
                    disabled={status !== "authenticated" || !accessToken}
                    onClick={() => setCreateDialogOpen(true)}
                  >
                    <Plus data-icon="inline-start" />
                    <span className="truncate">Добавить новую бытовку</span>
                  </Button>
                ) : null}
              </PageToolbarActions>
            </PageToolbar>

            {filterOptions.length > 0 && (
              <>
                <div className="hidden lg:block">
                  <RentalItemsFilters
                    options={filterOptions}
                    filters={filters}
                    onFiltersChange={setFilters}
                  />
                </div>

                {mobileFiltersOpen && (
                  <div className="rounded-lg border bg-card p-2 lg:hidden">
                    <RentalItemsFilters
                      options={filterOptions}
                      filters={filters}
                      onFiltersChange={setFilters}
                    />
                  </div>
                )}
              </>
            )}
          </div>
        ) : null}
      </div>

      {status !== "authenticated" || !accessToken ? (
        <div
          role="alert"
          className="flex flex-1 items-center justify-center rounded-lg border bg-card p-4 text-sm text-muted-foreground"
        >
          Для просмотра реестра бытовок требуется авторизация.
        </div>
      ) : rentalItemsQuery.isLoading ? (
        <div className="flex flex-1 items-center justify-center rounded-lg border bg-card text-sm text-muted-foreground">
          Загрузка бытовок...
        </div>
      ) : rentalItemsQuery.isError && !hasLoadedPages ? (
        <div
          role="alert"
          className="flex flex-1 items-center justify-center rounded-lg border bg-card p-4 text-sm text-destructive"
        >
          <div className="flex flex-wrap items-center justify-center gap-3">
            <span>
              {rentalItemsQuery.error instanceof Error
                ? rentalItemsQuery.error.message
                : "Не удалось загрузить реестр бытовок."}
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                void rentalItemsQuery.refetch()
              }}
            >
              Повторить
            </Button>
          </div>
        </div>
      ) : viewMode === "table" || items.length === 0 ? (
        <RentalItemsTableView
          schema={tableSchema}
          items={items}
          sorting={sorting}
          columnsConfig={columnsConfig}
          onSortingChange={setSorting}
          onOpenPhotos={setPhotoItem}
          onOpenItem={openRentalItem}
          canManageContents={canManageRentalItemContents}
          mediaCovers={mediaCovers}
          coverAvailability={coverAvailability}
        />
      ) : (
        <RentalItemsGridView
          items={items}
          gridFormat={effectiveGridFormat}
          onOpenPhotos={setPhotoItem}
          onOpenItem={openRentalItem}
          accessToken={accessToken}
          mediaCovers={mediaCovers}
          coverAvailability={coverAvailability}
        />
      )}

      {rentalItemsQuery.data ? (
        <div className="shrink-0 text-center text-sm text-muted-foreground">
          {totalRentalItems === 0
            ? "Бытовки не найдены"
            : `Загружено ${loadedItems.length} из ${totalRentalItems}`}
        </div>
      ) : null}

      {rentalItemsQuery.isFetchingNextPage ? (
        <div
          role="status"
          aria-live="polite"
          className="shrink-0 text-center text-sm text-muted-foreground"
        >
          Загрузка ещё бытовок...
        </div>
      ) : rentalItemsQuery.isFetchNextPageError ? (
        <div
          role="alert"
          className="flex shrink-0 flex-wrap items-center justify-center gap-3 text-sm text-destructive"
        >
          <span>Не удалось загрузить следующую страницу бытовок.</span>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={loadNextPage}
          >
            Повторить загрузку
          </Button>
        </div>
      ) : null}

      <RentalItemsColumnSettingsDialog
        open={columnsDialogOpen}
        columns={columnsConfig}
        onOpenChange={setColumnsDialogOpen}
        onColumnsChange={(nextColumns) =>
          setSavedColumnsConfig(
            normalizeRentalItemsColumnConfig(tableSchema.columns, nextColumns)
          )
        }
        onReset={() => setSavedColumnsConfig(tableSchema.columns)}
      />

      {gridFormatSelectionAvailable && (
        <RentalItemsGridSettingsDialog
          open={gridSettingsDialogOpen}
          value={effectiveGridFormat.columns}
          maxSize={gridFormatMax}
          defaultValue={getRentalItemsDefaultGridSize(viewport)}
          onOpenChange={setGridSettingsDialogOpen}
          onValueChange={(value) =>
            setSavedGridSize(normalizeRentalItemsGridSize(value, gridFormatMax))
          }
        />
      )}

      <RentalItemPhotoDialog
        item={photoItem}
        open={photoItem !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPhotoItem(null)
          }
        }}
      />

      {canEditRentalItems ? (
        <RentalItemCreateDialog
          open={createDialogOpen}
          warehouseId={warehouseId}
          onOpenChange={setCreateDialogOpen}
        />
      ) : null}
    </div>
  )
}
