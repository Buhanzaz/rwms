package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns only the database lease and fencing lifecycle for logistics external attempts. PostgreSQL
 * transaction time, rather than a replica JVM clock, defines eligibility and expiry. Claim
 * transactions finish before a processor makes remote HTTP calls; workflow stores retain all
 * owner-specific state transitions and call this service only to verify an exact lease capability.
 */
@Service
@RequiredArgsConstructor
public class LogisticsExternalAttemptClaimService {
  private static final List<LogisticsExternalAttemptResult> CLAIMABLE_RESULTS =
      List.of(LogisticsExternalAttemptResult.PENDING, LogisticsExternalAttemptResult.RETRY);

  private final LogisticsExternalAttemptRepository attemptRepository;
  private final LogisticsExternalAttemptClaimProperties properties;
  private final LogisticsExternalAttemptClaimMetrics metrics;

  /**
   * Claims at most {@code requestedPageSize} attempts from explicit operation families. The
   * repository uses a pessimistic skip-locked page, and this transaction flushes its leases before
   * returning immutable claim values to a worker.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Claim> claimDue(
      Owner owner, List<String> operationTypes, int requestedPageSize) {
    requireOwner(owner);
    long startedAt = System.nanoTime();
    try {
      List<String> types = requireOperationTypes(operationTypes);
      List<LogisticsExternalAttempt> attempts =
          attemptRepository.lockDueByOperationTypes(
              types, CLAIMABLE_RESULTS, PageRequest.of(0, pageSize(requestedPageSize)));
      return claim(owner, attempts);
    } finally {
      metrics.claimLatency(owner, System.nanoTime() - startedAt);
    }
  }

  /**
   * Claims at most {@code requestedPageSize} attempts from one closed operation namespace. This
   * is used only by shipment hold operations whose suffix is part of the durable operation name.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Claim> claimDueByOperationPrefix(
      Owner owner, String operationPrefix, int requestedPageSize) {
    requireOwner(owner);
    long startedAt = System.nanoTime();
    try {
      List<LogisticsExternalAttempt> attempts =
          attemptRepository.lockDueByOperationPrefix(
              escapedOperationPrefix(operationPrefix),
              CLAIMABLE_RESULTS,
              PageRequest.of(0, pageSize(requestedPageSize)));
      return claim(owner, attempts);
    } finally {
      metrics.claimLatency(owner, System.nanoTime() - startedAt);
    }
  }

  /** Claims one exact-family attempt, preserving a relay's current owner permit budget. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claimNext(Owner owner, List<String> operationTypes) {
    return claimDue(owner, operationTypes, 1).stream().findFirst();
  }

  /** Claims one namespace attempt, preserving a relay's current owner permit budget. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claimNextByOperationPrefix(Owner owner, String operationPrefix) {
    return claimDueByOperationPrefix(owner, operationPrefix, 1).stream().findFirst();
  }

  /**
   * Locks and validates every immutable claim field before an owning workflow store changes local
   * state. The method joins the store's short transaction and never initiates remote I/O.
   */
  @Transactional
  public LogisticsExternalAttempt requireCurrentAttempt(Claim claim) {
    if (claim == null) throw new IllegalArgumentException("claim is required");
    LogisticsExternalAttempt attempt =
        attemptRepository
            .lockCurrentClaim(
                claim.attemptId(),
                claim.operationId(),
                claim.leaseToken(),
                claim.leaseFence(),
                claim.rowVersion(),
                claim.requestSha256(),
                CLAIMABLE_RESULTS)
            .orElseThrow(() -> stale(claim.owner()));
    OffsetDateTime databaseNow = databaseNow(claim.attemptId());
    if (!attempt.matchesClaim(
        claim.operationId(),
        claim.leaseToken(),
        claim.leaseFence(),
        claim.rowVersion(),
        claim.requestSha256(),
        databaseNow)) {
      throw stale(claim.owner());
    }
    return attempt;
  }

