import { useEffect } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"

import {
  EQUIPMENT_MOCK_STORAGE_KEY,
  EQUIPMENT_MOCK_UPDATED_EVENT,
  getEquipmentItems,
} from "@/api/equipment-api"
import { Badge } from "@/components/ui/badge"
import {
  RENTAL_ITEMS_MOCK_STORAGE_KEY,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
} from "@/features/rental-items/api/rental-items-api"
import { useWarehouse } from "@/hooks/use-warehouse"

export function EquipmentItemCountBadge() {
  const queryClient = useQueryClient()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id
  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, ""],
    queryFn: () =>
      getEquipmentItems({
        warehouseId: warehouseId!,
      }),
    enabled: warehouseId !== undefined,
  })

  useEffect(() => {
    function invalidateEquipmentItems() {
      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })
    }

    function handleStorageEvent(event: StorageEvent) {
      if (
        event.key !== EQUIPMENT_MOCK_STORAGE_KEY &&
        event.key !== RENTAL_ITEMS_MOCK_STORAGE_KEY
      ) {
        return
      }

      invalidateEquipmentItems()
    }

    window.addEventListener(
      EQUIPMENT_MOCK_UPDATED_EVENT,
      invalidateEquipmentItems
    )
    window.addEventListener(
      RENTAL_ITEMS_MOCK_UPDATED_EVENT,
      invalidateEquipmentItems
    )
    window.addEventListener("storage", handleStorageEvent)

    return () => {
      window.removeEventListener(
        EQUIPMENT_MOCK_UPDATED_EVENT,
        invalidateEquipmentItems
      )
      window.removeEventListener(
        RENTAL_ITEMS_MOCK_UPDATED_EVENT,
        invalidateEquipmentItems
      )
      window.removeEventListener("storage", handleStorageEvent)
    }
  }, [queryClient])

  if (equipmentQuery.data === undefined) {
    return null
  }

  return <Badge variant="secondary">{equipmentQuery.data.length} поз.</Badge>
}
