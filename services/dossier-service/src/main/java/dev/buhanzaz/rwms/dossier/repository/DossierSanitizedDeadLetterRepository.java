package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Repository for pending and terminal hash-only source-failure relay records. */
public interface DossierSanitizedDeadLetterRepository
    extends JpaRepository<DossierSanitizedDeadLetter, UUID> {
  long countByCoverageGenerationIdAndCoverageSubjectCabinIdAndCoverageResolvedAtIsNull(
      UUID coverageGenerationId, UUID coverageSubjectCabinId);

  /** Locks one audit row before changing either its coverage or transport state. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DossierSanitizedDeadLetter> findForUpdateById(UUID id);

  /** Locks unresolved rows for one exact source and generation before resolving their coverage. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<DossierSanitizedDeadLetter>
      findAllForUpdateBySourceEventIdAndCoverageGenerationIdAndCoverageResolvedAtIsNull(
          UUID sourceEventId, UUID coverageGenerationId);

  /**
   * Advances unresolved reader coverage during verified activation. The caller holds the active
   * pointer write lock, and the SQL update locks affected rows until activation commits. Relay
   * mutations also lock each row and dynamic updates restrict their write set, while audit identity
   * and relay state remain unchanged by this bulk operation.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
      update DossierSanitizedDeadLetter failure
      set failure.coverageGenerationId = :targetGenerationId
      where failure.coverageGenerationId = :sourceGenerationId
        and failure.coverageResolvedAt is null
      """)
  void advanceUnresolvedCoverage(
      @Param("sourceGenerationId") UUID sourceGenerationId,
      @Param("targetGenerationId") UUID targetGenerationId);

  List<DossierSanitizedDeadLetter> findAllByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAscIdAsc(
      Collection<DossierOutboxState> states, OffsetDateTime now, Pageable pageable);

  /**
   * Counts retained DLT rows with exact, not-yet-recovered visibility coverage.
   *
   * <p>The result spans retained generations for operational recovery work. It neither changes
   * coverage nor decides whether an individual active-generation cabin response is {@code PARTIAL}.
   *
   * @return number of rows with complete unresolved generation-and-cabin coverage
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(deadLetter)
      from DossierSanitizedDeadLetter deadLetter
      where deadLetter.coverageGenerationId is not null
        and deadLetter.coverageSubjectCabinId is not null
        and deadLetter.coverageResolvedAt is null
      """)
  long countUnresolvedCoverage();

  /**
   * Counts sanitized DLT rows that remain pending publication or retry.
   *
   * <p>The count includes work that is not yet due; it is backlog, not the relay's immediate
   * selection result.
   *
   * @return number of pending or retryable sanitized DLT rows
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(deadLetter)
      from DossierSanitizedDeadLetter deadLetter
      where deadLetter.status in (
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.PENDING,
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.RETRY)
      """)
  long countRecoverableBacklog();

  /**
   * Finds the immutable failure time of the oldest sanitized DLT row still awaiting relay work.
   *
   * @return oldest pending or retryable DLT failure time, or empty when that backlog is clear
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(deadLetter.failedAt)
      from DossierSanitizedDeadLetter deadLetter
      where deadLetter.status in (
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.PENDING,
        dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.RETRY)
      """)
  Optional<OffsetDateTime> findOldestRecoverableBacklogFailedAt();

  /**
   * Counts sanitized DLT rows whose relay state requires reviewed recovery.
   *
   * <p>This read-only observation never requeues or republishes an audit row.
   *
   * @return number of terminal sanitized DLT rows
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select count(deadLetter)
      from DossierSanitizedDeadLetter deadLetter
      where deadLetter.status = dev.buhanzaz.rwms.dossier.domain.DossierOutboxState.DLT
      """)
  long countTerminallyDeadLettered();
}
