package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Coordinates the prepare/remote/consume warehouse lifecycle handshake outside aggregate
 * transactions. A durable local identity plus exact operation marks can skip remote reads only as
 * a replay candidate; the owning create path remains the payload-checksum authority.
 */
@Service
public class LogisticsWarehouseLifecycle {
  private static final String DEPENDENCY_UNAVAILABLE =
      "Warehouse admission dependencies are not ready";

  private final LogisticsDependencyGateway dependencies;
  private final LogisticsWarehouseLifecycleStore store;
  private final Environment environment;

  /** Creates the lifecycle boundary from the private owner gateway, local fence store and profile. */
  public LogisticsWarehouseLifecycle(
      LogisticsDependencyGateway dependencies,
      LogisticsWarehouseLifecycleStore store,
      Environment environment) {
    this.dependencies = dependencies;
    this.store = store;
    this.environment = environment;
  }

  /**
   * Runs before the owning aggregate transaction. The reserved local intent remains a readiness
   * blocker while the exact warehouse-service admission and timezone reads are in flight.
   */
  public AdmissionTicket prepareDocument(
      UUID subjectId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    if (subjectId == null
        || operationName == null
        || operationName.isBlank()
        || idempotencyKey == null
        || requirements == null
        || requirements.isEmpty()) {
      throw new IllegalArgumentException("Warehouse admission command identity is incomplete");
    }
    return prepare(
        subjectId,
        operationName,
        idempotencyKey,
        requirements,
        store.evidencedDocumentReplay(subjectId, operationName, idempotencyKey).orElse(null));
  }

