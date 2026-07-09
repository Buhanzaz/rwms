import { useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import type { SortingState } from "@tanstack/react-table"
import { Filter, Grid2X2, List, Plus, Settings2 } from "lucide-react"
import { useNavigate } from "react-router-dom"

import {
  getRentalItemFilterOptions,
  getRentalItems,
  getRentalItemsTableSchema,
} from "@/features/rental-items/api/rental-items-api"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"
import { RentalItemsColumnSettingsDialog } from "@/features/rental-items/rental-items-column-settings-dialog"
import { RentalItemsFilters } from "@/features/rental-items/rental-items-filters"
import { RentalItemsGridView } from "@/features/rental-items/rental-items-grid-view"
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
    getStorageKey(warehouseId, "sorting"),
    []
  )
}

function getInitialColumnsConfig(warehouseId: string) {
  return readLocalStorage<RentalItemsColumnConfig[]>(
    getStorageKey(warehouseId, "columns"),
    []
  )
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

  const [photoItem, setPhotoItem] = useState<RentalItemDto | null>(null)
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false)
  const [columnsDialogOpen, setColumnsDialogOpen] = useState(false)

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
    writeLocalStorage(getStorageKey(warehouseId, "sorting"), sorting)
  }, [sorting, warehouseId])

  useEffect(() => {
    writeLocalStorage(getStorageKey(warehouseId, "columns"), savedColumnsConfig)
  }, [savedColumnsConfig, warehouseId])

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

  function openRentalItem(item: RentalItemDto) {
    navigate(`/warehouse/${item.id}`)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div className="flex min-w-0 items-center gap-3">
          <h2 className="truncate text-xl font-semibold">Бытовки</h2>

          <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {rentalItemsQuery.data?.totalElements ?? 0} шт.
          </span>

          {activeFiltersCount > 0 && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
              Фильтры: {activeFiltersCount}
            </span>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant={mobileFiltersOpen ? "secondary" : "outline"}
            className="h-10 lg:hidden"
            onClick={() => setMobileFiltersOpen((current) => !current)}
          >
            <Filter className="mr-2 size-4" />
            Фильтры
          </Button>

          <Button
            variant="outline"
            className="h-10"
            onClick={() => setColumnsDialogOpen(true)}
          >
            <Settings2 className="mr-2 size-4" />
            Столбцы
          </Button>

          <div className="flex h-10 items-center rounded-md border bg-card p-1">
            <Button
              variant={viewMode === "table" ? "secondary" : "ghost"}
              size="icon"
              className="size-8"
              onClick={() => setViewMode("table")}
            >
              <List className="size-4" />
            </Button>

            <Button
              variant={viewMode === "grid" ? "secondary" : "ghost"}
              size="icon"
              className="size-8"
              onClick={() => setViewMode("grid")}
            >
              <Grid2X2 className="size-4" />
            </Button>
          </div>

          <Button className="h-10">
            <Plus className="mr-2 size-4" />
            Добавить бытовку
          </Button>
        </div>
      </div>

      <Input
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="Поиск по номеру, арендатору или комментарию..."
        className="h-10 max-w-xl"
      />

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

      <RentalItemPhotoDialog
        item={photoItem}
        open={photoItem !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPhotoItem(null)
          }
        }}
      />
    </div>
  )
}
