package worker

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

const inventoryRecordLimit = 1 << 20

// InventoryOwnerConsumer materializes and validates inventory finding owner
// proofs so public media operations can fail closed on invalid source streams.
type InventoryOwnerConsumer struct {
	repository  inventoryOwnerPersistence
	client      kafkaConsumerClient
	logger      *slog.Logger
	sourceTopic string
	delays      []time.Duration
	sleep       func(context.Context, time.Duration) error
	pollTimeout time.Duration
}

type inventoryOwnerPersistence interface {
	ApplyInventoryFindingMessage(context.Context, persistence.InventoryFindingMessage) (persistence.InventoryFindingApplyResult, error)
	RecordInventoryOwnerDLT(context.Context, persistence.InventoryOwnerDLTMessage) error
	QuarantineInventoryOwnerProcessingFailure(context.Context, persistence.InventoryFindingMessage, int) error
	ScheduleInventoryOwnerRetry(context.Context, persistence.InventoryFindingMessage, int, time.Duration) error
	InventoryOwnerRetryState(context.Context, uuid.UUID, string) (persistence.InventoryOwnerRetryState, bool, error)
	QuarantineInventoryOwnerRetryIdentityConflict(context.Context, persistence.InventoryFindingMessage) error
	ClearInventoryOwnerRetry(context.Context, uuid.UUID) error
}

// InventoryOwnerConsumerOptions permits isolated tests to substitute a
// physical topic, bounded retry delays, and waiting behavior.
type InventoryOwnerConsumerOptions struct {
	SourceTopic string
	RetryDelays []time.Duration
	Sleep       func(context.Context, time.Duration) error
}

// NewInventoryOwnerKafkaConsumer creates a manual-commit client only for the
// canonical inventory owner-proof topic and group.
func NewInventoryOwnerKafkaConsumer(brokers []string, group, topic string) (*kgo.Client, error) {
	if len(brokers) == 0 || group != persistence.InventoryOwnerConsumerGroup ||
		topic != persistence.InventorySessionTopic {
		return nil, fmt.Errorf("inventory owner Kafka configuration is not canonical")
	}
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(inventoryRecordLimit),
	)
}

// NewInventoryOwnerKafkaConsumerForIsolatedTest binds unique physical Kafka
// resources while the parser and persistence contract remain canonical.
func NewInventoryOwnerKafkaConsumerForIsolatedTest(
	brokers []string,
	group string,
	physicalTopic string,
) (*kgo.Client, error) {
	if len(brokers) == 0 || strings.TrimSpace(group) == "" ||
		strings.TrimSpace(physicalTopic) == "" || group == persistence.InventoryOwnerConsumerGroup ||
		physicalTopic == persistence.InventorySessionTopic {
		return nil, fmt.Errorf("isolated inventory owner Kafka resources are required")
	}
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(physicalTopic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(inventoryRecordLimit),
	)
}

// NewInventoryOwnerConsumer constructs the production inventory owner-proof
// consumer with its canonical topic and retry policy.
func NewInventoryOwnerConsumer(
	repository *persistence.Repository,
	client *kgo.Client,
	logger *slog.Logger,
) *InventoryOwnerConsumer {
	return NewInventoryOwnerConsumerWithOptions(repository, client, logger, InventoryOwnerConsumerOptions{})
}

// NewInventoryOwnerConsumerWithOptions constructs an inventory owner consumer
// with explicit test-only delivery and retry collaborators.
func NewInventoryOwnerConsumerWithOptions(
	repository inventoryOwnerPersistence,
	client *kgo.Client,
	logger *slog.Logger,
	options InventoryOwnerConsumerOptions,
) *InventoryOwnerConsumer {
	sourceTopic := strings.TrimSpace(options.SourceTopic)
	if sourceTopic == "" {
		sourceTopic = persistence.InventorySessionTopic
	}
	delays := options.RetryDelays
	if len(delays) == 0 {
		delays = []time.Duration{time.Second, 2 * time.Second, 4 * time.Second}
	} else {
		delays = append([]time.Duration(nil), delays...)
	}
	sleeper := options.Sleep
	if sleeper == nil {
		sleeper = waitInventoryRetry
	}
	return &InventoryOwnerConsumer{
		repository:  repository,
		client:      client,
		logger:      logger,
		sourceTopic: sourceTopic,
		delays:      delays,
		sleep:       sleeper,
		pollTimeout: kafkaConsumerPollTimeout,
	}
}

