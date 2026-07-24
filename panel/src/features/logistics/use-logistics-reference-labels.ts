import { useMemo } from "react"
import { useQueries } from "@tanstack/react-query"

import { ORDERS_QUERY_KEY, getOrder } from "@/features/orders/api/orders-api"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"

type LogisticsReferenceLine = {
  assetId: string
  rentalOrderId: string | null
}

type LogisticsReferenceDocument = {
  rentalOrderId: string | null
  lines: readonly LogisticsReferenceLine[]
}

export type LogisticsReferenceLabels = {
  assetNumbers: ReadonlyMap<string, string>
  orderNumbers: ReadonlyMap<string, string>
}

const REFERENCE_STALE_TIME = 5 * 60 * 1_000

function uniqueSorted(values: Iterable<string>) {
  return [...new Set(values)].sort()
}

export function useLogisticsReferenceLabels(
  accessToken: string | null,
  documents: readonly LogisticsReferenceDocument[]
): LogisticsReferenceLabels {
  const assetIds = useMemo(
    () =>
      uniqueSorted(
        documents.flatMap((document) =>
          document.lines.map((line) => line.assetId)
        )
      ),
    [documents]
  )
  const orderIds = useMemo(
    () =>
      uniqueSorted(
        documents.flatMap((document) => [
          ...(document.rentalOrderId ? [document.rentalOrderId] : []),
          ...document.lines.flatMap((line) =>
            line.rentalOrderId ? [line.rentalOrderId] : []
          ),
        ])
      ),
    [documents]
  )

  const assetQueries = useQueries({
    queries: assetIds.map((assetId) => ({
      queryKey: ["rental-items", "logistics-reference", assetId],
      queryFn: () => getAssetRentalItem(accessToken, assetId),
      enabled: Boolean(accessToken),
      staleTime: REFERENCE_STALE_TIME,
    })),
  })
  const orderQueries = useQueries({
    queries: orderIds.map((orderId) => ({
      queryKey: [...ORDERS_QUERY_KEY, "logistics-reference", orderId],
      queryFn: () => getOrder(accessToken!, orderId),
      enabled: Boolean(accessToken),
      staleTime: REFERENCE_STALE_TIME,
    })),
  })

  return useMemo(() => {
    const assetNumbers = new Map<string, string>()
    const orderNumbers = new Map<string, string>()

    orderQueries.forEach((query, index) => {
      const order = query.data
      const orderId = orderIds[index]
      if (!order || !orderId) return
      orderNumbers.set(orderId, order.number)
      order.units.forEach(({ unit }) => assetNumbers.set(unit.id, unit.number))
    })
    assetQueries.forEach((query, index) => {
      const asset = query.data
      const assetId = assetIds[index]
      if (asset && assetId) assetNumbers.set(assetId, asset.number)
    })

    return { assetNumbers, orderNumbers }
  }, [assetIds, assetQueries, orderIds, orderQueries])
}

export function logisticsAssetLabel(
  labels: LogisticsReferenceLabels,
  assetId: string
) {
  return labels.assetNumbers.get(assetId) ?? `ID ${assetId.slice(0, 8)}`
}

export function logisticsOrderLabel(
  labels: LogisticsReferenceLabels,
  orderId: string
) {
  return labels.orderNumbers.get(orderId) ?? `ID ${orderId.slice(0, 8)}`
}
