package persistence

import (
	"context"
	"errors"
	"strings"

	"github.com/google/uuid"
)

var ErrReferenceNotReady = errors.New("media reference is absent, incorrectly owned, or not ready at the requested generation")

const (
	OwnerTypeLogisticsReturn   = "LOGISTICS_RETURN"
	OwnerTypeLogisticsShipment = "LOGISTICS_SHIPMENT"
	OwnerTypeLogisticsTransfer = "LOGISTICS_TRANSFER"
)

type ReadyMediaReference struct {
	MediaID    uuid.UUID
	Generation int
}

type ValidateLogisticsReferencesCommand struct {
	OwnerType   string
	OwnerID     string
	WarehouseID uuid.UUID
	References  []ReadyMediaReference
}

func IsLogisticsOwnerType(ownerType string) bool {
	switch ownerType {
	case OwnerTypeLogisticsReturn, OwnerTypeLogisticsShipment, OwnerTypeLogisticsTransfer:
		return true
	default:
		return false
	}
}

func LogisticsOwnerID(documentID, lineID uuid.UUID) string {
	return documentID.String() + ":" + lineID.String()
}

// ValidateLogisticsReferences is a read-only receiver boundary. It deliberately
// reads media_asset directly and does not use the Stage 7 inventory owner-proof
// projection: logistics has its own opaque owner types and this call neither
// creates an owner binding nor changes an asset, object, event or outbox row.
func (repository *Repository) ValidateLogisticsReferences(
	ctx context.Context,
	command ValidateLogisticsReferencesCommand,
) error {
	if !IsLogisticsOwnerType(command.OwnerType) || strings.TrimSpace(command.OwnerID) == "" ||
		command.WarehouseID == uuid.Nil || len(command.References) < 1 || len(command.References) > 20 {
		return ErrConflict
	}
	mediaIDs := make([]uuid.UUID, 0, len(command.References))
	generations := make([]int, 0, len(command.References))
	seen := make(map[uuid.UUID]struct{}, len(command.References))
	for _, reference := range command.References {
		if reference.MediaID == uuid.Nil || reference.Generation <= 0 {
			return ErrConflict
		}
		if _, duplicate := seen[reference.MediaID]; duplicate {
			return ErrConflict
		}
		seen[reference.MediaID] = struct{}{}
		mediaIDs = append(mediaIDs, reference.MediaID)
		generations = append(generations, reference.Generation)
	}

	var matched int64
	err := repository.pool.QueryRow(ctx, `
		with requested(media_id, generation) as (
			select * from unnest($1::uuid[], $2::integer[])
		)
		select count(*)
		from requested
		join media_asset asset
		  on asset.media_id = requested.media_id
		 and asset.owner_type = $3
		 and asset.owner_id = $4
		 and asset.warehouse_id = $5
		 and asset.processing_status = 'READY'
		 and asset.current_generation = requested.generation
		 and asset.current_generation > 0
		 and asset.deleted_at is null`,
		mediaIDs, generations, command.OwnerType, command.OwnerID, command.WarehouseID,
	).Scan(&matched)
	if err != nil {
		return err
	}
	if matched != int64(len(command.References)) {
		return ErrReferenceNotReady
	}
	return nil
}