// Run validates, persists, and acknowledges inventory facts until ctx is
// canceled or a dependency failure stops this runtime process.
func (consumer *InventoryOwnerConsumer) Run(ctx context.Context) error {
	for {
		fetches, pollTimedOut := pollKafkaFetches(ctx, consumer.client, consumer.pollTimeout)
		if ctx.Err() != nil {
			consumer.client.AllowRebalance()
			return nil
		}
		if pollTimedOut {
			consumer.client.AllowRebalance()
			continue
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			consumer.client.AllowRebalance()
			consumer.logger.Error("poll inventory owner topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handle(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				consumer.logger.Error("process inventory owner fact", "eventOffset", record.Offset,
					"errorType", "DEPENDENCY_ERROR")
				return err
			}
			if err := consumer.client.CommitRecords(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				return err
			}
		}
		consumer.client.AllowRebalance()
	}
}

func (consumer *InventoryOwnerConsumer) handle(ctx context.Context, record *kgo.Record) error {
	logicalRecord := record
	if record != nil && record.Topic == consumer.sourceTopic && consumer.sourceTopic != persistence.InventorySessionTopic {
		copyRecord := *record
		copyRecord.Topic = persistence.InventorySessionTopic
		logicalRecord = &copyRecord
	}
	message, ignored, err := parseInventoryFindingRecord(logicalRecord)
	if err != nil {
		return consumer.repository.RecordInventoryOwnerDLT(ctx,
			invalidInventoryOwnerDLT(record, "INVALID_INVENTORY_OWNER_FACT", 0))
	}
	if ignored {
		return nil
	}
	attempt := 0
	state, found, stateErr := consumer.repository.InventoryOwnerRetryState(ctx,
		message.EventID, message.BodySHA256)
	if errors.Is(stateErr, persistence.ErrIdempotencyMismatch) {
		return consumer.repository.QuarantineInventoryOwnerRetryIdentityConflict(ctx, message)
	}
	if stateErr == nil && found {
		attempt = state.Attempt
		if remaining := time.Until(state.AvailableAt); remaining > 0 {
			if err := consumer.sleep(ctx, remaining); err != nil {
				return err
			}
		}
	}
	for ; ; attempt++ {
		_, applyErr := consumer.repository.ApplyInventoryFindingMessage(ctx, message)
		if applyErr == nil {
			return consumer.repository.ClearInventoryOwnerRetry(ctx, message.EventID)
		}
		if deterministicInventoryOwnerFailure(applyErr) {
			return consumer.repository.RecordInventoryOwnerDLT(ctx,
				inventoryOwnerMessageDLT(message, "INVALID_INVENTORY_OWNER_FACT", attempt+1))
		}
		if attempt >= len(consumer.delays) {
			return consumer.repository.QuarantineInventoryOwnerProcessingFailure(
				ctx, message, attempt+1)
		}
		// Durable retry bookkeeping is best-effort. A PostgreSQL outage must not
		// bypass the bounded in-memory 1s/2s/4s attempt budget.
		_ = consumer.repository.ScheduleInventoryOwnerRetry(ctx, message,
			attempt+1, consumer.delays[attempt])
		if err := consumer.sleep(ctx, consumer.delays[attempt]); err != nil {
			return err
		}
	}
}

func deterministicInventoryOwnerFailure(err error) bool {
	return errors.Is(err, persistence.ErrConflict) ||
		errors.Is(err, persistence.ErrIdempotencyMismatch) ||
		errors.Is(err, persistence.ErrVersionGap) ||
		errors.Is(err, persistence.ErrOwnerProofConflict) ||
		errors.Is(err, persistence.ErrAggregateBlocked) ||
		errors.Is(err, persistence.ErrReconciliation)
}