  /**
   * Releases a still-current claim when its owner-local prerequisites are not ready. The next
   * eligible time is delayed without incrementing remote retry count or retaining a worker permit.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void defer(Claim claim) {
    LogisticsExternalAttempt attempt = requireCurrentAttempt(claim);
    attempt.defer(databaseNow(claim.attemptId()).plus(properties.blockedWorkDelay()));
    metrics.deferred(claim.owner());
  }

  private List<Claim> claim(Owner owner, List<LogisticsExternalAttempt> attempts) {
    if (attempts.isEmpty()) return List.of();
    OffsetDateTime databaseNow = databaseNow(attempts.getFirst().getId());
    OffsetDateTime expiresAt = databaseNow.plus(properties.leaseDuration());
    for (LogisticsExternalAttempt attempt : attempts) {
      attempt.claim(UUID.randomUUID(), expiresAt, databaseNow);
    }
    attemptRepository.flush();
    List<Claim> claims =
        attempts.stream()
            .map(
                attempt ->
                    new Claim(
                        owner,
                        attempt.getId(),
                        attempt.getOperationId(),
                        attempt.getLeaseToken(),
                        attempt.getLeaseFence(),
                        attempt.getRowVersion(),
                        attempt.getRequestSha256()))
            .toList();
    metrics.claimed(owner, claims.size());
    return claims;
  }

  private int pageSize(int requestedPageSize) {
    if (requestedPageSize < 1) {
      throw new IllegalArgumentException("requestedPageSize must be positive");
    }
    return Math.min(requestedPageSize, properties.maximumPageSize());
  }

  private static List<String> requireOperationTypes(List<String> operationTypes) {
    if (operationTypes == null || operationTypes.isEmpty()) {
      throw new IllegalArgumentException("operationTypes are required");
    }
    List<String> copy = List.copyOf(operationTypes);
    if (copy.stream().anyMatch(type -> type == null || type.isBlank())) {
      throw new IllegalArgumentException("operationTypes must be non-blank");
    }
    return copy;
  }

  /**
   * Escapes a closed operation namespace before using it in JPQL {@code LIKE}. In particular,
   * underscore and percent must remain literal operation-name characters rather than widening an
   * owner's selection into another workflow family.
   */
  private static String escapedOperationPrefix(String operationPrefix) {
    if (operationPrefix == null || operationPrefix.isBlank()) {
      throw new IllegalArgumentException("operationPrefix is required");
    }
    return operationPrefix.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
  }

  private static void requireOwner(Owner owner) {
    if (owner == null) throw new IllegalArgumentException("owner is required");
  }

  private StaleClaimException stale(Owner owner) {
    metrics.stale(owner);
    return new StaleClaimException();
  }

  private OffsetDateTime databaseNow(UUID attemptId) {
    return attemptRepository
        .currentDatabaseTimestamp(attemptId)
        .orElseThrow(() -> new IllegalStateException("Claimed external attempt is missing"));
  }

  /** Fixed owner set for permit limits and metrics; it deliberately contains no document identity. */
  public enum Owner {
    RETURN_REGISTRATION("return_registration"),
    RETURN_COMPLETION("return_completion"),
    SHIPMENT("shipment"),
    TRANSFER("transfer"),
    MEDIA_OWNER_PROOF("media_owner_proof");

    private final String metricTag;

    Owner(String metricTag) {
      this.metricTag = metricTag;
    }

    /** Returns the closed metric label for this owner. */
    String metricTag() {
      return metricTag;
    }
  }

  /**
   * Immutable, payload-free capability for exactly one attempt lease. A processor may carry it
   * across remote I/O, but every local completion verifies all fields again under a row lock.
   */
  public record Claim(
      Owner owner,
      UUID attemptId,
      UUID operationId,
      UUID leaseToken,
      long leaseFence,
      long rowVersion,
      String requestSha256) {
    /** Rejects incomplete or non-digest capabilities before a worker can use them. */
    public Claim {
      if (owner == null
          || attemptId == null
          || operationId == null
          || leaseToken == null
          || requestSha256 == null
          || !requestSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("A complete external-attempt lease capability is required");
      }
      if (leaseFence < 1 || rowVersion < 0) {
        throw new IllegalArgumentException("Lease fence and row version are invalid");
      }
    }
  }

  /** Raised when a worker lost, expired or otherwise no longer owns its exact fenced lease. */
  public static final class StaleClaimException extends IllegalStateException {
    StaleClaimException() {
      super("External attempt claim is no longer current");
    }
  }
}
