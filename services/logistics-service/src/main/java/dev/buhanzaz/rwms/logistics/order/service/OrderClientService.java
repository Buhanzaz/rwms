package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientPageResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateClientRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import jakarta.persistence.criteria.Predicate;
import java.text.Normalizer;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderClientService {
  private static final String CREATE_CLIENT = "CREATE_CLIENT";
  private static final String CREATE_ORDER_CLIENT = "CREATE_ORDER_CLIENT";

  private final OrderClientRepository clients;
  private final RentalOrderResponseMapper mapper;

  public ClientPageResponse search(
      ClientType type, String search, int page, int size) {
    requirePage(page, size);
    String normalizedSearch = normalizeName(search == null ? "" : search);
    Specification<OrderClient> specification =
        (root, query, builder) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (type != null) predicates.add(builder.equal(root.get("clientType"), type));
          if (!normalizedSearch.isEmpty()) {
            predicates.add(
                builder.like(
                    root.get("normalizedName"), "%" + escapeLike(normalizedSearch) + "%", '\\'));
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

  @Transactional
  public CreateResult create(
      OrderActor actor, UUID idempotencyKey, CreateClientRequest request) {
    CreatedClient result =
        create(
            actor,
            idempotencyKey,
            CREATE_CLIENT,
            request.clientType(),
            request.displayName());
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
        request.displayName());
  }

  public OrderClient required(UUID clientId) {
    return clients
        .findById(clientId)
        .orElseThrow(
            () ->
                new OrderProblemException(
                    HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Клиент не найден"));
  }

  private CreatedClient create(
      OrderActor actor,
      UUID idempotencyKey,
      String scope,
      ClientType type,
      String requestedDisplayName) {
    if (actor == null || idempotencyKey == null || type == null) {
      throw new IllegalArgumentException("Client actor, type and Idempotency-Key are required");
    }
    String displayName = normalizeDisplayName(requestedDisplayName);
    String normalizedName = normalizeName(displayName);
    String checksum =
        OrderCommandChecksum.sha256(
            scope, List.of(type.name(), normalizedName));
    UUID scopedKey = OrderCommandChecksum.scopedKey(idempotencyKey, scope);
    clients.acquireTransactionLock(
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

    clients.acquireTransactionLock("order-client:name:" + type + ":" + normalizedName);
    OrderClient duplicate =
        clients.findByClientTypeAndNormalizedName(type, normalizedName).orElse(null);
    if (duplicate != null) {
      throw conflict(
          "CLIENT_ALREADY_EXISTS",
          "Клиент с таким типом и названием уже существует");
    }
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                type,
                displayName,
                normalizedName,
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

  public record CreateResult(ClientResponse response, boolean replayed) {}

  public record CreatedClient(OrderClient client, boolean replayed) {}
}
