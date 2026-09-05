import type { ShipmentDocumentState } from "./shipments/model"
import type { ReturnDocumentState } from "./returns/model"
import type { TransferDocumentState } from "./warehouse-transfers/model/warehouse-transfer"

/** Shared presentation only: each document retains its owner-defined state and label. */
const documentVariants = {
  DRAFT: "muted",
  PREPARING: "progress",
  AWAITING_CONFIRMATION: "warning",
  CONFIRMING_PREPARATION: "progress",
  SHIPPED: "success",
  CANCELLING: "warning",
  CANCELLED: "muted",
  CONFLICT: "destructive",
  RECONCILIATION_REQUIRED: "destructive",
  REGISTERING: "progress",
  INSPECTION_REQUIRED: "warning",
  ACCEPTING: "progress",
  ACCEPTED: "success",
  ESTIMATE_PENDING: "warning",
  ESTIMATE_REQUESTED: "progress",
  DEPARTING: "info",
  IN_TRANSIT: "info",
  ARRIVING: "info",
  COMPLETED: "success",
} as const satisfies Record<
  ShipmentDocumentState | ReturnDocumentState | TransferDocumentState,
  string
>

export function logisticsStatusVariant(
  state: ShipmentDocumentState | ReturnDocumentState | TransferDocumentState
) {
  return documentVariants[state]
}