  /** Prepares an equipment movement only after every affected warehouse admits its direction. */
  public AdmissionTicket prepareEquipmentMovement(
      UUID actorSubjectId,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || requirements == null
        || requirements.isEmpty()) {
      throw new IllegalArgumentException("Equipment movement admission identity is incomplete");
    }
    return prepare(
        actorSubjectId,
        "CREATE_EQUIPMENT_MOVEMENT_TASK",
        idempotencyKey,
        requirements,
        store.evidencedEquipmentMovementReplay(actorSubjectId, idempotencyKey).orElse(null));
  }

  /** Prepares a driver task only after every affected warehouse admits its direction. */
  public AdmissionTicket prepareDriverTask(
      UUID actorSubjectId,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    if (actorSubjectId == null
        || idempotencyKey == null
        || requirements == null
        || requirements.isEmpty()) {
      throw new IllegalArgumentException("Driver task admission identity is incomplete");
    }
    return prepare(
        actorSubjectId,
        "CREATE_DRIVER_LOGISTICS_TASK",
        idempotencyKey,
        requirements,
        store.evidencedDriverTaskReplay(actorSubjectId, idempotencyKey).orElse(null));
  }

  /** Reads the authoritative warehouse-local calendar date without opening an operation intent. */
  public LocalDate currentLocalDate(UUID warehouseId) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.WarehouseTimeZone timeZone =
        dependencies.warehouseTimeZoneAt(warehouseId, occurredAt);
    return occurredAt.toInstant().atZone(ZoneId.of(timeZone.timeZone())).toLocalDate();
  }

  private AdmissionTicket prepare(
      UUID ownerId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements,
      List<AdmissionEvidence> replayCandidateEvidence) {
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    List<AdmissionRequirement> orderedRequirements =
        requirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    UUID operationId =
        UUID.nameUUIDFromBytes(
            ("logistics-warehouse-admission-v1:"
                    + ownerId
                    + ":"
                    + operationName
                    + ":"
                    + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
    if (replayCandidateEvidence != null) {
      return AdmissionTicket.evidencedReplayCandidate(
          operationId, occurredAt, replayCandidateEvidence);
    }
    if (!dependencies.productionReady()) {
      throw unavailable();
    }

    boolean alreadyAdmitted = store.reserve(operationId, orderedRequirements);
    List<AdmissionEvidence> evidence;
    if (!alreadyAdmitted) {
      try {
        List<Long> versions =
            orderedRequirements.stream()
                .map(
                    requirement -> {
                      LogisticsDependencyGateway.WarehouseOperationAdmission admission =
                          dependencies.warehouseAdmission(
                              requirement.warehouseId(), requirement.direction());
                      if (!admission.admitted()) {
                        throw new LogisticsConflictException(
                            "Warehouse "
                                + admission.lifecycleState()
                                + " does not admit "
                                + requirement.direction()
                                + " logistics work");
                      }
                      return admission.warehouseVersion();
                    })
                .toList();
        store.admit(operationId, orderedRequirements, versions);
        evidence = evidence(orderedRequirements, versions);
      } catch (RuntimeException failure) {
        store.cancelReserved(operationId, orderedRequirements);
        throw failure;
      }
    } else {
      evidence = store.admittedEvidence(operationId, orderedRequirements);
    }

    Map<UUID, LocalDate> localDates = new LinkedHashMap<>();
    // An ADMITTED intent deliberately remains until its short expiry when a timezone lookup fails.
    // Readiness must not race a request whose local-date outcome is unknown.
    for (AdmissionRequirement requirement : orderedRequirements) {
      LogisticsDependencyGateway.WarehouseTimeZone timeZone =
          dependencies.warehouseTimeZoneAt(requirement.warehouseId(), occurredAt);
      localDates.put(
          requirement.warehouseId(),
          occurredAt.toInstant().atZone(ZoneId.of(timeZone.timeZone())).toLocalDate());
    }
    return AdmissionTicket.remote(
        operationId,
        orderedRequirements,
        occurredAt,
        Map.copyOf(localDates),
        evidence);
  }

  private static List<AdmissionEvidence> evidence(
      List<AdmissionRequirement> requirements, List<Long> versions) {
    if (requirements.size() != versions.size()) {
      throw new IllegalArgumentException("Warehouse admission versions are incomplete");
    }
    java.util.ArrayList<AdmissionEvidence> evidence =
        new java.util.ArrayList<>(requirements.size());
    for (int index = 0; index < requirements.size(); index++) {
      AdmissionRequirement requirement = requirements.get(index);
      evidence.add(
          new AdmissionEvidence(
              requirement.warehouseId(), requirement.direction(), versions.get(index)));
    }
    return List.copyOf(evidence);
  }

  /**
   * Consumes only a remote admission or an explicitly parent-owned/test-only ticket. An evidenced
   * replay candidate is valid only while an owner verifies the stored request checksum and returns
   * its already-created aggregate; it is rejected here so it can never authorize a new mutation.
   */
  public void consume(AdmissionTicket ticket) {
    if (ticket == null) {
      throw new IllegalArgumentException("Warehouse admission ticket is required");
    }
    switch (ticket.kind()) {
      case REMOTE_ADMISSION ->
          store.consume(
              ticket.operationId(), ticket.requirements(), ticket.admissionEvidence(), false);
      case EVIDENCED_REPLAY_CANDIDATE ->
          throw new LogisticsConflictException(
              "An evidenced warehouse replay candidate cannot create new logistics work");
      case OWNED_CONTINUATION ->
          store.consume(ticket.operationId(), ticket.requirements(), List.of(), true);
      case TEST_ONLY -> {
        requireTestOnlyBypass();
        store.consume(ticket.operationId(), ticket.requirements(), List.of(), true);
      }
    }
  }

  public LocalDate localDateAt(UUID warehouseId, OffsetDateTime at) {
    if (warehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse local-date lookup identity is incomplete");
    }
    if (!dependencies.productionReady()) return at.toLocalDate();
    LogisticsDependencyGateway.WarehouseTimeZone timeZone =
        dependencies.warehouseTimeZoneAt(warehouseId, at);
    return at.toInstant().atZone(ZoneId.of(timeZone.timeZone())).toLocalDate();
  }

  /**
   * Supplies a no-remote ticket only to direct fixtures running under the explicit {@code test}
   * profile; runtime callers cannot use this method as a disabled-dependency fallback.
   */
  public AdmissionTicket disabledTicket(
      UUID ownerId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    if (ownerId == null
        || operationName == null
        || operationName.isBlank()
        || idempotencyKey == null
        || requirements == null
        || requirements.isEmpty()) {
      throw new IllegalArgumentException("Warehouse admission command identity is incomplete");
    }
    requireTestOnlyBypass();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    UUID operationId =
        UUID.nameUUIDFromBytes(
            ("logistics-test-admission:"
                    + ownerId
                    + ":"
                    + operationName
                    + ":"
                    + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
    List<AdmissionRequirement> ordered =
        requirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    return AdmissionTicket.bypassed(
        operationId, ordered, occurredAt, AdmissionKind.TEST_ONLY);
  }

  /** Verifies that the current context is the isolated disabled-dependency test harness. */
  void requireTestOnlyBypass() {
    if (!environment.matchesProfiles("test") || dependencies.productionReady()) {
      throw unavailable();
    }
  }

  /**
   * Child work of an already-live logistics aggregate needs no second remote admission: the
   * parent document itself blocks readiness until the child is durable. Callers must hold/validate
   * that parent inside their owning transaction.
   */
  public AdmissionTicket ownedContinuation(
      UUID parentOperationId, UUID childOperationId, List<AdmissionRequirement> requirements) {
    if (parentOperationId == null
        || childOperationId == null
        || requirements == null
        || requirements.isEmpty()) {
      throw new IllegalArgumentException("Owned warehouse continuation identity is incomplete");
    }
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    UUID operationId =
        UUID.nameUUIDFromBytes(
            ("logistics-owned-continuation:"
                    + parentOperationId
                    + ":"
                    + childOperationId)
                .getBytes(StandardCharsets.UTF_8));
    List<AdmissionRequirement> ordered =
        requirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    store.requireOwnedContinuation(ordered);
    return AdmissionTicket.bypassed(
        operationId, ordered, occurredAt, AdmissionKind.OWNED_CONTINUATION);
  }

  /** Describes why a ticket does or does not require a local admitted-intent consumption. */
  public enum AdmissionKind {
    REMOTE_ADMISSION,
    /** Local evidence identifies an existing operation, but its owner must still match payload. */
    EVIDENCED_REPLAY_CANDIDATE,
    OWNED_CONTINUATION,
    TEST_ONLY
  }

  /**
   * Exact warehouse-service decision committed with a logistics operation mark. Its lifecycle
   * version is evidence, not a mutable warehouse projection.
   *
   * @param warehouseId warehouse whose lifecycle admitted the operation
   * @param direction exact admitted operation direction
   * @param warehouseVersion non-negative lifecycle version returned by warehouse-service
   */
  public record AdmissionEvidence(
      UUID warehouseId, WarehouseOperationDirection direction, long warehouseVersion) {
    public AdmissionEvidence {
      if (warehouseId == null || direction == null || warehouseVersion < 0) {
        throw new IllegalArgumentException("Warehouse admission evidence is invalid");
      }
    }
  }

  /**
   * Immutable admission ticket carried from preparation into the owning local transaction.
   * Remote tickets and evidenced replay candidates contain a complete exact evidence vector;
   * parent/test-only tickets are intentionally unproven and can never authorize public replay. A
   * candidate is not proof of payload equality; only its owning create path verifies the checksum.
   */
  public static final class AdmissionTicket {
    private final UUID operationId;
    private final List<AdmissionRequirement> requirements;
    private final OffsetDateTime occurredAt;
    private final Map<UUID, LocalDate> localDates;
    private final boolean bypassed;
    private final AdmissionKind kind;
    private final List<AdmissionEvidence> admissionEvidence;

    /**
     * Constructs an explicitly unproven test-only ticket for direct fixtures. Production remote,
     * replay and parent-owned tickets must use the lifecycle's validated factories.
     */
    public AdmissionTicket(
        UUID operationId,
        List<AdmissionRequirement> requirements,
        OffsetDateTime occurredAt,
        Map<UUID, LocalDate> localDates,
        boolean bypassed,
        AdmissionKind kind) {
      this(
          operationId,
          requirements,
          occurredAt,
          localDates,
          bypassed,
          requirePublicTestOnlyKind(kind),
          List.of());
    }

    private static AdmissionKind requirePublicTestOnlyKind(AdmissionKind kind) {
      if (kind != AdmissionKind.TEST_ONLY) {
        throw new IllegalArgumentException(
            "Only an explicit TEST_ONLY ticket can use the public constructor");
      }
      return kind;
    }

    private AdmissionTicket(
        UUID operationId,
        List<AdmissionRequirement> requirements,
        OffsetDateTime occurredAt,
        Map<UUID, LocalDate> localDates,
        boolean bypassed,
        AdmissionKind kind,
        List<AdmissionEvidence> admissionEvidence) {
      if (operationId == null
          || requirements == null
          || requirements.isEmpty()
          || occurredAt == null
          || localDates == null
          || kind == null
          || admissionEvidence == null
          || (bypassed == (kind == AdmissionKind.REMOTE_ADMISSION))) {
        throw new IllegalArgumentException("Warehouse admission ticket is invalid");
      }
      this.operationId = operationId;
      this.requirements =
          requirements.stream()
              .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
              .toList();
      if (this.requirements.stream().map(AdmissionRequirement::warehouseId).distinct().count()
          != this.requirements.size()) {
        throw new IllegalArgumentException("Warehouse admission ticket has duplicate warehouses");
      }
      java.util.Set<UUID> warehouses =
          this.requirements.stream()
              .map(AdmissionRequirement::warehouseId)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (!warehouses.equals(localDates.keySet())) {
        throw new IllegalArgumentException(
            "Warehouse admission ticket local dates are incomplete");
      }
      this.occurredAt = occurredAt;
      this.localDates = Map.copyOf(localDates);
      this.bypassed = bypassed;
      this.kind = kind;
      this.admissionEvidence = normalizedEvidence(this.requirements, admissionEvidence, kind);
    }

    /** Creates a fresh remote-admission ticket with a complete exact lifecycle-version vector. */
    static AdmissionTicket remote(
        UUID operationId,
        List<AdmissionRequirement> requirements,
        OffsetDateTime occurredAt,
        Map<UUID, LocalDate> localDates,
        List<AdmissionEvidence> admissionEvidence) {
      return new AdmissionTicket(
          operationId,
          requirements,
          occurredAt,
          localDates,
          false,
          AdmissionKind.REMOTE_ADMISSION,
          admissionEvidence);
    }

    /**
     * Creates a no-remote candidate backed by a live domain identity and exact permanent marks.
     * The owning create path must still validate its stored request checksum before returning.
     */
    static AdmissionTicket evidencedReplayCandidate(
        UUID operationId,
        OffsetDateTime occurredAt,
        List<AdmissionEvidence> admissionEvidence) {
      List<AdmissionRequirement> requirements =
          admissionEvidence.stream()
              .map(
                  evidence ->
                      new AdmissionRequirement(evidence.warehouseId(), evidence.direction()))
              .toList();
      Map<UUID, LocalDate> fallback = fallbackLocalDates(requirements, occurredAt);
      return new AdmissionTicket(
          operationId,
          requirements,
          occurredAt,
          fallback,
          true,
          AdmissionKind.EVIDENCED_REPLAY_CANDIDATE,
          admissionEvidence);
    }

    static AdmissionTicket bypassed(
        UUID operationId,
        List<AdmissionRequirement> requirements,
        OffsetDateTime occurredAt,
        AdmissionKind kind) {
      Map<UUID, LocalDate> fallback = fallbackLocalDates(requirements, occurredAt);
      return new AdmissionTicket(
          operationId, requirements, occurredAt, fallback, true, kind, List.of());
    }

    private static Map<UUID, LocalDate> fallbackLocalDates(
        List<AdmissionRequirement> requirements, OffsetDateTime occurredAt) {
      Map<UUID, LocalDate> fallback = new LinkedHashMap<>();
      for (AdmissionRequirement requirement : requirements) {
        fallback.put(requirement.warehouseId(), occurredAt.toLocalDate());
      }
      return Map.copyOf(fallback);
    }

    private static List<AdmissionEvidence> normalizedEvidence(
        List<AdmissionRequirement> requirements,
        List<AdmissionEvidence> admissionEvidence,
        AdmissionKind kind) {
      if (kind == AdmissionKind.OWNED_CONTINUATION || kind == AdmissionKind.TEST_ONLY) {
        if (!admissionEvidence.isEmpty()) {
          throw new IllegalArgumentException("A bypass ticket cannot carry admission evidence");
        }
        return List.of();
      }
      if (admissionEvidence.size() != requirements.size()) {
        throw new IllegalArgumentException("Warehouse admission ticket evidence is incomplete");
      }
      List<AdmissionEvidence> normalized =
          admissionEvidence.stream()
              .sorted(Comparator.comparing(AdmissionEvidence::warehouseId))
              .toList();
      for (int index = 0; index < requirements.size(); index++) {
        AdmissionRequirement requirement = requirements.get(index);
        AdmissionEvidence item = normalized.get(index);
        if (!requirement.warehouseId().equals(item.warehouseId())
            || requirement.direction() != item.direction()) {
          throw new IllegalArgumentException(
              "Warehouse admission ticket evidence does not match its requirements");
        }
      }
      return normalized;
    }

    public UUID operationId() {
      return operationId;
    }

    public List<AdmissionRequirement> requirements() {
      return requirements;
    }

    public OffsetDateTime occurredAt() {
      return occurredAt;
    }

    public Map<UUID, LocalDate> localDates() {
      return localDates;
    }

    public boolean bypassed() {
      return bypassed;
    }

    public AdmissionKind kind() {
      return kind;
    }

    public List<AdmissionEvidence> admissionEvidence() {
      return admissionEvidence;
    }

    /** Returns the exact remote evidence for one mark, or empty for unproven ticket kinds. */
    public java.util.Optional<AdmissionEvidence> evidenceFor(UUID warehouseId) {
      if (warehouseId == null) return java.util.Optional.empty();
      return admissionEvidence.stream()
          .filter(evidence -> warehouseId.equals(evidence.warehouseId()))
          .findFirst();
    }

    public LocalDate localDate(UUID warehouseId) {
      LocalDate value = localDates.get(warehouseId);
      if (value == null) {
        throw new IllegalArgumentException("Admission ticket has no warehouse local date");
      }
      return value;
    }
  }

  private static LogisticsDependencyException unavailable() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, DEPENDENCY_UNAVAILABLE);
  }
}
