import { useEffect } from "react"
import { useQueryClient } from "@tanstack/react-query"

import {
  evictMediaPreview,
  evictWarehouseMediaPreviews,
} from "@/features/media/media-preview-cache"
import {
  loadRentalItemCoverPage,
  RENTAL_ITEM_COVERS_QUERY_KEY,
} from "@/features/rental-items/use-rental-item-covers"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { startBearerEventStream, type SseEvent } from "@/lib/sse-client"
import {
  invalidateCabinMediaCaches,
  invalidateWarehouseRentalCaches,
  patchCabinCoverCaches,
  patchRentalItemCaches,
} from "@/hooks/warehouse-realtime-cache"

type WarehouseInvalidation = Readonly<{
  scope: string
  mediaId?: string
  ownerType?: string
  ownerId?: string
  aggregateType?: string
  aggregateId?: string
  changeType?: string
}>

export function useWarehouseRealtime({
  accessToken,
  warehouseId,
  userId,
}: {
  accessToken: string | null
  warehouseId: string | undefined
  userId: string | undefined
}) {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!accessToken || !warehouseId) return
    const runtime = getGatewayRuntimeConfig()
    const subject = userId ?? "unknown-user"
    const assetEventsUrl = new URL(`${runtime.assetApiBaseUrl}/v1/events`)
    const mediaEventsUrl = new URL(`${runtime.mediaApiBaseUrl}/v1/events`)
    assetEventsUrl.searchParams.set("warehouseId", warehouseId)
    mediaEventsUrl.searchParams.set("warehouseId", warehouseId)
    let active = true
    const assetResync = createResyncTracker()
    const mediaResync = createResyncTracker()

    const invalidateRentalItems = () => {
      void queryClient.invalidateQueries({
        queryKey: ["rental-items", subject, warehouseId],
      })
    }
    const invalidateCovers = () => {
      void queryClient.invalidateQueries({
        queryKey: [...RENTAL_ITEM_COVERS_QUERY_KEY, subject, warehouseId],
      })
    }
    const invalidateEquipment = () => {
      void queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })
      void queryClient.invalidateQueries({
        queryKey: ["equipment"],
      })
    }
    const invalidateEverything = () => {
      invalidateRentalItems()
      invalidateCovers()
      invalidateEquipment()
    }
    const invalidateWarehouseMedia = () => {
      void queryClient.invalidateQueries({
        predicate: (query) => {
          const key = query.queryKey
          return (
            (key[0] === "rental-item-media" ||
              key[0] === "service-owner-media") &&
            key[1] === warehouseId
          )
        },
      })
    }
    const hasCachedData = (queryKey: readonly unknown[]) =>
      queryClient
        .getQueryCache()
        .findAll({ queryKey })
        .some((query) => query.state.data !== undefined)

    const refreshRentalItem = async (
      rentalItemId: string,
      changeType?: string
    ) => {
      try {
        const item = await getAssetRentalItem(accessToken, rentalItemId)
        if (!active) return
        patchRentalItemCaches({
          queryClient,
          subject,
          warehouseId,
          item,
          changeType,
        })
      } catch {
        if (!active) return
        // A warehouse move can make the detail endpoint inaccessible from the
        // old warehouse grant. The scoped list refetch then removes the row
        // without exposing data from the destination warehouse.
        invalidateWarehouseRentalCaches(queryClient, subject, warehouseId)
      }
    }

    const refreshCabinCover = async (cabinId: string) => {
      try {
        const page = await loadRentalItemCoverPage(accessToken, warehouseId, [
          cabinId,
        ])
        if (!active) return
        patchCabinCoverCaches(
          queryClient,
          warehouseId,
          cabinId,
          page.items.find((item) => item.cabinId === cabinId) ?? null
        )
      } catch {
        if (!active) return
        // Owner-proof propagation can briefly lag the media event. The media
        // client's retry policy handles the common case; an active cover query
        // is marked stale for the next safe read if it still fails.
        void queryClient.invalidateQueries({
          queryKey: [...RENTAL_ITEM_COVERS_QUERY_KEY, subject, warehouseId],
          refetchType: "active",
        })
      }
    }

    const invalidateMediaWithoutOwner = () => {
      invalidateCovers()
      invalidateWarehouseMedia()
    }

    const handleAssetEvent = (event: SseEvent) => {
      const payload = parseWarehouseInvalidation(event)
      if (!payload) return
      if (payload.scope === "RESYNC") {
        const hasWarehouseData =
          hasCachedData(["rental-items", subject, warehouseId]) ||
          hasCachedData(["equipment-items"]) ||
          hasCachedData(["equipment"])
        if (assetResync(hasWarehouseData)) {
          invalidateEverything()
        }
      } else if (
        payload.scope === "RENTAL_ITEMS_CHANGED" &&
        payload.aggregateType === "RENTAL_ITEM" &&
        payload.aggregateId
      ) {
        void refreshRentalItem(payload.aggregateId, payload.changeType)
      } else if (payload.scope === "RENTAL_ITEMS_CHANGED") {
        invalidateRentalItems()
      } else if (
        payload.scope === "EQUIPMENT_CHANGED" ||
        payload.scope === "EQUIPMENT_CATALOG_CHANGED"
      ) {
        invalidateEquipment()
      } else {
        invalidateEverything()
      }
    }

    const handleMediaEvent = (event: SseEvent) => {
      const payload = parseWarehouseInvalidation(event)
      if (!payload) return
      if (payload.scope === "RESYNC") {
        const hasCoverData = hasCachedData([
          ...RENTAL_ITEM_COVERS_QUERY_KEY,
          subject,
          warehouseId,
        ])
        if (!mediaResync(hasCoverData)) {
          return
        }
        evictWarehouseMediaPreviews(warehouseId)
        invalidateMediaWithoutOwner()
        return
      }
      // Blob URLs are retained between route changes, but the exact media
      // generation must be discarded when the service reports a change.
      evictMediaPreview(payload.mediaId)
      if (payload.ownerType === "CABIN" && payload.ownerId) {
        invalidateCabinMediaCaches(
          queryClient,
          warehouseId,
          payload.ownerId,
          subject
        )
        void refreshCabinCover(payload.ownerId)
        return
      }
      // Older processing workers may omit owner metadata. Keep correctness by
      // refreshing projections, while still retaining every unaffected blob.
      invalidateMediaWithoutOwner()
    }

    const stopAsset = startBearerEventStream({
      url: assetEventsUrl,
      accessToken,
      onEvent: handleAssetEvent,
    })
    const stopMedia = startBearerEventStream({
      url: mediaEventsUrl,
      accessToken,
      onEvent: handleMediaEvent,
    })
    return () => {
      active = false
      stopAsset()
      stopMedia()
    }
  }, [accessToken, queryClient, userId, warehouseId])
}

