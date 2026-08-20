package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestBoundedProcessingRecoveryMigrationTreatsAmbiguousDLTAsLegacyIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL := testsupport.NewIsolatedPostgresDatabase(t, baseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	pool := openResidualPool(t, ctx, databaseURL)
	installResidualMigrations(t, ctx, pool, 12)

	mediaID, jobID := uuid.New(), uuid.New()
	if _, err := pool.Exec(ctx, `insert into media_asset (
		media_id,folder_id,owner_type,owner_id,warehouse_id,media_kind,
		original_file_name,original_content_type,source_object_key,
		processing_status,current_generation,next_generation,source_version_id,
		source_etag,source_checksum_sha256,finalized_content_type,
		finalized_size_bytes,size_bytes,version)
	values ($1,$1,'INVENTORY_FINDING',$2,$3,'IMAGE','legacy.jpg','image/jpeg',$4,
		'FAILED',0,2,'legacy-version','legacy-etag',$5,'image/jpeg',128,128,1)`,
		mediaID, uuid.NewString(), uuid.New(), "media/"+mediaID.String()+"/source/legacy.jpg",
		hex64('a')); err != nil {
		pool.Close()
		t.Fatalf("seed legacy failed asset: %v", err)
	}
	if _, err := pool.Exec(ctx, `insert into media_processing_job (
		processing_job_id,media_id,generation,processing_kind,
		requested_rotation_degrees,job_status,attempt_count,source_version_id,
		source_checksum_sha256,next_attempt_at,completed_at)
	values ($1,$2,1,'INITIAL',0,'FAILED',7,'legacy-version',$3,
		clock_timestamp(),clock_timestamp())`, jobID, mediaID, hex64('a')); err != nil {
		pool.Close()
		t.Fatalf("seed legacy failed job: %v", err)
	}
	for index, failure := range []string{"VALIDATION_FAILED", "PROCESSING_DEPENDENCY_UNAVAILABLE"} {
		if _, err := pool.Exec(ctx, `insert into media_dead_letter (
			consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
			body_sha256,failure_code,attempt_count,recorded_at)
		values ($1,$2,'PROCESSING_JOB',$3,1,$4,$5,4,
			clock_timestamp()+$6::interval)`, processingConsumer, uuid.New(), jobID,
			hex64(byte('b'+index)), failure, (time.Duration(index) * time.Second).String()); err != nil {
			pool.Close()
			t.Fatalf("seed ambiguous DLT %d: %v", index, err)
		}
	}
	applyResidualMigration(t, ctx, pool, 13, "11", "bounded media processing recovery",
		"V11__bounded_media_processing_recovery.sql", mediamigration.V11)

	var terminalCount, attemptInCycle, terminalAttempt int
	var failureCode string
	var eventID, bodySHA *string
	if err := pool.QueryRow(ctx, `select
		(select count(*) from media_processing_terminal where processing_job_id=$1),
		job.attempt_in_cycle,terminal.failure_code,terminal.attempt_count,
		terminal.source_event_id::text,terminal.source_body_sha256
	from media_processing_job job
	join media_processing_terminal terminal
	  on terminal.processing_job_id=job.processing_job_id
	where job.processing_job_id=$1`, jobID).Scan(&terminalCount, &attemptInCycle,
		&failureCode, &terminalAttempt, &eventID, &bodySHA); err != nil {
		pool.Close()
		t.Fatalf("read V11 ambiguous recovery state: %v", err)
	}
	if terminalCount != 1 || attemptInCycle != 4 || terminalAttempt != 4 ||
		failureCode != "LEGACY_TERMINAL" || eventID != nil || bodySHA != nil {
		t.Fatalf("V11 terminal count=%d cycle=%d code=%s attempt=%d event=%v hash=%v",
			terminalCount, attemptInCycle, failureCode, terminalAttempt, eventID, bodySHA)
	}
	applyResidualMigration(t, ctx, pool, 14, "12", "video playback variant",
		"V12__video_playback_variant.sql", mediamigration.V12)
	applyResidualMigration(t, ctx, pool, 15, "13", "authoritative inventory cabin photos",
		"V13__authoritative_inventory_cabin_photos.sql", mediamigration.V13)
	pool.Close()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open upgraded V13 ambiguous database: %v", err)
	}
	database.Close()
}

