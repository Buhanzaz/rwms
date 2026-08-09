package dev.buhanzaz.rwms.logistics.repository;

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
    idempotencyRecords.acquireTransactionLock(lockKey);
  }
}
