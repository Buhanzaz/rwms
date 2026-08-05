package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Remote-call-free transaction boundary for logistics warehouse admission and local dates. */
@Service
public class LogisticsWarehouseLifecycle {
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsWarehouseLifecycleStore store;

  public LogisticsWarehouseLifecycle(
      LogisticsDependencyGateway dependencies, LogisticsWarehouseLifecycleStore store) {
    this.dependencies = dependencies;
    this.store = store;
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
        store.hasLiveDocumentReplay(subjectId, operationName, idempotencyKey));
  }

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
        store.hasEquipmentMovementReplay(actorSubjectId, idempotencyKey));
  }

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
        store.hasDriverTaskReplay(actorSubjectId, idempotencyKey));
  }

  private AdmissionTicket prepare(
      UUID ownerId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements,
      boolean replayed) {
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
    if (!dependencies.productionReady() || replayed) {
      return AdmissionTicket.bypassed(operationId, orderedRequirements, occurredAt);
    }

    boolean alreadyAdmitted = store.reserve(operationId, orderedRequirements);
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
      } catch (RuntimeException failure) {
        store.cancelReserved(operationId, orderedRequirements);
        throw failure;
      }
    }

    Map<UUID, LocalDate> localDates = new LinkedHashMap<>();
    try {
      for (AdmissionRequirement requirement : orderedRequirements) {
        LogisticsDependencyGateway.WarehouseTimeZone timeZone =
            dependencies.warehouseTimeZoneAt(requirement.warehouseId(), occurredAt);
        localDates.put(
            requirement.warehouseId(),
            occurredAt.toInstant().atZone(ZoneId.of(timeZone.timeZone())).toLocalDate());
      }
    } catch (RuntimeException failure) {
      // An ADMITTED intent deliberately remains until its short expiry. Readiness must not race a
      // request whose timezone outcome is unknown.
      throw failure;
    }
    return new AdmissionTicket(
        operationId, orderedRequirements, occurredAt, Map.copyOf(localDates), false);
  }

  public void consume(AdmissionTicket ticket) {
    if (ticket == null) {
      throw new IllegalArgumentException("Warehouse admission ticket is required");
    }
    store.consume(ticket.operationId(), ticket.requirements(), ticket.bypassed());
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

  public AdmissionTicket disabledTicket(
      UUID ownerId,
      String operationName,
      UUID idempotencyKey,
      List<AdmissionRequirement> requirements) {
    if (dependencies.productionReady()) {
      throw new IllegalStateException(
          "A production logistics command requires a warehouse admission ticket");
    }
    return prepare(ownerId, operationName, idempotencyKey, requirements, true);
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
    return AdmissionTicket.bypassed(operationId, ordered, occurredAt);
  }

  public record AdmissionTicket(
      UUID operationId,
      List<AdmissionRequirement> requirements,
      OffsetDateTime occurredAt,
      Map<UUID, LocalDate> localDates,
      boolean bypassed) {
    public AdmissionTicket {
      if (operationId == null
          || requirements == null
          || requirements.isEmpty()
          || occurredAt == null
          || localDates == null) {
        throw new IllegalArgumentException("Warehouse admission ticket is invalid");
      }
      requirements = List.copyOf(requirements);
      localDates = Map.copyOf(localDates);
    }

    static AdmissionTicket bypassed(
        UUID operationId, List<AdmissionRequirement> requirements, OffsetDateTime occurredAt) {
      Map<UUID, LocalDate> fallback = new LinkedHashMap<>();
      for (AdmissionRequirement requirement : requirements) {
        fallback.put(requirement.warehouseId(), occurredAt.toLocalDate());
      }
      return new AdmissionTicket(
          operationId, requirements, occurredAt, Map.copyOf(fallback), true);
    }

    public LocalDate localDate(UUID warehouseId) {
      LocalDate value = localDates.get(warehouseId);
      if (value == null) {
        throw new IllegalArgumentException("Admission ticket has no warehouse local date");
      }
      return value;
    }
  }
}
