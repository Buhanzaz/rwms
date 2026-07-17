package persistence

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

var ErrReplayParity = errors.New("media event replay does not match the live projection")

type ReplayedAggregate struct {
	AggregateID uuid.UUID
	Version     int64
	State       []byte
	SHA256      string
}

// RebuildShadowProjection deterministically reduces the contiguous local event
// stream into an in-memory projection. Every event carries the complete local
// state; the reducer deliberately never reads the mutable live projection.
func (repository *Repository) RebuildShadowProjection(ctx context.Context, aggregateID uuid.UUID) (ReplayedAggregate, error) {
	rows, err := repository.pool.Query(ctx, `select aggregate_version,payload_wire,payload_sha256
		from media_domain_event where aggregate_type='MEDIA' and aggregate_id=$1
		order by aggregate_version`, aggregateID)
	if err != nil {
		return ReplayedAggregate{}, err
	}
	defer rows.Close()
	result := ReplayedAggregate{AggregateID: aggregateID}
	var expected int64 = 1
	for rows.Next() {
		var version int64
		var wire []byte
		var checksum string
		if err := rows.Scan(&version, &wire, &checksum); err != nil {
			return ReplayedAggregate{}, err
		}
		if version != expected {
			return ReplayedAggregate{}, fmt.Errorf("%w: stream gap at %d, observed %d", ErrReplayParity, expected, version)
		}
		sum := sha256.Sum256(wire)
		if hex.EncodeToString(sum[:]) != checksum || !json.Valid(wire) {
			return ReplayedAggregate{}, fmt.Errorf("%w: corrupt event at version %d", ErrReplayParity, version)
		}
		var identity struct {
			SchemaVersion int `json:"schemaVersion"`
			Asset         struct {
				MediaID uuid.UUID `json:"mediaId"`
			} `json:"asset"`
		}
		if err := json.Unmarshal(wire, &identity); err != nil || identity.SchemaVersion != 1 || identity.Asset.MediaID != aggregateID {
			return ReplayedAggregate{}, fmt.Errorf("%w: invalid full-state identity at version %d", ErrReplayParity, version)
		}
		result.Version = version
		result.State = append(result.State[:0], wire...)
		result.SHA256 = checksum
		expected++
	}
	if err := rows.Err(); err != nil {
		return ReplayedAggregate{}, err
	}
	if result.Version == 0 {
		return ReplayedAggregate{}, ErrNotFound
	}
	var head int64
	if err := repository.pool.QueryRow(ctx, `select stream_version from media_event_stream_head
		where aggregate_type='MEDIA' and aggregate_id=$1`, aggregateID).Scan(&head); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ReplayedAggregate{}, ErrReplayParity
		}
		return ReplayedAggregate{}, err
	}
	if head != result.Version {
		return ReplayedAggregate{}, fmt.Errorf("%w: head %d, replay %d", ErrReplayParity, head, result.Version)
	}
	var snapshotVersion int64
	var snapshotWire []byte
	var snapshotSHA string
	if err := repository.pool.QueryRow(ctx, `select aggregate_version,snapshot_wire,snapshot_sha256
		from media_event_snapshot where aggregate_type='MEDIA' and aggregate_id=$1`, aggregateID).
		Scan(&snapshotVersion, &snapshotWire, &snapshotSHA); err != nil {
		return ReplayedAggregate{}, fmt.Errorf("%w: snapshot: %v", ErrReplayParity, err)
	}
	if snapshotVersion != result.Version || snapshotSHA != result.SHA256 || !bytes.Equal(snapshotWire, result.State) {
		return ReplayedAggregate{}, fmt.Errorf("%w: snapshot is not the replay head", ErrReplayParity)
	}
	return result, nil
}

func (repository *Repository) VerifyReplayParity(ctx context.Context, aggregateID uuid.UUID) error {
	replayed, err := repository.RebuildShadowProjection(ctx, aggregateID)
	if err != nil {
		return err
	}
	live, checksum, err := fullAggregateState(ctx, repository.pool, aggregateID)
	if err != nil {
		return err
	}
	if checksum != replayed.SHA256 || !bytes.Equal(live, replayed.State) {
		return ErrReplayParity
	}
	return nil
}
