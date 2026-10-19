package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactInput;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns creation of a logistics-local rental order, including its scoped creation idempotency lock,
 * client selection, and immutable audit facts.
 */
@Service
@RequiredArgsConstructor
class RentalOrderCreationService {
  private static final String CREATE_ORDER = "CREATE_ORDER";

  private final RentalOrderCommandStore store;
  private final OrderClientService clientService;
  private final OrderAuditService audit;
  private final RentalOrderReadService reads;

  /** Creates or replays an order under the existing actor-scoped creation advisory lock. */
  RentalOrderCommandOutcome create(
      OrderActor actor, UUID idempotencyKey, CreateOrderRequest request) {
    if (actor == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Order actor, request and Idempotency-Key are required");
    }
    UUID managerId = actor.subjectId();
    String checksum = creationChecksum(request);
    UUID scopedKey = OrderCommandChecksum.scopedKey(idempotencyKey, CREATE_ORDER);
    store.lockCreation(actor, scopedKey);
    RentalOrder replay = store.creationReplay(actor, scopedKey);
    if (replay != null) {
      if (!replay.matchesCreationRequest(checksum)) {
        throw RentalOrderProblems.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой команды");
      }
      return new RentalOrderCommandOutcome(reads.visibleDetail(actor, replay), true);
    }

    OrderClientService.CreatedClient createdClient = null;
    OrderClient client;
    if (request.clientId() != null) {
      client = clientService.required(actor, request.clientId());
    } else {
      createdClient = clientService.createForOrder(actor, idempotencyKey, request.newClient());
      client = createdClient.client();
    }
    String managerDisplayName =
        managerId.equals(actor.subjectId()) ? actor.displayName() : managerId.toString();
    String number = "ORD-%06d".formatted(store.nextOrderNumber());
    RentalOrder order =
        store.persist(
            RentalOrder.create(
                number,
                client,
                managerId,
                managerDisplayName,
                actor.subjectId(),
                actor.displayName(),
                actor.role(),
                request.contactPhone(),
                request.comment(),
                scopedKey,
                checksum));
    if (createdClient != null && !createdClient.replayed()) {
      audit.append(
          order.getId(),
          OrderAuditEventType.CLIENT_CREATED,
          actor,
          "CLIENT",
          client.getId().toString(),
          null,
          Map.of(
              "clientType", client.getClientType().name(),
              "displayName", client.getDisplayName()));
    }
    audit.append(
        order.getId(),
        OrderAuditEventType.CLIENT_SELECTED,
        actor,
        "CLIENT",
        client.getId().toString(),
        null,
        Map.of("displayName", client.getDisplayName()));
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CREATED,
        actor,
        "ORDER",
        order.getId().toString(),
        null,
        Map.of("number", number, "status", order.getStatus().name()));
    return new RentalOrderCommandOutcome(reads.detail(order, actor, List.of()), false);
  }

  private static String creationChecksum(CreateOrderRequest request) {
    List<String> values = new ArrayList<>();
    if (request.clientId() != null) {
      values.add("EXISTING");
      values.add(request.clientId().toString());
    } else {
      values.add("NEW");
      values.add(request.newClient().clientType().name());
      values.add(OrderClientService.normalizeName(request.newClient().displayName()));
      values.add(OrderClientService.normalizePhone(request.newClient().phone()));
      values.add(value(request.newClient().contactPerson()));
      values.add(value(OrderClientService.normalizeEmail(request.newClient().email())));
      values.add(value(request.newClient().comment()));
      values.add(value(request.newClient().source()));
      appendAdditionalContacts(values, request.newClient().additionalContacts());
    }
    values.add(
        request.contactPhone() == null
            ? ""
            : OrderClientService.normalizePhone(request.contactPhone()));
    values.add(value(request.comment()));
    return OrderCommandChecksum.sha256(CREATE_ORDER, values);
  }

  private static String value(String value) {
    return value == null ? "" : value.trim();
  }

  private static void appendAdditionalContacts(
      List<String> values, List<AdditionalContactInput> contacts) {
    if (contacts == null) return;
    contacts.forEach(
        contact -> {
          values.add(value(contact.name()));
          values.add(OrderClientService.normalizePhone(contact.phone()));
        });
  }

}