func inventoryOwnerMessageDLT(
	message persistence.InventoryFindingMessage,
	failureCode string,
	attemptCount int,
) persistence.InventoryOwnerDLTMessage {
	version := message.AggregateVersion
	return persistence.InventoryOwnerDLTMessage{
		SourceEventID: message.EventID, BodySHA256: message.BodySHA256,
		AggregateID: &message.AggregateID, AggregateVersion: &version,
		RecordKey: message.RecordKey, FailureCode: failureCode, AttemptCount: attemptCount,
	}
}

// Close stops the underlying inventory Kafka client.
func (consumer *InventoryOwnerConsumer) Close() {
	consumer.client.Close()
}

type inventoryEnvelope struct {
	EnvelopeVersion  int             `json:"envelopeVersion"`
	EventID          string          `json:"eventId"`
	EventType        string          `json:"eventType"`
	EventVersion     int             `json:"eventVersion"`
	OccurredAt       json.RawMessage `json:"occurredAt"`
	RecordedAt       string          `json:"recordedAt"`
	Producer         string          `json:"producer"`
	AggregateType    string          `json:"aggregateType"`
	AggregateID      string          `json:"aggregateId"`
	AggregateVersion int64           `json:"aggregateVersion"`
	Correlation      struct {
		CorrelationID string          `json:"correlationId"`
		CausationID   json.RawMessage `json:"causationId"`
	} `json:"correlation"`
	ActorRef json.RawMessage `json:"actorRef"`
	Payload  json.RawMessage `json:"payload"`
}

type inventoryOwnerProofPayload struct {
	OwnerType     string `json:"ownerType"`
	OwnerID       string `json:"ownerId"`
	WarehouseID   string `json:"warehouseId"`
	OwnerRevision int64  `json:"ownerRevision"`
	Active        bool   `json:"active"`
}

type inventoryFindingPayload struct {
	InventoryID      string          `json:"inventoryId"`
	FindingID        string          `json:"findingId"`
	WarehouseID      string          `json:"warehouseId"`
	SessionRevision  int64           `json:"sessionRevision"`
	FindingRevision  int64           `json:"findingRevision"`
	Origin           string          `json:"origin"`
	Inspection       string          `json:"inspection"`
	Reconciliation   string          `json:"reconciliation"`
	MembershipActive *bool           `json:"membershipActive"`
	AssetID          json.RawMessage `json:"assetId"`
	SourceAttached   bool            `json:"sourceAttached"`
	MediaCount       int             `json:"mediaCount"`
	PlanFingerprint  json.RawMessage `json:"planFingerprintSha256"`
}

// parseInventoryFindingRecord strictly validates live Kafka records, including
// the membershipActive value required on every finding lifecycle marker.
func parseInventoryFindingRecord(record *kgo.Record) (persistence.InventoryFindingMessage, bool, error) {
	return parseInventoryFindingRecordWithLegacyLifecycle(record, false)
}

// parseInventoryFindingReconciliationRecord validates operator-reviewed replay
// bytes without rewriting them. It accepts only the historical lifecycle
// marker omission that predates membershipActive and infers the expected value
// from the event type; every other live-parser invariant remains unchanged.
func parseInventoryFindingReconciliationRecord(
	record *kgo.Record,
) (persistence.InventoryFindingMessage, bool, error) {
	return parseInventoryFindingRecordWithLegacyLifecycle(record, true)
}

