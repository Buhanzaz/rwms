package dev.buhanzaz.rwms.logistics.customer.claims;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinProblemRepository;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager-side lifecycle foundation for immutable customer cabin-problem evidence. It performs
 * only local database work in its write transactions; any remediation execution remains owned by
 * its future command workflow.
 */
@Service
@RequiredArgsConstructor
public class CustomerCabinProblemClaimService {
  private static final String RENTAL_MANAGER_ROLE = "RENTAL_MANAGER";

  private final CustomerCabinProblemRepository problems;
  private final CustomerCabinProblemActionRepository actions;
  private final RentalOrderRepository orders;
  private final OrderAuthorizer access;
  private final Clock clock;

  /** Returns one visible claim and its immutable lifecycle history. */
  @Transactional(readOnly = true)
  public CustomerCabinProblemClaimView get(OrderActor actor, UUID problemId) {
    ScopedProblem scoped = requiredVisibleProblem(actor, problemId);
    return view(scoped.problem(), scoped.order());
  }

  /**
   * Returns the claim queue under the claim-specific visibility policy: rental managers receive
   * the full queue, while operational actors remain constrained by visible orders and warehouse
   * grants.
   */
  @Transactional(readOnly = true)
  public List<CustomerCabinProblemClaimView> list(
      OrderActor actor, CustomerCabinProblemStatus status) {
    Objects.requireNonNull(actor, "actor");
    List<CustomerCabinProblem> candidates =
        status == null
            ? problems.findAllByOrderByResolutionDeadlineAscIdAsc()
            : problems.findAllByStatusOrderByResolutionDeadlineAscIdAsc(status);
    return candidates.stream()
        .map(problem -> visibleProblem(actor, problem))
        .flatMap(Optional::stream)
        .map(scoped -> view(scoped.problem(), scoped.order()))
        .toList();
  }

  /** Lists only claims attached to one visible order. */
  @Transactional(readOnly = true)
  public List<CustomerCabinProblemClaimView> listForOrder(OrderActor actor, UUID orderId) {
    RentalOrder order = requiredVisibleClaimOrder(actor, orderId);
    return problems
        .findAllByOrderIdOrderByReportedAtAscIdAsc(orderId)
        .stream()
        .map(problem -> view(problem, order))
        .toList();
  }

  /** Moves one visible claim from OPEN to IN_PROGRESS under an explicit optimistic fence. */
  @Transactional
  public CustomerCabinProblemClaimView startProgress(
      OrderActor actor, UUID problemId, long expectedVersion) {
    ScopedProblem scoped = requiredMutableProblem(actor, problemId);
    CustomerCabinProblem problem = scoped.problem();
    CustomerCabinProblemStatus previous = transition(() -> problem.startProgress(expectedVersion));
    persist(problem);
    actions.save(
        CustomerCabinProblemAction.statusTransition(
            problem.getId(), previous, actor.subjectId(), now()));
    return view(problem, scoped.order());
  }

  /** Resolves one in-progress visible claim and records the selected remedy in the immutable ledger. */
  @Transactional
  public CustomerCabinProblemClaimView resolve(
      OrderActor actor,
      UUID problemId,
      long expectedVersion,
      CustomerCabinProblemResolutionKind resolutionKind,
      String resolutionComment) {
    ScopedProblem scoped = requiredMutableProblem(actor, problemId);
    CustomerCabinProblem problem = scoped.problem();
    OffsetDateTime resolvedAt = now();
    CustomerCabinProblemStatus previous =
        transition(
            () ->
                problem.resolve(
                    expectedVersion,
                    resolutionKind,
                    actor.subjectId(),
                    resolutionComment,
                    resolvedAt));
    persist(problem);
    actions.save(
        CustomerCabinProblemAction.resolutionDecision(
            problem.getId(),
            previous,
            resolutionKind,
            actor.subjectId(),
            problem.getResolutionComment(),
            resolvedAt));
    return view(problem, scoped.order());
  }

