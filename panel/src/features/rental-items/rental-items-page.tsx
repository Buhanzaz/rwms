import { useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import type { SortingState } from "@tanstack/react-table"
import { Filter, Grid2X2, List, Plus, Settings2 } from "lucide-react"
import { useNavigate, useSearchParams } from "react-router-dom"

import {
  getRentalItemFilterOptions,
  getRentalItems,
  getRentalItemsTableSchema,
} from "@/features/rental-items/api/rental-items-api"
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
import {
  EMPTY_RENTAL_ITEMS_TABLE_SCHEMA,
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

const EMPTY_FILTER_OPTIONS: RentalItemsFilterOptionSet[] = []
const DESKTOP_GRID_FORMAT_MAX = 5

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

function getColumnsConfigKey(columnsConfig: RentalItemsColumnConfig[]) {
  return columnsConfig
    .map((column) => `${column.id}:${column.visible ? "1" : "0"}`)
    .join("|")
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

  const tableSchemaQuery = useQuery({
    queryKey: ["rental-items-table-schema", warehouseId],
    queryFn: () => getRentalItemsTableSchema(warehouseId),
  })

  const tableSchema = tableSchemaQuery.data ?? EMPTY_RENTAL_ITEMS_TABLE_SCHEMA

  const columnsConfig = useMemo(() => {
    return normalizeRentalItemsColumnConfig(
      tableSchema.columns,
      savedColumnsConfig
    )
  }, [savedColumnsConfig, tableSchema.columns])

  const columnsConfigKey = useMemo(() => {
    return getColumnsConfigKey(columnsConfig)
  }, [columnsConfig])

  const filterOptionsQuery = useQuery({
    queryKey: ["rental-item-filter-options", warehouseId, columnsConfigKey],
    queryFn: () =>
      getRentalItemFilterOptions({
        warehouseId,
        columnsConfig,
      }),
  })

  const filterOptions = filterOptionsQuery.data ?? EMPTY_FILTER_OPTIONS

  const effectiveFilters = useMemo(() => {
    return pruneFilters(filters, filterOptions)
  }, [filterOptions, filters])

  const sortBy = sorting[0]?.id
  const sortDirection = sorting[0]?.desc ? "desc" : "asc"

  const rentalItemsQuery = useQuery({
    queryKey: [
      "rental-items",
      warehouseId,
      search,
      effectiveFilters,
      sortBy,
      sortDirection,
    ],
    queryFn: () =>
      getRentalItems({
        warehouseId,
        page: 0,
        size: 1000,
        search,
        filters: effectiveFilters,
        sortBy,
        sortDirection,
      }),
  })

  const items = rentalItemsQuery.data?.content ?? []

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
                  onChange={(event) => setSearch(event.target.value)}
                  aria-label="Поиск по реестру склада"
                  name="rental-items-search"
                  autoComplete="off"
                  placeholder="Поиск по номеру, арендатору или комментарию…"
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

      {rentalItemsQuery.isLoading ? (
        <div className="flex flex-1 items-center justify-center rounded-lg border bg-card text-sm text-muted-foreground">
          Загрузка бытовок...
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
