import { useCallback, useMemo, useState, type ReactNode } from "react"

import {
  BookingSelectionContext,
  type BookingSelectionContextValue,
} from "@/features/booking/booking-selection-context"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"

export function BookingSelectionProvider({
  children,
}: {
  children: ReactNode
}) {
  const { selectedWarehouseId } = useWarehouse()
  return (
    <BookingSelectionScope
      key={selectedWarehouseId ?? "no-warehouse"}
      warehouseId={selectedWarehouseId}
    >
      {children}
    </BookingSelectionScope>
  )
}

function BookingSelectionScope({
  children,
  warehouseId,
}: {
  children: ReactNode
  warehouseId: string | null
}) {
  const [items, setItems] = useState<RentalItemDto[]>([])
  const selectedIds = useMemo(
    () => new Set(items.map((item) => item.id)),
    [items]
  )

  const select = useCallback(
    (item: RentalItemDto) => {
      if (warehouseId !== item.warehouseId) return

      setItems((current) => {
        const existingIndex = current.findIndex(
          (candidate) => candidate.id === item.id
        )
        if (existingIndex < 0) return [...current, item]
        if (current[existingIndex] === item) return current

        const next = [...current]
        next[existingIndex] = item
        return next
      })
    },
    [warehouseId]
  )

  const deselect = useCallback((rentalItemId: string) => {
    setItems((current) => current.filter((item) => item.id !== rentalItemId))
  }, [])

  const removeMany = useCallback((rentalItemIds: readonly string[]) => {
    const ids = new Set(rentalItemIds)
    if (ids.size === 0) return
    setItems((current) => current.filter((item) => !ids.has(item.id)))
  }, [])

  const toggle = useCallback(
    (item: RentalItemDto) => {
      if (selectedIds.has(item.id)) deselect(item.id)
      else select(item)
    },
    [deselect, select, selectedIds]
  )

  const clear = useCallback(() => setItems([]), [])
  const value = useMemo<BookingSelectionContextValue>(
    () => ({
      warehouseId,
      selectedItems: items,
      selectedIds,
      select,
      deselect,
      toggle,
      removeMany,
      clear,
    }),
    [
      clear,
      deselect,
      items,
      removeMany,
      select,
      selectedIds,
      toggle,
      warehouseId,
    ]
  )

  return (
    <BookingSelectionContext.Provider value={value}>
      {children}
    </BookingSelectionContext.Provider>
  )
}
