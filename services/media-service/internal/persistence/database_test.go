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
	v4 := flywayChecksum(mediamigration.V4)
	v4Guard := flywayChecksum(mediamigration.V4_1)
	v5 := flywayChecksum(mediamigration.V5)
	v5Guard := flywayChecksum(mediamigration.V5_1)
	v6 := flywayChecksum(mediamigration.V6)
	v7 := flywayChecksum(mediamigration.V7)
	v8 := flywayChecksum(mediamigration.V8)
	v9 := flywayChecksum(mediamigration.V9)
	v10 := flywayChecksum(mediamigration.V10)
	v11 := flywayChecksum(mediamigration.V11)
	v12 := flywayChecksum(mediamigration.V12)
	v13 := flywayChecksum(mediamigration.V13)
	v14 := flywayChecksum(mediamigration.V14)
	v15 := flywayChecksum(mediamigration.V15)
	const (
		flyway124V1      int32 = -1307356325
		flyway124V2      int32 = -573926044
		flyway124V3      int32 = 2075479860
		flyway124V4      int32 = -1969433918
		flyway124V4Guard int32 = 1260285653
		flyway124V5      int32 = -296070644
		flyway124V5Guard int32 = -809997685
		flyway124V6      int32 = 1927176510
		flyway124V7      int32 = 725844632
		flyway124V8      int32 = -405784491
		flyway124V9      int32 = -122399598
		flyway124V10     int32 = 1320117672
		flyway124V11     int32 = -48948501
		flyway124V12     int32 = 1877852350
		flyway124V13     int32 = -455290715
		flyway124V14     int32 = -2079734905
		flyway124V15     int32 = 1136570652
	)
	if v1 != flyway124V1 || v2 != flyway124V2 || v3 != flyway124V3 || v4 != flyway124V4 ||
		v4Guard != flyway124V4Guard || v5 != flyway124V5 || v5Guard != flyway124V5Guard ||
		v6 != flyway124V6 || v7 != flyway124V7 || v8 != flyway124V8 || v9 != flyway124V9 ||
		v10 != flyway124V10 || v11 != flyway124V11 || v12 != flyway124V12 || v13 != flyway124V13 ||
		v14 != flyway124V14 || v15 != flyway124V15 {
		t.Fatalf(
			"Flyway 12.4 checksum drift: V1=%d (want %d), V2=%d (want %d), V3=%d (want %d), V4=%d (want %d), V4.1=%d (want %d), V5=%d (want %d), V5.1=%d (want %d), V6=%d (want %d), V7=%d (want %d), V8=%d (want %d), V9=%d (want %d), V10=%d (want %d), V11=%d (want %d), V12=%d (want %d), V13=%d (want %d), V14=%d (want %d), V15=%d (want %d)",
			v1,
			flyway124V1,
			v2,
			flyway124V2,
			v3,
			flyway124V3,
			v4,
			flyway124V4,
			v4Guard,
			flyway124V4Guard,
			v5,
			flyway124V5,
			v5Guard,
			flyway124V5Guard,
			v6,
			flyway124V6,
			v7,
			flyway124V7,
			v8,
			flyway124V8,
			v9,
			flyway124V9,
			v10,
			flyway124V10,
			v11,
			flyway124V11,
			v12,
			flyway124V12,
			v13,
			flyway124V13,
			v14,
			flyway124V14,
			v15,
			flyway124V15,
		)
	}
}

func TestVerifyMigrationHistoryAcceptsCanonicalAndOutOfOrderFlywayRanks(t *testing.T) {
	canonical := approvedMigrationHistory()
	if err := verifyMigrationHistory(canonical); err != nil {
		t.Fatalf("canonical Flyway history rejected: %v", err)
	}

	// A database that already had V1 through V5 receives V4.1 and V5.1 after
	// V5 when Flyway runs with outOfOrder=true. Installed rank therefore does
	// not match semantic version order, while the approved migration set does.
	outOfOrder := []migrationHistoryRow{
		canonical[0], canonical[1], canonical[2], canonical[3], canonical[5],
		canonical[4], canonical[6], canonical[7], canonical[8], canonical[9], canonical[10],
		canonical[11], canonical[12],
		canonical[13], canonical[14], canonical[15], canonical[16],
	}
	if err := verifyMigrationHistory(outOfOrder); err != nil {
		t.Fatalf("real out-of-order Flyway upgrade history rejected: %v", err)
	}
}

