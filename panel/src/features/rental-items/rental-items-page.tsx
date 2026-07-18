import { useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
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
import { useWarehouse } from "@/hooks/use-warehouse"
import { useAuth } from "@/features/auth/use-auth"
import {
  buildRentalItemsTableSchema,
  EMPTY_RENTAL_ITEMS_TABLE_SCHEMA,
  getRentalItemFieldLabel,
  getRentalItemSortValue,
  getVisibleRentalItemFilterDefinitions,
  normalizeRentalItemsColumnConfig,
  type RentalItemDto,
  type RentalItemsColumnConfig,
  type RentalItemsFilterOptionSet,
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

function pruneFilters(
  filters: RentalItemsFiltersState,
  filterOptions: RentalItemsFilterOptionSet[]
) {
  const enabledFilterIds = new Set(filterOptions.map((option) => option.id))

  return Object.fromEntries(
    Object.entries(filters).filter(([key, values]) => {
      return enabledFilterIds.has(key) && values && values.length > 0
    })
  ) as RentalItemsFiltersState
}

function filterRentalItems(
  items: RentalItemDto[],
  filters: RentalItemsFiltersState
) {
  return items.filter((item) =>
    Object.entries(filters).every(([key, values]) => {
      if (!values || values.length === 0) return true
      return values.includes(getRentalItemFieldLabel(item, key))
    })
  )
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

function buildFilterOptions(
  items: RentalItemDto[],
  schema: typeof EMPTY_RENTAL_ITEMS_TABLE_SCHEMA,
  columnsConfig: RentalItemsColumnConfig[]
): RentalItemsFilterOptionSet[] {
  return getVisibleRentalItemFilterDefinitions(schema, columnsConfig).map(
    (filter) => ({
      ...filter,
      values: Array.from(
        new Set(items.map((item) => getRentalItemFieldLabel(item, filter.id)))
      ).sort((left, right) => left.localeCompare(right, "ru")),
    })
  )
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

  const [search, setSearch] = useState(() => getInitialSearch(warehouseId))
  const [page, setPage] = useState(0)
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

  const rentalItemsQuery = useQuery({
    // Never place a bearer token in a query key: it is not an identity and may
    // be exposed by query-devtools. The authenticated subject still separates
    // cached warehouse data when a different user signs in during this session.
    queryKey: [
      "rental-items",
      currentUser?.id ?? "unknown-user",
      warehouseId,
      search,
      page,
    ],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page,
        size: 50,
        search,
      }),
    enabled: status === "authenticated" && Boolean(accessToken),
  })

  const loadedItems = rentalItemsQuery.data?.content ?? EMPTY_RENTAL_ITEMS
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
    () => buildFilterOptions(loadedItems, tableSchema, columnsConfig),
    [columnsConfig, loadedItems, tableSchema]
  )

  const effectiveFilters = useMemo(() => {
    return pruneFilters(filters, filterOptions)
  }, [filterOptions, filters])

  const sortBy = sorting[0]?.id
  const sortDirection = sorting[0]?.desc ? "desc" : "asc"
  const items = useMemo(
    () =>
      sortRentalItems(
        filterRentalItems(loadedItems, effectiveFilters),
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

  function openRentalItem(item: RentalItemDto) {
    navigate(`/warehouse/${item.id}`)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
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
                    setPage(0)
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

                <Button
                  size="sm"
                  className="min-w-0 flex-1 justify-center px-2 text-xs lg:flex-none lg:px-3 lg:text-sm"
                  disabled={status !== "authenticated" || !accessToken}
                  onClick={() => setCreateDialogOpen(true)}
                >
                  <Plus data-icon="inline-start" />
                  <span className="truncate">Добавить новую бытовку</span>
                </Button>
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
      ) : rentalItemsQuery.isError ? (
        <div
          role="alert"
          className="flex flex-1 items-center justify-center rounded-lg border bg-card p-4 text-sm text-destructive"
        >
          {rentalItemsQuery.error instanceof Error
            ? rentalItemsQuery.error.message
            : "Не удалось загрузить реестр бытовок."}
        </div>
      ) : viewMode === "table" ? (
        <RentalItemsTableView
          schema={tableSchema}
          items={items}
          sorting={sorting}
          columnsConfig={columnsConfig}
          onSortingChange={setSorting}
          onOpenPhotos={setPhotoItem}
          onOpenItem={openRentalItem}
        />
      ) : (
        <RentalItemsGridView
          items={items}
          gridFormat={effectiveGridFormat}
          onOpenPhotos={setPhotoItem}
          onOpenItem={openRentalItem}
        />
      )}

      {rentalItemsQuery.data ? (
        <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
          <span>
            {rentalItemsQuery.data.totalElements === 0
              ? "Бытовки не найдены"
              : `Показано ${loadedItems.length} из ${rentalItemsQuery.data.totalElements}`}
          </span>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={page === 0 || rentalItemsQuery.isFetching}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Назад
            </Button>
            <span>
              Страница {rentalItemsQuery.data.totalPages === 0 ? 0 : page + 1}
              {" из "}
              {rentalItemsQuery.data.totalPages}
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={
                page + 1 >= rentalItemsQuery.data.totalPages ||
                rentalItemsQuery.isFetching
              }
              onClick={() => setPage((current) => current + 1)}
            >
              Вперёд
            </Button>
          </div>
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

      <RentalItemCreateDialog
        open={createDialogOpen}
        warehouseId={warehouseId}
        onOpenChange={setCreateDialogOpen}
      />
    </div>
  )
}
