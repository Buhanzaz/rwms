import { createContext, useContext } from "react"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

export type BookingSelectionContextValue = {
  warehouseId: string | null
  selectedItems: RentalItemDto[]
  selectedIds: ReadonlySet<string>
  select: (item: RentalItemDto) => void
  deselect: (rentalItemId: string) => void
  toggle: (item: RentalItemDto) => void
  removeMany: (rentalItemIds: readonly string[]) => void
  clear: () => void
}

export const BookingSelectionContext =
  createContext<BookingSelectionContextValue | null>(null)

export function useBookingSelection() {
  const context = useContext(BookingSelectionContext)
  if (!context) {
    throw new Error(
      "useBookingSelection must be used inside BookingSelectionProvider"
    )
  }
  return context
}