func TestVerifyMigrationHistoryRejectsAnythingExceptTheExactApprovedSet(t *testing.T) {
	checksum := flywayChecksum(mediamigration.V9)
	tests := []struct {
		name   string
		mutate func([]migrationHistoryRow) []migrationHistoryRow
	}{
		{
			name: "missing",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				return history[:len(history)-1]
			},
		},
		{
			name: "extra",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				return append(history, migrationHistoryRow{
					version: "10", description: "unapproved", migrationType: "SQL",
					script: "V10__unapproved.sql", checksum: &checksum, success: true,
				})
			},
		},
		{
			name: "duplicate",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1] = history[len(history)-2]
				return history
			},
		},
		{
			name: "unapproved replaces approved",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1] = migrationHistoryRow{
					version: "9", description: "unapproved", migrationType: "SQL",
					script: "V9__unapproved.sql", checksum: &checksum, success: true,
				}
				return history
			},
		},
		{
			name: "failed",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1].success = false
				return history
			},
		},
		{
			name: "description mismatch",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1].description = "changed"
				return history
			},
		},
		{
			name: "type mismatch",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1].migrationType = "JDBC"
				return history
			},
		},
		{
			name: "script mismatch",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1].script = "V7__changed.sql"
				return history
			},
		},
		{
			name: "missing checksum",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				history[len(history)-1].checksum = nil
				return history
			},
		},
		{
			name: "checksum mismatch",
			mutate: func(history []migrationHistoryRow) []migrationHistoryRow {
				changed := *history[len(history)-1].checksum + 1
				history[len(history)-1].checksum = &changed
				return history
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			history := append([]migrationHistoryRow(nil), approvedMigrationHistory()...)
			err := verifyMigrationHistory(test.mutate(history))
			if !errors.Is(err, ErrSchemaNotReady) {
				t.Fatalf("verification error = %v, want ErrSchemaNotReady", err)
			}
		})
	}
}

func approvedMigrationHistory() []migrationHistoryRow {
	migrations := []approvedMigration{
		{"1", "media schema", "V1__media_schema.sql", mediamigration.V1},
		{"2", "media runtime recovery", "V2__media_runtime_recovery.sql", mediamigration.V2},
		{"3", "inventory owner proof", "V3__inventory_owner_proof.sql", mediamigration.V3},
		{"4", "cabin owner bindings", "V4__cabin_owner_bindings.sql", mediamigration.V4},
		{"4.1", "prepare legacy photo folder backfill", "V4_1__prepare_legacy_photo_folder_backfill.sql", mediamigration.V4_1},
		{"5", "media photo folders", "V5__media_photo_folders.sql", mediamigration.V5},
		{"5.1", "restore runtime source guard", "V5_1__restore_runtime_source_guard.sql", mediamigration.V5_1},
		{"6", "service owner proofs and soft delete", "V6__service_owner_proofs_and_soft_delete.sql", mediamigration.V6},
		{"7", "dynamic cabin owner projection", "V7__dynamic_cabin_owner_projection.sql", mediamigration.V7},
		{"8", "task board worker media", "V8__task_board_worker_media.sql", mediamigration.V8},
		{"9", "asset import worker", "V9__asset_import_worker.sql", mediamigration.V9},
		{"10", "canonical cabin photo library", "V10__canonical_cabin_photo_library.sql", mediamigration.V10},
		{"11", "bounded media processing recovery", "V11__bounded_media_processing_recovery.sql", mediamigration.V11},
		{"12", "video playback variant", "V12__video_playback_variant.sql", mediamigration.V12},
		{"13", "authoritative inventory cabin photos", "V13__authoritative_inventory_cabin_photos.sql", mediamigration.V13},
		{"14", "task board reader audience", "V14__task_board_reader_audience.sql", mediamigration.V14},
		{"15", "inventory finding membership markers", "V15__inventory_finding_membership_markers.sql", mediamigration.V15},
	}
	history := make([]migrationHistoryRow, 0, len(migrations))
	for _, migration := range migrations {
		checksum := flywayChecksum(migration.contents)
		history = append(history, migrationHistoryRow{
			version:       migration.version,
			description:   migration.description,
			migrationType: "SQL",
			script:        migration.script,
			checksum:      &checksum,
			success:       true,
		})
	}
	return history
}

func TestPhotoFolderBackfillGuardDropsAndRestoresOnlyTheRuntimeSourceConstraint(t *testing.T) {
	prepare := strings.ToLower(string(mediamigration.V4_1))
	restore := strings.ToLower(string(mediamigration.V5_1))
	if !strings.Contains(prepare, "drop constraint media_asset_runtime_source_state") ||
		strings.Contains(prepare, "drop table") || strings.Contains(prepare, "delete from") ||
		!strings.Contains(restore, "add constraint media_asset_runtime_source_state") ||
		!strings.Contains(restore, ") not valid") || strings.Contains(restore, "drop table") ||
		strings.Contains(restore, "delete from") {
		t.Fatalf("photo folder backfill guards are not narrowly scoped")
	}
}

