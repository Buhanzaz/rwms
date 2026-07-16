import type { RepairEstimateMediaClient } from "@/features/repair-estimates/ports/repair-estimate-media-client"

/** Shared operational-media shape; current browser implementation reuses the estimate IndexedDB. */
export type InventoryMediaClient = RepairEstimateMediaClient
