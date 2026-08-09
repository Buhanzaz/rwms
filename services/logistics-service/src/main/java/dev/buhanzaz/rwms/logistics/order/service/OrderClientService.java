package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateClientRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPageResponse;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.PhoneNumberNormalizer;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import jakarta.persistence.criteria.Predicate;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns rental-order client data and its validation within the logistics database.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderClientService {
  private static final String CREATE_CLIENT = "CREATE_CLIENT";
  private static final String CREATE_ORDER_CLIENT = "CREATE_ORDER_CLIENT";

  private final OrderClientRepository clients;
  private final RentalOrderRepository orders;
  private final RentalOrderResponseMapper mapper;
  private final LogisticsTransactionLock transactionLock;
  private final RentalOrderReadService orderReads;

  public ClientPageResponse search(
      OrderActor actor, ClientType type, String search, int page, int size) {
    if (actor == null) throw new IllegalArgumentException("Client actor is required");
    requirePage(page, size);
    String normalizedSearch = normalizeName(search == null ? "" : search);
    Specification<OrderClient> specification =
        (root, query, builder) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(visibility(root, query, builder, actor));
          if (type != null) predicates.add(builder.equal(root.get("clientType"), type));
          if (!normalizedSearch.isEmpty()) {
            predicates.add(
                builder.or(
                    builder.like(
                        root.get("normalizedName"),
                        "%" + escapeLike(normalizedSearch) + "%",
                        '\\'),
                    builder.like(
                        root.get("normalizedPhone"),
                        "%" + escapeLike(normalizedSearch) + "%",
                        '\\'),
                    builder.like(
                        root.get("normalizedEmail"),
                        "%" + escapeLike(normalizedSearch) + "%",
                        '\\'),
                    builder.like(
                        builder.lower(root.get("contactPerson")),
                        "%" + escapeLike(normalizedSearch) + "%",
                        '\\'),
                    builder.like(
                        builder.lower(root.get("source")),
                        "%" + escapeLike(normalizedSearch) + "%",
                        '\\')));
          }
          return builder.and(predicates.toArray(Predicate[]::new));
        };
    Sort order =
        normalizedSearch.isEmpty()
            ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
            : Sort.by(Sort.Order.asc("normalizedName"), Sort.Order.asc("id"));
    Page<OrderClient> result = clients.findAll(specification, PageRequest.of(page, size, order));
    return new ClientPageResponse(
        result.getContent().stream().map(mapper::toClientResponse).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  /** Returns one visible client without disclosing an inaccessible client identity. */
  public ClientResponse get(OrderActor actor, UUID clientId) {
    return mapper.toClientResponse(requiredVisible(actor, clientId));
  }

  /** Lists only orders visible to the actor for one already-authorized client. */
  public OrderPageResponse orders(
      OrderActor actor,
      UUID clientId,
      int page,
      int size,
      String sort,
      String direction,
      List<RentalOrderStatus> statuses,
      List<UUID> warehouseIds,
      OffsetDateTime createdFrom,
      OffsetDateTime createdTo) {
    requiredVisible(actor, clientId);
    return orderReads.listForClient(
        actor,
        clientId,
        page,
        size,
        sort,
        direction,
        statuses,
        warehouseIds,
        createdFrom,
        createdTo);
  }

  @Transactional
  public CreateResult create(
      OrderActor actor, UUID idempotencyKey, CreateClientRequest request) {
    CreatedClient result =
        create(
            actor,
            idempotencyKey,
            CREATE_CLIENT,
            request.clientType(),
            request.displayName(),
            request.phone(),
            request.contactPerson(),
            request.email(),
            request.comment(),
            request.source());
    return new CreateResult(mapper.toClientResponse(result.client()), result.replayed());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public CreatedClient createForOrder(
      OrderActor actor, UUID idempotencyKey, NewClientInput request) {
    return create(
        actor,
        idempotencyKey,
        CREATE_ORDER_CLIENT,
        request.clientType(),
        request.displayName(),
        request.phone(),
        request.contactPerson(),
        request.email(),
        request.comment(),
        request.source());
  }

  /** Resolves an existing client under the same no-disclosure policy as the client detail API. */
  public OrderClient required(OrderActor actor, UUID clientId) {
    return requiredVisible(actor, clientId);
  }

  private CreatedClient create(
      OrderActor actor,
      UUID idempotencyKey,
      String scope,
      ClientType type,
      String requestedDisplayName,
      String requestedPhone,
      String requestedContactPerson,
      String requestedEmail,
      String requestedComment,
      String requestedSource) {
    if (actor == null || idempotencyKey == null || type == null) {
      throw new IllegalArgumentException("Client actor, type and Idempotency-Key are required");
    }
    String displayName = normalizeDisplayName(requestedDisplayName);
    String normalizedName = normalizeName(displayName);
    String normalizedPhone = normalizePhone(requestedPhone);
    String contactPerson = normalizeOptionalText(requestedContactPerson, 255, "contactPerson");
    if (type == ClientType.LEGAL_ENTITY && contactPerson == null) {
      throw new IllegalArgumentException("contactPerson is required for this client type");
    }
    String normalizedEmail = normalizeEmail(requestedEmail);
    String comment = normalizeOptionalText(requestedComment, 2_000, "comment");
    String source = normalizeOptionalText(requestedSource, 255, "source");
    String checksum =
        OrderCommandChecksum.sha256(
            scope,
            List.of(
                type.name(),
                normalizedName,
                normalizedPhone,
                contactPerson == null ? "" : contactPerson,
                normalizedEmail == null ? "" : normalizedEmail,
                comment == null ? "" : comment,
                source == null ? "" : source));
    UUID scopedKey = OrderCommandChecksum.scopedKey(idempotencyKey, scope);
    transactionLock.acquire(
        "order-client:idempotency:" + actor.subjectId() + ":" + scopedKey);
    OrderClient replay =
        clients
            .findByCreatedBySubjectIdAndCreationIdempotencyKey(actor.subjectId(), scopedKey)
            .orElse(null);
    if (replay != null) {
      if (!replay.matchesCreationRequest(checksum)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже использован для другой команды");
      }
      return new CreatedClient(replay, true);
    }

    transactionLock.acquire("order-client:phone:" + type + ":" + normalizedPhone);
    OrderClient duplicate =
        clients.findByClientTypeAndNormalizedPhone(type, normalizedPhone).orElse(null);
    if (duplicate != null) {
      if (!isVisible(actor, duplicate)) {
        throw conflict(
            "CLIENT_ALREADY_EXISTS",
            "Клиент с указанными реквизитами уже существует");
      }
      return new CreatedClient(duplicate, true);
    }
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                type,
                displayName,
                normalizedName,
                normalizedPhone,
                normalizedPhone,
                normalizedEmail,
                normalizedEmail,
                contactPerson,
                actor.subjectId(),
                actor.displayName(),
                comment,
                source,
                actor.subjectId(),
                scopedKey,
                checksum));
    return new CreatedClient(client, false);
  }

  public static String normalizeName(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .replaceAll("[\\p{Z}\\s]+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  public static String normalizePhone(String value) {
    return PhoneNumberNormalizer.normalizeRequired(value);
  }

  public static String normalizeEmail(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 320
        || normalized.indexOf('@') < 1
        || normalized.endsWith("@")) {
      throw new IllegalArgumentException("email is invalid");
    }
    return normalized;
  }

  private static String normalizeDisplayName(String value) {
    String normalized =
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .replaceAll("[\\p{Z}\\s]+", " ")
            .trim();
    if (normalized.isEmpty() || normalized.length() > 512) {
      throw new IllegalArgumentException("displayName is invalid");
    }
    return normalized;
  }

  private OrderClient requiredVisible(OrderActor actor, UUID clientId) {
    if (actor == null || clientId == null) {
      throw new IllegalArgumentException("Client actor and identity are required");
    }
    OrderClient client = clients.findById(clientId).orElseThrow(OrderClientService::notFound);
    if (isVisible(actor, client)) {
      return client;
    }
    throw notFound();
  }

  private boolean isVisible(OrderActor actor, OrderClient client) {
    return actor.globalAdministrator()
        || actor.subjectId().equals(client.getResponsibleManagerId())
        || visibleThroughWarehouseOrder(actor, client.getId());
  }

  private boolean visibleThroughWarehouseOrder(OrderActor actor, UUID clientId) {
    if (!actor.localAdministrator() || actor.readableWarehouses().isEmpty()) return false;
    return orders.count(
            (root, query, builder) ->
                builder.and(
                    builder.equal(root.get("client").get("id"), clientId),
                    root.get("warehouseId").in(actor.readableWarehouses())))
        > 0;
  }

  private static Predicate visibility(
      jakarta.persistence.criteria.Root<OrderClient> root,
      jakarta.persistence.criteria.CriteriaQuery<?> query,
      jakarta.persistence.criteria.CriteriaBuilder builder,
      OrderActor actor) {
    if (actor.globalAdministrator()) return builder.conjunction();
    Predicate own = builder.equal(root.get("responsibleManagerId"), actor.subjectId());
    if (!actor.localAdministrator() || actor.readableWarehouses().isEmpty()) return own;
    var subquery = query.subquery(Integer.class);
    var order = subquery.from(RentalOrder.class);
    subquery.select(builder.literal(1));
    subquery.where(
        builder.equal(order.get("client"), root),
        order.get("warehouseId").in(actor.readableWarehouses()));
    return builder.or(own, builder.exists(subquery));
  }

  private static String normalizeOptionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\p{Z}\\s]+", " ")
            .trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static void requirePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid client page request");
    }
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Клиент не найден");
  }

  /** Result of a standalone client create command and its replay status. */
  public record CreateResult(ClientResponse response, boolean replayed) {}

  /** Persisted client selected or created inside an enclosing order transaction. */
  public record CreatedClient(OrderClient client, boolean replayed) {}
}