// parseInventoryFindingRecordWithLegacyLifecycle centralizes strict parsing;
// its compatibility switch is enabled only by the reviewed file command.
func parseInventoryFindingRecordWithLegacyLifecycle(
	record *kgo.Record,
	allowLegacyLifecycleMembershipInference bool,
) (persistence.InventoryFindingMessage, bool, error) {
	if record == nil || record.Topic != persistence.InventorySessionTopic || len(record.Value) == 0 ||
		len(record.Value) > inventoryRecordLimit {
		return persistence.InventoryFindingMessage{}, false, errors.New("invalid inventory record")
	}
	decoder := json.NewDecoder(bytes.NewReader(record.Value))
	decoder.DisallowUnknownFields()
	var envelope inventoryEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return persistence.InventoryFindingMessage{}, false, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return persistence.InventoryFindingMessage{}, false, errors.New("trailing inventory record data")
	}
	eventID, eventErr := strictUUID(envelope.EventID)
	aggregateID, aggregateErr := strictUUID(envelope.AggregateID)
	recordKey, keyErr := strictUUID(string(record.Key))
	_, correlationErr := strictUUID(envelope.Correlation.CorrelationID)
	recordedAt, recordedErr := time.Parse(time.RFC3339Nano, envelope.RecordedAt)
	if eventErr != nil || aggregateErr != nil || keyErr != nil || correlationErr != nil || recordedErr != nil ||
		envelope.EnvelopeVersion != 2 || envelope.EventVersion != 1 || envelope.Producer != "inventory-service" ||
		envelope.AggregateVersion < 0 || recordKey != aggregateID ||
		!nullableInventoryDateTime(envelope.OccurredAt) || !nullableInventoryUUID(envelope.Correlation.CausationID) ||
		!validInventoryActor(envelope.ActorRef) {
		return persistence.InventoryFindingMessage{}, false, errors.New("invalid inventory envelope")
	}
	if envelope.AggregateType == "SESSION" {
		if !setContains(envelope.EventType, "inventory.session.started.v1", "inventory.session.completed.v1",
			"inventory.session.cancelled.v1") || !jsonObject(envelope.Payload) {
			return persistence.InventoryFindingMessage{}, false, errors.New("invalid inventory session fact")
		}
		return persistence.InventoryFindingMessage{}, true, nil
	}
	if envelope.AggregateType != persistence.InventoryFindingAggregate {
		return persistence.InventoryFindingMessage{}, false, errors.New("invalid inventory aggregate family")
	}
	sum := sha256.Sum256(record.Value)
	message := persistence.InventoryFindingMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: append([]byte(nil), record.Value...),
		Topic: record.Topic, EventType: envelope.EventType, AggregateType: envelope.AggregateType,
		AggregateID: aggregateID, AggregateVersion: envelope.AggregateVersion, RecordKey: recordKey,
		RecordedAt: recordedAt.UTC(),
	}
	switch envelope.EventType {
	case "inventory.finding.added.v1", "inventory.finding.inspection-saved.v1",
		"inventory.finding.membership-departed.v1", "inventory.finding.membership-refreshed.v1",
		"inventory.finding.membership-restored.v1":
		warehouseID, err := validateFindingMarker(envelope.Payload, aggregateID, envelope.EventType,
			allowLegacyLifecycleMembershipInference)
		if err != nil {
			return persistence.InventoryFindingMessage{}, false, err
		}
		message.WarehouseID = warehouseID
	case persistence.InventoryOwnerProofEvent:
		proof, err := validateOwnerProof(envelope.Payload, aggregateID)
		if err != nil {
			return persistence.InventoryFindingMessage{}, false, err
		}
		message.Proof = &proof
		message.WarehouseID = proof.WarehouseID
	default:
		return persistence.InventoryFindingMessage{}, false, errors.New("invalid inventory finding event")
	}
	return message, false, nil
}

func validateOwnerProof(raw json.RawMessage, aggregateID uuid.UUID) (persistence.InventoryOwnerProof, error) {
	if err := exactJSONFields(raw, "ownerType", "ownerId", "warehouseId", "ownerRevision", "active"); err != nil {
		return persistence.InventoryOwnerProof{}, err
	}
	var payload inventoryOwnerProofPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return persistence.InventoryOwnerProof{}, err
	}
	ownerID, ownerErr := strictUUID(payload.OwnerID)
	warehouseID, warehouseErr := strictUUID(payload.WarehouseID)
	if ownerErr != nil || warehouseErr != nil || ownerID != aggregateID ||
		payload.OwnerType != persistence.OwnerTypeInventoryFinding || payload.OwnerRevision < 0 {
		return persistence.InventoryOwnerProof{}, errors.New("invalid inventory owner proof")
	}
	return persistence.InventoryOwnerProof{
		OwnerType: payload.OwnerType, OwnerID: ownerID, WarehouseID: warehouseID,
		OwnerRevision: payload.OwnerRevision, Active: payload.Active,
	}, nil
}

