package persistence

import (
	"context"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type ServiceOwnerProofCommand struct {
	SourceService    string
	OwnerType        string
	OwnerID          uuid.UUID
	DocumentID       uuid.UUID
	LineID           uuid.UUID
	WarehouseID      uuid.UUID
	OwnerRevision    int64
	AggregateVersion int64
	ProofEventID     uuid.UUID
	Active           bool
}

type ServiceOwnerProofRecord struct {
	SourceService    string
	OwnerType        string
	OwnerID          uuid.UUID
	DocumentID       uuid.UUID
	LineID           uuid.UUID
	WarehouseID      uuid.UUID
	OwnerRevision    int64
	AggregateVersion int64
	ProofEventID     uuid.UUID
	Active           bool
}

type normalizedServiceOwnerProof struct {
	ServiceOwnerProofRecord
	InternalOwnerID string
	ConsumerName    string
	AggregateType   string
	AggregateID     uuid.UUID
	RequestSHA256   string
}

type persistedServiceOwnerProof struct {
	normalizedServiceOwnerProof
	Outcome string
}

type serviceOwnerCheckpoint struct {
	SourceService    string
	ConsumerName     string
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	OwnerRevision    int64
	WarehouseID      uuid.UUID
	DocumentID       *uuid.UUID
	LineID           *uuid.UUID
	Active           bool
	ProofEventID     uuid.UUID
	RequestSHA256    string
	Quarantined      bool
}

func (repository *Repository) UpsertServiceOwnerProof(
	ctx context.Context,
	command ServiceOwnerProofCommand,
) (ServiceOwnerProofRecord, bool, error) {
	proof, err := normalizeServiceOwnerProof(command)
	if err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockServiceOwnerProof(ctx, tx, proof); err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}

	priorEvent, found, err := readServiceOwnerProofReceipt(ctx, tx, proof.ProofEventID)
	if err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	if found {
		if priorEvent.RequestSHA256 == proof.RequestSHA256 {
			if err := tx.Commit(ctx); err != nil {
				return ServiceOwnerProofRecord{}, false, err
			}
			if priorEvent.Outcome != "APPLIED" {
				return ServiceOwnerProofRecord{}, false, ErrConflict
			}
			return priorEvent.ServiceOwnerProofRecord, true, nil
		}
		if err := repository.recordServiceOwnerProofConflict(ctx, tx, proof, &priorEvent,
			"EVENT_ID_CONFLICT"); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := repository.quarantineServiceOwnerProof(ctx, tx, priorEvent.normalizedServiceOwnerProof,
			priorEvent.AggregateVersion, priorEvent.AggregateVersion, "EVENT_ID_CONFLICT"); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := repository.quarantineServiceOwnerProof(ctx, tx, proof,
			proof.AggregateVersion, proof.AggregateVersion, "EVENT_ID_CONFLICT"); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		return ServiceOwnerProofRecord{}, false, ErrConflict
	}
	var openQuarantine bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3
		  and reconciled_at is null)`, proof.ConsumerName, proof.AggregateType,
		proof.AggregateID).Scan(&openQuarantine); err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	if openQuarantine {
		if err := repository.rejectServiceOwnerProof(ctx, tx, proof, nil,
			"OWNER_PROOF_QUARANTINED", proof.AggregateVersion, proof.AggregateVersion); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		return ServiceOwnerProofRecord{}, false, ErrConflict
	}

	priorVersion, versionFound, err := readServiceOwnerProofVersion(ctx, tx, proof.OwnerType,
		proof.InternalOwnerID, proof.AggregateVersion)
	if err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	if versionFound {
		if err := repository.rejectServiceOwnerProof(ctx, tx, proof, &priorVersion,
			"AGGREGATE_VERSION_CONFLICT", proof.AggregateVersion, proof.AggregateVersion); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		return ServiceOwnerProofRecord{}, false, ErrConflict
	}

	checkpoint, checkpointFound, err := readServiceOwnerCheckpoint(ctx, tx, proof.OwnerType,
		proof.InternalOwnerID)
	if err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	failureCode := ""
	expectedVersion := int64(0)
	if checkpointFound {
		expectedVersion = checkpoint.AggregateVersion + 1
		switch {
		case checkpoint.Quarantined:
			failureCode = "OWNER_PROOF_QUARANTINED"
			expectedVersion = proof.AggregateVersion
		case proof.AggregateVersion > expectedVersion:
			failureCode = "AGGREGATE_VERSION_GAP"
		case proof.AggregateVersion < expectedVersion:
			failureCode = "AGGREGATE_VERSION_REGRESSION"
		case proof.OwnerRevision < checkpoint.OwnerRevision:
			failureCode = "OWNER_REVISION_REGRESSION"
			expectedVersion = proof.AggregateVersion
		case proof.OwnerRevision > checkpoint.OwnerRevision+1:
			failureCode = "OWNER_REVISION_GAP"
			expectedVersion = proof.AggregateVersion
		case proof.OwnerRevision == checkpoint.OwnerRevision &&
			(checkpoint.WarehouseID != proof.WarehouseID || checkpoint.Active != proof.Active):
			failureCode = "OWNER_REVISION_CONFLICT"
			expectedVersion = proof.AggregateVersion
		}
	} else if proof.AggregateVersion != 0 {
		failureCode = "AGGREGATE_VERSION_GAP"
	}
	if failureCode != "" {
		var prior *persistedServiceOwnerProof
		if checkpointFound {
			checkpointProof := checkpoint.persistedProof(proof.OwnerType, proof.InternalOwnerID)
			prior = &checkpointProof
		}
		if err := repository.rejectServiceOwnerProof(ctx, tx, proof, prior, failureCode,
			expectedVersion, proof.AggregateVersion); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ServiceOwnerProofRecord{}, false, err
		}
		return ServiceOwnerProofRecord{}, false, ErrConflict
	}

	if err := applyServiceOwnerProof(ctx, tx, proof, repository.now().UTC()); err != nil {
		return ServiceOwnerProofRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ServiceOwnerProofRecord{}, false, translateConstraint(err)
	}
	return proof.ServiceOwnerProofRecord, false, nil
}

func normalizeServiceOwnerProof(command ServiceOwnerProofCommand) (normalizedServiceOwnerProof, error) {
	definition, found := ServiceOwnerScope(command.OwnerType)
	if !found || command.SourceService != definition.SourceService || command.WarehouseID == uuid.Nil ||
		command.ProofEventID == uuid.Nil || command.OwnerRevision < 0 || command.AggregateVersion < 0 {
		return normalizedServiceOwnerProof{}, ErrConflict
	}
	proof := normalizedServiceOwnerProof{
		ServiceOwnerProofRecord: ServiceOwnerProofRecord{
			SourceService: command.SourceService, OwnerType: command.OwnerType,
			WarehouseID: command.WarehouseID, OwnerRevision: command.OwnerRevision,
			AggregateVersion: command.AggregateVersion, ProofEventID: command.ProofEventID,
			Active: command.Active,
		},
		ConsumerName: definition.ConsumerName, AggregateType: definition.AggregateType,
	}
	if definition.Structured {
		if command.OwnerID != uuid.Nil || command.DocumentID == uuid.Nil || command.LineID == uuid.Nil {
			return normalizedServiceOwnerProof{}, ErrConflict
		}
		proof.DocumentID = command.DocumentID
		proof.LineID = command.LineID
		proof.InternalOwnerID = LogisticsOwnerID(command.DocumentID, command.LineID)
	} else {
		if command.OwnerID == uuid.Nil || command.DocumentID != uuid.Nil || command.LineID != uuid.Nil {
			return normalizedServiceOwnerProof{}, ErrConflict
		}
		proof.OwnerID = command.OwnerID
		proof.InternalOwnerID = command.OwnerID.String()
	}
	proof.AggregateID, found = ServiceOwnerAggregateID(proof.OwnerType, proof.InternalOwnerID)
	if !found {
		return normalizedServiceOwnerProof{}, ErrConflict
	}
	_, proof.RequestSHA256, _ = canonicalJSON(map[string]any{
		"sourceService": proof.SourceService, "ownerType": proof.OwnerType,
		"ownerId": nullableUUID(proof.OwnerID), "documentId": nullableUUID(proof.DocumentID),
		"lineId": nullableUUID(proof.LineID), "warehouseId": proof.WarehouseID,
		"ownerRevision": proof.OwnerRevision, "aggregateVersion": proof.AggregateVersion,
		"proofEventId": proof.ProofEventID, "active": proof.Active,
	})
	return proof, nil
}

func nullableUUID(value uuid.UUID) any {
	if value == uuid.Nil {
		return nil
	}
	return value
}

func lockServiceOwnerProof(ctx context.Context, tx pgx.Tx, proof normalizedServiceOwnerProof) error {
	locks := []string{
		"media-service-owner-proof:event:" + proof.ProofEventID.String(),
		"media-service-owner-proof:owner:" + proof.OwnerType + ":" + proof.InternalOwnerID,
	}
	sort.Strings(locks)
	for _, lock := range locks {
		if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
			return err
		}
	}
	return nil
}

func readServiceOwnerProofReceipt(
	ctx context.Context,
	tx pgx.Tx,
	proofEventID uuid.UUID,
) (persistedServiceOwnerProof, bool, error) {
	row := tx.QueryRow(ctx, `select source_service,consumer_name,owner_type,owner_id,
		document_id,line_id,warehouse_id,owner_revision,aggregate_type,aggregate_id,
		aggregate_version,proof_event_id,active,request_sha256,outcome
		from media_service_owner_proof_receipt where proof_event_id=$1 for update`, proofEventID)
	proof, err := scanPersistedServiceOwnerProof(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return persistedServiceOwnerProof{}, false, nil
	}
	return proof, err == nil, err
}

func readServiceOwnerProofVersion(
	ctx context.Context,
	tx pgx.Tx,
	ownerType, ownerID string,
	aggregateVersion int64,
) (persistedServiceOwnerProof, bool, error) {
	row := tx.QueryRow(ctx, `select source_service,consumer_name,owner_type,owner_id,
		document_id,line_id,warehouse_id,owner_revision,aggregate_type,aggregate_id,
		aggregate_version,proof_event_id,active,request_sha256,outcome
		from media_service_owner_proof_receipt
		where owner_type=$1 and owner_id=$2 and aggregate_version=$3
		order by received_at,proof_event_id limit 1 for update`, ownerType, ownerID, aggregateVersion)
	proof, err := scanPersistedServiceOwnerProof(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return persistedServiceOwnerProof{}, false, nil
	}
	return proof, err == nil, err
}

func scanPersistedServiceOwnerProof(row rowScanner) (persistedServiceOwnerProof, error) {
	var proof persistedServiceOwnerProof
	var ownerID string
	var documentID, lineID *uuid.UUID
	err := row.Scan(&proof.SourceService, &proof.ConsumerName, &proof.OwnerType, &ownerID,
		&documentID, &lineID, &proof.WarehouseID, &proof.OwnerRevision, &proof.AggregateType,
		&proof.AggregateID, &proof.AggregateVersion, &proof.ProofEventID, &proof.Active,
		&proof.RequestSHA256, &proof.Outcome)
	if err != nil {
		return persistedServiceOwnerProof{}, err
	}
	proof.InternalOwnerID = ownerID
	if documentID != nil {
		proof.DocumentID = *documentID
	}
	if lineID != nil {
		proof.LineID = *lineID
	}
	if documentID == nil {
		proof.OwnerID, err = uuid.Parse(ownerID)
		if err != nil {
			return persistedServiceOwnerProof{}, err
		}
	}
	return proof, nil
}

func readServiceOwnerCheckpoint(
	ctx context.Context,
	tx pgx.Tx,
	ownerType, ownerID string,
) (serviceOwnerCheckpoint, bool, error) {
	var checkpoint serviceOwnerCheckpoint
	err := tx.QueryRow(ctx, `select source_service,consumer_name,aggregate_type,aggregate_id,
		aggregate_version,owner_revision,warehouse_id,document_id,line_id,active,
		last_proof_event_id,last_request_sha256,quarantined
		from media_service_owner_proof_checkpoint
		where owner_type=$1 and owner_id=$2 for update`, ownerType, ownerID).Scan(
		&checkpoint.SourceService, &checkpoint.ConsumerName, &checkpoint.AggregateType,
		&checkpoint.AggregateID, &checkpoint.AggregateVersion, &checkpoint.OwnerRevision,
		&checkpoint.WarehouseID, &checkpoint.DocumentID, &checkpoint.LineID, &checkpoint.Active,
		&checkpoint.ProofEventID, &checkpoint.RequestSHA256, &checkpoint.Quarantined)
	if errors.Is(err, pgx.ErrNoRows) {
		return serviceOwnerCheckpoint{}, false, nil
	}
	return checkpoint, err == nil, err
}

func (checkpoint serviceOwnerCheckpoint) persistedProof(ownerType, ownerID string) persistedServiceOwnerProof {
	proof := persistedServiceOwnerProof{
		normalizedServiceOwnerProof: normalizedServiceOwnerProof{
			ServiceOwnerProofRecord: ServiceOwnerProofRecord{
				SourceService: checkpoint.SourceService, OwnerType: ownerType,
				WarehouseID: checkpoint.WarehouseID, OwnerRevision: checkpoint.OwnerRevision,
				AggregateVersion: checkpoint.AggregateVersion, ProofEventID: checkpoint.ProofEventID,
				Active: checkpoint.Active,
			},
			InternalOwnerID: ownerID, ConsumerName: checkpoint.ConsumerName,
			AggregateType: checkpoint.AggregateType, AggregateID: checkpoint.AggregateID,
			RequestSHA256: checkpoint.RequestSHA256,
		},
		Outcome: "APPLIED",
	}
	if checkpoint.DocumentID != nil {
		proof.DocumentID = *checkpoint.DocumentID
		proof.LineID = *checkpoint.LineID
	} else {
		proof.OwnerID = uuid.MustParse(ownerID)
	}
	return proof
}

func applyServiceOwnerProof(
	ctx context.Context,
	tx pgx.Tx,
	proof normalizedServiceOwnerProof,
	recordedAt time.Time,
) error {
	if err := insertServiceOwnerProofReceipt(ctx, tx, proof, "APPLIED", "", recordedAt); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `insert into media_service_owner_proof_checkpoint (
		owner_type,owner_id,source_service,consumer_name,aggregate_type,aggregate_id,
		aggregate_version,owner_revision,warehouse_id,document_id,line_id,active,
		last_proof_event_id,last_request_sha256,quarantined,quarantine_reason,updated_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,false,null,$15)
	on conflict (owner_type,owner_id) do update set
		source_service=excluded.source_service,consumer_name=excluded.consumer_name,
		aggregate_type=excluded.aggregate_type,aggregate_id=excluded.aggregate_id,
		aggregate_version=excluded.aggregate_version,owner_revision=excluded.owner_revision,
		warehouse_id=excluded.warehouse_id,document_id=excluded.document_id,line_id=excluded.line_id,
		active=excluded.active,last_proof_event_id=excluded.last_proof_event_id,
		last_request_sha256=excluded.last_request_sha256,quarantined=false,
		quarantine_reason=null,updated_at=excluded.updated_at`, proof.OwnerType,
		proof.InternalOwnerID, proof.SourceService, proof.ConsumerName, proof.AggregateType,
		proof.AggregateID, proof.AggregateVersion, proof.OwnerRevision, proof.WarehouseID,
		nullableUUID(proof.DocumentID), nullableUUID(proof.LineID), proof.Active,
		proof.ProofEventID, proof.RequestSHA256, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint (
		consumer_name,aggregate_type,aggregate_id,aggregate_version,updated_at)
	values ($1,$2,$3,$4,$5)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		aggregate_version=excluded.aggregate_version,updated_at=excluded.updated_at`,
		proof.ConsumerName, proof.AggregateType, proof.AggregateID, proof.AggregateVersion, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_owner_binding (
		owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,
		proof_consumer_name,proof_aggregate_type,proof_aggregate_id,
		proof_aggregate_version,proof_recorded_at,active,updated_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$10)
	on conflict (owner_type,owner_id) do update set
		warehouse_id=excluded.warehouse_id,owner_revision=excluded.owner_revision,
		proof_event_id=excluded.proof_event_id,proof_consumer_name=excluded.proof_consumer_name,
		proof_aggregate_type=excluded.proof_aggregate_type,
		proof_aggregate_id=excluded.proof_aggregate_id,
		proof_aggregate_version=excluded.proof_aggregate_version,
		proof_recorded_at=excluded.proof_recorded_at,active=excluded.active,
		updated_at=excluded.updated_at`, proof.OwnerType, proof.InternalOwnerID,
		proof.WarehouseID, proof.OwnerRevision, proof.ProofEventID, proof.ConsumerName,
		proof.AggregateType, proof.AggregateID, proof.AggregateVersion, recordedAt, proof.Active)
	return translateConstraint(err)
}

func insertServiceOwnerProofReceipt(
	ctx context.Context,
	tx pgx.Tx,
	proof normalizedServiceOwnerProof,
	outcome, failureCode string,
	recordedAt time.Time,
) error {
	var failure any
	if failureCode != "" {
		failure = failureCode
	}
	_, err := tx.Exec(ctx, `insert into media_service_owner_proof_receipt (
		proof_event_id,source_service,consumer_name,owner_type,owner_id,document_id,line_id,
		warehouse_id,owner_revision,aggregate_type,aggregate_id,aggregate_version,active,
		request_sha256,outcome,failure_code,received_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)`,
		proof.ProofEventID, proof.SourceService, proof.ConsumerName, proof.OwnerType,
		proof.InternalOwnerID, nullableUUID(proof.DocumentID), nullableUUID(proof.LineID),
		proof.WarehouseID, proof.OwnerRevision, proof.AggregateType, proof.AggregateID,
		proof.AggregateVersion, proof.Active, proof.RequestSHA256, outcome, failure, recordedAt)
	return translateConstraint(err)
}

func (repository *Repository) rejectServiceOwnerProof(
	ctx context.Context,
	tx pgx.Tx,
	proof normalizedServiceOwnerProof,
	prior *persistedServiceOwnerProof,
	failureCode string,
	expectedVersion, observedVersion int64,
) error {
	if err := repository.recordServiceOwnerProofConflict(ctx, tx, proof, prior, failureCode); err != nil {
		return err
	}
	if err := insertServiceOwnerProofReceipt(ctx, tx, proof, "QUARANTINED", failureCode,
		repository.now().UTC()); err != nil {
		return err
	}
	reason := failureCode
	switch failureCode {
	case "AGGREGATE_VERSION_GAP":
		reason = "VERSION_GAP"
	case "AGGREGATE_VERSION_REGRESSION":
		reason = "AGGREGATE_VERSION_REGRESSION"
	case "AGGREGATE_VERSION_CONFLICT":
		reason = "EVENT_ID_CONFLICT"
	}
	return repository.quarantineServiceOwnerProof(ctx, tx, proof, expectedVersion,
		observedVersion, reason)
}

func (repository *Repository) recordServiceOwnerProofConflict(
	ctx context.Context,
	tx pgx.Tx,
	proof normalizedServiceOwnerProof,
	prior *persistedServiceOwnerProof,
	failureCode string,
) error {
	var priorEventID, priorOwnerType, priorOwnerID, priorVersion, priorSHA any
	if prior != nil {
		priorEventID = prior.ProofEventID
		priorOwnerType = prior.OwnerType
		priorOwnerID = prior.InternalOwnerID
		priorVersion = prior.AggregateVersion
		priorSHA = prior.RequestSHA256
	}
	_, err := tx.Exec(ctx, `insert into media_service_owner_proof_conflict (
		conflict_id,incoming_proof_event_id,incoming_source_service,incoming_owner_type,
		incoming_owner_id,incoming_aggregate_version,incoming_owner_revision,
		incoming_request_sha256,prior_proof_event_id,prior_owner_type,prior_owner_id,
		prior_aggregate_version,prior_request_sha256,failure_code)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
	on conflict (incoming_proof_event_id,incoming_request_sha256) do nothing`, uuid.New(),
		proof.ProofEventID, proof.SourceService, proof.OwnerType, proof.InternalOwnerID,
		proof.AggregateVersion, proof.OwnerRevision, proof.RequestSHA256, priorEventID,
		priorOwnerType, priorOwnerID, priorVersion, priorSHA, failureCode)
	return translateConstraint(err)
}

func (repository *Repository) quarantineServiceOwnerProof(
	ctx context.Context,
	tx pgx.Tx,
	proof normalizedServiceOwnerProof,
	expectedVersion, observedVersion int64,
	reason string,
) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,$2,$3,$4,$5,$6,$7)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, proof.ConsumerName, proof.AggregateType,
		proof.AggregateID, expectedVersion, observedVersion, reason, proof.ProofEventID)
	if err != nil {
		return translateConstraint(err)
	}
	if _, err = tx.Exec(ctx, `update media_service_owner_proof_checkpoint
		set quarantined=true,quarantine_reason=$3,updated_at=clock_timestamp()
		where owner_type=$1 and owner_id=$2`, proof.OwnerType, proof.InternalOwnerID, reason); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `update media_owner_binding set active=false,updated_at=clock_timestamp()
		where owner_type=$1 and owner_id=$2`, proof.OwnerType, proof.InternalOwnerID)
	return err
}
