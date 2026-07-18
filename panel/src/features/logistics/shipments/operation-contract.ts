export const SHIPMENT_OPERATION_IDS = {
  listShipments: "listShipments",
  getShipment: "getShipment",
  createShipment: "createShipment",
  replacePlan: "replaceShipmentPlan",
  confirmPreparation: "confirmShipmentPreparation",
  cancelShipment: "cancelShipment",
} as const

export const SHIPMENT_CONTRACT_GAPS = [
  "listCompanies",
  "listCandidates",
  "listEquipment",
  "listSourceCabins",
  "finalizeShipment",
] as const

export const SHIPMENT_TRANSIENT_STATES = ["CANCELLING"] as const