func TestBoundedProcessingRecoveryMigrationIsAdditiveAndPayloadFree(t *testing.T) {
	sql := strings.ToLower(string(mediamigration.V11))
	for _, required := range []string{
		"attempt_in_cycle between 0 and 4",
		"media_processing_terminal",
		"media_processing_retry_review",
		"eligible_count = 1",
		"legacy_terminal",
		"processing_attempt_exhausted",
		"reviewed_by_subject_id",
		"dependency_recovered",
		"attempt_budget_reset",
		"validation_confirmed",
		"append-only",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V11 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{
		"drop table", "truncate table", "delete from media_", "wire_body",
		"envelope_body", "object_key", "raw_payload",
	} {
		if strings.Contains(sql, forbidden) {
			t.Errorf("V11 contains destructive or payload-bearing statement %q", forbidden)
		}
	}
}

func TestVideoPlaybackMigrationOnlyExpandsTheVariantConstraint(t *testing.T) {
	sql := strings.ToLower(string(mediamigration.V12))
	for _, required := range []string{
		"drop constraint media_variant_variant_check",
		"add constraint media_variant_variant_check",
		"'small', 'medium', 'large', 'playback', 'original'",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V12 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from", "update media_"} {
		if strings.Contains(sql, forbidden) {
			t.Errorf("V12 contains destructive data statement %q", forbidden)
		}
	}
}

func TestDynamicCabinOwnerMigrationIsAdditiveAndRetainsV4Bindings(t *testing.T) {
	sql := string(mediamigration.V7)
	for _, required := range []string{
		"media_cabin_owner_inbox",
		"media_cabin_owner_event_conflict",
		"media-service-cabin-owner-v1",
		"rwms.asset.rental-item.v1",
		"aggregate_version >= 0",
		"WRITTEN_OFF",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V7 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{
		"drop table", "truncate table", "delete from media_", "update media_owner_binding",
	} {
		if strings.Contains(strings.ToLower(sql), forbidden) {
			t.Errorf("V7 contains destructive or seed mutation %q", forbidden)
		}
	}
}

func TestPhotoFolderMigrationBackfillsWithoutInventingGroups(t *testing.T) {
	sql := string(mediamigration.V5)
	for _, required := range []string{
		"add column folder_id uuid",
		"set folder_id = media_id",
		"alter column folder_id set not null",
		"'folderId', asset.folder_id",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V5 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from media_"} {
		if strings.Contains(strings.ToLower(sql), forbidden) {
			t.Errorf("V5 contains destructive statement %q", forbidden)
		}
	}
}

func TestServiceOwnerProofMigrationExpandsScopesWithoutDeletingMedia(t *testing.T) {
	sql := string(mediamigration.V6)
	for _, required := range []string{
		"media_service_owner_proof_checkpoint",
		"media_service_owner_proof_receipt",
		"media_service_owner_proof_conflict",
		"MAINTENANCE_CATALOG_NODE",
		"LOGISTICS_TRANSFER",
		"media-service-maintenance-owner-proof-v1",
		"media-service-logistics-owner-proof-v1",
		"processing_status = 'DELETED'",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V6 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from media_", "delete from media_variant"} {
		if strings.Contains(strings.ToLower(sql), forbidden) {
			t.Errorf("V6 contains destructive statement %q", forbidden)
		}
	}
}

func TestCabinOwnerMigrationSeedsOnlyTheCanonicalOldPanelScope(t *testing.T) {
	sql := string(mediamigration.V4)
	for _, required := range []string{
		"owner_type = 'CABIN'",
		"proof_aggregate_type = 'RENTAL_ITEM'",
		"generate_series(1, 195)",
		"sequence_number <= 120",
		"00000000-0000-0000-0000-000000000001",
		"00000000-0000-0000-0000-000000000002",
		"51000000-0000-4000-8000-%s",
		"media-service-old-panel-cabin-migration-v1",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V4 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from media_"} {
		if strings.Contains(strings.ToLower(sql), forbidden) {
			t.Errorf("V4 contains destructive statement %q", forbidden)
		}
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

func TestInventoryFindingMembershipMarkerMigrationWidensOnlyTheInboxEventConstraint(t *testing.T) {
	sql := strings.ToLower(string(mediamigration.V15))
	for _, required := range []string{
		"drop constraint media_inventory_finding_inbox_check2",
		"add constraint media_inventory_finding_inbox_check2",
		"inventory.finding.owner-proof.v1",
		"inventory.finding.added.v1",
		"inventory.finding.inspection-saved.v1",
		"inventory.finding.membership-departed.v1",
		"inventory.finding.membership-refreshed.v1",
		"inventory.finding.membership-restored.v1",
		"owner_revision is not null",
		"owner_revision is null",
	} {
		if !strings.Contains(sql, required) {
			t.Errorf("V15 does not contain %q", required)
		}
	}
	for _, forbidden := range []string{"drop table", "truncate table", "delete from", "update media_"} {
		if strings.Contains(sql, forbidden) {
			t.Errorf("V15 contains destructive or data-rewriting statement %q", forbidden)
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
