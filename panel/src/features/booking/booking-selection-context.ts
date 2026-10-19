import { createContext, useContext } from "react"

import type { ManualBookingDraftHold } from "@/features/booking/api/manual-booking-drafts-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

export type BookingSelectionContextValue = {
  warehouseId: string | null
  draftId: string
  checkedItems: RentalItemDto[]
  checkedIds: ReadonlySet<string>
  stagedItems: RentalItemDto[]
  stagedIds: ReadonlySet<string>
  activeHold: ManualBookingDraftHold | null
  toggleChecked: (item: RentalItemDto) => void
  addCheckedToStaged: () => void
  removeStaged: (rentalItemId: string) => void
  removeMany: (rentalItemIds: readonly string[]) => void
  syncSnapshot: (item: RentalItemDto) => void
  setActiveHold: (hold: ManualBookingDraftHold) => void
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