func TestProcessingTerminalReviewIsVersionedIdempotentAndDoesNotRequeueIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	ctx, repository, database := newProcessingConflictRepository(t, baseURL)
	message := createAuthoritativeProcessingMessage(t, ctx, repository)
	claim, err := repository.ClaimProcessingJob(ctx, message, "terminal-review-worker", time.Minute)
	if err != nil || claim.Duplicate || claim.Job.AttemptInCycle != 1 {
		t.Fatalf("claim reviewed terminal work = %#v, %v", claim, err)
	}
	if _, terminal, err := repository.RecordProcessingFailure(
		ctx, claim.Job, "VALIDATION_FAILED"); err != nil || !terminal {
		t.Fatalf("record reviewed terminal = terminal:%v error:%v", terminal, err)
	}

	before, err := repository.ProcessingRecoveryTelemetry(ctx)
	if err != nil || before.PendingValidationReviews != 1 {
		t.Fatalf("telemetry before review = %#v, %v", before, err)
	}
	review := ProcessingRetryReview{
		ReviewID: uuid.New(), ProcessingJobID: claim.Job.JobID, TerminalVersion: 1,
		ReviewedBySubjectID: uuid.New(), Decision: ProcessingRetryApproved,
		Reason: ProcessingReviewSourceRepaired,
	}
	wrongVersion := review
	wrongVersion.TerminalVersion = 2
	if replayed, err := repository.ReviewProcessingTerminal(ctx, wrongVersion); replayed ||
		!errors.Is(err, ErrConflict) {
		t.Fatalf("wrong terminal version replayed=%v error=%v", replayed, err)
	}
	if replayed, err := repository.ReviewProcessingTerminal(ctx, review); err != nil || replayed {
		t.Fatalf("first terminal review replayed=%v error=%v", replayed, err)
	}
	if replayed, err := repository.ReviewProcessingTerminal(ctx, review); err != nil || !replayed {
		t.Fatalf("exact terminal review replayed=%v error=%v", replayed, err)
	}
	changed := review
	changed.Reason = ProcessingReviewDependencyRecovered
	if replayed, err := repository.ReviewProcessingTerminal(ctx, changed); replayed ||
		!errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("changed review replayed=%v error=%v", replayed, err)
	}

	after, err := repository.ProcessingRecoveryTelemetry(ctx)
	if err != nil || after.PendingValidationReviews != 0 {
		t.Fatalf("telemetry after review = %#v, %v", after, err)
	}
	var status string
	var processingRequests, reviewRows int
	if err := database.Pool.QueryRow(ctx, `select job.job_status,
		(select count(*) from media_transport_outbox
		 where aggregate_id=$1 and event_type='media.processing.request.v1'),
		(select count(*) from media_processing_retry_review
		 where processing_job_id=$1)
	from media_processing_job job where job.processing_job_id=$1`, claim.Job.JobID).Scan(
		&status, &processingRequests, &reviewRows); err != nil {
		t.Fatalf("read reviewed terminal state: %v", err)
	}
	if status != "FAILED" || processingRequests != 1 || reviewRows != 1 {
		t.Fatalf("review mutated work status=%s requests=%d reviews=%d",
			status, processingRequests, reviewRows)
	}
}

