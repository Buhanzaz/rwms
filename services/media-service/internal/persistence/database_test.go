package persistence

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
)

func TestEmbeddedMigrationChecksumsAreStableAndDistinct(t *testing.T) {
	v1 := flywayChecksum(mediamigration.V1)
	v2 := flywayChecksum(mediamigration.V2)
	v3 := flywayChecksum(mediamigration.V3)
	const (
		flyway124V1 int32 = -1307356325
		flyway124V2 int32 = -573926044
		flyway124V3 int32 = 2075479860
	)
	if v1 != flyway124V1 || v2 != flyway124V2 || v3 != flyway124V3 {
		t.Fatalf(
			"Flyway 12.4 checksum drift: V1=%d (want %d), V2=%d (want %d), V3=%d (want %d)",
			v1,
			flyway124V1,
			v2,
			flyway124V2,
			v3,
			flyway124V3,
		)
	}
}

func TestInventoryOwnerMigrationIsAdditiveAndCanonical(t *testing.T) {
	sql := string(mediamigration.V3)
	for _, required := range []string{
		"media_inventory_finding_inbox",
		"proof_aggregate_type = 'FINDING'",
		"aggregate_version >= 0",
		"rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt",
		"resolution_reason",
		"resolved_by_subject_id",
		"UNVERIFIED_PRE_TASK1B_BINDING",
		"media_inventory_finding_stream",
		"media_inventory_finding_event_conflict",
		"media_retry_schedule_inventory_owner_identity_check",
		"aggregate_id uuid",
		"warehouse_id uuid",
		"media_dead_letter_inventory_owner_failure_check",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V3 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from media_"} {
		if strings.Contains(strings.ToLower(sql), forbidden) {
			t.Errorf("V3 contains destructive statement %q", forbidden)
		}
	}
}

func TestInventoryOwnerDLTWireValidationMatchesClosedFailureEnum(t *testing.T) {
	for _, failureCode := range []string{
		"INVALID_INVENTORY_OWNER_FACT",
		"OWNER_EVENT_ID_CONFLICT",
		"OWNER_PROOF_PROCESSING_FAILED",
	} {
		body, _, err := canonicalJSON(map[string]any{
			"failureCode":   failureCode,
			"messageSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"recordedAt":    time.Date(2026, 7, 17, 12, 0, 0, 0, time.UTC),
		})
		if err != nil || validateInventoryOwnerDLTWire(body) != nil {
			t.Fatalf("valid DLT %s rejected: canonical=%v validation=%v", failureCode, err,
				validateInventoryOwnerDLTWire(body))
		}
	}
	for _, failureCode := range []string{"", "RAW_DATABASE_ERROR", "OWNER_PROOF_FAILED"} {
		if validInventoryOwnerDLTFailure(failureCode) {
			t.Fatalf("unexpected DLT failure code accepted: %q", failureCode)
		}
	}
}

func TestOpenRejectsChecksumDriftIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DRIFT_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DRIFT_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if database != nil {
		database.Close()
	}
	if !errors.Is(err, ErrSchemaNotReady) {
		t.Fatalf("Open() error = %v, want ErrSchemaNotReady", err)
	}
}
