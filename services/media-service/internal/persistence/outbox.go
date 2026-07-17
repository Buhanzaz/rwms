package persistence

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type OutboxClaim struct {
	EventID    uuid.UUID
	Topic      string
	RecordKey  uuid.UUID
	Body       []byte
	BodySHA256 string
	Attempt    int
	LeaseToken uuid.UUID
	LeaseFence int64
}

func (repository *Repository) ClaimOutbox(ctx context.Context, owner string, leaseDuration time.Duration) (*OutboxClaim, error) {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var eventID uuid.UUID
	err = tx.QueryRow(ctx, `
		select candidate.event_id
		from media_transport_outbox candidate
		where candidate.next_attempt_at <= clock_timestamp()
		  and (candidate.event_status='PENDING'
		       or (candidate.event_status='PUBLISHING' and candidate.lease_until < clock_timestamp()))
		  and (candidate.depends_on_event_id is null or exists (
			select 1 from media_transport_outbox dependency
			where dependency.event_id=candidate.depends_on_event_id and dependency.event_status='PUBLISHED'))
		  and not exists (
			select 1 from media_transport_outbox prior
			where prior.aggregate_type=candidate.aggregate_type
			  and prior.aggregate_id=candidate.aggregate_id
			  and prior.publication_ordinal<candidate.publication_ordinal
			  and prior.event_status<>'PUBLISHED')
		order by candidate.recorded_at,candidate.publication_ordinal,candidate.event_id
		for update skip locked limit 1`).Scan(&eventID)
	if errors.Is(err, pgx.ErrNoRows) {
		if err := tx.Commit(ctx); err != nil {
			return nil, err
		}
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	claim := &OutboxClaim{EventID: eventID, LeaseToken: uuid.New()}
	err = tx.QueryRow(ctx, `
		update media_transport_outbox
		set event_status='PUBLISHING',attempt_count=attempt_count+1,
			lease_owner=$2,lease_token=$3,lease_fence=lease_fence+1,
			lease_until=clock_timestamp()+$4::interval
		where event_id=$1
		returning topic,record_key,wire_body,envelope_sha256,attempt_count,lease_fence`,
		eventID, owner, claim.LeaseToken, leaseDuration.String()).Scan(
		&claim.Topic, &claim.RecordKey, &claim.Body, &claim.BodySHA256, &claim.Attempt, &claim.LeaseFence)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return claim, nil
}

func (repository *Repository) MarkOutboxPublished(ctx context.Context, claim OutboxClaim) error {
	command, err := repository.pool.Exec(ctx, `
		update media_transport_outbox set event_status='PUBLISHED',published_at=clock_timestamp(),
			lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
		where event_id=$1 and event_status='PUBLISHING' and lease_token=$2 and lease_fence=$3`,
		claim.EventID, claim.LeaseToken, claim.LeaseFence)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return ErrLeaseLost
	}
	return nil
}

func (repository *Repository) MarkOutboxFailed(ctx context.Context, claim OutboxClaim, failureCode string) error {
	delay := time.Second * time.Duration(1<<min(claim.Attempt-1, 4))
	if delay > 30*time.Second {
		delay = 30 * time.Second
	}
	tx, err := repository.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	command, err := tx.Exec(ctx, `
		update media_transport_outbox set event_status='PENDING',
			next_attempt_at=clock_timestamp()+$4::interval,last_error_code=$5,
			lease_owner=null,lease_token=null,lease_until=null
		where event_id=$1 and event_status='PUBLISHING' and lease_token=$2 and lease_fence=$3`,
		claim.EventID, claim.LeaseToken, claim.LeaseFence, delay.String(), failureCode)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return ErrLeaseLost
	}
	return tx.Commit(ctx)
}

func (repository *Repository) MarkOutboxPermanent(ctx context.Context, claim OutboxClaim, failureCode string) error {
	tx, err := repository.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	command, err := tx.Exec(ctx, `update media_transport_outbox
		set event_status='FAILED',last_error_code=$4,
			lease_owner=null,lease_token=null,lease_until=null
		where event_id=$1 and event_status='PUBLISHING' and lease_token=$2 and lease_fence=$3`,
		claim.EventID, claim.LeaseToken, claim.LeaseFence, failureCode)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return ErrLeaseLost
	}
	_, err = tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
		body_sha256,failure_code,attempt_count)
	select 'media-outbox-relay',event_id,aggregate_type,aggregate_id,aggregate_version,
		envelope_sha256,$2,least(attempt_count,4)
	from media_transport_outbox where event_id=$1
	on conflict (consumer_name,event_id) do nothing`, claim.EventID, failureCode)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) ReleaseOutboxLeases(ctx context.Context, owner string) error {
	_, err := repository.pool.Exec(ctx, `update media_transport_outbox
		set event_status='PENDING',lease_owner=null,lease_token=null,lease_until=null
		where event_status='PUBLISHING' and lease_owner=$1`, owner)
	return err
}
