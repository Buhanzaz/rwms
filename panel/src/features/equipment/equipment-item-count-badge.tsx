import { useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
import { Badge } from "@/components/ui/badge"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

export function EquipmentItemCountBadge() {
  const { accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id
  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, ""],
    queryFn: () =>
      getEquipmentItems(accessToken, {
        warehouseId: warehouseId!,
      }),
    enabled: Boolean(warehouseId && accessToken),
  })

  if (equipmentQuery.data === undefined) {
    return null
  }

  return <Badge variant="secondary">{equipmentQuery.data.length} поз.</Badge>
}
