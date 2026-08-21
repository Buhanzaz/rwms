package dev.buhanzaz.rwms.logistics.repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Acquires the logistics-local transaction advisory lock through the one approved persistence
 * query.
 *
 * <p>The caller owns the surrounding transaction and therefore also the lock lifetime. Keeping
 * the PostgreSQL-specific query behind this narrow boundary prevents business repositories from
 * exposing technical locking operations as part of their aggregate persistence API.
 */
@Component
public class LogisticsTransactionLock {
  private static final String KEY_SEPARATOR = "\u001f";
  private final LogisticsIdempotencyRecordRepository idempotencyRecords;

  public LogisticsTransactionLock(LogisticsIdempotencyRecordRepository idempotencyRecords) {
    this.idempotencyRecords = idempotencyRecords;
  }

  /**
   * Acquires the transaction-scoped lock for the supplied stable logistics key.
   *
   * <p>This method must be called inside the transaction that protects the associated state
   * transition.
   */
  public void acquire(String lockKey) {
    acquireAll(List.of(lockKey));
  }

  /**
   * Acquires a deterministic set of transaction locks with one bounded database round trip.
   *
   * <p>Sorting prevents deadlocks between overlapping batches. The control-character separator is
   * deliberately rejected in keys so PostgreSQL can expand the one bound value losslessly.
   */
  public void acquireAll(Collection<String> lockKeys) {
    List<String> ordered =
        Objects.requireNonNull(lockKeys, "Lock keys are required").stream()
            .map(value -> Objects.requireNonNull(value, "Lock key is required"))
            .peek(
                value -> {
                  if (value.contains(KEY_SEPARATOR)) {
                    throw new IllegalArgumentException("Lock key contains the reserved separator");
                  }
                })
            .distinct()
            .sorted()
            .toList();
    if (ordered.isEmpty()) return;
    List<Integer> acquired =
        idempotencyRecords.acquireTransactionLocks(String.join(KEY_SEPARATOR, ordered));
    if (acquired.size() != ordered.size()) {
      throw new IllegalStateException("Not every requested transaction lock was acquired");
    }
  }
}
