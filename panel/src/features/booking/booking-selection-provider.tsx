import { useCallback, useMemo, useState, type ReactNode } from "react"

import type { ManualBookingDraftHold } from "@/features/booking/api/manual-booking-drafts-api"
import {
  BookingSelectionContext,
  type BookingSelectionContextValue,
} from "@/features/booking/booking-selection-context"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"

type BookingSelectionState = {
  draftId: string
  checkedItems: RentalItemDto[]
  stagedItems: RentalItemDto[]
  activeHold: ManualBookingDraftHold | null
}

function createInitialState(): BookingSelectionState {
  return {
    draftId: crypto.randomUUID(),
    checkedItems: [],
    stagedItems: [],
    activeHold: null,
  }
}

function replaceSnapshot(items: RentalItemDto[], snapshot: RentalItemDto) {
  const index = items.findIndex((item) => item.id === snapshot.id)
  if (index < 0 || items[index] === snapshot) return items
  const next = [...items]
  next[index] = snapshot
  return next
}

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
  const [state, setState] = useState(createInitialState)
  const checkedIds = useMemo(
    () => new Set(state.checkedItems.map((item) => item.id)),
    [state.checkedItems]
  )
  const stagedIds = useMemo(
    () => new Set(state.stagedItems.map((item) => item.id)),
    [state.stagedItems]
  )

  const toggleChecked = useCallback(
    (item: RentalItemDto) => {
      if (warehouseId !== item.warehouseId) return
      setState((current) => {
        if (current.stagedItems.some((candidate) => candidate.id === item.id)) {
          return current
        }
        const checked = current.checkedItems.some(
          (candidate) => candidate.id === item.id
        )
        return {
          ...current,
          checkedItems: checked
            ? current.checkedItems.filter(
                (candidate) => candidate.id !== item.id
              )
            : [...current.checkedItems, item],
        }
      })
    },
    [warehouseId]
  )

  const addCheckedToStaged = useCallback(() => {
    setState((current) => {
      if (current.checkedItems.length === 0) return current
      const stagedIds = new Set(current.stagedItems.map((item) => item.id))
      return {
        ...current,
        checkedItems: [],
        stagedItems: [
          ...current.stagedItems,
          ...current.checkedItems.filter((item) => !stagedIds.has(item.id)),
        ],
      }
    })
  }, [])

  const removeStaged = useCallback((rentalItemId: string) => {
    setState((current) => ({
      ...current,
      stagedItems: current.stagedItems.filter(
        (item) => item.id !== rentalItemId
      ),
    }))
  }, [])

  const removeMany = useCallback((rentalItemIds: readonly string[]) => {
    const ids = new Set(rentalItemIds)
    if (ids.size === 0) return
    setState((current) => ({
      ...current,
      checkedItems: current.checkedItems.filter((item) => !ids.has(item.id)),
      stagedItems: current.stagedItems.filter((item) => !ids.has(item.id)),
      activeHold: current.activeHold
        ? (() => {
            const retainedIds = current.activeHold.rentalItemIds.filter(
              (id) => !ids.has(id)
            )
            return retainedIds.length > 0
              ? { ...current.activeHold, rentalItemIds: retainedIds }
              : null
          })()
        : null,
    }))
  }, [])

  const syncSnapshot = useCallback((item: RentalItemDto) => {
    setState((current) => {
      const checkedItems = replaceSnapshot(current.checkedItems, item)
      const stagedItems = replaceSnapshot(current.stagedItems, item)
      if (
        checkedItems === current.checkedItems &&
        stagedItems === current.stagedItems
      ) {
        return current
      }
      return { ...current, checkedItems, stagedItems }
    })
  }, [])

  const setActiveHold = useCallback((hold: ManualBookingDraftHold) => {
    setState((current) =>
      hold.draftId === current.draftId
        ? { ...current, activeHold: hold }
        : current
    )
  }, [])

  const clear = useCallback(() => setState(createInitialState()), [])
  const value = useMemo<BookingSelectionContextValue>(
    () => ({
      warehouseId,
      draftId: state.draftId,
      checkedItems: state.checkedItems,
      checkedIds,
      stagedItems: state.stagedItems,
      stagedIds,
      activeHold: state.activeHold,
      toggleChecked,
      addCheckedToStaged,
      removeStaged,
      removeMany,
      syncSnapshot,
      setActiveHold,
      clear,
    }),
    [
      addCheckedToStaged,
      checkedIds,
      clear,
      removeMany,
      removeStaged,
      setActiveHold,
      stagedIds,
      state,
      syncSnapshot,
      toggleChecked,
      warehouseId,
    ]
  )

  return (
    <BookingSelectionContext.Provider value={value}>
      {children}
    </BookingSelectionContext.Provider>
  )
}
