package dev.buhanzaz.rwms.inventory.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Port for durable inventory-start capture and release recovery records.
 */
public interface InventoryStartPersistencePort {
  StartOperation reserve(
      UUID subjectId, UUID idempotencyKey, String requestHash, UUID warehouseId);

  CapturedCapture latestCapture(UUID operationId);

  long beginCaptureAttempt(UUID operationId, String requestFingerprint);

  void recordCaptureFailure(
      UUID operationId, long technicalAttempt, boolean rejected, String failureCode);

  void recordCaptured(
      UUID operationId,
      long technicalAttempt,
      UUID captureId,
      String membershipDigest,
      long totalCount,
      OffsetDateTime expiresAt);

  void sessionCommitted(UUID operationId, UUID sessionId);

  void releaseSucceeded(UUID operationId, UUID captureId);

  void releaseFailed(UUID operationId, String failureCode);

  List<CaptureReleaseClaim> claimPendingReleases(
      String workerId, int limit, Duration leaseDuration);

  void recordClaimSucceeded(UUID operationId, UUID captureId, UUID leaseToken);

  void recordClaimFailed(
      UUID operationId, UUID captureId, UUID leaseToken, String failureCode);

  record StartOperation(
      UUID operationId,
      String requestHash,
      UUID warehouseId,
      String state,
      UUID sessionId,
      OffsetDateTime createdAt) {}

  record CapturedCapture(
      UUID captureId,
      long technicalAttempt,
      long totalCount,
      String membershipDigest,
      OffsetDateTime expiresAt) {}

  record CaptureReleaseClaim(
      UUID operationId, UUID captureId, UUID leaseToken, int attemptCount) {}
}
