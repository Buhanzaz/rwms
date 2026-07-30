import { useMemo } from "react"
import { useQueries } from "@tanstack/react-query"

import { ORDERS_QUERY_KEY, getOrder } from "@/features/orders/api/orders-api"
import type { OrderDetail } from "@/features/orders/domain/orders"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

type LogisticsReferenceLine = {
  assetId: string
  rentalOrderId: string | null
}

type LogisticsReferenceDocument = {
  rentalOrderId: string | null
  lines: readonly LogisticsReferenceLine[]
}

export type LogisticsAssetReference =
  | { status: "loading" }
  | { status: "unavailable" }
  | { status: "available"; asset: RentalItemDto }

export type LogisticsOrderReference =
  | { status: "loading" }
  | { status: "unavailable" }
  | { status: "available"; order: OrderDetail }

export type LogisticsReferenceLabels = {
  assetNumbers: ReadonlyMap<string, string>
  orderNumbers: ReadonlyMap<string, string>
  assets: ReadonlyMap<string, LogisticsAssetReference>
  orders: ReadonlyMap<string, LogisticsOrderReference>
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
    const assets = new Map<string, LogisticsAssetReference>()
    const orders = new Map<string, LogisticsOrderReference>()

    orderQueries.forEach((query, index) => {
      const order = query.data
      const orderId = orderIds[index]
      if (!orderId) return

      if (order) {
        orders.set(orderId, { status: "available", order })
        orderNumbers.set(orderId, order.number)
        order.units.forEach(({ unit }) =>
          assetNumbers.set(unit.id, unit.number)
        )
        return
      }

      orders.set(orderId, {
        status: query.isLoading ? "loading" : "unavailable",
      })
    })
    assetQueries.forEach((query, index) => {
      const asset = query.data
      const assetId = assetIds[index]
      if (!assetId) return

      if (asset) {
        assets.set(assetId, { status: "available", asset })
        assetNumbers.set(assetId, asset.number)
        return
      }

      assets.set(assetId, {
        status: query.isLoading ? "loading" : "unavailable",
      })
    })

    return { assetNumbers, orderNumbers, assets, orders }
  }, [assetIds, assetQueries, orderIds, orderQueries])
}

export function logisticsAssetLabel(
  labels: LogisticsReferenceLabels,
  assetId: string
) {
  return labels.assetNumbers.get(assetId) ?? "Бытовка недоступна"
}

export function logisticsOrderLabel(
  labels: LogisticsReferenceLabels,
  orderId: string
) {
  return labels.orderNumbers.get(orderId) ?? "Заказ недоступен"
}
