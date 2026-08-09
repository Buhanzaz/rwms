package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.SelectWarehouseRequest;
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
 * Owns the two core mutable order details: the selected client and selected warehouse. It retains
 * their receipt replay, version fence, and saved-shipment draft synchronization ordering.
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
        OrderCommandChecksum.sha256(
            UPDATE_ORDER,
            updateChecksumValues(orderId, request));
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
    boolean deliveryChanged =
        order.replaceDeliveryDetails(
            request.deliveryAddress(),
            request.latitude(),
            request.longitude(),
            request.contactPhone(),
            request.comment(),
            request.acceptableDeliveryDates());
    if (!clientChanged && !deliveryChanged) {
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
    if (deliveryChanged) changedFields.add("deliveryDetails");
    changed(order, actor, String.join(",", changedFields));
    store.remember(actor, UPDATE_ORDER, idempotencyKey, checksum, order);
    return new RentalOrderCommandOutcome(
        reads.detail(order, actor, reads.readUnits(order)), false);
  }

  RentalOrderCommandOutcome selectWarehouse(
      OrderActor actor,
      UUID orderId,
      UUID idempotencyKey,
      SelectWarehouseRequest request) {
    String checksum =
        OrderCommandChecksum.sha256(
            SELECT_WAREHOUSE,
            List.of(
                orderId.toString(),
                request.warehouseId().toString(),
                Long.toString(request.expectedVersion())));
    OrderCommandReceipt replay =
        store.replay(actor, SELECT_WAREHOUSE, idempotencyKey, checksum);
    if (replay != null) {
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay.getOrder()), true);
    }
    RentalOrder order = store.lockedOrder(orderId);
    access.requireMutable(actor, order);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    order.requireDraft();
    access.requireWarehouseEdit(actor, request.warehouseId());
    if (Objects.equals(order.getWarehouseId(), request.warehouseId())) {
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
      warehouse = dependencies.readWarehouseIdentity(request.warehouseId());
    } catch (LogisticsDependencyException exception) {
      throw RentalOrderProblems.dependencyProblem(exception);
    }
    if (!request.warehouseId().equals(warehouse.id()) || !warehouse.active()) {
      throw RentalOrderProblems.conflict("WAREHOUSE_UNAVAILABLE", "Выбранный склад недоступен");
    }
    UUID previous = order.getWarehouseId();
    order.selectWarehouse(request.warehouseId());
    store.persist(order);
    audit.append(
        orderId,
        OrderAuditEventType.WAREHOUSE_SELECTED,
        actor,
        "WAREHOUSE",
        request.warehouseId().toString(),
        previous == null ? null : Map.of("warehouseId", previous.toString()),
        Map.of("warehouseId", request.warehouseId().toString()));
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
    values.add(value(request.deliveryAddress()));
    values.add(decimal(request.latitude()));
    values.add(decimal(request.longitude()));
    values.add(
        request.contactPhone() == null
            ? ""
            : OrderClientService.normalizePhone(request.contactPhone()));
    values.add(value(request.comment()));
    if (request.acceptableDeliveryDates() != null) {
      request.acceptableDeliveryDates().stream()
          .sorted()
          .map(java.time.LocalDate::toString)
          .forEach(values::add);
    }
    return values;
  }

  private static String value(String value) {
    return value == null ? "" : value.trim();
  }

  private static String decimal(java.math.BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }
}
