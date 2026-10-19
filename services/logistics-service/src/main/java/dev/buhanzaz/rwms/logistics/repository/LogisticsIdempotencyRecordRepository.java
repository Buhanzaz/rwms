package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Idempotency Record Repository; it does not own cross-service workflow decisions.
 */
public interface LogisticsIdempotencyRecordRepository
    extends JpaRepository<LogisticsIdempotencyRecord, UUID> {
  /** Acquires an already sorted delimiter-encoded transaction-lock set in one statement. */
  @Query(
      value =
          """
          select 1
          from (
            select lock_key
            from unnest(string_to_array(cast(:lockKeys as text), chr(31))) as keys(lock_key)
            order by lock_key
          ) ordered
          cross join lateral pg_advisory_xact_lock(
            hashtextextended(cast(ordered.lock_key as text), 0)) ignored
          """,
      nativeQuery = true)
  List<Integer> acquireTransactionLocks(@Param("lockKeys") String lockKeys);

  Optional<LogisticsIdempotencyRecord> findBySubjectIdAndOperationNameAndIdempotencyKey(
      UUID subjectId, String operationName, UUID idempotencyKey);
}
