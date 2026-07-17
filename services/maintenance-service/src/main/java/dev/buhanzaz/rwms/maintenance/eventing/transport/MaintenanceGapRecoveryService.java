package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit operator recovery for a quarantined aggregate-version gap. */
@Service
public class MaintenanceGapRecoveryService {
  private final JdbcTemplate jdbc;
  private final MaintenanceInboundStagingStore staging;
  private final MaintenanceInboxProcessor inbox;

  public MaintenanceGapRecoveryService(
      JdbcTemplate jdbc,
      MaintenanceInboundStagingStore staging,
      MaintenanceInboxProcessor inbox) {
    this.jdbc = jdbc;
    this.staging = staging;
    this.inbox = inbox;
  }

  @Transactional
  public boolean reconcile(
      UUID quarantineId, List<UUID> missingEventIds, UUID reviewerSubjectId) {
    Gap gap =
        jdbc.query(
                """
                select consumer_group,aggregate_type,aggregate_id,expected_version,received_version
                  from version_gap_quarantine
                 where quarantine_id=? and status='OPEN' for update
                """,
                (result, row) ->
                    new Gap(
                        result.getString("consumer_group"),
                        result.getString("aggregate_type"),
                        result.getString("aggregate_id"),
                        result.getLong("expected_version"),
                        result.getLong("received_version")),
                quarantineId)
            .stream()
            .findFirst()
            .orElse(null);
    if (gap == null) {
      return false;
    }
    if (!MaintenanceTransportTopics.CONSUMER_GROUP.equals(gap.consumerGroup())) {
      throw new IllegalArgumentException("Version gap belongs to a different consumer group");
    }
    long expected = gap.expectedVersion();
    for (UUID eventId : List.copyOf(missingEventIds)) {
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event =
          staging
              .load(eventId)
              .orElseThrow(
                  () -> new IllegalArgumentException("Missing gap event is not safely staged"));
      if (!event.aggregateType().equals(gap.aggregateType())
          || !event.aggregateId().equals(gap.aggregateId())
          || event.aggregateVersion() != expected) {
        throw new IllegalArgumentException(
            "Gap recovery events must match the blocked aggregate in exact version order");
      }
      inbox.applyMissingDuringReconciliation(event, gap.receivedVersion());
      expected = Math.addExact(expected, 1);
    }
    if (expected != gap.receivedVersion()) {
      throw new IllegalArgumentException("Gap recovery must provide every missing aggregate version");
    }
    int unblocked =
        jdbc.update(
            """
            update consumer_aggregate_checkpoint
               set blocked=false,quarantine_reason=null,updated_at=clock_timestamp()
             where consumer_group=? and aggregate_type=? and aggregate_id=? and blocked
               and last_aggregate_version=?
            """,
            gap.consumerGroup(),
            gap.aggregateType(),
            gap.aggregateId(),
            gap.receivedVersion() - 1);
    if (unblocked != 1) {
      throw new IllegalStateException("Gap checkpoint did not reach the required predecessor version");
    }
    return jdbc.update(
            """
            update version_gap_quarantine
               set status='RESOLVED',resolved_at=clock_timestamp(),
                   resolution_reason='MISSING_EVENTS_REPLAYED',resolved_by_subject_id=?
             where quarantine_id=? and status='OPEN'
            """,
            reviewerSubjectId,
            quarantineId)
        == 1;
  }

  private record Gap(
      String consumerGroup,
      String aggregateType,
      String aggregateId,
      long expectedVersion,
      long receivedVersion) {}
}
