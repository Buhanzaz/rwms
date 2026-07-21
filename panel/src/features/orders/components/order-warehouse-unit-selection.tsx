import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import type { SortingState } from "@tanstack/react-table"
import {
  Add01Icon,
  CheckmarkCircle02Icon,
  GridViewIcon,
  ListViewIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type { OrderUnitCandidate } from "@/features/orders/domain/orders"
import { orderUnitToRentalItem } from "@/features/orders/domain/orders"
import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"
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
  buildRentalItemsTableSchema,
  getRentalItemSortValue,
  normalizeRentalItemsColumnConfig,
  type RentalItemDto,
  type RentalItemsFiltersState,
  type RentalItemsViewMode,
} from "@/features/rental-items/model/rental-item"
import {
  loadRentalItemCoverPage,
  RENTAL_ITEM_COVERS_QUERY_KEY,
} from "@/features/rental-items/use-rental-item-covers"
import { useOrdersModule } from "@/features/orders/orders-module-context"

export function OrderWarehouseUnitSelection({
  accessToken,
  warehouseId,
  candidates,
  canEdit,
  pendingUnitIds,
  conflictingUnitIds,
  onAdd,
  onEditContents,
}: {
  accessToken: string
  warehouseId: string
  candidates: OrderUnitCandidate[]
  canEdit: boolean
  pendingUnitIds: ReadonlySet<string>
  conflictingUnitIds: ReadonlySet<string>
  onAdd: (candidate: OrderUnitCandidate) => void
  onEditContents: (candidate: OrderUnitCandidate) => void
}) {
  const { currentUser } = useOrdersModule()
  const [photoItemId, setPhotoItemId] = useState<string | null>(null)
  const [filters, setFilters] = useState<RentalItemsFiltersState>({})
  const [sorting, setSorting] = useState<SortingState>([])
  const [viewMode, setViewMode] = useState<RentalItemsViewMode>("grid")
  const [gridSettingsOpen, setGridSettingsOpen] = useState(false)
  const [savedGridSize, setSavedGridSize] = useState<number | null>(2)
  const viewport = useRentalItemsGridViewport()
  const gridFormatMax = getRentalItemsGridFormatMax(viewport)
  const effectiveGridFormat = getEffectiveRentalItemsGridFormat(
    savedGridSize,
    viewport
  )
  const gridFormatSelectionAvailable =
    !isRentalItemsMobileViewport(viewport) && gridFormatMax > 1
  const items = useMemo(
    () => candidates.map((candidate) => orderUnitToRentalItem(candidate.unit)),
    [candidates]
  )
  const tableSchema = useMemo(() => buildRentalItemsTableSchema(items), [items])
  const columnsConfig = useMemo(
    () => normalizeRentalItemsColumnConfig(tableSchema.columns, []),
    [tableSchema.columns]
  )
  const filterOptions = useMemo(
    () => buildRentalItemsFilterOptions(items, tableSchema, columnsConfig),
    [columnsConfig, items, tableSchema]
  )
  const effectiveFilters = useMemo(
    () => pruneRentalItemsFilters(filters, filterOptions),
    [filterOptions, filters]
  )
  // The backend has already limited this page to available units and units of
  // the current order. Shared warehouse filters only narrow that safe page.
  const filteredItems = useMemo(
    () => filterRentalItemsByFilters(items, effectiveFilters),
    [effectiveFilters, items]
  )
  const displayedItems = useMemo(() => {
    const activeSort = sorting[0]
    if (!activeSort) return filteredItems

    const direction = activeSort.desc ? -1 : 1
    return [...filteredItems].sort((left, right) => {
      const leftValue = getRentalItemSortValue(left, activeSort.id)
      const rightValue = getRentalItemSortValue(right, activeSort.id)
      const compared =
        typeof leftValue === "number" && typeof rightValue === "number"
          ? leftValue - rightValue
          : String(leftValue).localeCompare(String(rightValue), "ru", {
              numeric: true,
            })
      return compared * direction
    })
  }, [filteredItems, sorting])
  const candidateByUnitId = useMemo(
    () =>
      new Map(candidates.map((candidate) => [candidate.unit.id, candidate])),
    [candidates]
  )
  const unitIds = useMemo(() => items.map((item) => item.id), [items])
  const coversQuery = useQuery({
    queryKey: [
      ...RENTAL_ITEM_COVERS_QUERY_KEY,
      "order-selection",
      currentUser?.id ?? "unknown-user",
      warehouseId,
      unitIds,
    ],
    queryFn: () => loadRentalItemCoverPage(accessToken, warehouseId, unitIds),
    enabled: unitIds.length > 0,
  })
  const mediaCovers = useMemo(
    () =>
      new Map(
        (coversQuery.data?.items ?? []).map((projection) => [
          projection.cabinId,
          projection,
        ])
      ),
    [coversQuery.data?.items]
  )
  const photoItem = items.find((item) => item.id === photoItemId) ?? null

  function renderItemActions(item: RentalItemDto) {
    const candidate = candidateByUnitId.get(item.id)
    if (!candidate) return null

    const pending = pendingUnitIds.has(item.id)
    const conflicting = conflictingUnitIds.has(item.id)

    if (candidate.added) {
      return (
        <>
          <Button
            type="button"
            size="sm"
            variant="secondary"
            disabled
            data-order-unit-state="added"
          >
            <HugeiconsIcon
              icon={CheckmarkCircle02Icon}
              data-icon="inline-start"
            />
            Добавлено
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            disabled={!canEdit}
            aria-label={`Изменить наполнение ${item.number}`}
            onClick={() => onEditContents(candidate)}
          >
            <HugeiconsIcon icon={Add01Icon} />
          </Button>
        </>
      )
    }

    return (
      <Button
        type="button"
        size="sm"
        disabled={!canEdit || pending || conflicting}
        data-order-unit-state={conflicting ? "reserved" : "available"}
        onClick={() => onAdd(candidate)}
      >
        {pending ? (
          <HugeiconsIcon
            icon={Loading03Icon}
            data-icon="inline-start"
            className="animate-spin"
          />
        ) : (
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
        )}
        {pending ? "Добавление…" : conflicting ? "Уже занята" : "Добавить"}
      </Button>
    )
  }

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-3">
        {filterOptions.length > 0 ? (
          <RentalItemsFilters
            options={filterOptions}
            filters={effectiveFilters}
            onFiltersChange={setFilters}
          />
        ) : (
          <span />
        )}
        <div className="flex items-center gap-2">
          <ToggleGroup
            type="single"
            value={viewMode}
            onValueChange={(value) => {
              if (value === "table" || value === "grid") {
                setViewMode(value)
              }
            }}
            variant="outline"
            size="lg"
            spacing={2}
            aria-label="Вид бытовок заказа"
          >
            <ToggleGroupItem
              value="table"
              className="size-8 min-w-0 px-0"
              aria-label="Список"
            >
              <HugeiconsIcon icon={ListViewIcon} aria-hidden="true" />
            </ToggleGroupItem>
            <ToggleGroupItem
              value="grid"
              className="size-8 min-w-0 px-0"
              aria-label="Сетка"
            >
              <HugeiconsIcon icon={GridViewIcon} aria-hidden="true" />
            </ToggleGroupItem>
          </ToggleGroup>
          {viewMode === "grid" && gridFormatSelectionAvailable ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => setGridSettingsOpen(true)}
            >
              <HugeiconsIcon icon={GridViewIcon} data-icon="inline-start" />
              до {effectiveGridFormat.columns}x{effectiveGridFormat.rows}
            </Button>
          ) : null}
        </div>
      </div>
      {displayedItems.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Бытовки по выбранным фильтрам не найдены.
        </p>
      ) : (
        <div className="flex min-h-[24rem] flex-1">
          {viewMode === "table" ? (
            <RentalItemsTableView
              schema={tableSchema}
              items={displayedItems}
              sorting={sorting}
              columnsConfig={columnsConfig}
              onSortingChange={setSorting}
              onOpenPhotos={(item) => setPhotoItemId(item.id)}
              onOpenItem={() => undefined}
              mediaCovers={mediaCovers}
              renderItemActions={renderItemActions}
            />
          ) : (
            <RentalItemsGridView
              items={displayedItems}
              gridFormat={effectiveGridFormat}
              accessToken={accessToken}
              mediaCovers={mediaCovers}
              onOpenPhotos={(item) => setPhotoItemId(item.id)}
              onOpenItem={() => undefined}
              renderItemActions={renderItemActions}
            />
          )}
        </div>
      )}
      <RentalItemPhotoDialog
        item={photoItem}
        open={photoItem !== null}
        onOpenChange={(open) => {
          if (!open) setPhotoItemId(null)
        }}
      />
      {gridFormatSelectionAvailable ? (
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
    </>
  )
}