func TestProcessingDependencyFailureTerminalizesOnFourthAttemptIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	ctx, repository, database := newProcessingConflictRepository(t, baseURL)
	message := createAuthoritativeProcessingMessage(t, ctx, repository)

	for attempt := 1; attempt <= 4; attempt++ {
		claim, err := repository.ClaimProcessingJob(ctx, message,
			"dependency-terminal-worker", time.Minute)
		if err != nil || claim.Duplicate || claim.Job.AttemptInCycle != attempt {
			t.Fatalf("claim dependency attempt %d = %#v, %v", attempt, claim, err)
		}
		delay, terminal, err := repository.RecordProcessingFailure(
			ctx, claim.Job, "PROCESSING_DEPENDENCY_UNAVAILABLE")
		if err != nil {
			t.Fatalf("record dependency attempt %d: %v", attempt, err)
		}
		if attempt < 4 {
			expectedDelay := time.Second * time.Duration(1<<(attempt-1))
			if terminal || delay != expectedDelay {
				t.Fatalf("dependency attempt %d delay=%v terminal=%v", attempt, delay, terminal)
			}
			command, err := database.Pool.Exec(ctx, `update media_processing_job
				set next_attempt_at=clock_timestamp()
				where processing_job_id=$1 and job_status='PENDING'`, claim.Job.JobID)
			if err != nil || command.RowsAffected() != 1 {
				t.Fatalf("make dependency attempt %d immediately claimable: rows=%d error=%v",
					attempt+1, command.RowsAffected(), err)
			}
			continue
		}
		if !terminal || delay != 0 {
			t.Fatalf("fourth dependency attempt delay=%v terminal=%v", delay, terminal)
		}
	}

	var status string
	var attempts, cycle, terminalRows, retryRows, deadLetters, inboxRows int
	if err := database.Pool.QueryRow(ctx, `select job.job_status,job.attempt_count,
		job.attempt_in_cycle,
		(select count(*) from media_processing_terminal terminal
		 where terminal.processing_job_id=job.processing_job_id
		   and terminal.failure_code='PROCESSING_DEPENDENCY_UNAVAILABLE'
		   and terminal.attempt_count=4),
		(select count(*) from media_retry_schedule retry
		 where retry.consumer_name=$2 and retry.event_id=$3),
		(select count(*) from media_dead_letter dead
		 where dead.consumer_name=$2 and dead.event_id=$3
		   and dead.failure_code='PROCESSING_DEPENDENCY_UNAVAILABLE'
		   and dead.attempt_count=4),
		(select count(*) from media_processing_inbox inbox
		 where inbox.consumer_name=$2 and inbox.event_id=$3 and inbox.outcome='DLT')
		from media_processing_job job where job.processing_job_id=$1`, message.AggregateID,
		processingConsumer, message.EventID).Scan(&status, &attempts, &cycle,
		&terminalRows, &retryRows, &deadLetters, &inboxRows); err != nil {
		t.Fatalf("read fourth dependency terminal state: %v", err)
	}
	if status != "FAILED" || attempts != 4 || cycle != 4 || terminalRows != 1 ||
		retryRows != 0 || deadLetters != 1 || inboxRows != 1 {
		t.Fatalf("fourth dependency terminal status=%s attempts=%d cycle=%d terminals=%d retries=%d DLT=%d inbox=%d",
			status, attempts, cycle, terminalRows, retryRows, deadLetters, inboxRows)
	}
}

