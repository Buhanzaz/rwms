package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.InternalWarehouseExistenceResponse;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventType;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxWriter;
import dev.buhanzaz.rwms.warehouse.mapper.WarehouseResponseMapper;
import dev.buhanzaz.rwms.warehouse.repository.WarehouseRepository;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WarehouseService {
  private static final Comparator<Warehouse> ORDER =
      Comparator.<Warehouse, Integer>comparing(
              Warehouse::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Warehouse::getCode);
  private final WarehouseRepository warehouses;
  private final WarehouseResponseMapper responses;
  private final WarehouseOutboxWriter outbox;
  private final WarehouseIdempotencyStore idempotency;
  private final ObjectMapper objectMapper;

  public WarehouseService(
      WarehouseRepository warehouses,
      WarehouseResponseMapper responses,
      WarehouseOutboxWriter outbox,
      WarehouseIdempotencyStore idempotency,
      ObjectMapper objectMapper) {
    this.warehouses = warehouses;
    this.responses = responses;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<WarehouseResponse> list(boolean includeInactive) {
    List<Warehouse> source = includeInactive ? warehouses.findAll() : warehouses.findAllByActiveTrue();
    return source.stream().sorted(ORDER).map(responses::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public WarehouseResponse get(UUID id, boolean includeInactive) {
    Warehouse warehouse = require(id);
    if (!includeInactive && !warehouse.isActive()) throw new WarehouseNotFoundException();
    return responses.toResponse(warehouse);
  }

  @Transactional
  public CreateResult create(
      UUID subjectId, UUID idempotencyKey, CreateWarehouseRequest request) {
    Warehouse candidate = newWarehouse(request);
    String fingerprint = fingerprint(candidate);
    Optional<WarehouseResponse> replayed = idempotency.replay(subjectId, idempotencyKey, fingerprint);
    if (replayed.isPresent()) return new CreateResult(replayed.get(), true);
    if (warehouses.existsByCode(candidate.getCode())) {
      throw new WarehouseConflictException("Warehouse code is already used and cannot be reused");
    }
    Warehouse persisted = warehouses.saveAndFlush(candidate);
    outbox.append(persisted, WarehouseEventType.CREATED);
    WarehouseResponse response = responses.toResponse(persisted);
    idempotency.storeSuccess(subjectId, idempotencyKey, fingerprint, response);
    return new CreateResult(response, false);
  }

  @Transactional
  public WarehouseResponse replace(UUID id, ReplaceWarehouseRequest request) {
    Warehouse warehouse = require(id);
    assertExpectedVersion(warehouse, request.expectedVersion());
    String requestedCode = Warehouse.canonicalCode(request.code());
    if (warehouses.existsByCodeAndIdNot(requestedCode, warehouse.getId())) {
      throw new WarehouseConflictException("Warehouse code is already used and cannot be reused");
    }
    Warehouse.Mutation mutation =
        warehouse.replace(
            request.code(),
            request.name(),
            request.city(),
            request.address(),
            zone(request.timeZone()),
            request.active(),
            request.sortOrder());
    if (mutation == Warehouse.Mutation.NONE) return responses.toResponse(warehouse);
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    outbox.append(
        persisted,
        mutation == Warehouse.Mutation.DEACTIVATED
            ? WarehouseEventType.DEACTIVATED
            : WarehouseEventType.CHANGED);
    return responses.toResponse(persisted);
  }

  @Transactional
  public void deactivate(UUID id, long expectedVersion) {
    Warehouse warehouse = require(id);
    assertExpectedVersion(warehouse, expectedVersion);
    if (!warehouse.deactivate()) return;
    Warehouse persisted = warehouses.saveAndFlush(warehouse);
    outbox.append(persisted, WarehouseEventType.DEACTIVATED);
  }

  @Transactional(readOnly = true)
  public InternalWarehouseExistenceResponse existence(UUID id) {
    Warehouse warehouse = require(id);
    return new InternalWarehouseExistenceResponse(
        warehouse.getId(), warehouse.getVersion(), warehouse.isActive());
  }

  private Warehouse newWarehouse(CreateWarehouseRequest request) {
    return Warehouse.create(
        request.code(),
        request.name(),
        request.city(),
        request.address(),
        zone(request.timeZone()),
        request.sortOrder());
  }

  private Warehouse require(UUID id) {
    return warehouses.findById(id).orElseThrow(WarehouseNotFoundException::new);
  }

  private static void assertExpectedVersion(Warehouse warehouse, long expectedVersion) {
    if (warehouse.getVersion() != expectedVersion) {
      throw new WarehouseConflictException("Warehouse has been changed by another request");
    }
  }

  private static ZoneId zone(String value) {
    return ZoneId.of(value == null ? "" : value.trim());
  }

  private String fingerprint(Warehouse warehouse) {
    try {
      return WarehouseChecksum.sha256(
          objectMapper
              .writeValueAsBytes(
                  new CreateFingerprint(
                      warehouse.getCode(),
                      warehouse.getName(),
                      warehouse.getCity(),
                      warehouse.getAddress(),
                      warehouse.getTimeZone(),
                      warehouse.getSortOrder())));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Warehouse create command cannot be fingerprinted", exception);
    }
  }

  public record CreateResult(WarehouseResponse response, boolean replayed) {}

  private record CreateFingerprint(
      String code, String name, String city, String address, String timeZone, Integer sortOrder) {}
}
