package persistence

import (
	"strings"

	"github.com/google/uuid"
)

const (
	OwnerTypeMaintenanceEstimate    = "MAINTENANCE_ESTIMATE"
	OwnerTypeMaintenanceRepair      = "MAINTENANCE_REPAIR"
	OwnerTypeMaintenanceAcceptance  = "MAINTENANCE_ACCEPTANCE"
	OwnerTypeMaintenanceCatalogNode = "MAINTENANCE_CATALOG_NODE"
	ViewerContextEstimate           = "ESTIMATE"
	ViewerContextRepair             = "REPAIR"
	ViewerContextAcceptance         = "ACCEPTANCE"
	ViewerContextCatalog            = "CATALOG"
	ViewerContextReturnInspection   = "RETURN_INSPECTION"
	ViewerContextShipment           = "SHIPMENT"
	ViewerContextTransfer           = "TRANSFER"
	MaintenanceOwnerProofConsumer   = "media-service-maintenance-owner-proof-v1"
	LogisticsOwnerProofConsumer     = "media-service-logistics-owner-proof-v1"
	MaintenanceOwnerProofService    = "maintenance-service"
	LogisticsOwnerProofService      = "logistics-service"
	MaintenanceOwnerProofScope      = "media.maintenance"
	LogisticsOwnerProofScope        = "media.logistics"
)

type OwnerScopeDefinition struct {
	OwnerType     string
	ViewerContext string
	SourceService string
	ServiceScope  string
	ConsumerName  string
	AggregateType string
	Structured    bool
}

var ownerScopeDefinitions = map[string]OwnerScopeDefinition{
	OwnerTypeInventoryFinding: {
		OwnerType: OwnerTypeInventoryFinding, ViewerContext: ViewerContextInspection,
	},
	OwnerTypeCabin: {
		OwnerType: OwnerTypeCabin, ViewerContext: ViewerContextWarehouse,
	},
	OwnerTypeMaintenanceEstimate: {
		OwnerType: OwnerTypeMaintenanceEstimate, ViewerContext: ViewerContextEstimate,
		SourceService: MaintenanceOwnerProofService, ServiceScope: MaintenanceOwnerProofScope,
		ConsumerName: MaintenanceOwnerProofConsumer, AggregateType: "ESTIMATE",
	},
	OwnerTypeMaintenanceRepair: {
		OwnerType: OwnerTypeMaintenanceRepair, ViewerContext: ViewerContextRepair,
		SourceService: MaintenanceOwnerProofService, ServiceScope: MaintenanceOwnerProofScope,
		ConsumerName: MaintenanceOwnerProofConsumer, AggregateType: "REPAIR",
	},
	OwnerTypeMaintenanceAcceptance: {
		OwnerType: OwnerTypeMaintenanceAcceptance, ViewerContext: ViewerContextAcceptance,
		SourceService: MaintenanceOwnerProofService, ServiceScope: MaintenanceOwnerProofScope,
		ConsumerName: MaintenanceOwnerProofConsumer, AggregateType: "ACCEPTANCE",
	},
	OwnerTypeMaintenanceCatalogNode: {
		OwnerType: OwnerTypeMaintenanceCatalogNode, ViewerContext: ViewerContextCatalog,
		SourceService: MaintenanceOwnerProofService, ServiceScope: MaintenanceOwnerProofScope,
		ConsumerName: MaintenanceOwnerProofConsumer, AggregateType: "CATALOG_NODE",
	},
	OwnerTypeLogisticsReturn: {
		OwnerType: OwnerTypeLogisticsReturn, ViewerContext: ViewerContextReturnInspection,
		SourceService: LogisticsOwnerProofService, ServiceScope: LogisticsOwnerProofScope,
		ConsumerName: LogisticsOwnerProofConsumer, AggregateType: "RETURN", Structured: true,
	},
	OwnerTypeLogisticsShipment: {
		OwnerType: OwnerTypeLogisticsShipment, ViewerContext: ViewerContextShipment,
		SourceService: LogisticsOwnerProofService, ServiceScope: LogisticsOwnerProofScope,
		ConsumerName: LogisticsOwnerProofConsumer, AggregateType: "SHIPMENT", Structured: true,
	},
	OwnerTypeLogisticsTransfer: {
		OwnerType: OwnerTypeLogisticsTransfer, ViewerContext: ViewerContextTransfer,
		SourceService: LogisticsOwnerProofService, ServiceScope: LogisticsOwnerProofScope,
		ConsumerName: LogisticsOwnerProofConsumer, AggregateType: "TRANSFER", Structured: true,
	},
}

func PublicOwnerScope(ownerType, viewerContext string) (OwnerScopeDefinition, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	return definition, found && definition.ViewerContext == viewerContext
}

func ServiceOwnerScope(ownerType string) (OwnerScopeDefinition, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	return definition, found && definition.SourceService != ""
}

func ViewerContextForOwner(ownerType string) (string, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	if !found {
		return "", false
	}
	return definition.ViewerContext, true
}

func IsMaintenanceOwnerType(ownerType string) bool {
	definition, found := ownerScopeDefinitions[ownerType]
	return found && definition.SourceService == MaintenanceOwnerProofService
}

func IsPublicOwnerType(ownerType string) bool {
	_, found := ownerScopeDefinitions[ownerType]
	return found
}

func LogisticsOwnerParts(ownerID string) (uuid.UUID, uuid.UUID, bool) {
	parts := strings.Split(ownerID, ":")
	if len(parts) != 2 {
		return uuid.Nil, uuid.Nil, false
	}
	documentID, documentErr := uuid.Parse(parts[0])
	lineID, lineErr := uuid.Parse(parts[1])
	if documentErr != nil || lineErr != nil || documentID == uuid.Nil || lineID == uuid.Nil ||
		LogisticsOwnerID(documentID, lineID) != ownerID {
		return uuid.Nil, uuid.Nil, false
	}
	return documentID, lineID, true
}

func ServiceOwnerAggregateID(ownerType, ownerID string) (uuid.UUID, bool) {
	definition, found := ServiceOwnerScope(ownerType)
	if !found {
		return uuid.Nil, false
	}
	if !definition.Structured {
		ownerUUID, err := uuid.Parse(ownerID)
		return ownerUUID, err == nil && ownerUUID != uuid.Nil && ownerUUID.String() == ownerID
	}
	if _, _, valid := LogisticsOwnerParts(ownerID); !valid {
		return uuid.Nil, false
	}
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("rwms:media-owner:"+ownerType+":"+ownerID)), true
}
