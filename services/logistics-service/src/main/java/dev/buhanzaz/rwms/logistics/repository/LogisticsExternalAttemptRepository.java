package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned external attempts; it does not own
 * cross-service workflow decisions.
 */
public interface LogisticsExternalAttemptRepository
    extends JpaRepository<LogisticsExternalAttempt, UUID> {
  List<LogisticsExternalAttempt> findAllByDocument_IdOrderByCreatedAtAsc(UUID documentId);

  Optional<LogisticsExternalAttempt> findByOperationId(UUID operationId);

  Optional<LogisticsExternalAttempt> findByDocument_IdAndLine_IdAndOperationType(
      UUID documentId, UUID lineId, String operationType);

  /** Finds one document-scoped external effect whose durable identity has no cabin line. */
  Optional<LogisticsExternalAttempt> findByDocument_IdAndLineIsNullAndOperationType(
      UUID documentId, String operationType);

  /** Returns active attempts in the supplied closed result set without loading their payloads. */
  long countByResultIn(List<LogisticsExternalAttemptResult> results);

  /** Returns attempts whose outcome requires explicit reconciliation rather than automatic retry. */
  long countByResult(LogisticsExternalAttemptResult result);

  /**
   * Finds when the oldest attempt in the supplied closed result set was durably created.
   *
   * @return the oldest creation time, or empty when no matching attempt remains
   */
  @Query(
      """
      select min(attempt.createdAt)
      from LogisticsExternalAttempt attempt
      where attempt.result in :results
      """)
  Optional<OffsetDateTime> findOldestCreatedAtByResultIn(
      @Param("results") List<LogisticsExternalAttemptResult> results);

  /** Returns the maximum retry count in the supplied result set, or zero for an empty set. */
  @Query(
      """
      select coalesce(max(attempt.retryCount), 0)
      from LogisticsExternalAttempt attempt
      where attempt.result in :results
      """)
  int findMaximumRetryCountByResultIn(
      @Param("results") List<LogisticsExternalAttemptResult> results);

  /**
   * Locks one stable, bounded page of due attempts for exact operation families. Hibernate maps the
   * negative lock timeout to PostgreSQL {@code FOR UPDATE SKIP LOCKED}, allowing another replica to
   * continue past a worker's in-flight row without waiting for its transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select attempt
      from LogisticsExternalAttempt attempt
      where attempt.operationType in :operationTypes
        and attempt.result in :results
        and (attempt.nextAttemptAt is null or attempt.nextAttemptAt <= current_timestamp)
        and (attempt.leaseExpiresAt is null or attempt.leaseExpiresAt <= current_timestamp)
      order by coalesce(attempt.nextAttemptAt, attempt.createdAt), attempt.createdAt, attempt.id
      """)
  List<LogisticsExternalAttempt> lockDueByOperationTypes(
      @Param("operationTypes") List<String> operationTypes,
      @Param("results") List<LogisticsExternalAttemptResult> results,
      Pageable page);

  /**
   * Locks one stable, bounded page for a literal operation namespace whose per-line suffixes are
   * dynamic. The same skip-locked hint keeps this selector replica-safe without a native SQL
   * bypass.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select attempt
      from LogisticsExternalAttempt attempt
      where attempt.operationType like concat(:operationPrefix, '%') escape '\\'
        and attempt.result in :results
        and (attempt.nextAttemptAt is null or attempt.nextAttemptAt <= current_timestamp)
        and (attempt.leaseExpiresAt is null or attempt.leaseExpiresAt <= current_timestamp)
      order by coalesce(attempt.nextAttemptAt, attempt.createdAt), attempt.createdAt, attempt.id
      """)
  List<LogisticsExternalAttempt> lockDueByOperationPrefix(
      @Param("operationPrefix") String operationPrefix,
      @Param("results") List<LogisticsExternalAttemptResult> results,
      Pageable page);

  /**
   * Re-locks an exact, unexpired lease capability before a workflow mutates local state after its
   * remote call. Every field in the immutable claim is checked in SQL as well as in the entity.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select attempt
      from LogisticsExternalAttempt attempt
      where attempt.id = :attemptId
        and attempt.operationId = :operationId
        and attempt.leaseToken = :leaseToken
        and attempt.leaseFence = :leaseFence
        and attempt.rowVersion = :rowVersion
        and attempt.requestSha256 = :requestSha256
        and attempt.result in :results
        and attempt.leaseExpiresAt > current_timestamp
      """)
  Optional<LogisticsExternalAttempt> lockCurrentClaim(
      @Param("attemptId") UUID attemptId,
      @Param("operationId") UUID operationId,
      @Param("leaseToken") UUID leaseToken,
      @Param("leaseFence") long leaseFence,
      @Param("rowVersion") long rowVersion,
      @Param("requestSha256") String requestSha256,
      @Param("results") List<LogisticsExternalAttemptResult> results);

  /**
   * Reads PostgreSQL's transaction timestamp through JPQL in Hibernate's JDBC scalar type. This
   * narrow method exists only because JPQL {@code current_timestamp} has that specified scalar
   * representation in this provider.
   */
  @Query(
      """
      select current_timestamp
      from LogisticsExternalAttempt attempt
      where attempt.id = :attemptId
      """)
  Optional<Timestamp> currentDatabaseTimestampScalar(@Param("attemptId") UUID attemptId);

  /**
   * Returns PostgreSQL transaction time as a UTC domain value. Claim code uses this method rather
   * than a JVM clock or the provider-specific JDBC scalar above.
   */
  default Optional<OffsetDateTime> currentDatabaseTimestamp(UUID attemptId) {
    return currentDatabaseTimestampScalar(attemptId)
        .map(timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC));
  }
}
