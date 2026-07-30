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
import java.util.regex.Pattern;
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
  private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

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
            request.email());
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
        request.email());
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
      String requestedDisplayName,
      String requestedPhone,
      String requestedEmail) {
    if (actor == null || idempotencyKey == null || type == null) {
      throw new IllegalArgumentException("Client actor, type and Idempotency-Key are required");
    }
    String displayName = normalizeDisplayName(requestedDisplayName);
    String normalizedName = normalizeName(displayName);
    String normalizedPhone = normalizePhone(requestedPhone);
    String normalizedEmail = normalizeEmail(requestedEmail);
    String checksum =
        OrderCommandChecksum.sha256(
            scope,
            List.of(
                type.name(),
                normalizedName,
                normalizedPhone,
                normalizedEmail == null ? "" : normalizedEmail));
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

    clients.acquireTransactionLock("order-client:phone:" + type + ":" + normalizedPhone);
    OrderClient duplicate =
        clients.findByClientTypeAndNormalizedPhone(type, normalizedPhone).orElse(null);
    if (duplicate != null) {
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
    String raw =
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    boolean explicitPlus = raw.startsWith("+");
    String digits = raw.replaceAll("[^0-9]", "");
    if (!explicitPlus && digits.length() == 11 && digits.startsWith("8")) {
      digits = "7" + digits.substring(1);
    }
    String normalized = "+" + digits;
    if (!E164.matcher(normalized).matches()) {
      throw new IllegalArgumentException("phone is invalid");
    }
    return normalized;
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
