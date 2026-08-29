import { describe, expect, it } from "vitest"

import { parseShipmentDocument } from "@/features/logistics/shipments/adapters/http-shipment-client"
import {
  SHIPMENT_CONTRACT_GAPS,
  SHIPMENT_OPERATION_IDS,
  SHIPMENT_TRANSIENT_STATES,
} from "@/features/logistics/shipments/operation-contract"

describe("shipment UI contract mapping", () => {
  it("maps document actions to existing operationIds", () => {
    expect(SHIPMENT_OPERATION_IDS).toEqual({
      listShipments: "listShipments",
      getShipment: "getShipment",
      createShipment: "createShipment",
      replacePlan: "replaceShipmentPlan",
      getFurnitureReadiness: "getShipmentFurnitureReadiness",
      createFurnitureTasks: "createShipmentFurnitureTasks",
      confirmPreparation: "confirmShipmentPreparation",
      cancelShipment: "cancelShipment",
    })
  })

  it("does not invent candidate or finalization operations", () => {
    expect(SHIPMENT_CONTRACT_GAPS).toEqual([
      "listCompanies",
      "listCandidates",
      "listEquipment",
      "listSourceCabins",
      "finalizeShipment",
    ])
  })

  it("keeps the service CANCELLING transition in the contract and parser", () => {
    expect(SHIPMENT_TRANSIENT_STATES).toContain("CANCELLING")
    expect(
      parseShipmentDocument({
        id: "11111111-1111-4111-8111-111111111111",
        version: 3,
        documentType: "SHIPMENT",
        state: "CANCELLING",
        warehouseId: "22222222-2222-4222-8222-222222222222",
        destinationWarehouseId: null,
        partySnapshot: "ООО Тест",
        driverSnapshot: "Иванов",
        clientId: null,
        equipmentMovementTaskId: null,
        scheduledDate: null,
        rentalOrderId: null,
        lines: [
          {
            id: "33333333-3333-4333-8333-333333333333",
            version: 1,
            lineNumber: 1,
            assetId: "44444444-4444-4444-8444-444444444444",
            assetVersion: 8,
            state: "PENDING",
            tenantSnapshot: null,
            rentalOrderId: null,
            inventorySourceWarehouseId: "22222222-2222-4222-8222-222222222222",
            inventoryShipmentFurniture: null,
          },
        ],
        createdAt: "2026-07-18T08:00:00Z",
        updatedAt: "2026-07-18T08:10:00Z",
      }).state
    ).toBe("CANCELLING")
  })
})
