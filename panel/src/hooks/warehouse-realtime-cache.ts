import type { InfiniteData, QueryClient, QueryKey } from "@tanstack/react-query"

import type { CabinCoverProjection } from "@/features/media/media-service"
import type {
  PageResponse,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type RentalItemsInfiniteData = InfiniteData<PageResponse<RentalItemDto>, number>

type CoverPageData = Readonly<{
  items: readonly CabinCoverProjection[]
}>

type RentalItemCachePatch = Readonly<{
  queryClient: QueryClient
  subject: string
  warehouseId: string
  item: RentalItemDto
  changeType?: string
}>

const RENTAL_ITEMS_KEY = "rental-items"
const RENTAL_ITEM_DETAIL_KEY = "asset-rental-item"
const LEGACY_RENTAL_ITEM_DETAIL_KEY = "rental-item"
const COVERS_KEY = "rental-item-media-covers"

/**
 * Applies a single asset projection to every cached warehouse list that is
 * currently known to this browser. This deliberately does not invalidate the
 * whole list: status/passport/comment changes replace one row in-place, while
 * a create or warehouse move adjusts only the affected page metadata.
 */
export function patchRentalItemCaches({
  queryClient,
  subject,
  warehouseId,
  item,
  changeType,
}: RentalItemCachePatch) {
  const queries = queryClient.getQueryCache().findAll({
    queryKey: [RENTAL_ITEMS_KEY, subject, warehouseId],
  })

  for (const query of queries) {
    const search = readSearch(query.queryKey)
    queryClient.setQueryData<RentalItemsInfiniteData | undefined>(
      query.queryKey,
      (data) =>
        patchRentalItemsData(data, {
          item,
          warehouseId,
          search,
          changeType,
        })
    )
  }

  // Detail pages use a separate cache key. Keep an already-open detail view in
  // sync as well, including the null state after a warehouse move away from
  // the currently selected warehouse.
  const detailQueries = queryClient.getQueryCache().findAll({
    queryKey: [RENTAL_ITEM_DETAIL_KEY, warehouseId, item.id],
  })
  for (const query of detailQueries) {
    queryClient.setQueryData(query.queryKey, () =>
      item.warehouseId === warehouseId ? item : null
    )
  }
  queryClient.setQueriesData(
    { queryKey: [LEGACY_RENTAL_ITEM_DETAIL_KEY, item.id] },
    item.warehouseId === warehouseId ? item : null
  )
}

/**
 * Replaces one cabin cover projection in all cover queries that contain the
 * cabin. The query key stores the requested cabin-id batch at index three;
 * untouched cabins and their image leases remain intact.
 */
export function patchCabinCoverCaches(
  queryClient: QueryClient,
  warehouseId: string,
  cabinId: string,
  projection: CabinCoverProjection | null
) {
  const queries = queryClient.getQueryCache().findAll({
    queryKey: [COVERS_KEY],
  })

  for (const query of queries) {
    if (query.queryKey[2] !== warehouseId) continue
    const requestedCabinIds = readCabinIds(query.queryKey)
    if (!requestedCabinIds.includes(cabinId)) continue
    queryClient.setQueryData<CoverPageData | undefined>(
      query.queryKey,
      (data) => {
        if (!data) return data
        const index = data.items.findIndex((item) => item.cabinId === cabinId)
        if (index < 0) {
          return projection
            ? { ...data, items: [...data.items, projection] }
            : data
        }
        const items = [...data.items]
        if (projection) items[index] = projection
        else items.splice(index, 1)
        return { ...data, items }
      }
    )
  }
}

export function invalidateCabinMediaCaches(
  queryClient: QueryClient,
  warehouseId: string,
  cabinId: string,
  subject?: string
) {
  void queryClient.invalidateQueries({
    queryKey: ["rental-item-media", warehouseId, cabinId],
    refetchType: "active",
  })
  void queryClient.invalidateQueries({
    queryKey: ["service-owner-media", warehouseId, `CABIN:${cabinId}`],
    refetchType: "active",
  })
  if (subject) {
    void queryClient.invalidateQueries({
      queryKey: [COVERS_KEY, subject, warehouseId],
      refetchType: "none",
    })
  }
}

export function invalidateWarehouseRentalCaches(
  queryClient: QueryClient,
  subject: string,
  warehouseId: string
) {
  void queryClient.invalidateQueries({
    queryKey: [RENTAL_ITEMS_KEY, subject, warehouseId],
  })
  void queryClient.invalidateQueries({
    queryKey: [COVERS_KEY, subject, warehouseId],
  })
}

export function matchesRentalItemSearch(item: RentalItemDto, search: string) {
  const needle = search.trim().toLocaleUpperCase("ru-RU")
  if (!needle) return true
  return [
    item.number,
    item.type,
    item.dimensions,
    item.finishing,
    item.category,
    ...item.characteristics.map((characteristic) => characteristic.name),
  ].some(
    (value) =>
      typeof value === "string" &&
      value.toLocaleUpperCase("ru-RU").includes(needle)
  )
}

function patchRentalItemsData(
  data: RentalItemsInfiniteData | undefined,
  input: {
    item: RentalItemDto
    warehouseId: string
    search: string
    changeType?: string
  }
): RentalItemsInfiniteData | undefined {
  if (!data || data.pages.length === 0) return data

  const belongsToWarehouse = input.item.warehouseId === input.warehouseId
  const shouldBeVisible =
    belongsToWarehouse && matchesRentalItemSearch(input.item, input.search)
  const isStructuralChange =
    input.changeType?.includes("created") === true ||
    input.changeType?.includes("warehouse-changed") === true ||
    input.item.version === 0 ||
    !belongsToWarehouse
  const pageSize = data.pages[0]?.size || 200
  const flattened = data.pages.flatMap((page) => page.content)
  const existingIndex = flattened.findIndex(
    (entry) => entry.id === input.item.id
  )
  const currentTotal = data.pages[0]?.totalElements ?? flattened.length
  let nextTotal = currentTotal
  let nextItems = flattened

  if (existingIndex >= 0) {
    if (shouldBeVisible) {
      const current = flattened[existingIndex]
      if (!current || input.item.version >= current.version) {
        nextItems = flattened.with(existingIndex, input.item)
      }
    } else {
      nextItems = flattened.toSpliced(existingIndex, 1)
      nextTotal = Math.max(0, currentTotal - 1)
    }
  } else if (shouldBeVisible && isStructuralChange) {
    nextTotal = currentTotal + 1
    nextItems = insertByNumber(flattened, input.item)
  } else if (!belongsToWarehouse && isStructuralChange) {
    // A warehouse move is delivered to both the old and the new warehouse.
    // The old list may not have the row loaded (for example, on page two), but
    // its total still has to move immediately.
    nextTotal = Math.max(0, currentTotal - 1)
  }

  if (nextItems === flattened && nextTotal === currentTotal) return data

  const totalPages = nextTotal === 0 ? 0 : Math.ceil(nextTotal / pageSize)
  const pages = data.pages.map((page, pageIndex) => ({
    ...page,
    content: nextItems.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize),
    totalElements: nextTotal,
    totalPages,
  }))
  return { ...data, pages }
}

function insertByNumber(items: readonly RentalItemDto[], item: RentalItemDto) {
  const next = [...items]
  const index = next.findIndex(
    (entry) =>
      entry.number.localeCompare(item.number, "ru", { numeric: true }) > 0
  )
  next.splice(index < 0 ? next.length : index, 0, item)
  return next
}

function readSearch(queryKey: QueryKey) {
  const value = queryKey[3]
  return typeof value === "string" ? value : ""
}

function readCabinIds(queryKey: QueryKey): readonly string[] {
  const value = queryKey[3]
  // Covers keys are [prefix, subject, warehouseId, ids]. Keep the guard
  // tolerant of old cache entries that used a different subject slot.
  if (
    Array.isArray(value) &&
    value.every((entry) => typeof entry === "string")
  ) {
    return value
  }
  const legacyValue = queryKey[4]
  return Array.isArray(legacyValue) &&
    legacyValue.every((entry) => typeof entry === "string")
    ? legacyValue
    : []
}
