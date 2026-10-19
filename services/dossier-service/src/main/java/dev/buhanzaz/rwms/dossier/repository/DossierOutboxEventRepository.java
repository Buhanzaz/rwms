package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Repository for pending sanitized activity outbox records and their retry-safe relay selection. */
public interface DossierOutboxEventRepository extends JpaRepository<DossierOutboxEvent, UUID> {
  Optional<DossierOutboxEvent> findBySourceEventIdAndCabinId(UUID sourceEventId, UUID cabinId);

  boolean existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
      UUID cabinId, long aggregateVersion, DossierOutboxState status);

  List<DossierOutboxEvent> findAllByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAscEventIdAsc(
      Collection<DossierOutboxState> states, OffsetDateTime now, Pageable pageable);

  @Query(
      """
      select event
      from DossierOutboxEvent event
      where event.status in :states
        and event.nextAttemptAt <= :now
        and not exists (
          select predecessor.eventId
          from DossierOutboxEvent predecessor
          where predecessor.cabinId = event.cabinId
            and predecessor.aggregateVersion < event.aggregateVersion
            and predecessor.status <> :published
        )
      order by event.createdAt asc, event.eventId asc
      """)
  List<DossierOutboxEvent> findPublishableHeads(
      @Param("states") Collection<DossierOutboxState> states,
      @Param("published") DossierOutboxState published,
      @Param("now") OffsetDateTime now,
      Pageable pageable);

  /**
   * Counts activity-outbox rows that remain pending publication or retry.
   *
   * <p>The result includes blocked and not-yet-due rows; it is an operational backlog rather than
   * the relay's currently publishable-head selection.
   *
   * @return number of pending or retryable activity-outbox rows
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(event)
      from DossierOutboxEvent event
      where event.status in (
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.PENDING,
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.RETRY)
      """)
  long countRecoverableBacklog();

  /**
   * Finds the creation time of the oldest activity-outbox row still awaiting relay work.
   *
   * @return oldest pending or retryable activity-outbox creation time, or empty when the backlog
   *     is clear
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(event.createdAt)
      from DossierOutboxEvent event
      where event.status in (
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.PENDING,
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.RETRY)
      """)
  Optional<OffsetDateTime> findOldestRecoverableBacklogCreatedAt();

  /**
   * Counts activity-outbox rows whose relay state is terminal.
   *
   * <p>The observation does not requeue an event or alter aggregate publication ordering.
   *
   * @return number of terminal activity-outbox rows
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(event)
      from DossierOutboxEvent event
      where event.status = dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.DLT
      """)
  long countTerminallyDeadLettered();
}
