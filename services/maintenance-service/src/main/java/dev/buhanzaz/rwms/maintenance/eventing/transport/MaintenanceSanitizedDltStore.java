package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MaintenanceSanitizedDltStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MaintenanceSanitizedDltStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueue(
      String messageSha256,
      String failureCode,
      String sourceTopic,
      UUID sourceEventId,
      Map<String, String> safeBody) {
    String body = canonicalJson(write(safeBody));
    String identity =
        MaintenanceTransportTopics.SANITIZED_DLT
            + '\u001f'
            + (sourceTopic == null ? "" : sourceTopic)
            + '\u001f'
            + messageSha256
            + '\u001f'
            + failureCode;
    UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    String replayStatus = sourceEventId == null ? "NOT_REPLAYABLE" : "AWAITING_REVIEW";
    jdbc.update(
        """
        insert into sanitized_dead_letter(
          dlt_id,destination,source_topic,source_event_id,message_sha256,failure_code,safe_body,
          body_sha256,status,attempt_count,next_attempt_at,created_at,replay_status)
        values (?,?,?,?,?,?,?::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp(),?)
        on conflict (dlt_id) do nothing
        """,
        id,
        MaintenanceTransportTopics.SANITIZED_DLT,
        sourceTopic,
        sourceEventId,
        messageSha256,
        failureCode,
        body,
        MaintenanceChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)),
        replayStatus);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID leaseToken = UUID.randomUUID();
    return jdbc.query(
            """
            with candidate as (
              select dlt_id from sanitized_dead_letter
               where (status='PENDING' and next_attempt_at<=clock_timestamp())
                  or (status='IN_FLIGHT' and lease_until<clock_timestamp())
               order by created_at,dlt_id for update skip locked limit 1)
            update sanitized_dead_letter dlt
               set status='IN_FLIGHT',lease_owner=?,lease_token=?,
                   lease_until=clock_timestamp()+(? * interval '1 millisecond')
              from candidate where dlt.dlt_id=candidate.dlt_id
            returning dlt.dlt_id,dlt.destination,dlt.safe_body::text,dlt.body_sha256,
                      dlt.message_sha256,dlt.attempt_count
            """,
            (result, row) ->
                new Claim(
                    result.getObject("dlt_id", UUID.class),
                    result.getString("destination"),
                    result.getString("safe_body"),
                    result.getString("body_sha256").trim(),
                    result.getString("message_sha256").trim(),
                    result.getInt("attempt_count"),
                    leaseToken),
            owner,
            leaseToken,
            leaseDuration.toMillis())
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPublished(Claim claim) {
    return jdbc.update(
            """
            update sanitized_dead_letter set status='PUBLISHED',published_at=clock_timestamp(),
              lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
             where dlt_id=? and status='IN_FLIGHT' and lease_token=?
            """,
            claim.dltId(),
            claim.leaseToken())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transientFailure(Claim claim) {
    int nextAttempt = claim.attemptCount() + 1;
    if (nextAttempt >= 4) {
      jdbc.update(
          """
          update sanitized_dead_letter set status='FAILED',attempt_count=?,last_error_code='PUBLISH_FAILED',
            lease_owner=null,lease_token=null,lease_until=null
           where dlt_id=? and status='IN_FLIGHT' and lease_token=?
          """,
          nextAttempt,
          claim.dltId(),
          claim.leaseToken());
      return;
    }
    jdbc.update(
        """
        update sanitized_dead_letter set status='PENDING',attempt_count=?,last_error_code='PUBLISH_FAILED',
          next_attempt_at=clock_timestamp()+(? * interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        nextAttempt,
        1L << (nextAttempt - 1),
        claim.dltId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ReplayTicket> approveReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    return jdbc.query(
            """
            update sanitized_dead_letter dlt
               set replay_status='APPROVED',review_version=review_version+1,
                   reviewed_at=clock_timestamp(),reviewed_by_subject_id=?
             where dlt_id=? and review_version=? and replay_status='AWAITING_REVIEW'
               and source_event_id is not null
               and not exists (
                 select 1 from version_gap_quarantine gap
                  where gap.received_event_id=dlt.source_event_id and gap.status='OPEN')
            returning source_event_id,review_version
            """,
            (result, row) ->
                new ReplayTicket(
                    result.getObject("source_event_id", UUID.class),
                    result.getLong("review_version")),
            reviewerSubjectId,
            dltId,
            expectedReviewVersion)
        .stream()
        .findFirst()
        .map(
            ticket -> {
              int changed =
                  jdbc.update(
                      """
                      update maintenance_inbound_replay_message
                         set state='REPLAY_APPROVED',updated_at=clock_timestamp()
                       where event_id=? and state='DLT'
                      """,
                      ticket.eventId());
              if (changed != 1) {
                throw new IllegalStateException("Maintenance DLT replay source is not reviewable");
              }
              return ticket;
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean rejectReplay(
      UUID dltId, long expectedReviewVersion, UUID reviewerSubjectId) {
    var source =
        jdbc.query(
                """
                update sanitized_dead_letter
                   set replay_status='REJECTED',review_version=review_version+1,
                       reviewed_at=clock_timestamp(),reviewed_by_subject_id=?
                 where dlt_id=? and review_version=? and replay_status='AWAITING_REVIEW'
                returning source_event_id
                """,
                (result, row) -> result.getObject(1, UUID.class),
                reviewerSubjectId,
                dltId,
                expectedReviewVersion)
            .stream()
            .findFirst();
    source.ifPresent(
        eventId ->
            jdbc.update(
                """
                update maintenance_inbound_replay_message set state='REJECTED',updated_at=clock_timestamp()
                 where event_id=? and state in ('DLT','REPLAY_APPROVED')
                """,
                eventId));
    return source.isPresent();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markReplayed(UUID dltId, long approvedReviewVersion) {
    return jdbc.update(
            """
            update sanitized_dead_letter set replay_status='REPLAYED',replayed_at=clock_timestamp()
             where dlt_id=? and replay_status='APPROVED' and review_version=?
            """,
            dltId,
            approvedReviewVersion)
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<ReplayTicket> approvedReplay(UUID dltId, long reviewVersion) {
    return jdbc.query(
            """
            select source_event_id,review_version from sanitized_dead_letter
             where dlt_id=? and review_version=? and replay_status='APPROVED'
            """,
            (result, row) ->
                new ReplayTicket(
                    result.getObject("source_event_id", UUID.class),
                    result.getLong("review_version")),
            dltId,
            reviewVersion)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean requeueFailedDelivery(UUID dltId) {
    return jdbc.update(
            """
            update sanitized_dead_letter set status='PENDING',attempt_count=0,
              next_attempt_at=clock_timestamp(),last_error_code=null
             where dlt_id=? and status='FAILED'
            """,
            dltId)
        == 1;
  }

  private String canonicalJson(String json) {
    String value = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (value == null) {
      throw new IllegalStateException("PostgreSQL did not canonicalize maintenance DLT JSON");
    }
    return value;
  }

  private String write(Map<String, String> safeBody) {
    try {
      return mapper.writeValueAsString(safeBody);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException("Maintenance DLT metadata cannot be serialized", exception);
    }
  }

  public record Claim(
      UUID dltId,
      String destination,
      String safeBody,
      String bodySha256,
      String messageSha256,
      int attemptCount,
      UUID leaseToken) {}

  public record ReplayTicket(UUID eventId, long reviewVersion) {}
}