func validateFindingMarker(
	raw json.RawMessage,
	aggregateID uuid.UUID,
	eventType string,
	allowLegacyLifecycleMembershipInference bool,
) (uuid.UUID, error) {
	if err := exactJSONFieldsWithOptional(raw, []string{"membershipActive"},
		"inventoryId", "findingId", "warehouseId", "sessionRevision",
		"findingRevision", "origin", "inspection", "reconciliation", "assetId", "sourceAttached",
		"mediaCount", "planFingerprintSha256"); err != nil {
		return uuid.Nil, err
	}
	var payload inventoryFindingPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return uuid.Nil, err
	}
	var payloadFields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &payloadFields); err != nil {
		return uuid.Nil, err
	}
	_, membershipActivePresent := payloadFields["membershipActive"]
	_, inventoryErr := strictUUID(payload.InventoryID)
	findingID, findingErr := strictUUID(payload.FindingID)
	warehouseID, warehouseErr := strictUUID(payload.WarehouseID)
	if inventoryErr != nil || findingErr != nil || warehouseErr != nil || findingID != aggregateID ||
		payload.SessionRevision < 0 || payload.FindingRevision < 0 || payload.MediaCount < 0 ||
		payload.MediaCount > 100 || !setContains(payload.Origin, "EXPECTED", "ADDED_NEW", "ADDED_USED", "UNEXPECTED_EXISTING") ||
		!setContains(payload.Inspection, "NOT_INSPECTED", "READY", "WORK_STAGED") ||
		!setContains(payload.Reconciliation, "MATCHED", "MISSING", "CONFLICT") ||
		!nullableInventoryUUID(payload.AssetID) || !nullableSHA256(payload.PlanFingerprint) {
		return uuid.Nil, errors.New("invalid inventory finding marker")
	}
	membershipActive := payload.MembershipActive
	if membershipActive == nil && !membershipActivePresent && allowLegacyLifecycleMembershipInference {
		if eventType == "inventory.finding.membership-departed.v1" {
			inactive := false
			membershipActive = &inactive
		}
		if setContains(eventType, "inventory.finding.membership-refreshed.v1",
			"inventory.finding.membership-restored.v1") {
			active := true
			membershipActive = &active
		}
	}
	if eventType == "inventory.finding.membership-departed.v1" &&
		(membershipActive == nil || *membershipActive) {
		return uuid.Nil, errors.New("invalid departed inventory finding marker")
	}
	if setContains(eventType, "inventory.finding.membership-refreshed.v1",
		"inventory.finding.membership-restored.v1") &&
		(membershipActive == nil || !*membershipActive) {
		return uuid.Nil, errors.New("invalid active inventory finding marker")
	}
	return warehouseID, nil
}

func exactJSONFieldsWithOptional(
	raw json.RawMessage,
	optional []string,
	required ...string,
) error {
	var value map[string]json.RawMessage
	if err := json.Unmarshal(raw, &value); err != nil {
		return errors.New("invalid JSON object fields")
	}
	allowed := make(map[string]struct{}, len(required)+len(optional))
	for _, name := range required {
		allowed[name] = struct{}{}
		if _, exists := value[name]; !exists {
			return errors.New("invalid JSON object fields")
		}
	}
	for _, name := range optional {
		allowed[name] = struct{}{}
	}
	for name := range value {
		if _, exists := allowed[name]; !exists {
			return errors.New("invalid JSON object fields")
		}
	}
	return nil
}

