package dev.buhanzaz.rwms.logistics.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** Verifies that business callers reach the one approved advisory-lock repository query indirectly. */
class LogisticsTransactionLockTest {
  @Test
  void delegatesTheStableKeyToTheApprovedTechnicalRepository() {
    LogisticsIdempotencyRecordRepository records = mock(LogisticsIdempotencyRecordRepository.class);
    LogisticsTransactionLock lock = new LogisticsTransactionLock(records);

    lock.acquire("rental-order:command:subject:operation:key");

    verify(records).acquireTransactionLock("rental-order:command:subject:operation:key");
  }
}
