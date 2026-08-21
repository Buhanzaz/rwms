package dev.buhanzaz.rwms.logistics.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that business callers reach the one approved advisory-lock repository query indirectly. */
class LogisticsTransactionLockTest {
  @Test
  void delegatesTheStableKeyToTheApprovedTechnicalRepository() {
    LogisticsIdempotencyRecordRepository records = mock(LogisticsIdempotencyRecordRepository.class);
    LogisticsTransactionLock lock = new LogisticsTransactionLock(records);
    when(records.acquireTransactionLocks("rental-order:command:subject:operation:key"))
        .thenReturn(List.of(1));

    lock.acquire("rental-order:command:subject:operation:key");

    verify(records).acquireTransactionLocks("rental-order:command:subject:operation:key");
  }

  @Test
  void sortsAndDeduplicatesTheBatchBeforeTheSingleRepositoryCall() {
    LogisticsIdempotencyRecordRepository records = mock(LogisticsIdempotencyRecordRepository.class);
    LogisticsTransactionLock lock = new LogisticsTransactionLock(records);
    String encoded = "asset:a\u001fasset:b";
    when(records.acquireTransactionLocks(encoded)).thenReturn(List.of(1, 1));

    lock.acquireAll(List.of("asset:b", "asset:a", "asset:b"));

    verify(records).acquireTransactionLocks(encoded);
  }
}
