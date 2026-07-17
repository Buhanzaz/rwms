package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventoryCaptureRelease;
import dev.buhanzaz.rwms.inventory.domain.InventoryIdempotencyRecord;
import dev.buhanzaz.rwms.inventory.domain.InventoryStartCaptureAttempt;
import dev.buhanzaz.rwms.inventory.domain.InventoryStartCaptureResult;
import dev.buhanzaz.rwms.inventory.domain.InventoryStartOperation;
import dev.buhanzaz.rwms.inventory.repository.InventoryCaptureReleaseRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryIdempotencyRecordRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStartCaptureAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStartCaptureResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStartOperationRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryIdempotencyService
    implements InventoryIdempotencyPort, InventoryStartPersistencePort {
  private static final Duration RELEASE_RETRY_DELAY = Duration.ofSeconds(5);

  private final InventoryIdempotencyRecordRepository idempotencyRecords;
  private final InventoryStartOperationRepository startOperations;
  private final InventoryStartCaptureAttemptRepository captureAttempts;
  private final InventoryStartCaptureResultRepository captureResults;
  private final InventoryCaptureReleaseRepository captureReleases;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final TransactionTemplate independentTransactions;
  private final Duration idempotencyLease;

  public InventoryIdempotencyService(
      InventoryIdempotencyRecordRepository idempotencyRecords,
      InventoryStartOperationRepository startOperations,
      InventoryStartCaptureAttemptRepository captureAttempts,
      InventoryStartCaptureResultRepository captureResults,
      InventoryCaptureReleaseRepository captureReleases,
      InventoryCanonicalJsonPort canonicalJson,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager,
      @Value("${rwms.inventory.idempotency.lease-duration:PT60S}")
          Duration idempotencyLease) {
    this.idempotencyRecords = idempotencyRecords;
    this.startOperations = startOperations;
    this.captureAttempts = captureAttempts;
    this.captureResults = captureResults;
    this.captureReleases = captureReleases;
    this.canonicalJson = canonicalJson;
    this.mapper = mapper;
    transactions = new TransactionTemplate(transactionManager);
    independentTransactions = new TransactionTemplate(transactionManager);
    independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    if (idempotencyLease == null
        || idempotencyLease.isZero()
        || idempotencyLease.isNegative()) {
      throw new IllegalArgumentException("Idempotency lease duration must be positive");
    }
    this.idempotencyLease = idempotencyLease;
  }

  @Override
  public <T> T execute(
      UUID subjectId,
      String commandScope,
      UUID idempotencyKey,
      Object request,
      int responseStatus,
      Class<T> responseType,
      Supplier<T> command) {
    if (subjectId == null
        || commandScope == null
        || commandScope.isBlank()
        || commandScope.length() > 96
        || idempotencyKey == null
        || request == null
        || responseType == null
        || command == null) {
      throw new IllegalArgumentException("Idempotency command is incomplete");
    }
    String requestHash = canonicalJson.sha256(request);
    IdempotencyReservation reservation =
        reserveIdempotency(subjectId, commandScope, idempotencyKey, requestHash);
    if (reservation.responseBody() != null) {
      markReplay(true);
      return read(reservation.responseBody(), responseType);
    }

    return requireTransaction(
        transactions.execute(
            ignored -> {
              InventoryIdempotencyRecord record =
                  idempotencyRecords
                      .findForUpdate(subjectId, commandScope, idempotencyKey)
                      .orElseThrow(
                          () ->
                              new IllegalStateException("Idempotency reservation disappeared"));
              if (!record.ownsLease(reservation.leaseToken(), requestHash)) {
                throw idempotencyConflict("Idempotency reservation ownership changed");
              }
              markReplay(false);
              T response = command.get();
              String responseBody = writeResponse(response);
              try {
                record.complete(
                    reservation.leaseToken(),
                    requestHash,
                    responseStatus,
                    responseBody,
                    now());
                idempotencyRecords.flush();
              } catch (IllegalStateException exception) {
                throw idempotencyConflict(exception.getMessage());
              }
              return response;
            }));
  }

  @Override
  public StartOperation reserve(
      UUID subjectId, UUID idempotencyKey, String requestHash, UUID warehouseId) {
    requireStartIdentity(subjectId, idempotencyKey, requestHash, warehouseId);
    try {
      return requireTransaction(
          independentTransactions.execute(
              ignored -> {
                InventoryStartOperation existing =
                    startOperations
                        .findByKeyForUpdate(subjectId, idempotencyKey)
                        .orElse(null);
                if (existing != null) {
                  return startView(requireMatchingStart(existing, requestHash, warehouseId));
                }
                InventoryStartOperation created =
                    InventoryStartOperation.request(
                        UUID.randomUUID(),
                        subjectId,
                        idempotencyKey,
                        requestHash,
                        warehouseId,
                        now());
                startOperations.saveAndFlush(created);
                return startView(created);
              }));
    } catch (DataIntegrityViolationException race) {
      return requireTransaction(
          independentTransactions.execute(
              ignored ->
                  startView(
                      requireMatchingStart(
                          startOperations
                              .findByKeyForUpdate(subjectId, idempotencyKey)
                              .orElseThrow(
                                  () ->
                                      new IllegalStateException(
                                          "Start reservation winner is missing")),
                          requestHash,
                          warehouseId))));
    }
  }

  @Override
  public CapturedCapture latestCapture(UUID operationId) {
    if (operationId == null) {
      throw new IllegalArgumentException("Start operation is required");
    }
    OffsetDateTime current = now();
    return transactions.execute(
        ignored ->
            captureResults
                .findFirstByOperationIdAndOutcomeAndExpiresAtAfterOrderByTechnicalAttemptDesc(
                    operationId, "CAPTURED", current)
                .map(this::captureView)
                .orElse(null));
  }

  @Override
  public long beginCaptureAttempt(UUID operationId, String requestFingerprint) {
    requireOperationAndHash(operationId, requestFingerprint);
    Long value =
        independentTransactions.execute(
            ignored -> {
              InventoryStartOperation operation = requireUnlockedStart(operationId);
              if (operation.getSessionId() != null) {
                throw idempotencyConflict("Start operation already has a durable session");
              }
              InventoryStartCaptureAttempt latest =
                  captureAttempts
                      .findFirstByOperationIdOrderByTechnicalAttemptDesc(operationId)
                      .orElse(null);
              if (latest != null) {
                InventoryStartCaptureResult result =
                    captureResults
                        .findById(
                            new InventoryStartCaptureResult.Key(
                                operationId, latest.getTechnicalAttempt()))
                        .orElse(null);
                if (result == null) {
                  if (!latest.getRequestFingerprint().equals(requestFingerprint)) {
                    throw idempotencyConflict(
                        "Capture attempt is bound to another request fingerprint");
                  }
                  return latest.getTechnicalAttempt();
                }
              }
              long next =
                  latest == null ? 1 : Math.addExact(latest.getTechnicalAttempt(), 1);
              captureAttempts.saveAndFlush(
                  InventoryStartCaptureAttempt.request(
                      operationId, next, requestFingerprint, now()));
              return next;
            });
    return requireTransaction(value);
  }

  @Override
  public void recordCaptureFailure(
      UUID operationId, long technicalAttempt, boolean rejected, String failureCode) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          requireAttempt(operationId, technicalAttempt);
          InventoryStartCaptureResult.Key key =
              new InventoryStartCaptureResult.Key(operationId, technicalAttempt);
          InventoryStartCaptureResult existing = captureResults.findById(key).orElse(null);
          if (existing != null) {
            if (!existing.isSameFailure(rejected, failureCode)) {
              throw idempotencyConflict("Capture attempt already has another result");
            }
            return;
          }
          captureResults.saveAndFlush(
              InventoryStartCaptureResult.failed(
                  operationId, technicalAttempt, rejected, failureCode, now()));
          operation.markCaptureFailed(now());
        });
  }

  @Override
  public void recordCaptured(
      UUID operationId,
      long technicalAttempt,
      UUID captureId,
      String membershipDigest,
      long totalCount,
      OffsetDateTime expiresAt) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          requireAttempt(operationId, technicalAttempt);
          InventoryStartCaptureResult.Key key =
              new InventoryStartCaptureResult.Key(operationId, technicalAttempt);
          InventoryStartCaptureResult existing = captureResults.findById(key).orElse(null);
          if (existing != null) {
            if (!existing.isSameCapture(
                captureId, membershipDigest, totalCount, expiresAt)) {
              throw idempotencyConflict("Capture attempt already has another result");
            }
            return;
          }
          OffsetDateTime current = now();
          InventoryStartCaptureResult result =
              InventoryStartCaptureResult.captured(
                  operationId,
                  technicalAttempt,
                  captureId,
                  membershipDigest,
                  totalCount,
                  expiresAt,
                  current);
          captureResults.saveAndFlush(result);
          InventoryCaptureRelease release =
              captureReleases.findByIdForUpdate(operationId).orElse(null);
          if (release == null) {
            captureReleases.saveAndFlush(
                InventoryCaptureRelease.pending(
                    operationId, technicalAttempt, captureId, current));
          } else {
            release.retarget(technicalAttempt, captureId, current);
          }
          operation.markCaptured(current);
        });
  }

  @Override
  public void sessionCommitted(UUID operationId, UUID sessionId) {
    transactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          InventoryCaptureRelease release = requireRelease(operationId);
          if (operation.getSessionId() != null) {
            if (!operation.getSessionId().equals(sessionId)) {
              throw idempotencyConflict("Start operation points to another session");
            }
            return;
          }
          operation.markSessionCommitted(sessionId, now());
          if (!release.getOperationId().equals(operationId)) {
            throw new IllegalStateException("Capture release belongs to another operation");
          }
          release.makeEligible(now());
        });
  }

  @Override
  public void releaseSucceeded(UUID operationId, UUID captureId) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          InventoryCaptureRelease release = requireRelease(operationId);
          requireCapture(release, captureId);
          OffsetDateTime current = now();
          release.release(current);
          markCaptureReleased(operation, current);
        });
  }

  @Override
  public void releaseFailed(UUID operationId, String failureCode) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          InventoryCaptureRelease release = requireRelease(operationId);
          OffsetDateTime current = now();
          release.failDirect(failureCode, current.plus(RELEASE_RETRY_DELAY));
          operation.markReleasePending(current);
        });
  }

  @Override
  public List<CaptureReleaseClaim> claimPendingReleases(
      String workerId, int limit, Duration leaseDuration) {
    if (workerId == null
        || workerId.isBlank()
        || workerId.length() > 128
        || limit < 1
        || limit > 1000
        || leaseDuration == null
        || leaseDuration.isZero()
        || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Capture release claim is invalid");
    }
    return requireTransaction(
        independentTransactions.execute(
            ignored -> {
              OffsetDateTime current = now();
              return captureReleases
                  .findClaimableForUpdate(current, PageRequest.of(0, limit))
                  .stream()
                  .map(
                      release -> {
                        UUID token =
                            release.claim(workerId, current, current.plus(leaseDuration));
                        return new CaptureReleaseClaim(
                            release.getOperationId(),
                            release.getCaptureId(),
                            token,
                            release.getAttemptCount());
                      })
                  .toList();
            }));
  }

  @Override
  public void recordClaimSucceeded(UUID operationId, UUID captureId, UUID leaseToken) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          InventoryCaptureRelease release = requireRelease(operationId);
          requireCapture(release, captureId);
          if (!Objects.equals(leaseToken, release.getLeaseToken())) {
            throw idempotencyConflict("Capture release lease changed before completion");
          }
          OffsetDateTime current = now();
          release.release(current);
          markCaptureReleased(operation, current);
        });
  }

  @Override
  public void recordClaimFailed(
      UUID operationId, UUID captureId, UUID leaseToken, String failureCode) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryStartOperation operation = requireUnlockedStart(operationId);
          InventoryCaptureRelease release = requireRelease(operationId);
          requireCapture(release, captureId);
          OffsetDateTime current = now();
          release.fail(leaseToken, failureCode, current.plus(RELEASE_RETRY_DELAY));
          operation.markReleasePending(current);
        });
  }

  private static void markCaptureReleased(
      InventoryStartOperation operation, OffsetDateTime releasedAt) {
    if (operation.getSessionId() == null) {
      operation.markCaptureReleased(releasedAt);
    } else {
      operation.markReleased(releasedAt);
    }
  }

  private IdempotencyReservation reserveIdempotency(
      UUID subjectId, String commandScope, UUID idempotencyKey, String requestHash) {
    try {
      return requireTransaction(
          transactions.execute(
              ignored ->
                  reserveIdempotencyInTransaction(
                      subjectId, commandScope, idempotencyKey, requestHash)));
    } catch (DataIntegrityViolationException race) {
      return requireTransaction(
          transactions.execute(
              ignored ->
                  reserveExistingIdempotency(
                      idempotencyRecords
                          .findForUpdate(subjectId, commandScope, idempotencyKey)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Idempotency reservation winner is missing")),
                      requestHash,
                      now())));
    }
  }

  private IdempotencyReservation reserveIdempotencyInTransaction(
      UUID subjectId, String commandScope, UUID idempotencyKey, String requestHash) {
    OffsetDateTime current = now();
    InventoryIdempotencyRecord existing =
        idempotencyRecords
            .findForUpdate(subjectId, commandScope, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      if (existing.isExpired(current)) {
        idempotencyRecords.delete(existing);
        idempotencyRecords.flush();
      } else {
        return reserveExistingIdempotency(existing, requestHash, current);
      }
    }
    UUID leaseToken = UUID.randomUUID();
    idempotencyRecords.saveAndFlush(
        InventoryIdempotencyRecord.reserve(
            subjectId,
            commandScope,
            idempotencyKey,
            requestHash,
            leaseToken,
            current,
            current.plus(idempotencyLease)));
    return new IdempotencyReservation(leaseToken, null);
  }

  private IdempotencyReservation reserveExistingIdempotency(
      InventoryIdempotencyRecord existing, String requestHash, OffsetDateTime current) {
    if (!existing.hasRequestHash(requestHash)) {
      throw idempotencyConflict("Idempotency key is bound to another request");
    }
    if (existing.isCompleted()) {
      return new IdempotencyReservation(null, existing.getResponseBody());
    }
    if (existing.hasActiveLease(current)) {
      throw idempotencyConflict("An identical command is still in progress");
    }
    UUID leaseToken = UUID.randomUUID();
    existing.reclaim(leaseToken, current, current.plus(idempotencyLease));
    idempotencyRecords.flush();
    return new IdempotencyReservation(leaseToken, null);
  }

  private InventoryStartOperation requireMatchingStart(
      InventoryStartOperation operation, String requestHash, UUID warehouseId) {
    if (!operation.getRequestSha256().equals(requestHash)
        || !operation.getWarehouseId().equals(warehouseId)) {
      throw idempotencyConflict("Start operation is bound to another request");
    }
    return operation;
  }

  private InventoryStartOperation requireUnlockedStart(UUID operationId) {
    if (operationId == null) {
      throw new IllegalArgumentException("Start operation is required");
    }
    return startOperations
        .findByIdForUpdate(operationId)
        .orElseThrow(() -> new IllegalStateException("Start operation does not exist"));
  }

  private InventoryStartCaptureAttempt requireAttempt(UUID operationId, long technicalAttempt) {
    if (technicalAttempt < 1) {
      throw new IllegalArgumentException("Technical capture attempt must be positive");
    }
    return captureAttempts
        .findById(new InventoryStartCaptureAttempt.Key(operationId, technicalAttempt))
        .orElseThrow(() -> new IllegalStateException("Capture attempt does not exist"));
  }

  private InventoryCaptureRelease requireRelease(UUID operationId) {
    return captureReleases
        .findByIdForUpdate(operationId)
        .orElseThrow(() -> new IllegalStateException("Capture release does not exist"));
  }

  private void requireCapture(InventoryCaptureRelease release, UUID captureId) {
    if (captureId == null || !release.getCaptureId().equals(captureId)) {
      throw idempotencyConflict("Capture release is bound to another capture");
    }
  }

  private void requireStartIdentity(
      UUID subjectId, UUID idempotencyKey, String requestHash, UUID warehouseId) {
    if (subjectId == null
        || idempotencyKey == null
        || !sha256(requestHash)
        || warehouseId == null) {
      throw new IllegalArgumentException("Start operation identity is incomplete");
    }
  }

  private void requireOperationAndHash(UUID operationId, String requestFingerprint) {
    if (operationId == null || !sha256(requestFingerprint)) {
      throw new IllegalArgumentException("Capture attempt identity is incomplete");
    }
  }

  private boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private StartOperation startView(InventoryStartOperation value) {
    return new StartOperation(
        value.getOperationId(),
        value.getRequestSha256(),
        value.getWarehouseId(),
        value.getState(),
        value.getSessionId(),
        value.getCreatedAt());
  }

  private CapturedCapture captureView(InventoryStartCaptureResult value) {
    return new CapturedCapture(
        value.getCaptureId(),
        value.getTechnicalAttempt(),
        value.getTotalCount(),
        value.getMembershipDigest(),
        value.getExpiresAt());
  }

  private String writeResponse(Object value) {
    try {
      JsonNode node = mapper.valueToTree(value);
      if (!node.isObject() && !node.isArray()) {
        throw new IllegalArgumentException("Idempotency response must be a JSON object or array");
      }
      return mapper.writeValueAsString(node);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Idempotency response is not serializable", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.treeToValue(mapper.readTree(value), type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored idempotency response is invalid", exception);
    }
  }

  private void markReplay(boolean replayed) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.setAttribute(REPLAY_ATTRIBUTE, replayed, RequestAttributes.SCOPE_REQUEST);
    }
  }

  private InventoryException idempotencyConflict(String message) {
    return new InventoryException(
        HttpStatus.CONFLICT, "INVENTORY_IDEMPOTENCY_CONFLICT", message);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private <T> T requireTransaction(T value) {
    if (value == null) {
      throw new IllegalStateException("Persistence transaction returned no result");
    }
    return value;
  }

  private record IdempotencyReservation(UUID leaseToken, String responseBody) {}

}
