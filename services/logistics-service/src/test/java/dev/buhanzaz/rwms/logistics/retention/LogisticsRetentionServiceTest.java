package dev.buhanzaz.rwms.logistics.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionDataset;
import dev.buhanzaz.rwms.logistics.retention.persistence.LogisticsRetentionCandidateReader;
import dev.buhanzaz.rwms.logistics.retention.repository.LogisticsArchiveManifestRepository;
import dev.buhanzaz.rwms.logistics.retention.repository.LogisticsRetentionLegalHoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Verifies that retention reporting is non-destructive and legal-hold aware by default. */
class LogisticsRetentionServiceTest {
  @Test
  void dryRunReportsApprovedPolicyAndPerformsOnlyTerminalRowReads() {
    LogisticsRetentionProperties properties = new LogisticsRetentionProperties();
    LogisticsRetentionLegalHoldRepository holds = mock(LogisticsRetentionLegalHoldRepository.class);
    LogisticsArchiveManifestRepository manifests = mock(LogisticsArchiveManifestRepository.class);
    LogisticsRetentionCandidateReader candidates = mock(LogisticsRetentionCandidateReader.class);
    when(candidates.countPublishedOutboxRows(any(OffsetDateTime.class))).thenReturn(6L);
    when(candidates.countProcessedInboxRows(any(OffsetDateTime.class))).thenReturn(2L);
    when(holds.countByDatasetAndReleasedAtIsNull(LogisticsRetentionDataset.EVENT_OUTBOX))
        .thenReturn(1L);
    Clock clock = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);
    LogisticsRetentionService service =
        new LogisticsRetentionService(properties, holds, manifests, candidates, clock);

    var report = service.dryRun();

    assertThat(report.businessAuditProofDays()).isEqualTo(1_825);
    assertThat(report.eventOnlineDays()).isEqualTo(90);
    assertThat(report.eventArchiveDays()).isEqualTo(365);
    assertThat(report.gpsTelemetryDays()).isEqualTo(30);
    assertThat(report.deletionEnabled()).isFalse();
    assertThat(report.dualApprovalRequired()).isTrue();
    assertThat(report.candidates())
        .filteredOn(value -> value.dataset() == LogisticsRetentionDataset.EVENT_OUTBOX)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.rowCount()).isEqualTo(6);
              assertThat(value.activeHolds()).isEqualTo(1);
            });
    OffsetDateTime cutoff = OffsetDateTime.parse("2026-06-03T09:00:00Z");
    verify(candidates).countPublishedOutboxRows(cutoff);
    verify(candidates).countProcessedInboxRows(cutoff);
    verifyNoMoreInteractions(candidates);
  }
}
