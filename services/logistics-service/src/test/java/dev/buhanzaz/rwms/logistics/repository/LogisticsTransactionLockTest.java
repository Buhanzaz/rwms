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
    String lockKey = "rental-order:command:subject:operation:key";
    when(records.acquireTransactionLocks(lockKey)).thenReturn(List.of(1));

    lock.acquire(lockKey);

    verify(records).acquireTransactionLocks(lockKey);
  }

  @Test
  void preservesTheOpaqueLegacyDocumentLockKeyIncludingItsInternalSeparators() {
    LogisticsIdempotencyRecordRepository records = mock(LogisticsIdempotencyRecordRepository.class);
    LogisticsTransactionLock lock = new LogisticsTransactionLock(records);
    String lockKey = "subject\u001fCREATE_SHIPMENT\u001fidempotency-key";
    String encoded = "subject\\x1fCREATE_SHIPMENT\\x1fidempotency-key";
    when(records.acquireTransactionLocks(encoded)).thenReturn(List.of(1));

    lock.acquire(lockKey);

    verify(records).acquireTransactionLocks(encoded);
  }

  @Test
  void escapesLiteralBackslashesWithoutCollidingWithSeparatorEscapes() {
    LogisticsIdempotencyRecordRepository records = mock(LogisticsIdempotencyRecordRepository.class);
    LogisticsTransactionLock lock = new LogisticsTransactionLock(records);
    String encoded = "subject\\\\x1fidempotency-key";
    when(records.acquireTransactionLocks(encoded)).thenReturn(List.of(1));

    lock.acquire("subject\\x1fidempotency-key");

    verify(records).acquireTransactionLocks(encoded);
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