func exactJSONFields(raw json.RawMessage, expected ...string) error {
	var value map[string]json.RawMessage
	if err := json.Unmarshal(raw, &value); err != nil || len(value) != len(expected) {
		return errors.New("invalid JSON object fields")
	}
	for _, name := range expected {
		if _, exists := value[name]; !exists {
			return errors.New("invalid JSON object fields")
		}
	}
	return nil
}

func validInventoryActor(raw json.RawMessage) bool {
	if isNull(raw) {
		return true
	}
	if exactJSONFields(raw, "subjectId", "principalType", "profileRevision") != nil {
		return false
	}
	var actor struct {
		SubjectID       string          `json:"subjectId"`
		PrincipalType   string          `json:"principalType"`
		ProfileRevision json.RawMessage `json:"profileRevision"`
	}
	if json.Unmarshal(raw, &actor) != nil || !setContains(actor.PrincipalType, "USER", "SERVICE") {
		return false
	}
	if _, err := strictUUID(actor.SubjectID); err != nil {
		return false
	}
	if isNull(actor.ProfileRevision) {
		return true
	}
	var revision string
	if json.Unmarshal(actor.ProfileRevision, &revision) != nil {
		return false
	}
	if _, err := strictUUID(revision); err == nil {
		return true
	}
	return validLowerSHA256(revision)
}

func invalidInventoryOwnerDLT(record *kgo.Record, failureCode string, attempts int) persistence.InventoryOwnerDLTMessage {
	var raw []byte
	if record != nil {
		raw = record.Value
	}
	sum := sha256.Sum256(raw)
	hash := hex.EncodeToString(sum[:])
	deterministicID := uuid.NewSHA1(uuid.NameSpaceOID, []byte(hash))
	message := persistence.InventoryOwnerDLTMessage{
		SourceEventID: deterministicID, BodySHA256: hash, RecordKey: deterministicID,
		FailureCode: failureCode, AttemptCount: attempts,
	}
	if record == nil {
		return message
	}
	key, keyErr := strictUUID(string(record.Key))
	var identity struct {
		AggregateID      string `json:"aggregateId"`
		AggregateVersion int64  `json:"aggregateVersion"`
	}
	aggregate, aggregateErr := strictUUID(identity.AggregateID)
	if json.Unmarshal(record.Value, &identity) == nil {
		aggregate, aggregateErr = strictUUID(identity.AggregateID)
	}
	if keyErr == nil && aggregateErr == nil && key == aggregate && identity.AggregateVersion >= 0 {
		message.RecordKey = key
		message.AggregateID = &aggregate
		message.AggregateVersion = &identity.AggregateVersion
	}
	return message
}

func strictUUID(value string) (uuid.UUID, error) {
	identifier, err := uuid.Parse(value)
	if err != nil || identifier == uuid.Nil || identifier.String() != value {
		return uuid.Nil, errors.New("invalid canonical UUID")
	}
	return identifier, nil
}

func nullableInventoryDateTime(value json.RawMessage) bool {
	if isNull(value) {
		return true
	}
	var text string
	return json.Unmarshal(value, &text) == nil && validDateTime(text)
}

func nullableInventoryUUID(value json.RawMessage) bool {
	if isNull(value) {
		return true
	}
	var text string
	if json.Unmarshal(value, &text) != nil {
		return false
	}
	_, err := strictUUID(text)
	return err == nil
}

func nullableSHA256(value json.RawMessage) bool {
	if isNull(value) {
		return true
	}
	var text string
	return json.Unmarshal(value, &text) == nil && validLowerSHA256(text)
}

func validLowerSHA256(value string) bool {
	if len(value) != sha256.Size*2 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func validDateTime(value string) bool {
	_, err := time.Parse(time.RFC3339Nano, value)
	return err == nil
}

func jsonObject(value json.RawMessage) bool {
	var object map[string]json.RawMessage
	return json.Unmarshal(value, &object) == nil && object != nil
}

func setContains(value string, allowed ...string) bool {
	for _, candidate := range allowed {
		if value == candidate {
			return true
		}
	}
	return false
}

func waitInventoryRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
