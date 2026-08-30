package persistence

import (
	"strings"

	"github.com/google/uuid"
)

const (
	OwnerTypeMaintenanceEstimate      = "MAINTENANCE_ESTIMATE"
	OwnerTypeMaintenanceRepair        = "MAINTENANCE_REPAIR"
	OwnerTypeMaintenanceAcceptance    = "MAINTENANCE_ACCEPTANCE"
	OwnerTypeMaintenanceCatalogNode   = "MAINTENANCE_CATALOG_NODE"
	OwnerTypeLogisticsCustomerProfile = "LOGISTICS_CUSTOMER_PROFILE"
	OwnerTypeTaskBoardEntry           = "TASK_BOARD_ENTRY"
	OwnerTypeDriverShift              = "DRIVER_SHIFT"
	ViewerContextEstimate             = "ESTIMATE"
	ViewerContextRepair               = "REPAIR"
	ViewerContextAcceptance           = "ACCEPTANCE"
	ViewerContextCatalog              = "CATALOG"
	ViewerContextReturnInspection     = "RETURN_INSPECTION"
	ViewerContextShipment             = "SHIPMENT"
	ViewerContextTransfer             = "TRANSFER"
	ViewerContextProfileAvatar        = "PROFILE_AVATAR"
	ViewerContextWorkResult           = "WORK_RESULT"
	ViewerContextShiftEvidence        = "SHIFT_EVIDENCE"
	MaintenanceOwnerProofConsumer     = "media-service-maintenance-owner-proof-v1"
	LogisticsOwnerProofConsumer       = "media-service-logistics-owner-proof-v1"
	MaintenanceOwnerProofService      = "maintenance-service"
	LogisticsOwnerProofService        = "logistics-service"
	MaintenanceOwnerProofScope        = "media.maintenance"
	LogisticsOwnerProofScope          = "media.logistics"
	TaskBoardEntryOwnerProofConsumer  = "media-service-task-board-entry-owner-proof-v1"
	TaskBoardEntryOwnerProofTopic     = "rwms.task-board.entry-owner-proof.v1"
	TaskBoardEntryOwnerProofAggregate = "TASK_BOARD_ENTRY_OWNER_PROOF"
	DriverShiftOwnerProofConsumer     = "media-service-driver-shift-owner-proof-v1"
	DriverShiftOwnerProofTopic        = "rwms.task-board.driver-shift-owner-proof.v1"
	DriverShiftOwnerProofAggregate    = "DRIVER_SHIFT_OWNER_PROOF"
)

// OwnerScopeDefinition declares the authoritative service, scope, aggregate,
// and browser context allowed for one media owner type.
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
	OwnerTypeLogisticsCustomerProfile: {
		OwnerType: OwnerTypeLogisticsCustomerProfile, ViewerContext: ViewerContextProfileAvatar,
		SourceService: LogisticsOwnerProofService, ServiceScope: LogisticsOwnerProofScope,
		ConsumerName: LogisticsOwnerProofConsumer, AggregateType: "CUSTOMER_PROFILE",
	},
	OwnerTypeTaskBoardEntry: {
		OwnerType: OwnerTypeTaskBoardEntry, ViewerContext: ViewerContextWorkResult,
		ConsumerName:  TaskBoardEntryOwnerProofConsumer,
		AggregateType: TaskBoardEntryOwnerProofAggregate,
	},
	OwnerTypeDriverShift: {
		OwnerType: OwnerTypeDriverShift, ViewerContext: ViewerContextShiftEvidence,
		ConsumerName:  DriverShiftOwnerProofConsumer,
		AggregateType: DriverShiftOwnerProofAggregate,
	},
}

// IsWorkerEvidenceOwnerType reports whether the owner requires a stable client
// reference and a current task-board worker-audience proof.
func IsWorkerEvidenceOwnerType(ownerType string) bool {
	return ownerType == OwnerTypeTaskBoardEntry || ownerType == OwnerTypeDriverShift
}

// PublicOwnerScope returns the owner definition only when its browser-facing
// viewer context exactly matches.
func PublicOwnerScope(ownerType, viewerContext string) (OwnerScopeDefinition, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	return definition, found && definition.ViewerContext == viewerContext
}

// ServiceOwnerScope returns the private proof definition for a service-owned
// owner type.
func ServiceOwnerScope(ownerType string) (OwnerScopeDefinition, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	return definition, found && definition.SourceService != ""
}

// ViewerContextForOwner returns the sole public viewer context for ownerType.
func ViewerContextForOwner(ownerType string) (string, bool) {
	definition, found := ownerScopeDefinitions[ownerType]
	if !found {
		return "", false
	}
	return definition.ViewerContext, true
}

// IsMaintenanceOwnerType reports whether ownerType is proven by maintenance.
func IsMaintenanceOwnerType(ownerType string) bool {
	definition, found := ownerScopeDefinitions[ownerType]
	return found && definition.SourceService == MaintenanceOwnerProofService
}

// IsPublicOwnerType reports whether ownerType has a declared public scope.
func IsPublicOwnerType(ownerType string) bool {
	_, found := ownerScopeDefinitions[ownerType]
	return found
}

// IsCustomerSubjectBoundOwnerType reports whether public access to the owner
// must match the exact CustomerApp subject in the current logistics proof.
func IsCustomerSubjectBoundOwnerType(ownerType string) bool {
	return ownerType == OwnerTypeLogisticsShipment || ownerType == OwnerTypeLogisticsCustomerProfile
}

// LogisticsOwnerParts validates and splits a canonical documentId:lineId owner
// identifier used by structured logistics media scopes.
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

// ServiceOwnerAggregateID returns the source aggregate identity used to fence
// private service owner proofs for the given owner scope.
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