func TestProcessingExpiredFourthAttemptReclaimIsFencedAndIdempotentIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	ctx, repository, database := newProcessingConflictRepository(t, baseURL)
	message := createAuthoritativeProcessingMessage(t, ctx, repository)

	for attempt := 1; attempt <= 3; attempt++ {
		claim, err := repository.ClaimProcessingJob(ctx, message,
			"expired-attempt-worker", time.Minute)
		if err != nil || claim.Duplicate || claim.AttemptBudgetExhausted ||
			claim.Job.AttemptInCycle != attempt {
			t.Fatalf("claim pre-crash attempt %d = %#v, %v", attempt, claim, err)
		}
		if _, terminal, err := repository.RecordProcessingFailure(
			ctx, claim.Job, "PROCESSING_DEPENDENCY_UNAVAILABLE"); err != nil || terminal {
			t.Fatalf("record pre-crash attempt %d terminal=%v error=%v", attempt, terminal, err)
		}
		if _, err := database.Pool.Exec(ctx, `update media_processing_job
			set next_attempt_at=clock_timestamp()
			where processing_job_id=$1 and job_status='PENDING'`, claim.Job.JobID); err != nil {
			t.Fatalf("make pre-crash attempt %d claimable: %v", attempt+1, err)
		}
	}
	fourth, err := repository.ClaimProcessingJob(ctx, message, "crashing-fourth-worker", time.Minute)
	if err != nil || fourth.Duplicate || fourth.AttemptBudgetExhausted ||
		fourth.Job.Attempt != 4 || fourth.Job.AttemptInCycle != 4 {
		t.Fatalf("claim fresh fourth attempt = %#v, %v", fourth, err)
	}
	command, err := database.Pool.Exec(ctx, `update media_processing_job
		set lease_until=clock_timestamp()-interval '1 second'
		where processing_job_id=$1 and job_status='RUNNING' and lease_token=$2`,
		fourth.Job.JobID, fourth.Job.LeaseToken)
	if err != nil || command.RowsAffected() != 1 {
		t.Fatalf("expire crashed fourth attempt rows=%d error=%v", command.RowsAffected(), err)
	}

	outcomes := make(chan struct {
		claim ClaimResult
		err   error
	}, 2)
	start := make(chan struct{})
	for _, owner := range []string{"exhausted-reclaim-a", "exhausted-reclaim-b"} {
		go func(owner string) {
			<-start
			claim, claimErr := repository.ClaimProcessingJob(ctx, message, owner, time.Minute)
			outcomes <- struct {
				claim ClaimResult
				err   error
			}{claim: claim, err: claimErr}
		}(owner)
	}
	close(start)
	var exhausted WorkerJob
	successes, rejected := 0, 0
	for range 2 {
		outcome := <-outcomes
		if outcome.err == nil {
			successes++
			exhausted = outcome.claim.Job
			if !outcome.claim.AttemptBudgetExhausted || outcome.claim.Duplicate ||
				outcome.claim.Job.Attempt != 4 || outcome.claim.Job.AttemptInCycle != 4 {
				t.Fatalf("exhausted reclaim = %#v", outcome.claim)
			}
			continue
		}
		rejected++
	}
	if successes != 1 || rejected != 1 {
		t.Fatalf("concurrent exhausted reclaims successes=%d rejected=%d", successes, rejected)
	}
	if err := repository.RecordProcessingAttemptExhaustion(ctx, exhausted); err != nil {
		t.Fatalf("terminalize exhausted fourth attempt: %v", err)
	}
	if err := repository.RecordProcessingAttemptExhaustion(ctx, exhausted); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("repeat exhausted terminalization error=%v, want ErrLeaseLost", err)
	}
	replay, err := repository.ClaimProcessingJob(ctx, message, "post-terminal-replay", time.Minute)
	if err != nil || !replay.Duplicate || replay.AttemptBudgetExhausted {
		t.Fatalf("post-terminal exhausted replay = %#v, %v", replay, err)
	}

	beforeReview, err := repository.ProcessingRecoveryTelemetry(ctx)
	if err != nil || beforeReview.PendingExhaustedReviews != 1 {
		t.Fatalf("exhausted telemetry before review = %#v, %v", beforeReview, err)
	}
	review := ProcessingRetryReview{
		ReviewID: uuid.New(), ProcessingJobID: exhausted.JobID, TerminalVersion: 1,
		ReviewedBySubjectID: uuid.New(), Decision: ProcessingRetryApproved,
		Reason: ProcessingReviewAttemptBudgetReset,
	}
	if replayed, err := repository.ReviewProcessingTerminal(ctx, review); err != nil || replayed {
		t.Fatalf("review exhausted terminal replayed=%v error=%v", replayed, err)
	}
	afterReview, err := repository.ProcessingRecoveryTelemetry(ctx)
	if err != nil || afterReview.PendingExhaustedReviews != 0 {
		t.Fatalf("exhausted telemetry after review = %#v, %v", afterReview, err)
	}

	var status, failureCode, outboxFailureCode string
	var attempts, cycle, terminalRows, deadLetters, dltOutbox, reviewRows int
	if err := database.Pool.QueryRow(ctx, `select job.job_status,job.attempt_count,
		job.attempt_in_cycle,terminal.failure_code,
		(select count(*) from media_processing_terminal
		 where processing_job_id=job.processing_job_id),
		(select count(*) from media_dead_letter dead
		 where dead.consumer_name=$2 and dead.event_id=$3
		   and dead.failure_code='PROCESSING_ATTEMPT_EXHAUSTED'
		   and dead.attempt_count=4),
		(select count(*) from media_transport_outbox outbox
		 where outbox.aggregate_id=job.processing_job_id
		   and outbox.event_type='media.processing.dlt.v1'),
		(select outbox.envelope_body->>'failureCode' from media_transport_outbox outbox
		 where outbox.aggregate_id=job.processing_job_id
		   and outbox.event_type='media.processing.dlt.v1'),
		(select count(*) from media_processing_retry_review review
		 where review.processing_job_id=job.processing_job_id)
		from media_processing_job job
		join media_processing_terminal terminal
		  on terminal.processing_job_id=job.processing_job_id
		where job.processing_job_id=$1`, exhausted.JobID, processingConsumer,
		message.EventID).Scan(&status, &attempts, &cycle, &failureCode, &terminalRows,
		&deadLetters, &dltOutbox, &outboxFailureCode, &reviewRows); err != nil {
		t.Fatalf("read exhausted terminal state: %v", err)
	}
	if status != "FAILED" || attempts != 4 || cycle != 4 ||
		failureCode != "PROCESSING_ATTEMPT_EXHAUSTED" || terminalRows != 1 ||
		deadLetters != 1 || dltOutbox != 1 ||
		outboxFailureCode != "PROCESSING_ATTEMPT_EXHAUSTED" || reviewRows != 1 {
		t.Fatalf("exhausted terminal status=%s attempts=%d cycle=%d code=%s terminals=%d DLT=%d outbox=%d outboxCode=%s reviews=%d",
			status, attempts, cycle, failureCode, terminalRows, deadLetters,
			dltOutbox, outboxFailureCode, reviewRows)
	}
}

