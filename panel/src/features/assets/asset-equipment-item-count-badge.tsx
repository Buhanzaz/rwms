import { useQuery } from "@tanstack/react-query"

import { listAssetEquipment } from "@/api/asset-api"
import { Badge } from "@/components/ui/badge"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

export function AssetEquipmentItemCountBadge() {
  const { accessToken } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const equipmentQuery = useQuery({
    queryKey: ["asset-equipment", selectedWarehouseId ?? "none"],
    queryFn: () => listAssetEquipment(accessToken, selectedWarehouseId!),
    enabled: accessToken !== null && selectedWarehouseId !== null,
  })

  if (equipmentQuery.data === undefined) return null

  return <Badge variant="secondary">{equipmentQuery.data.length} поз.</Badge>
}