/**
 * The first server RESYNC only needs work when this browser already has a
 * projection. Every later RESYNC represents a reconnect or a collapsed event
 * backlog and must force a scoped reconciliation, even when no row event was
 * observed locally.
 */
export function createResyncTracker() {
  let hasSeenResync = false
  return (hasCachedData: boolean) => {
    const shouldRefresh = hasSeenResync || hasCachedData
    hasSeenResync = true
    return shouldRefresh
  }
}

export function parseWarehouseInvalidation(
  event: SseEvent
): WarehouseInvalidation | null {
  if (event.event !== "warehouse-invalidation") return null
  try {
    const value = JSON.parse(event.data) as Record<string, unknown>
    if (typeof value.scope !== "string" || !value.scope) return null
    return {
      scope: value.scope,
      mediaId: typeof value.mediaId === "string" ? value.mediaId : undefined,
      ownerType:
        typeof value.ownerType === "string" ? value.ownerType : undefined,
      ownerId: typeof value.ownerId === "string" ? value.ownerId : undefined,
      aggregateType:
        typeof value.aggregateType === "string"
          ? value.aggregateType
          : undefined,
      aggregateId:
        typeof value.aggregateId === "string" ? value.aggregateId : undefined,
      changeType:
        typeof value.changeType === "string"
          ? value.changeType
          : typeof value.eventType === "string"
            ? value.eventType
            : undefined,
    }
  } catch {
    return null
  }
}
