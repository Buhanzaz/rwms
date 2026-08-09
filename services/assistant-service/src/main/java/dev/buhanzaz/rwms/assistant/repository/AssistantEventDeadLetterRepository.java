package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantEventDeadLetter;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for sanitized, coordinate-unique assistant dead-letter review evidence. */
public interface AssistantEventDeadLetterRepository
    extends JpaRepository<AssistantEventDeadLetter, UUID> {
  /** Inserts one sanitized source receipt without changing evidence already bound to it. */
  @Modifying
  @Query(
      value =
          """
          insert into assistant_event_dead_letter (
            dlt_id, source_topic, source_partition, source_offset, source_event_id,
            message_sha256, failure_code, replay_state, review_version, created_at)
          values (
            :id, :sourceTopic, :sourcePartition, :sourceOffset, :sourceEventId,
            :messageSha256, :failureCode, :replayState, 0, :createdAt)
          on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("sourceTopic") String sourceTopic,
      @Param("sourcePartition") int sourcePartition,
      @Param("sourceOffset") long sourceOffset,
      @Param("sourceEventId") UUID sourceEventId,
      @Param("messageSha256") String messageSha256,
      @Param("failureCode") String failureCode,
      @Param("replayState") String replayState,
      @Param("createdAt") OffsetDateTime createdAt);

  /** Locks one source receipt for idempotent record comparison. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select evidence from AssistantEventDeadLetter evidence
      where evidence.sourceTopic = :sourceTopic
        and evidence.sourcePartition = :sourcePartition
        and evidence.sourceOffset = :sourceOffset
      """)
  Optional<AssistantEventDeadLetter> findBySourceForUpdate(
      @Param("sourceTopic") String sourceTopic,
      @Param("sourcePartition") int sourcePartition,
      @Param("sourceOffset") long sourceOffset);

  /** Locks one review ticket before applying its expected review version. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select evidence from AssistantEventDeadLetter evidence where evidence.id = :id")
  Optional<AssistantEventDeadLetter> findByIdForUpdate(@Param("id") UUID id);
}