  private ScopedProblem requiredVisibleProblem(OrderActor actor, UUID problemId) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(problemId, "problemId");
    CustomerCabinProblem problem =
        problems
            .findById(problemId)
            .orElseThrow(CustomerCabinProblemClaimService::problemNotFound);
    return new ScopedProblem(problem, requiredVisibleClaimOrder(actor, problem.getOrderId()));
  }

  private ScopedProblem requiredMutableProblem(OrderActor actor, UUID problemId) {
    ScopedProblem scoped = requiredVisibleProblem(actor, problemId);
    if (RENTAL_MANAGER_ROLE.equals(actor.role())) {
      if (!actor.writeScope()) {
        throw new AccessDeniedException("Required rental manager write scope is missing");
      }
      return scoped;
    }
    UUID warehouseId = scoped.order().getWarehouseId();
    if (warehouseId == null) {
      throw conflict(
          "CUSTOMER_CABIN_PROBLEM_ORDER_WAREHOUSE_REQUIRED",
          "Заказ по обращению должен быть закреплён за складом");
    }
    access.requireWarehouseEdit(actor, warehouseId);
    return scoped;
  }

  private RentalOrder requiredVisibleClaimOrder(OrderActor actor, UUID orderId) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(orderId, "orderId");
    RentalOrder order =
        orders
            .findWithClientById(orderId)
            .orElseThrow(CustomerCabinProblemClaimService::problemNotFound);
    if (!isClaimVisible(actor, order)) {
      throw problemNotFound();
    }
    return order;
  }

  private Optional<ScopedProblem> visibleProblem(OrderActor actor, CustomerCabinProblem problem) {
    return orders
        .findWithClientById(problem.getOrderId())
        .filter(order -> isClaimVisible(actor, order))
        .map(order -> new ScopedProblem(problem, order));
  }

  private boolean isClaimVisible(OrderActor actor, RentalOrder order) {
    return RENTAL_MANAGER_ROLE.equals(actor.role())
        ? actor.rentalAccess()
        : access.isVisible(actor, order);
  }

  private CustomerCabinProblemClaimView view(CustomerCabinProblem problem, RentalOrder order) {
    OrderClient client = Objects.requireNonNull(order.getClient(), "order client");
    return new CustomerCabinProblemClaimView(
        problem.getId(),
        problem.getOrderId(),
        problem.getWarehouseId(),
        problem.getBookingId(),
        problem.getCabinUnitId(),
        order.getOrderNumber(),
        problem.getCategory(),
        problem.getPhase(),
        problem.getDescription(),
        problem.getReportedAt(),
        client.getDisplayName(),
        client.getClientType(),
        client.getPhone(),
        order.getContactPhone(),
        order.getDeliveryAddress(),
        problem.getStatus(),
        problem.getResolutionDeadline(),
        problem.getVersion(),
        problem.getResolutionKind(),
        problem.getResolvedBySubjectId(),
        problem.getResolutionComment(),
        problem.getResolvedAt(),
        actions
            .findAllByProblemIdOrderByOccurredAtDescIdDesc(problem.getId())
            .stream()
            .sorted(Comparator.comparing(CustomerCabinProblemAction::getOccurredAt))
            .map(
                action ->
                    new CustomerCabinProblemActionView(
                        action.getId(),
                        action.getActionKind(),
                        action.getPreviousStatus(),
                        action.getLifecycleStatus(),
                        action.getResolutionKind(),
                        action.getActorSubjectId(),
                        action.getCommentText(),
                        action.getOccurredAt()))
            .toList());
  }

  private void persist(CustomerCabinProblem problem) {
    try {
      problems.saveAndFlush(problem);
    } catch (OptimisticLockingFailureException exception) {
      throw conflict(
          "CUSTOMER_CABIN_PROBLEM_VERSION_CONFLICT",
          "Обращение уже изменилось; обновите данные и повторите действие");
    }
  }

  private static CustomerCabinProblemStatus transition(
      Supplier<CustomerCabinProblemStatus> transition) {
    try {
      return transition.get();
    } catch (IllegalArgumentException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("version")) {
        throw conflict(
            "CUSTOMER_CABIN_PROBLEM_VERSION_CONFLICT",
            "Обращение уже изменилось; обновите данные и повторите действие");
      }
      throw exception;
    } catch (IllegalStateException exception) {
      throw conflict(
          "CUSTOMER_CABIN_PROBLEM_STATE_CONFLICT",
          "Переход состояния обращения больше недоступен");
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderProblemException problemNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_CABIN_PROBLEM_NOT_FOUND", "Обращение не найдено");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private record ScopedProblem(CustomerCabinProblem problem, RentalOrder order) {}
}