func TestProcessingDBSuccessRedeliveryIsIdempotentIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	ctx, repository, database := newProcessingConflictRepository(t, baseURL)
	message := createAuthoritativeProcessingMessage(t, ctx, repository)
	claim, err := repository.ClaimProcessingJob(ctx, message, "db-success-worker", time.Minute)
	if err != nil || claim.Duplicate {
		t.Fatalf("claim DB-success work = %#v, %v", claim, err)
	}
	if err := repository.CompleteProcessingJob(ctx, claim.Job,
		processedImageVariants(message.ExpectedMediaID, message.ExpectedGeneration)); err != nil {
		t.Fatalf("complete DB-success work: %v", err)
	}
	redelivery, err := repository.ClaimProcessingJob(ctx, message, "redelivery-worker", time.Minute)
	if err != nil || !redelivery.Duplicate {
		t.Fatalf("DB-success redelivery = %#v, %v", redelivery, err)
	}
	var variants, inboxRows, readyFacts int
	if err := database.Pool.QueryRow(ctx, `select
		(select count(*) from media_variant where media_id=$1 and generation=$2),
		(select count(*) from media_processing_inbox where consumer_name=$3 and event_id=$4),
		(select count(*) from media_domain_event where aggregate_id=$1 and event_type='media.media.ready.v1')`,
		message.ExpectedMediaID, message.ExpectedGeneration, processingConsumer,
		message.EventID).Scan(&variants, &inboxRows, &readyFacts); err != nil {
		t.Fatalf("read DB-success redelivery state: %v", err)
	}
	if variants != 4 || inboxRows != 1 || readyFacts != 1 {
		t.Fatalf("redelivery variants=%d inbox=%d readyFacts=%d", variants, inboxRows, readyFacts)
	}
	if err := repository.VerifyReplayParity(ctx, message.ExpectedMediaID); err != nil {
		t.Fatalf("DB-success redelivery replay parity: %v", err)
	}
}
