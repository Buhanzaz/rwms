package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.UpdateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns mutable manager-entered order details: client, primary contact, comment and selected
 * warehouse. Client delivery location, additional contacts and receiving preference arrive only
 * from a normal presentation confirmation. This owner retains command receipt replay, version
 * fencing, and saved-shipment draft synchronization ordering.
 */
@Service
@RequiredArgsConstructor
class RentalOrderLifecycleService {
  private static final String UPDATE_ORDER = "UPDATE_ORDER";
  private static final String SELECT_WAREHOUSE = "SELECT_WAREHOUSE";

  private final RentalOrderCommandStore store;
  private final OrderClientService clientService;
  private final OrderAuditService audit;
  private final OrderAuthorizer access;
  private final LogisticsDependencyGateway dependencies;
  private final RentalOrderReadService reads;
  private final RentalOrderEditabilityService editability;

  RentalOrderCommandOutcome update(
      OrderActor actor, UUID orderId, UUID idempotencyKey, UpdateOrderRequest request) {
    String checksum =
        OrderCommandChecksum.sha256(UPDATE_ORDER, updateChecksumValues(orderId, request));
    OrderCommandReceipt replay = store.replay(actor, UPDATE_ORDER, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }

    RentalOrder order = store.lockedOrder(orderId);
    editability.requireEditable(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    OrderClient nextClient = clientService.required(actor, request.clientId());
    OrderClient previousClient = order.getClient();
    boolean clientChanged = order.changeClient(nextClient);
    boolean managerDetailsChanged =
        order.replaceManagerOrderDetails(request.contactPhone(), request.comment());
    if (!clientChanged && !managerDetailsChanged) {
      store.remember(actor, UPDATE_ORDER, idempotencyKey, checksum, order);
      return new RentalOrderCommandOutcome(
          reads.detail(order, actor, reads.readUnits(order)), false);
    }

    store.persist(order);
    editability.synchronizeSavedShipmentDraft(order, actor, reads.readUnits(order));
    List<String> changedFields = new ArrayList<>();
    if (clientChanged) {
      audit.append(
          orderId,
          OrderAuditEventType.CLIENT_SELECTED,
          actor,
          "CLIENT",
          nextClient.getId().toString(),
          Map.of("displayName", previousClient.getDisplayName()),
          Map.of("displayName", nextClient.getDisplayName()));
      changedFields.add("clientId");
    }
    if (managerDetailsChanged) changedFields.add("managerOrderDetails");
    changed(order, actor, String.join(",", changedFields));
    store.remember(actor, UPDATE_ORDER, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, reads.readUnits(order)), false);
  }

  RentalOrderCommandOutcome selectWarehouseForPresentation(
      OrderActor actor, UUID orderId, UUID idempotencyKey, long expectedVersion, UUID warehouseId) {
    String checksum =
        OrderCommandChecksum.sha256(
            SELECT_WAREHOUSE,
            List.of(orderId.toString(), warehouseId.toString(), Long.toString(expectedVersion)));
    OrderCommandReceipt replay = store.replay(actor, SELECT_WAREHOUSE, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }
    RentalOrder order = store.lockedOrder(orderId);
    access.requireMutable(actor, order);
    RentalOrderProblems.requireVersion(order, expectedVersion);
    order.requireDraft();
    access.requireWarehouseEdit(actor, warehouseId);
    if (Objects.equals(order.getWarehouseId(), warehouseId)) {
      store.remember(actor, SELECT_WAREHOUSE, idempotencyKey, checksum, order);
      return new RentalOrderCommandOutcome(
          reads.detail(order, actor, reads.readUnits(order)), false);
    }
    List<LogisticsDependencyGateway.OrderUnitReservation> units = reads.readUnits(order);
    if (!units.isEmpty()) {
      throw RentalOrderProblems.conflict(
          "ORDER_WAREHOUSE_LOCKED", "Склад нельзя изменить после добавления первой бытовки");
    }
    LogisticsDependencyGateway.WarehouseIdentity warehouse;
    try {
      warehouse = dependencies.readWarehouseIdentity(warehouseId);
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    if (!warehouseId.equals(warehouse.id()) || !warehouse.active()) {
      throw RentalOrderProblems.conflict("WAREHOUSE_UNAVAILABLE", "Выбранный склад недоступен");
    }
    UUID previous = order.getWarehouseId();
    order.selectWarehouse(warehouseId);
    store.persist(order);
    audit.append(
        orderId,
        OrderAuditEventType.WAREHOUSE_SELECTED,
        actor,
        "WAREHOUSE",
        warehouseId.toString(),
        previous == null ? null : Map.of("warehouseId", previous.toString()),
        Map.of("warehouseId", warehouseId.toString()));
    changed(order, actor, "warehouseId");
    store.remember(actor, SELECT_WAREHOUSE, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(reads.detail(order, actor, units), false);
  }

  private void changed(RentalOrder order, OrderActor actor, String field) {
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "ORDER",
        order.getId().toString(),
        null,
        Map.of("changedField", field, "version", order.getVersion()));
  }

  private static List<String> updateChecksumValues(UUID orderId, UpdateOrderRequest request) {
    List<String> values = new ArrayList<>();
    values.add(orderId.toString());
    values.add(request.clientId().toString());
    values.add(Long.toString(request.expectedVersion()));
    values.add(
        request.contactPhone() == null
            ? ""
            : OrderClientService.normalizePhone(request.contactPhone()));
    values.add(value(request.comment()));
    return values;
  }

  private static String value(String value) {
    return value == null ? "" : value.trim();
  }

}
