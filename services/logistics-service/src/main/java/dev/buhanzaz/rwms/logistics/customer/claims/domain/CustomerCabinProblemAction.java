package dev.buhanzaz.rwms.logistics.customer.claims.domain;

import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemActionKind;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemResolutionKind;
import dev.buhanzaz.rwms.logistics.customer.claims.CustomerCabinProblemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.proxy.HibernateProxy;

/**
 * Append-only manager action ledger for a customer cabin problem. The database independently
 * rejects updates and deletes so the ledger cannot be rewritten after the fact.
 */
@Entity
@Table(name = "customer_cabin_problem_action")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerCabinProblemAction {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "problem_id", nullable = false, updatable = false)
  private UUID problemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_kind", nullable = false, length = 32, updatable = false)
  private CustomerCabinProblemActionKind actionKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_status", nullable = false, length = 32, updatable = false)
  private CustomerCabinProblemStatus previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "lifecycle_status", nullable = false, length = 32, updatable = false)
  private CustomerCabinProblemStatus lifecycleStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "resolution_kind", length = 32, updatable = false)
  private CustomerCabinProblemResolutionKind resolutionKind;

  @Column(name = "actor_subject_id", nullable = false, updatable = false)
  private UUID actorSubjectId;

  @Column(name = "comment_text", length = 2_000, updatable = false)
  private String commentText;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private OffsetDateTime occurredAt;

  /** Captures the only non-terminal transition, from open to active investigation. */
  public static CustomerCabinProblemAction statusTransition(
      UUID problemId,
      CustomerCabinProblemStatus previousStatus,
      UUID actorSubjectId,
      OffsetDateTime occurredAt) {
    if (previousStatus != CustomerCabinProblemStatus.OPEN) {
      throw new IllegalArgumentException("Customer cabin problem status transition is invalid");
    }
    CustomerCabinProblemAction action = base(problemId, actorSubjectId, occurredAt);
    action.actionKind = CustomerCabinProblemActionKind.STATUS_TRANSITION;
    action.previousStatus = previousStatus;
    action.lifecycleStatus = CustomerCabinProblemStatus.IN_PROGRESS;
    return action;
  }

  /** Captures one resolution decision and its accompanying terminal status transition. */
  public static CustomerCabinProblemAction resolutionDecision(
      UUID problemId,
      CustomerCabinProblemStatus previousStatus,
      CustomerCabinProblemResolutionKind resolutionKind,
      UUID actorSubjectId,
      String commentText,
      OffsetDateTime occurredAt) {
    if (previousStatus != CustomerCabinProblemStatus.IN_PROGRESS) {
      throw new IllegalArgumentException("Customer cabin problem resolution transition is invalid");
    }
    CustomerCabinProblemAction action = base(problemId, actorSubjectId, occurredAt);
    action.actionKind = CustomerCabinProblemActionKind.RESOLUTION_DECISION;
    action.previousStatus = previousStatus;
    action.lifecycleStatus = CustomerCabinProblemStatus.RESOLVED;
    action.resolutionKind = Objects.requireNonNull(resolutionKind, "resolutionKind");
    action.commentText = required(commentText, "commentText");
    return action;
  }

  private static CustomerCabinProblemAction base(
      UUID problemId, UUID actorSubjectId, OffsetDateTime occurredAt) {
    CustomerCabinProblemAction action = new CustomerCabinProblemAction();
    action.problemId = Objects.requireNonNull(problemId, "problemId");
    action.actorSubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    action.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    return action;
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 2_000) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((CustomerCabinProblemAction) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
